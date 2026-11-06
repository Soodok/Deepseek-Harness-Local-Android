package app.dsh.mobile.engine.cap

/**
 * btsnoop 二进制日志解析（HCI 帧级）。
 *
 * ## 为什么需要它
 * `/data/misc/bluetooth/logs/` 下的 `.log` 文件 是 bluedroid 的**文本 verbose 日志**
 * （`[时间][V][pid] [l2c_csm.cc:1426] ...`），能 grep 到 L2CAP/RFCOMM 的事件，
 * 但**拿不到帧字节**。真正的 HCI 帧在
 * `/data/misc/bluetooth/cached_hci/` 下的 `btsnoop_hci_*.cfa` 文件 里 —— 那是标准 btsnoop 格式。
 *
 * ## 文件格式（实测确认，别凭记忆改）
 * ```
 * 文件头 16 字节：>8sII   magic "btsnoop\0" | version(1) | datalink(1002 = H4)
 * 每条记录 24 字节 + 载荷：
 *        >IIIIQ  orig_len | incl_len | flags | drops | timestamp(µs since 0 AD)
 * ```
 * - `flags` 低位 1 bit = 方向（0=host→controller，1=controller→host）；
 *   高位 16 bit 的 bit0 表示该包是 command / event 标记。（实测 flags 取值 0x2/0x3）
 * - 载荷首字节即 H4 packet type：`0x01` Command、`0x02` ACL、`0x03` SCO、`0x04` Event。
 *
 * 实测样本 `btsnoop_hci_20261031_050524.cfa`（下划线日期命名）（24535 字节）解析出 447 包，
 * 其中 Command 223 / Event 224，字节全消费无残留。
 *
 * ## 为什么放在 App 侧的纯 Kotlin 里
 * 解析是纯字节运算，不碰 Android API —— 这样可以脱离设备用 kotlin 直接编译跑
 * （`scripts/probes/_test_btsnoop_parser.py` 就是这么验的）。
 */
object BtsnoopParser {

    /** 文件头魔数（含结尾的 NUL）。 */
    private val MAGIC = byteArrayOf(
        'b'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 'n'.code.toByte(),
        'o'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 0,
    )

    const val HEADER_SIZE = 16
    const val RECORD_SIZE = 24

    /** 单条载荷上限：HCI ACL 最大也就几 KB，超过这个数说明记录头是坏的。 */
    private const val MAX_PAYLOAD = 65536

    /**
     * 尾部重对齐的扫描窗口（字节）。
     *
     * 切口落进长载荷（ACL 常见 255B+）中间时，下一个记录边界可能在几十上百字节
     * 之后，只在记录头长度（24）内试探会直接错过。4096 足够覆盖单个 HCI 载荷。
     */
    private const val RESYNC_SCAN = 4096

    /** 一包解析出来的结果。 */
    data class Packet(
        /** 包在文件中的序号（0 基）。 */
        val index: Int,
        /** btsnoop 记录的 timestamp 原值（µs since 0 AD）。 */
        val timestampUs: Long,
        /** 方向：true = controller→host（收到），false = host→controller（发出）。 */
        val fromController: Boolean,
        /** H4 packet type 首字节：1 Command / 2 ACL / 3 SCO / 4 Event。 */
        val packetType: Int,
        /** 完整载荷（含 H4 类型字节）。 */
        val payload: ByteArray,
    ) {
        /** 可读的类型名。 */
        val typeName: String
            get() = when (packetType) {
                0x01 -> "cmd"
                0x02 -> "acl"
                0x03 -> "sco"
                0x04 -> "event"
                0x05 -> "iso"
                else -> "0x%02x".format(packetType)
            }

        /** 载荷的 hex（含 H4 类型字节，与常见 btmon 习惯一致）。 */
        fun hex(): String = buildString(payload.size * 2) {
            for (b in payload) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }

        /**
         * 一句话摘要。
         *
         * 只解**最常用**的几种，不追求完整协议栈 —— AI 要的是
         * 「有没有这条命令、这条事件码是多少」，不是把 HCI 全解一遍。
         */
        fun summary(): String = when (packetType) {
            0x01 -> cmdSummary()
            0x04 -> eventSummary()
            0x02 -> aclSummary()
            else -> typeName + " " + payload.size + "B"
        }

        private fun cmdSummary(): String {
            if (payload.size < 4) return "cmd (short)"
            val op = u16(payload, 1)
            val name = HCI_COMMANDS[op] ?: "0x%04x".format(op)
            val plen = payload[3].toInt() and 0xFF
            return "cmd $name (0x%04x) len=%d".format(op, plen)
        }

        private fun eventSummary(): String {
            if (payload.size < 3) return "event (short)"
            val code = payload[1].toInt() and 0xFF
            val name = HCI_EVENTS[code] ?: "0x%02x".format(code)
            val plen = payload[2].toInt() and 0xFF
            // Command Complete 带内层 opcode，一举把「哪条命令完成了」也带出来
            if (code == 0x0E && payload.size >= 7) {
                val op = u16(payload, 4)
                val status = payload[6].toInt() and 0xFF
                return "event $name op=0x%04x(%s) status=%d".format(
                    op, HCI_COMMANDS[op] ?: "?", status,
                )
            }
            if (code == 0x0F && payload.size >= 7) {
                // Command Status：status(3) + ncmd(4) + opcode(5..6)
                val status = payload[3].toInt() and 0xFF
                val op = u16(payload, 5)
                return "event $name op=0x%04x(%s) status=%d".format(
                    op, HCI_COMMANDS[op] ?: "?", status,
                )
            }
            return "event $name (0x%02x) len=%d".format(code, plen)
        }

        private fun aclSummary(): String {
            if (payload.size < 5) return "acl (short)"
            // handle 是低 12 位，PB/BC 在高 4 位
            val raw = u16(payload, 1)
            val handle = raw and 0x0FFF
            val pb = (raw ushr 12) and 0x03
            val dlen = u16(payload, 3)
            return "acl handle=0x%03x pb=%d len=%d".format(handle, pb, dlen)
        }
    }

    /** 解析结果。 */
    data class Result(
        val valid: Boolean,
        val error: String? = null,
        val version: Int = 0,
        val datalink: Int = 0,
        /** 解析出的所有包（按文件顺序）。 */
        val packets: List<Packet> = emptyList(),
        /** 被跳过的字节数（空文件/坏文件时为整份长度）。 */
        val bytesConsumed: Int = 0,
        /** 尾部无法解析的残余字节（正常应为 0）。 */
        val trailingBytes: Int = 0,
    )

    /** 完整解析。坏记录就停在那里，已解析的部分照常返回。 */
    fun parse(data: ByteArray, startOffset: Int = 0): Result {
        if (data.size - startOffset < HEADER_SIZE) {
            return Result(false, "file too small (${data.size} bytes)")
        }
        for (i in MAGIC.indices) {
            if (data[startOffset + i] != MAGIC[i]) {
                return Result(false, "bad magic (not a btsnoop file)")
            }
        }
        val version = readInt(data, startOffset + 8)
        val datalink = readInt(data, startOffset + 12)

        val out = ArrayList<Packet>()
        var off = startOffset + HEADER_SIZE
        var idx = 0
        while (off + RECORD_SIZE <= data.size) {
            val inclLen = readInt(data, off + 4)
            if (inclLen < 0 || inclLen > MAX_PAYLOAD) break
            if (off + RECORD_SIZE + inclLen > data.size) break
            val flags = readInt(data, off + 8)
            // 记录头 >IIIIQ：orig@0 incl@4 flags@8 drops@12 timestamp@16（8 字节）
            val tsHi = readInt(data, off + 16).toLong() and 0xFFFFFFFFL
            val tsLo = readInt(data, off + 20).toLong() and 0xFFFFFFFFL
            val payload = data.copyOfRange(off + RECORD_SIZE, off + RECORD_SIZE + inclLen)
            out.add(
                Packet(
                    index = idx,
                    timestampUs = (tsHi shl 32) or tsLo,
                    fromController = (flags and 0x01) != 0,
                    packetType = if (payload.isEmpty()) 0 else payload[0].toInt() and 0xFF,
                    payload = payload,
                ),
            )
            off += RECORD_SIZE + inclLen
            idx++
        }
        return Result(
            valid = true,
            version = version,
            datalink = datalink,
            packets = out,
            bytesConsumed = off - startOffset,
            trailingBytes = data.size - off,
        )
    }

    /**
     * 从一段「尾部切片」里重新对齐记录边界。
     *
     * 文件太大时我们只 `tail -c` 取末尾一段，切口必然落在某条记录中间。
     *
     * ## 为什么扫描窗口比记录头长得多
     * ACL 载荷常见 255 字节以上，切口落进载荷中间时，**下一个记录边界可能在
     * 几十上百字节之后**。只在 `RECORD_SIZE`（24）范围内试探会直接错过它 ——
     * 实测合成流里 cut=1500 就是这么失败的。所以扫 [RESYNC_SCAN]。
     *
     * ## 为什么不能只挑「包数最多」
     * 任意偏移都可能碰巧走出一个自洽链。所以额外要求：
     * ① 残余 0 字节（正好走到切片末尾）；② 每包首字节都是合法 H4 类型 1..5；
     * ③ 时间戳不倒退。满足这三条的候选里，取**吃掉的字节最多**的那个。
     */
    fun resyncTail(slice: ByteArray): Result? {
        var best: Result? = null
        val limit = minOf(RESYNC_SCAN, maxOf(0, slice.size - RECORD_SIZE))
        for (skip in 0..limit) {
            if (slice.size - skip < RECORD_SIZE) break
            val r = parseRecordsOnly(slice, skip) ?: continue
            if (r.trailingBytes != 0 || r.packets.isEmpty()) continue
            if (r.packets.any { it.packetType < 1 || it.packetType > 5 }) continue
            var monotonic = true
            for (i in 0 until r.packets.size - 1) {
                if (r.packets[i].timestampUs > r.packets[i + 1].timestampUs) {
                    monotonic = false
                    break
                }
            }
            if (!monotonic) continue
            if (best == null || r.bytesConsumed > best.bytesConsumed) best = r
        }
        return best
    }

    /** 只解记录、不要求文件头（尾部切片用）。 */
    private fun parseRecordsOnly(data: ByteArray, startOffset: Int): Result? {
        val out = ArrayList<Packet>()
        var off = startOffset
        var idx = 0
        while (off + RECORD_SIZE <= data.size) {
            val inclLen = readInt(data, off + 4)
            if (inclLen < 0 || inclLen > MAX_PAYLOAD) return null
            if (off + RECORD_SIZE + inclLen > data.size) break
            val flags = readInt(data, off + 8)
            // 记录头 >IIIIQ：orig@0 incl@4 flags@8 drops@12 timestamp@16（8 字节）
            val tsHi = readInt(data, off + 16).toLong() and 0xFFFFFFFFL
            val tsLo = readInt(data, off + 20).toLong() and 0xFFFFFFFFL
            val payload = data.copyOfRange(off + RECORD_SIZE, off + RECORD_SIZE + inclLen)
            out.add(
                Packet(
                    index = idx,
                    timestampUs = (tsHi shl 32) or tsLo,
                    fromController = (flags and 0x01) != 0,
                    packetType = if (payload.isEmpty()) 0 else payload[0].toInt() and 0xFF,
                    payload = payload,
                ),
            )
            off += RECORD_SIZE + inclLen
            idx++
        }
        return Result(
            valid = true,
            packets = out,
            bytesConsumed = off - startOffset,
            trailingBytes = data.size - off,
        )
    }

    // ───────────────────────────── 字节读取 ─────────────────────────────

    private fun readInt(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or
            ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or
            (d[at + 3].toInt() and 0xFF)

    private fun u16(d: ByteArray, at: Int): Int =
        if (at + 1 >= d.size) 0
        else ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private const val HEX = "0123456789abcdef"

    /**
     * 常见 HCI Command OpCode → 名字。
     *
     * 只收「排查连接/配对/广播时真的会盯着看」的那些；未知 opcode 原样打成
     * `0xXXXX` —— 宁可显示原始值，也不放一张抄错名字的大表。
     */
    private val HCI_COMMANDS: Map<Int, String> = mapOf(
        // Link Control
        0x0401 to "Inquiry",
        0x0402 to "Inquiry_Cancel",
        0x0405 to "Create_Connection",
        0x0406 to "Disconnect",
        0x0408 to "Create_Connection_Cancel",
        0x040B to "Accept_Connection_Request",
        0x040C to "Reject_Connection_Request",
        0x040D to "Connection_Packet_Type_Changed",
        0x040F to "Authentication_Requested",
        0x0411 to "Set_Connection_Encryption",
        0x0413 to "Remote_Name_Request",
        0x041B to "Read_Remote_Supported_Features",
        0x041D to "Read_Remote_Version_Information",
        // Controller & Baseband
        0x0C01 to "Set_Event_Mask",
        0x0C03 to "Reset",
        0x0C05 to "Set_Event_Filter",
        0x0C13 to "Write_Local_Name",
        0x0C14 to "Read_Local_Name",
        0x0C1A to "Write_Scan_Enable",
        0x0C1E to "Write_Class_Of_Device",
        0x0C23 to "Host_Buffer_Size",
        0x0C24 to "Host_Number_Of_Completed_Packets",
        0x0C33 to "Read_Local_Version",
        0x0C35 to "Write_Simple_Pairing_Mode",
        0x0C3D to "Write_Inquiry_Response",
        0x0C45 to "Read_Extended_Inquiry_Response",
        0x0C46 to "Write_Extended_Inquiry_Response",
        0x0C47 to "Refresh_Encryption_Key",
        0x0C52 to "Read_Buffer_Size",
        0x0C56 to "Write_LE_Host_Support",
        0x0C57 to "Read_Encryption_Key_Size",
        // Informational
        0x1001 to "Read_Local_Version",
        0x1002 to "Read_Local_Supported_Commands",
        0x1005 to "Read_Buffer_Size",
        0x1009 to "Read_BD_ADDR",
        // LE Controller
        0x2001 to "LE_Set_Event_Mask",
        0x2002 to "LE_Read_Buffer_Size",
        0x2005 to "LE_Set_Random_Address",
        0x2006 to "LE_Set_Advertising_Parameters",
        0x2008 to "LE_Set_Advertising_Data",
        0x2009 to "LE_Set_Scan_Response_Data",
        0x200A to "LE_Set_Advertising_Enable",
        0x200B to "LE_Set_Scan_Parameters",
        0x200C to "LE_Set_Scan_Enable",
        0x200D to "LE_Create_Connection",
        0x200E to "LE_Create_Connection_Cancel",
        0x2013 to "LE_Connection_Update",
        0x2016 to "LE_Read_Remote_Features",
        0x2019 to "LE_Enable_Encryption",
        0x201A to "LE_Long_Term_Key_Request_Reply",
        0x201B to "LE_Long_Term_Key_Request_Negative_Reply",
        0x2022 to "LE_Set_Data_Length",
        0x2036 to "LE_Enhanced_Receiver_Test",
        0x2039 to "LE_Set_Extended_Advertising_Parameters",
        0x203A to "LE_Set_Extended_Advertising_Data",
        0x203C to "LE_Set_Extended_Advertising_Enable",
        0x2044 to "LE_Set_Extended_Scan_Parameters",
        0x2045 to "LE_Set_Extended_Scan_Enable",
        0x2046 to "LE_Extended_Create_Connection",
    )

    /**
     * 常见 HCI Event Code → 名字。同样只收排查常用项。
     */
    private val HCI_EVENTS: Map<Int, String> = mapOf(
        0x01 to "Inquiry_Complete",
        0x03 to "Connection_Complete",
        0x05 to "Disconnection_Complete",
        0x06 to "Authentication_Complete",
        0x07 to "Remote_Name_Request_Complete",
        0x08 to "Encryption_Change",
        0x0B to "Read_Remote_Supported_Features_Complete",
        0x0C to "Read_Remote_Version_Information_Complete",
        0x0E to "Command_Complete",
        0x0F to "Command_Status",
        0x10 to "Hardware_Error",
        0x13 to "Number_Of_Completed_Packets",
        0x1C to "Read_Clock_Offset_Complete",
        0x1D to "Connection_Packet_Type_Changed",
        0x2F to "Extended_Inquiry_Result",
        0x30 to "Encryption_Key_Refresh_Complete",
        0x31 to "IO_Capability_Request",
        0x32 to "IO_Capability_Response",
        0x33 to "User_Confirmation_Request",
        0x34 to "User_Passkey_Request",
        0x36 to "Simple_Pairing_Complete",
        0x3E to "LE_Meta",
        0xFF to "Vendor",
    )
}
