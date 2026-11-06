package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 手机当 BLE 外设（GATT Server）。
 *
 * ## 和 [RadioCap] 的 GATT 是什么关系
 * [RadioCap] 里的 gatt 是**客户端**（手机去连别人、读写别人的特征值）。
 * 这里是**服务端**：手机自己开一个 service，等别的设备（电脑、另一台手机、
 * 单片机）连上来读写。两者方向相反，互不冲突。
 *
 * ## 为什么这条是 A 档（不需要 root）
 * `BluetoothGattServer` + `BluetoothLeAdvertiser` 都是公开 API，
 * 只要 BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE 两个运行时权限。
 *
 * ## 状态放在单例里
 * GATT Server 必须活过单次请求（客户端要能被持续连上），
 * 所以 server 与「最近一次写入」都挂在这个 object 上。
 *
 * ## 已知限制（真机实测口径）
 * - 广播载荷上限 31 字节（含 3 字节 flags + 2 字节 service UUID），
 *   设备名超过部分会被截断，发 `scan-response` 也受同样限制。
 * - 连上后 Android 不会主动通知，AI 得自己轮询 `op=events` 看有没有写进来。
 * - 手机熄屏被 Cgroup Freezer 冻结时服务会断（与其他后台能力同一个坑）。
 */
object BlePeripheralCap {

    /** 默认自定义 service / characteristic 的 UUID 前缀，便于客户端按前缀找。 */
    private const val DEFAULT_SERVICE = "d5b0a000-1e4b-4c0a-9f2e-000000000001"
    private const val DEFAULT_CHAR = "d5b0a000-1e4b-4c0a-9f2e-000000000002"
    private const val DEFAULT_DESC = "d5b0a000-1e4b-4c0a-9f2e-000000000003"

    /** 客户端配置描述符的标准 UUID（开通知时必须写它）。 */
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── 运行态（活过单次请求）──────────────────────────────────────────
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var serviceUuid: String = DEFAULT_SERVICE
    @Volatile private var charUuid: String = DEFAULT_CHAR
    @Volatile private var advertisedName: String = "DSH"
    @Volatile private var advertising: Boolean = false
    @Volatile private var lastWritten: String = ""
    @Volatile private var lastWrittenAt: Long = 0L
    private val connected = LinkedHashMap<String, BluetoothDevice>()
    private val events = ArrayDeque<String>()
    private val notifySubscribers = LinkedHashSet<String>() // "addr|charUuid"

    private fun pushEvent(msg: String) {
        synchronized(events) {
            events.addLast("[${System.currentTimeMillis()}] $msg")
            while (events.size > 64) events.removeFirst()
        }
    }

    @SuppressLint("MissingPermission")
    fun peripheral(ctx: Context, rawBody: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.BT)) return denied(CapabilityGate.BT)
        val body = runCatching { JSONObject(rawBody.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        val manager = ctx.getSystemService(BluetoothManager::class.java)
            ?: return 503 to error("bluetooth manager unavailable")
        val adapter = manager.adapter ?: return 503 to error("bluetooth unavailable")
        if (!adapter.isEnabled) return 409 to error("bluetooth disabled")

        return when (val op = body.optString("op").ifBlank { "status" }) {
            "status" -> status()
            "start" -> start(ctx, manager, body)
            "stop" -> stop()
            "events" -> events(body)
            "notify" -> notify(body)
            "read" -> readValue()
            "write" -> writeValue(body)
            else -> 400 to error(
                "unknown op '$op' (status|start|stop|events|notify|read|write)",
            )
        }
    }

    // ───────────────────────────── status ─────────────────────────────

    private fun status(): Pair<Int, String> {
        val devices = JSONArray()
        synchronized(connected) {
            connected.forEach { (addr, d) ->
                devices.put(JSONObject().put("address", addr).put("name", runCatching { d.name }.getOrNull() ?: ""))
            }
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("running", server != null)
            .put("advertising", advertising)
            .put("service_uuid", serviceUuid)
            .put("char_uuid", charUuid)
            .put("name", advertisedName)
            .put("connected_count", devices.length())
            .put("connected", devices)
            .put("last_written", lastWritten)
            .put("last_written_at", lastWrittenAt)
            .put("notify_subscribers", notifySubscribers.size)
            .toString()
    }

    // ───────────────────────────── start ──────────────────────────────

    @SuppressLint("MissingPermission")
    private fun start(ctx: Context, manager: BluetoothManager, body: JSONObject): Pair<Int, String> {
        if (server != null) {
            return 409 to error("already running; call op=stop first")
        }
        val adapter = manager.adapter ?: return 503 to error("bluetooth unavailable")
        serviceUuid = body.optString("service").ifBlank { DEFAULT_SERVICE }
        charUuid = body.optString("char").ifBlank { DEFAULT_CHAR }
        advertisedName = body.optString("name").ifBlank { "DSH" }

        val sUuid = runCatching { UUID.fromString(serviceUuid) }.getOrNull()
            ?: return 400 to error("invalid service uuid")
        val cUuid = runCatching { UUID.fromString(charUuid) }.getOrNull()
            ?: return 400 to error("invalid char uuid")

        val srv = runCatching { manager.openGattServer(ctx, callback) }.getOrNull()
            ?: return 500 to error("openGattServer failed (permission or adapter busy)")

        // 特征值：可读 + 可写 + 可通知，权限放开以便任意客户端测试
        val ch = BluetoothGattCharacteristic(
            cUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        runCatching { ch.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)) }
        val svc = BluetoothGattService(sUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        if (!runCatching { srv.addService(svc) }.getOrDefault(false)) {
            runCatching { srv.close() }
            return 500 to error("addService failed")
        }
        server = srv
        synchronized(connected) { connected.clear() }
        notifySubscribers.clear()
        pushEvent("server started service=$serviceUuid char=$charUuid")

        // 开始广播（失败不致命：客户端仍可按 UUID 搜索到 service，只是扫不到名字）
        val advErr = startAdvertising(ctx, adapter, sUuid, advertisedName)
        CapabilityGate.audit(ctx, "bt.peripheral.start")
        return 200 to JSONObject()
            .put("ok", true)
            .put("service_uuid", serviceUuid)
            .put("char_uuid", charUuid)
            .put("name", advertisedName)
            .put("advertising", advertising)
            .also { if (advErr != null) it.put("advertise_error", advErr) }
            .put(
                "next",
                "let the other device scan for service $serviceUuid, connect, then " +
                    "op=events to see writes and op=notify to push data back",
            )
            .toString()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(
        ctx: Context,
        adapter: android.bluetooth.BluetoothAdapter,
        sUuid: UUID,
        name: String,
    ): String? {
        val adv = adapter.bluetoothLeAdvertiser ?: return "advertiser unavailable"
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // 广播包只放 service UUID（省空间）；名字放 scan response
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(sUuid))
            .build()
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        var failure: String? = null
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                advertising = true
                pushEvent("advertising started as '$name'")
            }

            override fun onStartFailure(errorCode: Int) {
                advertising = false
                failure = "advertise failed code=$errorCode (${advertiseErrorName(errorCode)})"
                pushEvent(failure ?: "advertise failed")
            }
        }
        runCatching { adv.startAdvertising(settings, data, scanResp, cb) }
            .onFailure { return "advertise threw: ${it.message}" }
        // 回调是异步的，这里给个短窗口拿同步失败（成功会自己把 advertising 置 true）
        runCatching { Thread.sleep(300) }
        return failure
    }

    private fun advertiseErrorName(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large (>31B)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "unknown"
    }

    // ────────────────────────────── stop ──────────────────────────────

    @SuppressLint("MissingPermission")
    private fun stop(): Pair<Int, String> {
        val srv = server
        if (srv == null) return 200 to JSONObject().put("ok", true).put("was_running", false).toString()
        runCatching {
            synchronized(connected) {
                connected.values.forEach { d -> runCatching { srv.cancelConnection(d) } }
                connected.clear()
            }
            runCatching { srv.close() }
        }
        server = null
        advertising = false
        notifySubscribers.clear()
        pushEvent("server stopped")
        return 200 to JSONObject().put("ok", true).put("was_running", true).toString()
    }

    // ───────────────────────────── events ─────────────────────────────

    private fun events(body: JSONObject): Pair<Int, String> {
        val limit = body.optInt("limit", 30).coerceIn(1, 64)
        val drain = body.optBoolean("drain", false)
        val snapshot = synchronized(events) {
            val take = events.takeLast(limit)
            if (drain) events.clear()
            take
        }
        val arr = JSONArray()
        snapshot.forEach { arr.put(it) }
        return 200 to JSONObject()
            .put("ok", true)
            .put("count", snapshot.size)
            .put("events", arr)
            .put("note", "call with drain=true to consume; otherwise the buffer keeps the last 64")
            .toString()
    }

    // ───────────────────────────── notify ─────────────────────────────

    @SuppressLint("MissingPermission")
    private fun notify(body: JSONObject): Pair<Int, String> {
        val srv = server ?: return 409 to error("server not running; op=start first")
        val text = body.optString("text")
        if (text.isEmpty()) return 400 to error("need text=<string to push>")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val ch = findChar(srv) ?: return 500 to error("characteristic not found")
        ch.value = bytes
        var sent = 0
        val failures = JSONArray()
        val targets = synchronized(connected) { connected.toMap() }
        if (targets.isEmpty()) return 409 to error("no client connected")
        targets.forEach { (addr, dev) ->
            val ok = runCatching {
                srv.notifyCharacteristicChanged(dev, ch, false)
            }.getOrDefault(false)
            if (ok) sent++ else failures.put(addr)
        }
        pushEvent("notify '${text.take(40)}' -> $sent/${targets.size}")
        return 200 to JSONObject()
            .put("ok", sent > 0)
            .put("sent_to", sent)
            .put("clients", targets.size)
            .put("bytes", bytes.size)
            .also { if (failures.length() > 0) it.put("failed", failures) }
            .toString()
    }

    private fun readValue(): Pair<Int, String> = 200 to JSONObject()
        .put("ok", true)
        .put("last_written", lastWritten)
        .put("last_written_at", lastWrittenAt)
        .put("notify_subscribers", notifySubscribers.size)
        .toString()

    private fun writeValue(body: JSONObject): Pair<Int, String> {
        // 这只是在本地改「特征值内容」，让客户端下次 read 时读到
        val v = body.optString("value")
        lastWritten = v
        lastWrittenAt = System.currentTimeMillis()
        return 200 to JSONObject().put("ok", true).put("value", v).toString()
    }

    private fun findChar(srv: BluetoothGattServer): BluetoothGattCharacteristic? {
        val u = runCatching { UUID.fromString(charUuid) }.getOrNull() ?: return null
        return runCatching { srv.getService(UUID.fromString(serviceUuid))?.getCharacteristic(u) }.getOrNull()
    }

    // ───────────────────────── server callback ─────────────────────────

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = runCatching { device.address }.getOrNull() ?: "?"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(connected) { connected[addr] = device }
                pushEvent("connected $addr status=$status")
            } else {
                synchronized(connected) { connected.remove(addr) }
                notifySubscribers.removeAll { it.startsWith("$addr|") }
                pushEvent("disconnected $addr status=$status state=$newState")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val text = runCatching { value?.toString(Charsets.UTF_8) }.getOrNull() ?: ""
            lastWritten = text
            lastWrittenAt = System.currentTimeMillis()
            val addr = runCatching { device.address }.getOrNull() ?: "?"
            pushEvent("write from $addr: '${text.take(80)}'")
            if (responseNeeded) {
                server?.let { srv ->
                    runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value) }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = lastWritten.toByteArray(Charsets.UTF_8)
            val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
            server?.let { srv ->
                runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice) }
            }
            pushEvent("read from ${runCatching { device.address }.getOrNull()} offset=$offset")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val addr = runCatching { device.address }.getOrNull() ?: "?"
            val enabling = value?.isNotEmpty() == true && (value[0].toInt() and 0x01) != 0
            val key = "$addr|$charUuid"
            if (enabling) notifySubscribers.add(key) else notifySubscribers.remove(key)
            pushEvent("cccd write from $addr -> ${if (enabling) "notify ON" else "notify OFF"}")
            if (responseNeeded) {
                server?.let { srv ->
                    runCatching { srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value) }
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pushEvent("notification ack ${runCatching { device.address }.getOrNull()} status=$status")
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
