package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import app.dsh.mobile.engine.CapabilityGate
import app.dsh.mobile.engine.Privilege
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 蓝牙 / WiFi 的**底层**能力：不止「开开关」，而是能收发真实报文、读真实日志。
 *
 * ## 能做到什么、做不到什么（2026-10-31 真机实测确定，别凭想象扩）
 * 能做到：
 *  - BLE 广播：自定义 service UUID + 厂商数据（最高 31 字节，可自称任意名字）
 *  - BLE 扫描：拿到 MAC/名称/RSSI/广播原始载荷
 *  - BLE GATT：连接、列 service/characteristic、读、写（任意字节 hex）
 *  - 经典蓝牙 RFCOMM：connect 到指定 MAC+channel 后 write 任意字节
 *  - 读 HCI 日志：`/data/misc/bluetooth/logs/` 下的 `.log` 文件 是 l2cap/rfcomm 层文本日志
 *  - WiFi：扫描列表、连接信息、`iw` 链路细节、`tcpdump` 抓包
 *
 * 做不到（设备实测无 hcitool/hciconfig/btmon，普通 App 通路也拿不到）：
 *  - 伪造底层 HCI command / 直接注入 raw HCI 帧
 *  - 监听空中所有 Bluetooth 流量（需要专门嗅探硬件）
 *  - 破解配对/伪造其它设备的蓝牙身份
 * 所以「自己编一条蓝牙报文」的落地形态是**应用层报文**：GATT write 的
 * 任意字节、RFCOMM write 的任意字节、BLE 广播载荷，而不是 HCI 帧。
 */
object RadioCap {

    /** HCI 日志目录（需要 root 或 shell；普通 App 无权限）。 */
    private const val BT_LOG_DIR = "/data/misc/bluetooth/logs"

    // ─────────────────────────────── 蓝牙 ───────────────────────────────

    @SuppressLint("MissingPermission")
    fun bt(ctx: Context, rawBody: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.BT)) return denied(CapabilityGate.BT)
        val body = runCatching { JSONObject(rawBody.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        val op = body.optString("op").ifBlank { "status" }
        val adapter = bluetoothAdapter(ctx)
            ?: return 503 to error("bluetooth unavailable")
        if (!adapter.isEnabled && op != "enable") return 409 to error("bluetooth disabled")

        return when (op) {
            "status" -> btStatus(adapter)
            "enable" -> {
                @Suppress("DEPRECATION")
                val ok = adapter.enable()
                200 to JSONObject().put("ok", true).put("enabled", ok).toString()
            }
            "disable" -> {
                @Suppress("DEPRECATION")
                val ok = adapter.disable()
                200 to JSONObject().put("ok", true).put("disabled", ok).toString()
            }
            "scan" -> btScan(ctx, adapter, body)
            "advertise" -> btAdvertise(ctx, adapter, body)
            "connect" -> btConnect(adapter, body)
            "services" -> btServices(ctx, adapter, body)
            "read" -> btGattIo(ctx, adapter, body, write = false)
            "write" -> btGattIo(ctx, adapter, body, write = true)
            "rfcomm" -> btRfcomm(adapter, body)
            "log" -> btLog(ctx, body)
            else -> 400 to error("op must be status|enable|disable|scan|advertise|connect|services|read|write|rfcomm|log")
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothAdapter(ctx: Context): BluetoothAdapter? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(BluetoothManager::class.java)?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
        }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun btStatus(adapter: BluetoothAdapter): Pair<Int, String> {
        val bonded = JSONArray()
        runCatching {
            adapter.bondedDevices?.forEach { d ->
                bonded.put(
                    JSONObject()
                        .put("name", d.name ?: "")
                        .put("address", d.address)
                        .put("type", deviceTypeName(d.type))
                )
            }
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("enabled", adapter.isEnabled)
            .put("state", adapter.state)
            .put("name", adapter.name ?: "")
            .put("address", adapter.address ?: "")
            .put("bonded", bonded)
            .put("le_supported", adapter.isMultipleAdvertisementSupported)
            .toString()
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    @SuppressLint("MissingPermission")
    private fun btScan(ctx: Context, adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val ms = body.optInt("ms", 6_000).coerceIn(500, 30_000)
        val scanner = adapter.bluetoothLeScanner ?: return 503 to error("le scanner unavailable")
        val found = LinkedHashMap<String, JSONObject>()
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val o = JSONObject()
                    .put("address", result.device.address)
                    .put("name", result.device.name ?: rec?.deviceName ?: "")
                    .put("rssi", result.rssi)
                    .put("connectable", result.isConnectable)
                runCatching {
                    val uuids = JSONArray()
                    rec?.serviceUuids?.forEach { uuids.put(it.uuid.toString()) }
                    o.put("service_uuids", uuids)
                    val mfr = rec?.manufacturerSpecificData
                    if (mfr != null && mfr.size() > 0) {
                        o.put("manufacturer_id", mfr.keyAt(0))
                        o.put("manufacturer_hex", hex(mfr.valueAt(0)))
                    }
                    rec?.serviceData?.forEach { (k, v) ->
                        o.put("service_data_uuid", k.uuid.toString())
                        o.put("service_data_hex", hex(v))
                    }
                }
                found[result.device.address] = o
            }

            override fun onScanFailed(errorCode: Int) {
                latch.countDown()
            }
        }
        return try {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(), cb)
            latch.await(ms.toLong(), TimeUnit.MILLISECONDS)
            val arr = JSONArray()
            found.values.forEach { arr.put(it) }
            CapabilityGate.audit(ctx, "bt.scan:${found.size}")
            200 to JSONObject().put("ok", true).put("count", found.size).put("devices", arr).toString()
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    /**
     * BLE 广播：**这就是「自己编一条报文发出去」最干净的形态**。
     *
     * @param service_uuid 自定义 128bit UUID，接收方按此识别
     * @param manufacturer_hex 任意字节（hex），会原样出现在空中的厂商数据里
     * @param name 广播名（部分系统会用它覆盖设备名）
     */
    @SuppressLint("MissingPermission")
    private fun btAdvertise(ctx: Context, adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return 503 to error("advertising unsupported on this device")
        val uuidStr = body.optString("service_uuid").ifBlank {
            "0000fd6f-0000-1000-8000-00805f9b34fb"
        }
        val mfrHex = body.optString("manufacturer_hex").ifBlank { "0201060aff4c001005011c1a0d1a" }
        val payload = parseHex(mfrHex) ?: return 400 to error("manufacturer_hex is not valid hex")
        if (payload.size > 27) return 400 to error("manufacturer payload too long (max 27 bytes)")
        val mfrId = body.optInt("manufacturer_id", 0x004C)
        val ms = body.optInt("ms", 15_000).coerceIn(1_000, 120_000)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(body.optBoolean("connectable", false))
            .setTimeout(ms)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(uuidStr)))
            .addManufacturerData(mfrId, payload)
            .build()
        val latch = CountDownLatch(1)
        var failure: String? = null
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                latch.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                failure = "advertise failed: $errorCode (${advertiseErrorName(errorCode)})"
                latch.countDown()
            }
        }
        return try {
            advertiser.startAdvertising(settings, data, cb)
            if (!latch.await(3, TimeUnit.SECONDS)) {
                504 to error("advertise start timeout")
            } else if (failure != null) {
                409 to error(failure!!)
            } else {
                CapabilityGate.audit(ctx, "bt.advertise:$uuidStr")
                200 to JSONObject()
                    .put("ok", true)
                    .put("service_uuid", uuidStr)
                    .put("manufacturer_id", mfrId)
                    .put("manufacturer_hex", mfrHex)
                    .put("bytes", payload.size)
                    .put("duration_ms", ms)
                    .toString()
            }
        } finally {
            runCatching { advertiser.stopAdvertising(cb) }
        }
    }

    private fun advertiseErrorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "unknown"
    }

    /** 连接 GATT 并返还 handle，后续 read/write 复用它（存在进程内缓存）。 */
    private val gattCache = HashMap<String, BluetoothGatt>()

    @SuppressLint("MissingPermission")
    private fun btConnect(adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val addr = body.optString("address").ifBlank { return 400 to error("address required") }
        val device = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
            ?: return 400 to error("invalid address")
        val gatt = openGatt(device) ?: return 409 to error("gatt connect failed")
        gattCache[addr] = gatt
        return 200 to JSONObject().put("ok", true).put("address", addr).put("connected", true).toString()
    }

    private val connectLatchMap = HashMap<String, CountDownLatch>()

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice): BluetoothGatt? {
        val latch = CountDownLatch(1)
        connectLatchMap[device.address] = latch
        val gatt = device.connectGatt(null, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED ||
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    connectLatchMap[device.address]?.countDown()
                }
            }
        }, BluetoothDevice.TRANSPORT_LE) ?: return null
        return if (latch.await(8, TimeUnit.SECONDS)) gatt else {
            runCatching { gatt.close() }
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun btServices(ctx: Context, adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val addr = body.optString("address").ifBlank { return 400 to error("address required") }
        val gatt = gattCache[addr] ?: run {
            val d = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
                ?: return 400 to error("invalid address")
            openGatt(d) ?: return 409 to error("gatt connect failed")
        }
        gattCache[addr] = gatt
        Thread.sleep(600)
        val services = JSONArray()
        runCatching {
            gatt.services?.forEach { s ->
                val chars = JSONArray()
                s.characteristics.forEach { c ->
                    chars.put(
                        JSONObject()
                            .put("uuid", c.uuid.toString())
                            .put("properties", props(c.properties))
                    )
                }
                services.put(JSONObject().put("uuid", s.uuid.toString()).put("characteristics", chars))
            }
        }
        CapabilityGate.audit(ctx, "bt.services:$addr")
        return 200 to JSONObject().put("ok", true).put("address", addr).put("services", services).toString()
    }

    private fun props(p: Int): String {
        val out = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out += "read"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out += "write"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out += "write_no_response"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out += "notify"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out += "indicate"
        return out.joinToString(",")
    }

    /** GATT 读写：**write 就是「发一条任意字节报文」**。 */
    @SuppressLint("MissingPermission")
    private fun btGattIo(
        ctx: Context,
        adapter: BluetoothAdapter,
        body: JSONObject,
        write: Boolean,
    ): Pair<Int, String> {
        val addr = body.optString("address").ifBlank { return 400 to error("address required") }
        val svc = body.optString("service_uuid").ifBlank { return 400 to error("service_uuid required") }
        val chr = body.optString("char_uuid").ifBlank { return 400 to error("char_uuid required") }
        val gatt = gattCache[addr] ?: return 409 to error("not connected; call op=connect first")
        val service = runCatching { gatt.getService(UUID.fromString(svc)) }.getOrNull()
            ?: return 404 to error("service not found")
        val ch = runCatching { service.getCharacteristic(UUID.fromString(chr)) }.getOrNull()
            ?: return 404 to error("characteristic not found")

        if (!write) {
            val latch = CountDownLatch(1)
            var value: ByteArray? = null
            // 读回调只能通过全局 callback 拿；这里用反射式轮询兜底更稳
            @Suppress("DEPRECATION")
            val started = gatt.readCharacteristic(ch)
            if (!started) return 409 to error("read rejected")
            repeat(30) {
                Thread.sleep(100)
                @Suppress("DEPRECATION")
                val v = ch.value
                if (v != null && v.isNotEmpty()) {
                    value = v
                    latch.countDown()
                    return@repeat
                }
            }
            val v = value ?: return 504 to error("read timeout")
            CapabilityGate.audit(ctx, "bt.read:$addr")
            return 200 to JSONObject().put("ok", true).put("hex", hex(v))
                .put("bytes", v.size).put("text", runCatching { String(v, Charsets.UTF_8) }.getOrDefault(""))
                .toString()
        }

        val payload = parseHex(body.optString("hex"))
            ?: return 400 to error("hex required (e.g. \"0102ff\")")
        @Suppress("DEPRECATION")
        val ok = runCatching {
            ch.writeType = if (body.optBoolean("no_response", false)) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            ch.value = payload
            gatt.writeCharacteristic(ch)
        }.getOrDefault(false)
        if (!ok) return 409 to error("write rejected")
        CapabilityGate.audit(ctx, "bt.write:$addr:${payload.size}b")
        return 200 to JSONObject().put("ok", true).put("sent_hex", hex(payload))
            .put("bytes", payload.size).toString()
    }

    /** 经典蓝牙 RFCOMM：连到 MAC+channel 后直接写任意字节。 */
    @SuppressLint("MissingPermission")
    private fun btRfcomm(adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val addr = body.optString("address").ifBlank { return 400 to error("address required") }
        val channel = body.optInt("channel", 1).coerceIn(1, 30)
        val payload = parseHex(body.optString("hex")) ?: return 400 to error("hex required")
        val device = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
            ?: return 400 to error("invalid address")
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createRfcommSocketToServiceRecord(
                UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", channel))
            )
            socket.connect()
            socket.outputStream.write(payload)
            socket.outputStream.flush()
            200 to JSONObject().put("ok", true).put("address", addr)
                .put("channel", channel).put("sent_hex", hex(payload)).toString()
        } catch (e: Exception) {
            409 to error("rfcomm failed: ${e.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * 读蓝牙 HCI 日志。
     *
     * ⚠️ 设备实测（2026-10-31）：这些 `.log` 是 **bluedroid 的文本 verbose 日志**
     * （`[时间][V][pid] [l2c_csm.cc:1426] ...`），**不是标准 btsnoop 二进制**，
     * 因此能 grep 协议层事件（L2CAP/RFCOMM 收发、连接、配对），但解不出完整
     * HCI 帧字节。标准 `btsnoop_hci.log` 在本机不存在。
     */
    private fun btLog(ctx: Context, body: JSONObject): Pair<Int, String> {
        val limit = body.optInt("lines", 80).coerceIn(1, 2000)
        val filter = body.optString("filter")
        val explicitPath = body.optString("path").ifBlank { "" }
        // ⚠️ 枚举也必须走提权通道：`/data/misc/bluetooth/logs` 的 SELinux 标签是
        // `bluetooth_logs_data_file:s0`，App 域（即使 uid=0，域仍是 untrusted_app）
        // 对它 listFiles 直接返回 null。旧实现先 listFiles 再提权读，于是在枚举
        // 这一步就 404 了，提权分支永远走不到 —— 真机实测就是这个形态。
        val listOut = Privilege.runPrivileged(
            ctx,
            "ls -t " + BT_LOG_DIR + "/*.log 2>/dev/null | head -1",
        )
        val newestPath = explicitPath.ifBlank { listOut?.lineSequence()?.firstOrNull()?.trim().orEmpty() }
        if (newestPath.isBlank()) {
            return 404 to error("no bluetooth log found (need root to list " + BT_LOG_DIR + ")")
        }
        val newest = File(newestPath)
        val out = Privilege.runPrivileged(ctx, "tail -n $limit '" + newestPath + "'")
            ?: return 403 to error("cannot read bluetooth log (root required)")
        val lines = out.lines().filter { it.isNotBlank() }
        val filtered = if (filter.isBlank()) lines else lines.filter { it.contains(filter, ignoreCase = true) }
        CapabilityGate.audit(ctx, "bt.log:${newest.name}")
        val arr = JSONArray()
        filtered.takeLast(limit).forEach { arr.put(it) }
        return 200 to JSONObject()
            .put("ok", true)
            .put("file", newest.name)
            .put("mtime", newest.lastModified())
            .put("total_matched", filtered.size)
            .put("lines", arr)
            .toString()
    }

    // ─────────────────────────────── WiFi ───────────────────────────────

    @SuppressLint("MissingPermission")
    fun wifi(ctx: Context, rawBody: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.WIFI)) return denied(CapabilityGate.WIFI)
        val body = runCatching { JSONObject(rawBody.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        val op = body.optString("op").ifBlank { "status" }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?: return 503 to error("wifi manager unavailable")
        return when (op) {
            "status" -> wifiStatus(wm)
            "scan" -> wifiScan(ctx, wm, body)
            "enable" -> 200 to JSONObject().put("ok", true)
                .put("enabled", runCatching { wm.isWifiEnabled = true }.isSuccess).toString()
            "disable" -> 200 to JSONObject().put("ok", true)
                .put("disabled", runCatching { wm.isWifiEnabled = false }.isSuccess).toString()
            "link" -> wifiLink(ctx)
            "capture" -> wifiCapture(ctx, body)
            else -> 400 to error("op must be status|scan|enable|disable|link|capture")
        }
    }

    @SuppressLint("MissingPermission")
    private fun wifiStatus(wm: WifiManager): Pair<Int, String> {
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        return 200 to JSONObject()
            .put("ok", true)
            .put("enabled", wm.isWifiEnabled)
            .put("state", wm.wifiState)
            .put("ssid", info?.ssid ?: "")
            .put("bssid", info?.bssid ?: "")
            .put("rssi", info?.rssi ?: 0)
            .put("link_speed_mbps", info?.linkSpeed ?: 0)
            .put("frequency_mhz", info?.frequency ?: 0)
            .put("ip", intToIp(info?.ipAddress ?: 0))
            .put("mac", info?.macAddress ?: "")
            .put("hidden_ssid", info?.hiddenSSID ?: false)
            .toString()
    }

    @SuppressLint("MissingPermission")
    private fun wifiScan(ctx: Context, wm: WifiManager, body: JSONObject): Pair<Int, String> {
        // 优先 root 的 `iw scan`：Android 的 startScan 有 4 次/2 分钟限流，且隐藏 SSID
        // 拿不到；真机实测同一时刻 API 返回 0 条、iw 返回 6 条（含 2 个隐藏 SSID）。
        val viaIw = wifiScanIw(ctx)
        if (viaIw.length() > 0) {
            CapabilityGate.audit(ctx, "wifi.scan.iw:${viaIw.length()}")
            return 200 to JSONObject().put("ok", true).put("source", "iw")
                .put("count", viaIw.length()).put("networks", viaIw).toString()
        }

        val ms = body.optInt("ms", 6_000).coerceIn(1_000, 30_000)
        runCatching { wm.startScan() }
        val deadline = System.currentTimeMillis() + ms
        var results: List<android.net.wifi.ScanResult> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            results = runCatching { wm.scanResults }.getOrDefault(emptyList())
            if (results.isNotEmpty()) break
            Thread.sleep(300)
        }
        val arr = JSONArray()
        results.sortedByDescending { it.level }.forEach { r ->
            arr.put(
                JSONObject()
                    .put("ssid", runCatching { r.SSID }.getOrDefault(""))
                    .put("bssid", r.BSSID ?: "")
                    .put("rssi", r.level)
                    .put("frequency_mhz", r.frequency)
                    .put("capabilities", r.capabilities ?: "")
            )
        }
        return 200 to JSONObject().put("ok", true).put("source", "api")
            .put("count", results.size).put("networks", arr).toString()
    }

    /**
     * 用 root 的 `iw dev wlan0 scan` 取周边 AP。
     *
     * 比 Android API 强的地方（真机 2026-10-31 对照）：
     *  · 不受 `startScan` 4 次/2 分钟限流；
     *  · 能拿到隐藏 SSID 的 BSS（API 里直接不出现）；
     *  · 带 `associated` 标记，能区分当前连的是哪个 BSS。
     */
    private fun wifiScanIw(ctx: Context): JSONArray {
        val out = Privilege.runPrivileged(ctx, "iw dev wlan0 scan 2>/dev/null") ?: return JSONArray()
        val arr = JSONArray()
        // 不用 var 闭包捕获（Kotlin 智能转换对闭包捕获变量失效），改用显式可空局部
        var cur: JSONObject? = null
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                // ⚠️ 只认 `BSS <mac>(on <iface>)`：`iw scan` 输出里还有子字段
                // `BSS Load:` / `BSS Load:` 这类行，用 startsWith("BSS ") 会把它们
                // 当成新 BSS，产生 bssid="Load:" 的垃圾条目（真机 27 条里一半是它）。
                isBssHeader(line) -> {
                    val prev = cur
                    if (prev != null) arr.put(prev)
                    val head = line.removePrefix("BSS ")
                    val bssid = head.substringBefore("(").trim()
                    cur = JSONObject().put("bssid", bssid)
                        .put("associated", head.contains("associated"))
                }
                line.startsWith("freq:") -> {
                    val o = cur ?: continue
                    o.put("frequency_mhz", line.removePrefix("freq:").trim().toIntOrNull() ?: 0)
                }
                line.startsWith("signal:") -> {
                    val o = cur ?: continue
                    o.put("rssi", line.removePrefix("signal:").trim()
                        .removeSuffix("dBm").trim().toDoubleOrNull()?.toInt() ?: 0)
                }
                line.startsWith("SSID:") -> {
                    val o = cur ?: continue
                    o.put("ssid", line.removePrefix("SSID:").trim())
                }
                line.startsWith("capability:") -> {
                    val o = cur ?: continue
                    o.put("capabilities", line.removePrefix("capability:").trim())
                }
            }
        }
        val last = cur
        if (last != null) arr.put(last)
        return arr
    }

    /** `BSS c2:6b:76:3f:9b:41(on wlan0) -- associated` 才是 BSS 头。 */
    private fun isBssHeader(line: String): Boolean {
        if (!line.startsWith("BSS ")) return false
        val rest = line.removePrefix("BSS ")
        val mac = rest.substringBefore("(").trim()
        // MAC 形态校验：6 组 2 位 hex
        return mac.matches(Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$"))
    }

    /** `iw dev` 的链路细节：信道/带宽/发射功率，这些 Android API 拿不到。 */
    private fun wifiLink(ctx: Context): Pair<Int, String> {
        val out = Privilege.runPrivileged(ctx, "iw dev 2>/dev/null")
            ?: return 403 to error("iw requires root")
        val stations = Privilege.runPrivileged(ctx, "iw dev wlan0 station dump 2>/dev/null") ?: ""
        CapabilityGate.audit(ctx, "wifi.link")
        return 200 to JSONObject().put("ok", true).put("dev", out.trim())
            .put("station", stations.trim()).toString()
    }

    /**
     * tcpdump 抓包（需要 root）。
     *
     * ⚠️ 必须限时 + 限量：无限制抓包会写满磁盘并拖慢设备。
     */
    private fun wifiCapture(ctx: Context, body: JSONObject): Pair<Int, String> {
        val iface = body.optString("iface").ifBlank { "wlan0" }
        val secs = body.optInt("seconds", 5).coerceIn(1, 30)
        val count = body.optInt("count", 200).coerceIn(1, 2000)
        val filter = body.optString("filter").ifBlank { "" }
        if (iface.contains(' ') || iface.contains(';')) return 400 to error("bad iface")
        if (filter.contains(';') || filter.contains('`') || filter.contains('$')) {
            return 400 to error("filter must not contain shell metacharacters")
        }
        val outFile = "/data/local/tmp/dsh_cap_${System.currentTimeMillis()}.pcap"
        val cmd = buildString {
            append("timeout $secs tcpdump -i ").append(iface)
            append(" -c ").append(count)
            append(" -w ").append(outFile)
            if (filter.isNotBlank()) append(" ").append(filter)
            append(" 2>/dev/null; ")
            append("ls -l ").append(outFile).append(" 2>/dev/null; echo ---; ")
            append("tcpdump -r ").append(outFile).append(" -nn -c 30 2>/dev/null")
        }
        val out = Privilege.runPrivileged(ctx, cmd, )
            ?: return 403 to error("tcpdump requires root")
        CapabilityGate.audit(ctx, "wifi.capture:$iface")
        return 200 to JSONObject().put("ok", true).put("iface", iface)
            .put("seconds", secs).put("count", count).put("pcap", outFile)
            .put("output", out.trim()).toString()
    }

    private fun intToIp(ip: Int): String =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    // ─────────────────────────────── 通用 ───────────────────────────────

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace(":", "").replace("-", "").lowercase()
        if (clean.isEmpty()) return null
        if (clean.length % 2 != 0) return null
        if (!clean.all { it in "0123456789abcdef" }) return null
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun denied(capability: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false)
        .put("error", "capability disabled")
        .put("capability", capability)
        .toString()

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
