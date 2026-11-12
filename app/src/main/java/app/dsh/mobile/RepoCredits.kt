package app.dsh.mobile

/**
 * 关于页「致谢者」与「仓库引用」的数据源（v1.6.0）。
 *
 * 收录口径（宝宝定的）：**只收有代码引用的仓库** —— 我们是它们的下游（依赖、
 * 打包来源、插件来源、上游宿主）。我们自己借鉴学习过但代码里没用到的项目不收。
 *
 * 排序：先放指定的五个（宿主 → 本应用 → 容器 → 语音 → 三方 UI 插件），其余按
 * 「对本项目功能的重要性」排：运行库与工具链 → 系统集成 → 社区插件源。
 *
 * 名称一律用仓库原名（英文）：i18n 门禁禁止 Kotlin 字符串字面量里出现 CJK，
 * 而且这些名字本身就是项目标识，翻译反而更难对上。
 */
object RepoCredits {

    /** 贡献者：代码、设计、测试、文档与反馈。名单来自 GitHub contributors（2026-11-12 核对）。 */
    val CONTRIBUTORS: List<String> = listOf(
        "Soodok — project lead: Android shell, engine integration, UI, releases",
        "XiaoPb — pet bridge & mobile-layout plugins, WebView back-stack bridge, assets",
        "xiaowu8848 — theme: dynamic-colour flicker and palette rebuild fixes",
        "xiaoming132887 — build: duplicate META-INF/DEPENDENCIES and packaging fixes",
        "qingjian0 — startup dependency and parallelism regression probe",
        "Masspedro — plugin-market MCP console entry, plugin-install root-cause write-up",
        "henryz78 — data export (issue #31, step 1)",
        "zazha093 — Thai locale",
        "jahdaganj02ki-sketch — three-language UI and screen-orientation tri-state",
        "skynit — login-dialog rendering diagnosis (WebView compositing), with device evidence",
        "PyGuy2 — dsh-asr-service contributions",
        "dsh community — plugin ecosystem, bug reports and hands-on testing",
    )

    /** 仓库引用：name 用于显示，url 用于打开。 */
    data class Repo(val name: String, val url: String)

    val REPOS: List<Repo> = listOf(
        // —— 指定的前五个 ——
        Repo("DeepSeek Harness (upstream host)", "https://github.com/deepseek-ai/DeepSeek-Harness"),
        Repo("DSH Mobile (this app)", "https://github.com/Soodok/Deepseek-Harness-Local-Android"),
        Repo("DSH Linux Runtime (container rootfs)", "https://github.com/Soodok/dsh-linux-runtime"),
        Repo("DSH ASR Service (on-device recognition)", "https://github.com/Soodok/dsh-asr-service"),
        Repo("dsh-mobile-layout (third-party UI plugin)", "https://github.com/XiaoPb/dsh-mobile-layout"),

        // —— 引擎与运行时：引擎本体、包管理、端侧推理 ——
        Repo("Node.js (engine runtime, 24.x)", "https://github.com/nodejs/node"),
        Repo("pnpm (offline package manager shim)", "https://github.com/pnpm/pnpm"),
        Repo("llama.cpp (llama-server for local embeddings)", "https://github.com/ggerganov/llama.cpp"),
        Repo(
            "bge-small-zh-v1.5 GGUF (on-device embedding model)",
            "https://huggingface.co/CompendiumLabs/bge-small-zh-v1.5-gguf",
        ),
        Repo("sherpa-onnx (offline ASR engine)", "https://github.com/k2-fsa/sherpa-onnx"),
        Repo("ONNX Runtime (inference runtime)", "https://github.com/microsoft/onnxruntime"),

        // —— 工具链与 rootfs ——
        Repo("Termux packages (Android userland toolchain)", "https://github.com/termux/termux-packages"),
        Repo("termux-app (terminal-view widget)", "https://github.com/termux/termux-app"),
        Repo("Alpine Linux aports (container rootfs upstream)", "https://gitlab.alpinelinux.org/alpine/aports"),
        Repo("Ubuntu base rootfs (glibc container)", "https://cdimage.ubuntu.com/ubuntu-base/releases/"),

        // —— 系统集成：应用直接调用的能力来源 ——
        Repo("Shizuku (rootless privileged channel)", "https://github.com/RikkaApps/Shizuku"),
        Repo("KernelSU (root solution)", "https://github.com/tiann/KernelSU"),
        Repo("cloudflared (tunnel extension)", "https://github.com/cloudflare/cloudflared"),

        // —— 直接依赖的 Android 库 ——
        Repo("Kotlin Coroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
        Repo("Java-WebSocket", "https://github.com/TooTallNate/Java-WebSocket"),
        Repo("Apache MINA SSHD (in-app SSH server)", "https://github.com/apache/mina-sshd"),
        Repo("XZ for Java (archive handling)", "https://github.com/tukaani-project/xz"),
        Repo(
            "Material Components for Android",
            "https://github.com/material-components/material-components-android",
        ),

        // —— 协议参考：Edge TTS 的私有协议逆向自它 ——
        Repo("edge-tts (Edge TTS protocol reference)", "https://github.com/rany2/edge-tts"),

        // —— 社区插件源：插件市场里可一键安装的那些 ——
        Repo("dsh-pet-bridge (desktop pet bridge)", "https://github.com/XiaoPb/dsh-pet-bridge"),
        Repo("dsh-pet-mobile-assets (pet assets)", "https://github.com/XiaoPb/dsh-pet-mobile-assets"),
        Repo("dsh-solution-explorer (solution explorer plugin)", "https://github.com/XiaoPb/dsh-solution-explorer"),
        Repo("dsh-hardssh (hardened SSH plugin)", "https://github.com/XiaoPb/dsh-hardssh"),
        Repo("dsh-web-mobile-fix (mobile WebView fixes)", "https://github.com/AcidGr/dsh-web-mobile-fix"),
        Repo("dsh-ai-providers (model providers)", "https://github.com/Firefox2100/dsh-ai-providers"),
        Repo("dsh-plugin-kit (plugin SDK)", "https://github.com/hyzyn/dsh-plugin-kit"),
        Repo(
            "DeepSeek-Harness-Desktop-Ultra (desktop variant)",
            "https://github.com/ijry/DeepSeek-Harness-Desktop-Ultra",
        ),
    )
}
