/*
 * dsh_pty_exec.c —— 终端 PTY 执行助手（roadmap M2：node-pty 的 Android 桥接）
 *
 * 背景（issue #18「终端错误」第二层）：
 *   引擎的 Web 终端与持久 bash 工具都经 `@deepseek-ai/dsh-subprocess-local`
 *   调 node-pty 申请一个**新的**伪终端。Android/bionic 下 node-pty 没有预编译
 *   产物（runtime 里是显式抛错的空壳），而 App 侧已有的 libdshpty.so 只服务
 *   「拉起引擎主进程」这一件事，且是 JNI——引擎是另一个进程，用不了。
 *
 * 本助手就是那条缺失的桥：一个**独立可执行文件**，由引擎内的 node-pty 兼容层
 * （runtime 内 node_modules/node-pty/lib/index.js 的 Android 实现）以普通子进程
 * 方式启动，负责：
 *   fork 出挂了 PTY 的 shell（setsid + TIOCSCTTY，真实 tty 语义）、
 *   在 PTY master ↔ 自己的 stdio 之间双向搬运字节、
 *   接收控制通道指令做 resize / 信号投递、
 *   子进程退出后回报退出码/信号。
 *
 * 通道约定（父进程 = node-pty 兼容层）：
 *   0 (stdin)  → 写入 PTY 的数据（用户按键）
 *   1 (stdout) → PTY 输出的数据（屏幕内容）
 *   2 (stderr) → 助手自身的诊断信息（不混入终端内容）
 *   3 (ctrl)   → **双向**控制通道，行协议（ASCII，'\n' 结尾）：
 *                  引擎 → 助手： `R <cols> <rows>`  改窗口尺寸
 *                               `K <SIGNAL>`        向子进程组投递信号
 *                 助手 → 引擎： `EXIT <code> <signal>` 子进程已退出
 *   argv 尾部  → 可选 `--dsh-pty-pidfile <path>`：fork 后**立刻**把子进程 pid 写进该文件。
 *                ⚠️ 走文件而不是管道：引擎 spawn 后要**同步**读 terminal.pid
 *               （LocalTerminalHandle 构造时就取值），管道的非阻塞读会 EAGAIN，
 *               而普通文件短轮询读即可拿到 —— 实现最简且无竞态。
 *
 * 用法：
 *   dsh_pty_exec <rows> <cols> <cwd> <program> [args...] [--dsh-pty-pidfile <path>]
 */
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define CTRL_FD 3
#define BUF_SIZE 16384

static pid_t g_child = -1;

/*
 * 关掉所有 > stderr 的继承 fd（与 dsh_pty.c 同源，2026-10-09 事故的同一课）。
 *
 * 本助手由引擎（node 进程）spawn，会继承 node 侧的一堆 fd —— 包括 3080/3083 的
 * 监听 socket（Java 的 ServerSocket 不带 O_CLOEXEC）以及 node-pty 给的双向管道。
 * 子进程（用户的 shell）再继承下去，就会出现：终端关掉了、shell 还攥着监听 fd，
 * 端口永不释放。所以 exec 前必须把 >2 的 fd 全摘掉。
 */
static void close_inherited_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return;
    int dfd = dirfd(d);
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd > STDERR_FILENO && fd != dfd) close(fd);
    }
    closedir(d);
}

static void diag(const char *msg) {
    ssize_t ignored = write(STDERR_FILENO, msg, strlen(msg));
    (void) ignored;
}

static void diag_errno(const char *what) {
    char buf[256];
    int n = snprintf(buf, sizeof(buf), "dsh_pty_exec: %s: %s\n", what, strerror(errno));
    if (n > 0) {
        ssize_t ignored = write(STDERR_FILENO, buf, (size_t) n);
        (void) ignored;
    }
}

/* 阻塞写全（PTY/stdout 是管道，可能短写） */
static int write_all(int fd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, buf + off, len - off);
        if (n > 0) {
            off += (size_t) n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

static volatile sig_atomic_t g_terminate = 0;

static void on_signal(int sig) {
    if (g_child > 0) {
        /* 助手自身被终止时，连带收掉子进程组，避免孤儿攥着资源。
         * ⚠️ 交互式 bash 默认**忽略** SIGTERM，所以这里对 TERM/HUP 走 SIGKILL ——
         * 助手正在被收掉，没有「让 shell 自己体面退出」的余地；不这么做的话
         * 终端关闭后 shell 会作为孤儿残留（实测 "surviving pid" 就是这么来的）。 */
        int forward = (sig == SIGTERM || sig == SIGHUP) ? SIGKILL : sig;
        kill(-g_child, forward);
    }
    g_terminate = sig;
}

/* 把信号名映射成编号；未识别返回 -1 */
static int signal_number(const char *name) {
    if (name == NULL || name[0] == '\0') return SIGTERM;
    if (strcmp(name, "SIGKILL") == 0) return SIGKILL;
    if (strcmp(name, "SIGTERM") == 0) return SIGTERM;
    if (strcmp(name, "SIGINT") == 0) return SIGINT;
    if (strcmp(name, "SIGHUP") == 0) return SIGHUP;
    if (strcmp(name, "SIGQUIT") == 0) return SIGQUIT;
    if (strcmp(name, "SIGWINCH") == 0) return SIGWINCH;
    if (strcmp(name, "SIGSTOP") == 0) return SIGSTOP;
    if (strcmp(name, "SIGCONT") == 0) return SIGCONT;
    if (strcmp(name, "SIGUSR1") == 0) return SIGUSR1;
    if (strcmp(name, "SIGUSR2") == 0) return SIGUSR2;
    if (strcmp(name, "SIGTSTP") == 0) return SIGTSTP;
    if (name[0] >= '0' && name[0] <= '9') return atoi(name);
    return -1;
}

/* 处理一行控制指令（原地修改 line） */
static void handle_control_line(char *line, int master) {
    char *cmd = strtok(line, " \t\r\n");
    if (cmd == NULL) return;
    if (strcmp(cmd, "R") == 0) {
        char *sc = strtok(NULL, " \t\r\n");
        char *sr = strtok(NULL, " \t\r\n");
        if (sc == NULL || sr == NULL) return;
        int cols = atoi(sc);
        int rows = atoi(sr);
        if (cols <= 0 || rows <= 0) return;
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_col = (unsigned short) cols;
        ws.ws_row = (unsigned short) rows;
        /* 内核在 winsize 变化时会自动给前台进程组发 SIGWINCH */
        if (ioctl(master, TIOCSWINSZ, &ws) != 0) diag_errno("TIOCSWINSZ");
        return;
    }
    if (strcmp(cmd, "K") == 0) {
        char *sig = strtok(NULL, " \t\r\n");
        int num = signal_number(sig);
        if (num < 0) return;
        if (g_child > 0) {
            /* 投给整个进程组：交互式 shell 常把前台作业放在自己的 pgid 里 */
            if (kill(-g_child, num) != 0 && errno == ESRCH) kill(g_child, num);
        }
        return;
    }
}

static int g_ctrl_open = 0;

int main(int argc, char **argv) {
    if (argc < 5) {
        diag("dsh_pty_exec: usage: dsh_pty_exec <rows> <cols> <cwd> <program> [args...]\n");
        return 2;
    }
    int rows = atoi(argv[1]);
    int cols = atoi(argv[2]);
    const char *cwd = argv[3];
    const char *program = argv[4];
    if (rows <= 0) rows = 24;
    if (cols <= 0) cols = 80;

    /* fd 3/4 由父进程（node-pty 兼容层）显式开好；手跑调试时没有也要能用。
     * ⚠️ 必须先用 fcntl 探活：把无效 fd 交给 poll() 会立刻返回 POLLNVAL，
     * 让主循环变成 100% CPU 空转（实测踩过）。 */
    if (fcntl(CTRL_FD, F_GETFD) != -1) g_ctrl_open = 1;

    /* 可选 --pidfile：把子进程 pid 落盘（引擎侧同步读取用）。
     * 从 argv 传而不走 fd 3：fd 3 在 JS 侧是单向下行管道，子进程往里写会阻塞。 */
    const char *pidfile = NULL;
    for (int i = 5; i + 1 < argc; i++) {
        if (strcmp(argv[i], "--dsh-pty-pidfile") == 0) {
            pidfile = argv[i + 1];
            /* 该选项不属于子进程 argv：就地截断 */
            for (int j = i; j + 2 <= argc; j++) argv[j] = argv[j + 2];
            argc -= 2;
            break;
        }
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        diag_errno("posix_openpt");
        return 3;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        diag_errno("grantpt/unlockpt");
        close(master);
        return 3;
    }
    char slave_path[128];
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        diag_errno("ptsname_r");
        close(master);
        return 3;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(master, TIOCSWINSZ, &ws);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    pid_t pid = fork();
    if (pid < 0) {
        diag_errno("fork");
        close(master);
        return 3;
    }
    if (pid == 0) {
        /* 子进程：新会话 + 以 slave 为控制终端
         *
         * ⚠️ 顺序：setsid → PDEATHSIG → 清继承 fd → 才 open slave。
         * 若先开 slave，close_inherited_fds() 会把它一并关掉（与 dsh_pty.c 同坑）。 */
        setsid();
        /* 助手被杀（含 SIGKILL，signal handler 跑不到）时，shell 必须跟着死。
         * 没有这一句，助手被强杀后 shell 就是孤儿 —— 它继承着 3080/3083 的监听
         * socket，端口再也起不来（v1.2.104 那类「桥 bind FAILED」的现场）。 */
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        /* 竞态：prctl 生效前父进程可能已经没了，那时 PDEATHSIG 不会补发信号 */
        if (getppid() == 1) _exit(0);
        close_inherited_fds();
        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(126);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        ioctl(STDIN_FILENO, TIOCSCTTY, 0);
        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) != 0) _exit(125);
        }
        execv(program, &argv[4]);
        _exit(127);
    }

    g_child = pid;
    /* pid 立刻落盘：引擎构造句柄时同步读取 */
    if (pidfile != NULL) {
        int pf = open(pidfile, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (pf >= 0) {
            char pid_line[64];
            int n = snprintf(pid_line, sizeof(pid_line), "%d\n", (int) pid);
            if (n > 0) write_all(pf, pid_line, (size_t) n);
            close(pf);
        }
    }

    char ctrl_buf[512];
    size_t ctrl_len = 0;
    char buf[BUF_SIZE];
    int stdin_open = 1;
    int child_exited = 0;
    int exit_code = 0;
    int exit_signal = 0;

    while (!child_exited) {
        struct pollfd fds[3];
        int nfds = 0;
        int master_idx = -1, stdin_idx = -1, ctrl_idx = -1;

        master_idx = nfds;
        fds[nfds].fd = master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        nfds++;

        if (stdin_open) {
            stdin_idx = nfds;
            fds[nfds].fd = STDIN_FILENO;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }
        if (g_ctrl_open) {
            ctrl_idx = nfds;
            fds[nfds].fd = CTRL_FD;
            fds[nfds].events = POLLIN;
            fds[nfds].revents = 0;
            nfds++;
        }

        int ready = poll(fds, (nfds_t) nfds, -1);
        if (ready < 0) {
            if (errno == EINTR) {
                /* 自身收到终止信号：子进程组已在前面的处理器里收掉，这里直接退出 */
                if (g_terminate != 0) break;
                /* 有子进程状态变化时先收割，避免僵尸 */
                int status;
                pid_t w = waitpid(g_child, &status, WNOHANG);
                if (w == g_child) {
                    child_exited = 1;
                    if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
                    if (WIFSIGNALED(status)) exit_signal = WTERMSIG(status);
                }
                continue;
            }
            break;
        }

        if (ctrl_idx >= 0 && (fds[ctrl_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(CTRL_FD, ctrl_buf + ctrl_len, sizeof(ctrl_buf) - ctrl_len);
            if (r > 0) {
                ctrl_len += (size_t) r;
                size_t start = 0;
                for (size_t i = 0; i < ctrl_len; i++) {
                    if (ctrl_buf[i] != '\n') continue;
                    ctrl_buf[i] = '\0';
                    handle_control_line(ctrl_buf + start, master);
                    start = i + 1;
                }
                if (start > 0) {
                    memmove(ctrl_buf, ctrl_buf + start, ctrl_len - start);
                    ctrl_len -= start;
                }
            } else if (r == 0) {
                /* 控制通道关闭 = 引擎侧放弃这个终端：收掉子进程组 */
                g_ctrl_open = 0;
                if (g_child > 0) kill(-g_child, SIGHUP);
            } else if (errno != EINTR && errno != EAGAIN) {
                g_ctrl_open = 0;
            }
        }

        if (stdin_idx >= 0 && (fds[stdin_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r > 0) {
                if (write_all(master, buf, (size_t) r) != 0) stdin_open = 0;
            } else if (r == 0) {
                stdin_open = 0;
                if (g_child > 0) kill(-g_child, SIGHUP);
            } else if (errno != EINTR && errno != EAGAIN) {
                stdin_open = 0;
            }
        }

        if (master_idx >= 0 && (fds[master_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t r = read(master, buf, sizeof(buf));
            if (r > 0) {
                if (write_all(STDOUT_FILENO, buf, (size_t) r) != 0) break;
            } else if (r == 0) {
                break;   /* PTY 全关（shell 退出） */
            } else if (errno != EINTR && errno != EAGAIN) {
                break;
            }
        }

        int status;
        pid_t w = waitpid(g_child, &status, WNOHANG);
        if (w == g_child) {
            child_exited = 1;
            if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
            if (WIFSIGNALED(status)) exit_signal = WTERMSIG(status);
        }
    }

    /* 子进程可能还没被收割：给它一点时间，再不行就强杀 */
    if (!child_exited) {
        int status;
        for (int i = 0; i < 20; i++) {
            pid_t w = waitpid(g_child, &status, WNOHANG);
            if (w == g_child) {
                child_exited = 1;
                if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
                if (WIFSIGNALED(status)) exit_signal = WTERMSIG(status);
                break;
            }
            usleep(50 * 1000);
        }
        if (!child_exited) {
            kill(-g_child, SIGKILL);
            kill(g_child, SIGKILL);
            waitpid(g_child, &status, 0);
            child_exited = 1;
            if (WIFSIGNALED(status)) exit_signal = WTERMSIG(status);
        }
    }

    /* 退出状态回写 pidfile（内容从 pid 变成 EXIT 行）：JS 侧在 exit 回调里同步读。
     * ⚠️ 为什么不从 fd 3 回报：fd 3 在 JS 侧是**单向**管道（Node 的 stdio "pipe"
     * 只给父进程写端），子进程往里写没人读，缓冲区满了就阻塞。因此 fd 3 只做
     * 指令下行，上行状态走这个文件 —— 零竞态，也不占额外 fd。 */
    if (pidfile != NULL) {
        int sf = open(pidfile, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (sf >= 0) {
            char status_line[64];
            int m2 = snprintf(status_line, sizeof(status_line), "EXIT %d %d\n", exit_code, exit_signal);
            if (m2 > 0) write_all(sf, status_line, (size_t) m2);
            close(sf);
        }
    }
    close(master);
    return 0;
}
