package app.dsh.mobile.engine.cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.dsh.mobile.engine.CapabilityGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机冒充蓝牙键盘 / 鼠标（BluetoothHidDevice，API 28+）。
 *
 * ## 和 `/dev/uhid` 是什么关系
 * - `/dev/uhid`：在**本机**注册一个虚拟输入设备（需要 root）。
 * - 这里：把手机变成**另一台设备（电脑/平板）的蓝牙键盘**，走真实 HID over GATT 链路。
 *   两者互补，一个是"AI 能按本机"，一个是"AI 能按别的设备"。
 *
 * ## 为什么成功率要看 OEM（这是本模块的核心风险）
 * `BluetoothHidDevice` 在 AOSP 里是公开 API，但 `registerApp()` 底层要求
 * profile `BluetoothProfile.HID_DEVICE` 已注册到系统。多数 OEM 会把它做成
 * `BLUETOOTH_PRIVILEGED` 保护 —— 也就是普通 App 调用会直接返回 false 或异常。
 * 所以 [hid] 的 `op=probe` 先做一次真实注册尝试，把**失败原因原样返回**，
 * 让调用方知道是「这台机器不支持」而不是「参数错了」。
 *
 * ## 实测口径（2026-11-03，OPPO PLZ110 / Android 16 / KernelSU root）
 * 见 `op=probe` 的返回；结果记录在 docs/CAPABILITY_MAP.md。
 */
object BtHidCap {

    private const val TAG = "BtHidCap"

    /** HID 报告的修饰键位。 */
    private const val MOD_SHIFT = 0x02
    private const val MOD_CTRL = 0x01

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var registered: Boolean = false
    @Volatile private var hostDevice: BluetoothDevice? = null
    @Volatile private var lastError: String = ""

    /** US 布局：字符 → HID usage id。NULL 表示不支持。 */
    private val ASCII_MAP: Map<Char, Pair<Int, Boolean>> = run {
        val m = HashMap<Char, Pair<Int, Boolean>>()
        // 字母
        for ((i, c) in ('a'..'z').withIndex()) m[c] = (0x04 + i) to false
        for ((i, c) in ('A'..'Z').withIndex()) m[c] = (0x04 + i) to true
        // 数字行（键盘上方那排）
        val digits = "1234567890"
        for ((i, c) in digits.withIndex()) {
            m[c] = (0x1E + i) to false
        }
        val shiftedDigits = "!@#\$%^&*()"
        for ((i, c) in shiftedDigits.withIndex()) {
            m[c] = (0x1E + i) to true
        }
        m[' '] = 0x2C to false
        m['\n'] = 0x28 to false
        m['\t'] = 0x2B to false
        m['-'] = 0x2D to false
        m['_'] = 0x2D to true
        m['='] = 0x2E to false
        m['+'] = 0x2E to true
        m['['] = 0x2F to false
        m['{'] = 0x2F to true
        m[']'] = 0x30 to false
        m['}'] = 0x30 to true
        m['\\'] = 0x31 to false
        m['|'] = 0x31 to true
        m[';'] = 0x33 to false
        m[':'] = 0x33 to true
        m['\''] = 0x34 to false
        m['"'] = 0x34 to true
        m['`'] = 0x35 to false
        m['~'] = 0x35 to true
        m[','] = 0x36 to false
        m['<'] = 0x36 to true
        m['.'] = 0x37 to false
        m['>'] = 0x37 to true
        m['/'] = 0x38 to false
        m['?'] = 0x38 to true
        m
    }

    @SuppressLint("MissingPermission")
    fun hid(ctx: Context, rawBody: String): Pair<Int, String> {
        if (!CapabilityGate.can(ctx, CapabilityGate.BT)) return denied(CapabilityGate.BT)
        val body = runCatching { JSONObject(rawBody.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to error("invalid json")
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return 503 to error("bluetooth unavailable")
        if (!adapter.isEnabled) return 409 to error("bluetooth disabled")

        return when (val op = body.optString("op").ifBlank { "status" }) {
            "status" -> status()
            "probe" -> probe(ctx, adapter)
            "register" -> register(ctx, adapter, body)
            "unregister" -> unregister()
            "connect" -> connect(adapter, body)
            "type" -> typeText(body)
            "key" -> pressKey(body)
            "mouse" -> mouse(body)
            else -> 400 to error(
                "unknown op '$op' (status|probe|register|unregister|connect|type|key|mouse)",
            )
        }
    }

    private fun status(): Pair<Int, String> {
        val devs = JSONArray()
        hostDevice?.let { d ->
            devs.put(
                JSONObject().put("name", runCatching { d.name }.getOrNull() ?: "")
                    .put("address", runCatching { d.address }.getOrNull() ?: ""),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("registered", registered)
            .put("connected", hostDevice != null)
            .put("host", devs)
            .put("last_error", lastError)
            .toString()
    }

    /**
     * 探测本机能不能当 HID 设备。
     *
     * **这是本模块最重要的一步**：OEM 普遍用 `BLUETOOTH_PRIVILEGED` 把
     * `HID_DEVICE` profile 保护起来，普通 App 调 `getProfileProxy` 会拿不到。
     * 这里做一次真实尝试并返回具体原因，而不是给个"成功/失败"了事。
     */
    @SuppressLint("MissingPermission")
    private fun probe(ctx: Context, adapter: BluetoothAdapter): Pair<Int, String> {
        val what = JSONObject()
        // ① 系统是否声明支持 HID Device profile
        val supported = runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.HID_DEVICE)
        }.isSuccess
        what.put("profile_constant_ok", supported)

        // ② 能否拿到 proxy（拿不到 = OEM 未开放）
        val latch = CountDownLatch(1)
        var proxyOk = false
        var proxyErr = ""
        val ok = runCatching {
            adapter.getProfileProxy(
                ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.HID_DEVICE) proxyOk = proxy is BluetoothHidDevice
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        proxyOk = false
                        latch.countDown()
                    }
                },
                BluetoothProfile.HID_DEVICE,
            )
        }.getOrElse { e ->
            proxyErr = e.message.orEmpty().take(160)
            false
        }
        latch.await(3, TimeUnit.SECONDS)
        what.put("get_profile_proxy_called", ok)
        what.put("proxy_obtained", proxyOk)
        if (proxyErr.isNotBlank()) what.put("proxy_error", proxyErr)

        // ③ 权限自查
        val privGranted = runCatching {
            ctx.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        what.put("bluetooth_privileged_granted", privGranted)

        val verdict = when {
            proxyOk -> "supported — op=register to become a keyboard"
            !proxyOk && !privGranted -> "unsupported — OEM protects HID_DEVICE with BLUETOOTH_PRIVILEGED"
            else -> "unknown — proxy unavailable despite permission; try op=register"
        }
        CapabilityGate.audit(ctx, "bt.hid.probe")
        return 200 to JSONObject().put("ok", true)
            .put("verdict", verdict)
            .put("detail", what)
            .put("note", "op=register does a real registration attempt; its error is authoritative")
            .toString()
    }

    @SuppressLint("MissingPermission")
    private fun register(ctx: Context, adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        if (registered) return 409 to error("already registered; op=unregister first")
        val name = body.optString("name").ifBlank { "DSH Keyboard" }
        val desc = body.optString("description").ifBlank { "AI virtual keyboard" }
        val provider = body.optString("provider").ifBlank { "dsh" }

        val latch = CountDownLatch(1)
        var succeeded = false
        val err = StringBuilder()

        val cb = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                succeeded = isRegistered
                latch.countDown()
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    hostDevice = device
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (hostDevice?.address == runCatching { device.address }.getOrNull()) hostDevice = null
                }
            }
        }

        val proxyLatch = CountDownLatch(1)
        val okCall = runCatching {
            adapter.getProfileProxy(
                ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                            hid = proxy
                            // SDP 记录：键盘（0x2540 是 boot keyboard 的常用 subclass）
                            val sdp = BluetoothHidDeviceAppSdpSettings(
                                name, desc, provider,
                                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                                KEYBOARD_REPORT_DESC,
                            )
                            val reg = runCatching {
                                proxy.registerApp(sdp, null, null, java.util.concurrent.Executor { r -> r.run() }, cb)
                            }.getOrElse { e ->
                                err.append("registerApp threw: ${e.message}; ")
                                false
                            }
                            if (!reg) err.append("registerApp returned false")
                        }
                        proxyLatch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        hid = null
                        proxyLatch.countDown()
                    }
                },
                BluetoothProfile.HID_DEVICE,
            )
        }.getOrElse { e ->
            err.append("getProfileProxy threw: ${e.message}")
            false
        }
        proxyLatch.await(3, TimeUnit.SECONDS)
        if (!okCall || hid == null) {
            lastError = if (err.isBlank()) "HID_DEVICE proxy unavailable (OEM may require BLUETOOTH_PRIVILEGED)" else err.toString()
            return 403 to JSONObject().put("ok", false)
                .put("error", "hid_device_unavailable")
                .put("detail", lastError.take(240))
                .put("hint", "run op=probe; many OEMs block this profile for non-system apps")
                .toString()
        }
        latch.await(4, TimeUnit.SECONDS)
        registered = succeeded
        if (!succeeded) {
            lastError = if (err.isBlank()) "registerApp callback did not confirm within 4s" else err.toString()
            return 403 to JSONObject().put("ok", false)
                .put("error", "register_failed")
                .put("detail", lastError.take(240))
                .toString()
        }
        CapabilityGate.audit(ctx, "bt.hid.register")
        return 200 to JSONObject().put("ok", true)
            .put("registered", true)
            .put("name", name)
            .put("next", "pair from the other device's bluetooth settings, then op=type")
            .toString()
    }

    @SuppressLint("MissingPermission")
    private fun unregister(): Pair<Int, String> {
        val h = hid
        if (h == null) return 200 to JSONObject().put("ok", true).put("was_registered", false).toString()
        runCatching { h.unregisterApp() }
        runCatching { h.disconnect(hostDevice) }
        hid = null
        registered = false
        hostDevice = null
        return 200 to JSONObject().put("ok", true).put("was_registered", true).toString()
    }

    @SuppressLint("MissingPermission")
    private fun connect(adapter: BluetoothAdapter, body: JSONObject): Pair<Int, String> {
        val h = hid ?: return 409 to error("not registered; op=register first")
        val addr = body.optString("address")
        if (addr.isBlank()) {
            // 没给地址就列出已配对设备让调用方挑
            val arr = JSONArray()
            runCatching {
                adapter.bondedDevices?.forEach { d ->
                    arr.put(
                        JSONObject().put("name", runCatching { d.name }.getOrNull() ?: "")
                            .put("address", runCatching { d.address }.getOrNull() ?: ""),
                    )
                }
            }
            return 400 to JSONObject().put("ok", false)
                .put("error", "need address=<MAC>")
                .put("bonded", arr)
                .toString()
        }
        val dev = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
            ?: return 400 to error("invalid address")
        val ok = runCatching { h.connect(dev) }.getOrDefault(false)
        return if (ok) {
            hostDevice = dev
            200 to JSONObject().put("ok", true).put("connected_to", addr).toString()
        } else {
            409 to JSONObject().put("ok", false).put("error", "connect returned false").toString()
        }
    }

    // ─────────────────────────── 输入注入 ───────────────────────────

    private fun typeText(body: JSONObject): Pair<Int, String> {
        val h = hid ?: return 409 to error("not registered")
        val dev = hostDevice ?: return 409 to error("no host connected; op=connect first")
        val text = body.optString("text")
        if (text.isEmpty()) return 400 to error("need text=<string>")
        var sent = 0
        val skipped = StringBuilder()
        for (ch in text) {
            val mapped = ASCII_MAP[ch]
            if (mapped == null) {
                skipped.append(ch)
                continue
            }
            val (code, shift) = mapped
            sendKey(h, dev, code, shift)
            runCatching { Thread.sleep(12) }
            sent++
        }
        return 200 to JSONObject().put("ok", true)
            .put("sent", sent)
            .put("skipped", skipped.toString())
            .put("note", "US layout only; non-ASCII chars are skipped, not mistaken for other keys")
            .toString()
    }

    private fun pressKey(body: JSONObject): Pair<Int, String> {
        val h = hid ?: return 409 to error("not registered")
        val dev = hostDevice ?: return 409 to error("no host connected")
        val code = body.optInt("code", -1)
        if (code < 0 || code > 0xFF) return 400 to error("need code=<0-255 HID usage id>")
        val mod = body.optInt("modifier", 0).coerceIn(0, 0xFF)
        val ok = runCatching { sendKey(h, dev, code, mod) }.getOrDefault(false)
        return if (ok) 200 to JSONObject().put("ok", true).put("code", code).put("modifier", mod).toString()
        else 409 to error("sendReport failed (host may have disconnected)")
    }

    private fun mouse(body: JSONObject): Pair<Int, String> {
        val h = hid ?: return 409 to error("not registered")
        val dev = hostDevice ?: return 409 to error("no host connected")
        val dx = body.optInt("dx", 0).coerceIn(-127, 127)
        val dy = body.optInt("dy", 0).coerceIn(-127, 127)
        val buttons = body.optInt("buttons", 0).coerceIn(0, 7)
        // 键盘 SDP 报告描述符里没有鼠标，发鼠标报告会失败；这里如实说明
        val rep = byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), 0)
        val ok = runCatching {
            h.sendReport(dev, MOUSE_ID, rep)
        }.getOrDefault(false)
        return if (ok) 200 to JSONObject().put("ok", true).put("dx", dx).put("dy", dy).toString()
        else 409 to JSONObject().put("ok", false)
            .put("error", "mouse_report_rejected")
            .put("note", "this app registers as a KEYBOARD only; a mouse needs its own SDP+report descriptor")
            .toString()
    }

    private fun sendKey(h: BluetoothHidDevice, dev: BluetoothDevice, code: Int, modifier: Int): Boolean {
        // 8 字节键盘报告：modifier, reserved, key1..key6
        val down = byteArrayOf(modifier.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0)
        val up = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        val ok1 = runCatching { h.sendReport(dev, KEYBOARD_ID, down) }.getOrDefault(false)
        runCatching { Thread.sleep(8) }
        val ok2 = runCatching { h.sendReport(dev, KEYBOARD_ID, up) }.getOrDefault(false)
        return ok1 && ok2
    }

    private fun sendKey(h: BluetoothHidDevice, dev: BluetoothDevice, code: Int, shift: Boolean): Boolean =
        sendKey(h, dev, code, if (shift) MOD_SHIFT else 0)

    /** 标准 boot keyboard 报告描述符（8 字节报告：修饰键 + 6 键位）。 */
    private val KEYBOARD_REPORT_DESC: ByteArray = byteArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte().toByte(), 0x01, // Collection (Application)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0xE0.toByte().toByte(), //   Usage Min (224)
        0x29, 0xE7.toByte().toByte(), //   Usage Max (231)
        0x15, 0x00, //   Logical Min (0)
        0x25, 0x01, //   Logical Max (1)
        0x75, 0x01, //   Report Size (1)
        0x95.toByte(), 0x08, //   Report Count (8)
        0x81.toByte().toByte(), 0x02, //   Input (Data,Var,Abs)  ← 修饰键
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81.toByte().toByte(), 0x01, //   Input (Const)          ← 保留字节
        0x95.toByte(), 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Min (0)
        0x25, 0x65, //   Logical Max (101)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0x00, //   Usage Min (0)
        0x29, 0x65, //   Usage Max (101)
        0x81.toByte().toByte(), 0x00, //   Input (Data,Array)     ← 6 个键位
        0xC0.toByte().toByte(), // End Collection
    )

    private const val KEYBOARD_ID = 1
    private const val MOUSE_ID = 2

    private fun denied(capability: String): Pair<Int, String> = 403 to JSONObject()
        .put("ok", false).put("error", "capability disabled").put("capability", capability).toString()

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
