package app.dsh.mobile.engine

/**
 * 3080 清场脚本（v1.6.0-beta.18）。
 *
 * ## 为什么单独抽出来
 *
 * 这段脚本原本以 Kotlin raw string 内联在 EngineSupervisor.reclaimPortByPort() 里，
 * awk 的字段引用写成了「反斜杠 + 数字」。raw string 里反斜杠不是转义字符，于是交给
 * shell 的文本带着反斜杠 → awk 直接 syntax error 退出 → INODES 恒为空 → case 分支
 * 不成立 → 一个进程都没杀，而脚本末尾的 true 让它仍然 exit 0。
 *
 * 实测（emulator-5554，先用 nc -l -p 3080 占位）：跑旧脚本后 nc 仍在监听、端口仍被持有；
 * 跑修复后的脚本，同一个 nc 被 kill、端口释放。
 *
 * 后果正是用户报的「第一次启动正常，杀掉后台再启动就白屏，必须重启手机」：root 模式下
 * 引擎 node 是 su 的孙进程，App 被强杀时 PDEATHSIG 只挂到 su 外壳上，node 以 uid 0
 * 变孤儿继续监听 3080；本该由本脚本清掉，却因语法错误静默跳过 → 新引擎听 EADDRINUSE
 * → 反复失败直到 MAX_RESTART，界面停在白屏。
 *
 * 抽成对象的理由：纯函数，JVM 单测能直接断言**渲染后的文本**（raw string 的坑只有渲染
 * 之后才看得出），探针也能做静态护栏。
 *
 * ## 纪律（改这里必须守住）
 *
 * raw string 内任何 shell 的美元符都必须写成 ${'$'} 插值；直接写「反斜杠 + 美元符」会
 * 留下一个反斜杠 —— 这是**静默失败**，没有异常、也没有非零退出码。
 *
 * ## 安全边界
 *
 * 只杀**正在 LISTEN 该端口**的进程（/proc/net/tcp{,6} 的 0A 状态 + socket inode 反查），
 * 不按 cmdline 猜，也不动监听别的端口的 LAN 反向代理。
 */
object PortReclaim {

    /** 生成按端口的清场脚本。port 参与 printf %04X 的十六进制换算（3080 → 0C08）。 */
    fun script(port: Int): String = """
        PORT_HEX=${'$'}(printf '%04X' $port)
        INODES=''
        for f in /proc/net/tcp /proc/net/tcp6; do
          [ -r "${'$'}f" ] || continue
          INODES="${'$'}INODES ${'$'}(awk -v p="${'$'}PORT_HEX" 'NR>1 && ${'$'}4 == "0A" { split(${'$'}2,a,":"); if (a[2]==p) print ${'$'}10 }' "${'$'}f" 2>/dev/null)"
        done
        case "${'$'}INODES" in
          *[0-9]*)
            for p in ${'$'}(ls /proc | grep -E '^[0-9]+${'$'}'); do
              for fd in /proc/${'$'}p/fd/*; do
                tgt=${'$'}(readlink "${'$'}fd" 2>/dev/null) || continue
                case "${'$'}tgt" in
                  socket:*)
                    ino=${'$'}{tgt#socket:}
                    ino=${'$'}{ino#[}
                    ino=${'$'}{ino%]}
                    case " ${'$'}INODES " in
                      *" ${'$'}ino "*) kill -9 ${'$'}p 2>/dev/null; break ;;
                    esac ;;
                esac
              done
            done ;;
        esac
        true
    """.trimIndent()

    /**
     * 兜底清场：按 /proc/<pid>/cmdline 找「命令行里带本 app 引擎路径或家目录路径」
     * 的进程并杀掉。只在按端口清场之后端口仍被占用时使用。
     *
     * 为什么需要它：按端口清场依赖 awk 与 /proc/net/tcp 的表格式，任何一环在特定 ROM
     * 上失效都会退回「什么都没做」且没有任何信号。这条路径只用 tr / case / kill，
     * 对表格式变化免疫，是另一条独立证据链。
     *
     * 绝不误杀：cmdline 必须含 [enginePath] 或 [homePath]；含 lan-web-proxy 的跳过
     * （误杀会让局域网访问在每次引擎重启后静默失效）。
     *
     * 末尾那个额外的 `2>/dev/null` 关的是**赋值命令**自身的 stderr（不是 tr 的）：
     * 读不到别的 uid 的 cmdline 时，sh 会替每条读失败打印一行 `can't open …: Permission denied`，
     * 一次清场能刷出上百行，把 logcat / engine.log 里真正有用的记录挤没。
     */
    fun cmdlineSweepScript(enginePath: String, homePath: String): String {
        val e = quote(enginePath)
        val h = quote(homePath)
        return """
            for p in ${'$'}(ls /proc | grep -E '^[0-9]+${'$'}'); do
              c=${'$'}(tr '\0' ' ' < /proc/${'$'}p/cmdline 2>/dev/null) 2>/dev/null || continue
              case "${'$'}c" in
                *lan-web-proxy*) continue ;;
              esac
              case "${'$'}c" in
                *$e*|*$h*) kill -9 ${'$'}p 2>/dev/null ;;
              esac
            done
            true
        """.trimIndent()
    }

    /** POSIX 单引号转义：路径里可能有空格（/data/user/0/app.dsh.mobile/files 不含空格，
     *  但用户可自定 dsh-home 位置）。 */
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
