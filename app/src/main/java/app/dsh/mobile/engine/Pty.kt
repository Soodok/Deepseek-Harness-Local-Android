package app.dsh.mobile.engine

/**
 * PTY 原语绑定。
 * C 层实现见 app/src/main/cpp/dsh_pty.c，命名约定绑定（JNI 默认导出）。
 */
object Pty {

    init {
        System.loadLibrary("dshpty")
    }

    /**
     * 派生挂载伪终端的子进程。
     * @param cmd 可执行文件绝对路径
     * @param args argv[1..]（不含程序名）
     * @param cwd 工作目录
     * @param env "KEY=VALUE" 环境变量数组
     * @return master fd；失败返回 -errno
     */
    external fun nativeForkPty(
        cmd: String,
        args: Array<out String>,
        cwd: String,
        env: Array<out String>,
        rows: Int,
        cols: Int,
    ): Int

    /** 阻塞等待子进程退出，返回原始 wait status */
    external fun nativeWaitChild(): Int

    /** 发送信号（15=TERM, 9=KILL） */
    external fun nativeSignalChild(sig: Int)

    /** 兜底强杀 */
    external fun nativeForceKill()
}
