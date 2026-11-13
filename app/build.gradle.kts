import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

// ── 自动更新链接：Fork 构建自动指向 Fork ─────────────────────────────────
// Soodok 上游构建原样保留；Fork 构建（GITHUB_REPOSITORY owner != Soodok）时
// 由 scripts/apply-fork-links.py 用正则把 Soodok/Deepseek-Harness-Local-Android
// 替换为 <owner>/Deepseek-Harness-Local-Android，再生成 RepoConfig.kt。
// 产物只写 build/generated，不污染工作树；可用 -PupdateRepo=OWNER/REPO 覆盖。
val updateRepoDefault = "Soodok/Deepseek-Harness-Local-Android"
val updateRepo = (project.findProperty("updateRepo") as String?)
    ?: System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() }
    ?: updateRepoDefault
val repoConfigDir = layout.buildDirectory.dir("generated/repoConfig").get().asFile
val runtimeAssetsDir = layout.buildDirectory.dir("generated/runtimeAssets").get().asFile
val indexResDir = layout.buildDirectory.dir("generated/indexRes").get().asFile
// ── 固定 debug 签名材料（**不入库**，见 docs/RELEASE.md §3）─────────────────
// 目的不是"更安全"，而是"签名稳定"：AGP 默认 debug key 是每台机器首次构建时随机
// 生成的，CI runner 每次都是干净 HOME → 每个 edge/preview 的 debug 包签名都不同，
// 互相无法覆盖安装，自动更新在 debug 渠道上等于不存在。
//
// 但"稳定"不需要靠把私钥提交进仓库来换。解析顺序：
//   1. CI / 自定义：`DSH_DEBUG_STORE_FILE` 环境变量（CI 从 DEBUG_KEYSTORE_BASE64
//      secret 解码到构建目录后指过去）或 `-PdshDebugStoreFile=...`；
//   2. 本地：`debug-signing/dsh-debug.keystore`（已 gitignore，从团队密码库取）；
//   3. 都没有：**不覆盖** debug signingConfig，退回 AGP 默认 ~/.android/debug.keystore。
//      此时产物只与本机构建互相可覆盖 —— 日常开发够用，但不可用于分发。
//
// 与 release 的分界：release 缺私钥 = 正式包构建失败（发布物不能没有身份）；
// debug 缺私钥 = 退化为本机默认 key，不阻断任何人开发。
val debugSigningProps = Properties().apply {
    val f = rootProject.file("debug-signing/dsh-debug.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun debugSetting(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propName) as String?)?.takeIf { it.isNotBlank() }
        ?: debugSigningProps.getProperty(propName)?.takeIf { it.isNotBlank() }

val debugStorePath = debugSetting("DSH_DEBUG_STORE_FILE", "dshDebugStoreFile")
val debugKeystoreFile: File? = if (debugStorePath != null) {
    // 显式指定却不存在 → 立即失败。静默回落会让人以为用的是共享 key，
    // 实际产出的是 runner 随机 key 的包（正是本机制要消灭的坑）。
    // 注意 Windows 上 "/tmp/x" 对 java.io.File 不算绝对路径，这里一并按 POSIX 绝对路径处理。
    val f = File(debugStorePath).let {
        if (it.isAbsolute || debugStorePath.startsWith("/")) it.absoluteFile else rootProject.file(debugStorePath)
    }
    if (!f.isFile) {
        throw GradleException("DSH_DEBUG_STORE_FILE 指向的 keystore 不存在：${f.absolutePath}")
    }
    f
} else {
    rootProject.file("debug-signing/dsh-debug.keystore").takeIf { it.isFile }
}
// 口令**随机生成**（scripts/make-debug-keystore.sh），故意不留默认值：
// "keystore 泄漏" + "口令是公开常量" 叠加就等于私钥泄漏。缺口令时下面直接失败。
val debugStorePassword = debugSetting("DSH_DEBUG_STORE_PASSWORD", "storePassword")
val debugKeyAlias = debugSetting("DSH_DEBUG_KEY_ALIAS", "keyAlias") ?: "dsh-debug"
val debugKeyPassword = debugSetting("DSH_DEBUG_KEY_PASSWORD", "keyPassword") ?: debugStorePassword
val debugSigningReady = debugKeystoreFile != null
// AGP 在配置期会检查 sourceSets 目录；先建空目录，任务稍后再填内容。
listOf(repoConfigDir, runtimeAssetsDir, indexResDir).forEach { it.mkdirs() }

// CI 的 ubuntu runner 通常只有 python3；本地 Windows 只有 python。构建期脚本统一走这里。
fun hasCommand(cmd: String): Boolean = runCatching {
    val p = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
    p.waitFor() == 0
}.getOrDefault(false)
val pythonCmd = if (hasCommand("python3")) "python3" else "python"

val generateRepoConfig by tasks.registering {
    val template = file("src/main/java/app/dsh/mobile/engine/RepoConfig.kt.tmpl")
    val outFile = File(repoConfigDir, "app/dsh/mobile/engine/RepoConfig.kt")
    inputs.file(template)
    inputs.property("updateRepo", updateRepo)
    outputs.file(outFile)
    doLast {
        outFile.parentFile.mkdirs()
        val script = rootProject.file("scripts/apply-fork-links.py")
        val rc = providers.exec {
            commandLine(
                pythonCmd, script.absolutePath,
                "--repo", updateRepo,
                "--in", template.absolutePath,
                "--out", outFile.absolutePath,
            )
            isIgnoreExitValue = true
        }.result.get().exitValue
        if (rc != 0 || !outFile.isFile) {
            throw GradleException("generateRepoConfig failed (exit=$rc): $updateRepo")
        }
    }
}
tasks.named("preBuild") { dependsOn(generateRepoConfig) }
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateRepoConfig) }

// ── Release 签名（v1.4.18）────────────────────────────────────────────────
// 正式包必须用固定 keystore 签名，否则老用户无法覆盖升级。签名材料**绝不入库**
// （keystore/ 已在 .gitignore）：
//   · CI：从 RELEASE_KEYSTORE_BASE64 secret 解码到 keystore/dsh-release.keystore，
//     并把 DSH_RELEASE_* 环境变量传给 Gradle（见 .github/workflows/android-build.yml）；
//   · 本地：keystore/release.properties（与 keystore 同目录、同样不进 git）。
// 两者都没有时 releaseSigningReady=false：assembleRelease 仍能编译，只是产出
// app-release-unsigned.apk —— debug 构建与无 secret 的 fork/PR 因此不受影响。
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore/release.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun releaseSetting(envName: String, propName: String): String? =
    // CI 里 env 变量即使 secret 未配置也会被设为空字符串（而不是不存在），
    // 必须先判空再回落到 keystore/release.properties，避免空 env 覆盖本地签名材料。
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: releaseProps.getProperty(propName)?.takeIf { it.isNotBlank() }

val releaseStorePath = releaseSetting("DSH_RELEASE_STORE_FILE", "storeFile")
    ?: rootProject.file("keystore/dsh-release.keystore").absolutePath
val releaseKeystoreFile: File = File(releaseStorePath).let {
    if (it.isAbsolute) it else rootProject.file(releaseStorePath)
}
val releaseStorePassword = releaseSetting("DSH_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSetting("DSH_RELEASE_KEY_ALIAS", "keyAlias") ?: "dsh-release"
val releaseKeyPassword = releaseSetting("DSH_RELEASE_KEY_PASSWORD", "keyPassword")
    ?: releaseStorePassword
val releaseSigningReady = releaseStorePassword != null && releaseKeystoreFile.isFile

// 发布物必须有身份：显式传 `-PrequireReleaseSigning=true`（CI 发布链路 / 本地出正式包）
// 时，缺签名材料让 packageRelease **直接失败**，而不是悄悄产出 app-release-unsigned.apk
// 被误当正式包上传。
// 不传该属性时维持原来的"能编就编"：fork / PR 没有 secrets 也能正常构建。
val requireReleaseSigning =
    (project.findProperty("requireReleaseSigning") as String?)?.toBoolean() == true
tasks.matching { it.name.startsWith("packageRelease") }.configureEach {
    doFirst {
        if (requireReleaseSigning && !releaseSigningReady) {
            throw GradleException(
                "缺少 release 签名材料，拒绝产出未签名的正式包。\n" +
                    "配置 RELEASE_KEYSTORE_BASE64 / RELEASE_STORE_PASSWORD（见 docs/RELEASE.md §2），\n" +
                    "或去掉 -PrequireReleaseSigning=true（仅供内部/PR 构建）。",
            )
        }
    }
}

// ── 运行时索引验签公钥（构建期从正式 keystore 导出，不入库）──────────────
// 索引用 APK 正式签名密钥签（见 publish-release），所以验签公钥必须来自同一把 key。
// 这样 Fork 用各自的 keystore 自动得到各自的公钥，不需要提交/手工替换 PEM。
// 无签名材料（PR / 本地无 keystore）时生成空文件：App 仍可回退到「自身签名证书」验签，
// 但 debug 包无法验证 release 签名的索引（云更新入口会明确失败，不影响启动）。
val indexPubKeyProp = (project.findProperty("indexPubKey") as String?)?.takeIf { it.isNotBlank() }

fun extractPublicKeyPem(keystore: File, password: String, alias: String): String {
    val certFile = File.createTempFile("dsh-index-cert", ".pem")
    try {
        val keytoolName = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$keytoolName").absolutePath
        val pb = ProcessBuilder(
            keytool, "-exportcert", "-rfc",
            "-alias", alias,
            "-keystore", keystore.absolutePath,
            "-storepass:env", "DSH_INDEX_STOREPASS",
            "-file", certFile.absolutePath,
        )
        pb.environment()["DSH_INDEX_STOREPASS"] = password
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw GradleException("keytool exportcert failed: $output")
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certFile.inputStream())
        val der = cert.publicKey.encoded
        val b64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    } finally {
        certFile.delete()
    }
}

val generateIndexPubKey by tasks.registering {
    val outFile = File(indexResDir, "raw/runtime_index_pub.pem")
    val explicit = indexPubKeyProp?.let { rootProject.file(it) }
    val keystore = releaseKeystoreFile
    val password = releaseStorePassword
    val alias = releaseKeyAlias
    outputs.file(outFile)
    outputs.upToDateWhen { false }
    doLast {
        outFile.parentFile.mkdirs()
        val pem = when {
            explicit != null && explicit.isFile -> explicit.readText()
            keystore.isFile && !password.isNullOrBlank() -> try {
                extractPublicKeyPem(keystore, password, alias)
            } catch (e: Exception) {
                logger.warn("index pubkey: cannot export from keystore: ${e.message}")
                ""
            }
            else -> ""
        }
        outFile.writeText(pem.ifBlank { "\n" })
        if (pem.isBlank()) {
            logger.lifecycle("index pubkey: none (cloud runtime updates disabled for this build)")
        } else {
            logger.lifecycle("index pubkey: generated from release keystore (${pem.length} bytes)")
        }
    }
}
tasks.named("preBuild") { dependsOn(generateIndexPubKey) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Resources") }
    .configureEach { dependsOn(generateIndexPubKey) }
tasks.matching { it.name.startsWith("process") && it.name.endsWith("Resources") }
    .configureEach { dependsOn(generateIndexPubKey) }

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    // NDK 版本**写死**，别依赖 AGP 默认值。AGP 8.5.2 的默认就是 26.1.10909125，但
    // 默认值会随 AGP 升级漂移；而 CI 按版本号判断"是否已就绪"（见
    // .github/workflows/android-build.yml 的 native 组件预装步骤）。写死之后
    // 本地 / CI / 文档三处的版本号才对得上，构建产物也可复现。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.dsh.mobile"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        // v1.6.0-beta.1（2026-11-11）：无障碍记忆系统 v2（录制→剪枝→向量检索→重放→修正→面板）。
        // versionCode 必须大于已发布的 375（v1.5.5-beta.1），否则已在跑旧版的用户会被判降级。
        // v1.6.0-beta.2（2026-11-12）：API 暴露面补全（79 条隐形端点登记、反向对账修前缀过滤、
        // 7 个 GET 端点吃 query、5 条用户数据路由、蓝牙/HID/包可见性/精确闹钟四类就绪判据修正）。
        // v1.6.0-beta.3（2026-11-13）：扩展中心多版本状态改为条目级判定
        // （ExtensionVersionPlan），换版本必须先卸载当前版本。
        // v1.6.0-beta.4（2026-11-13）：并行会话的改动归一 —— 强调色覆盖资源层取色 +
        // 阻断 AppCompat 改写 uiMode、Linux 装包成功标记逐包核验 + glibc 发行版 Node
        // 走官方包 + DNS 代理 socket 存活自愈、loader PATH 搜索链在 bind 挂载点断链。
        // v1.6.0-beta.5（2026-11-13）：双端会话同步 v1 落地 —— 撤回 PR #105 的 .jsonl 协议
        // （实测 0/503 命中、zstd 前缀快进 0/20 不成立），改为整文件 sha256 + 三方比较；
        // 同时收回全局明文 HTTP 放开，network_security_config 恢复仅放行回环。
        // versionCode 继续单调递增：375 → 376 → 377 → 378 → 379 → 380。
        versionCode = 380
        // v1.5.0 起进入 1.5 系列：设备能力（蓝牙报文/WiFi 底层/定时提醒）+ AI 开关 UI。
        // versionName 允许带后缀（Android 不校验格式）。v1.5.0 起 CI 支持两种 tag 形态
        // （见 android-build.yml:68-86）：v1.5.0（正式版，prerelease: false）与
        // v1.5.0-beta.2（试发行，prerelease: true），但 tag 必须与 versionName 完全拼得上
        // （publish-release 的 `test "$GITHUB_REF_NAME" = "v$V"`），所以打 tag 前先确认这里。
        versionName = "1.6.0-beta.5"

        ndk {
            abiFilters += listOf(targetAbi)
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 构建期生成的 RepoConfig.kt（Fork 自动改自动更新链接）
    sourceSets.getByName("main").java.srcDir(repoConfigDir)
    // 构建期生成的 runtime/hardssh 资产（runtime.zip、runtime/MANIFEST.json、hardssh/）
    sourceSets.getByName("main").assets.srcDir(runtimeAssetsDir)
    // 构建期从正式 keystore 导出的索引验签公钥
    sourceSets.getByName("main").res.srcDir(indexResDir)

    // 正式签名在材料齐全时启用；缺失时保持 unsigned（不阻断 debug/PR 构建）。
    signingConfigs {
        // 固定 debug 签名（v1.4.37）：只有拿到共享 key 时才覆盖 AGP 默认签名。
        //
        // 拿不到（新克隆的开发者 / 无 secret 的 fork PR）时**故意不碰**
        // signingConfigs.debug —— 让 AGP 走 ~/.android/debug.keystore。
        // 这时的包签名是"本机稳定"的：本机连装多个版本没问题，但不能覆盖
        // CI 打出来的 edge/preview 包（要覆盖就先卸载一次，或从团队密码库
        // 取 debug-signing/dsh-debug.keystore 放到本地）。
        //
        // 密钥本体与口令**都不入库**（见 docs/RELEASE.md §3）。debug key 即便
        // 泄露也不该被当成安全边界 —— 但如果能不出现在公开仓库里，就没理由放进去。
        if (debugSigningReady && debugKeystoreFile != null) {
            val pass = debugStorePassword ?: throw GradleException(
                "找到 debug keystore（${debugKeystoreFile.absolutePath}）但缺少口令。\n" +
                    "口令不再有公开默认值：本地写在 debug-signing/dsh-debug.properties 的 " +
                    "storePassword=（该文件已 gitignore），CI 用 DEBUG_STORE_PASSWORD secret。\n" +
                    "见 docs/RELEASE.md §3。",
            )
            getByName("debug") {
                storeFile = debugKeystoreFile
                storePassword = pass
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        } else {
            logger.lifecycle(
                "⚠️ 未配置共享 debug 签名密钥，本次 debug 包使用本机默认 key" +
                    "（无法覆盖 edge/preview 包）。配置方法见 docs/RELEASE.md §3。",
            )
        }
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 调试用开关：-PdebuggableRelease=true 时给 release 包开 android:debuggable，
            // 让 `adb run-as app.dsh.mobile` 能读 files/engine/engine.log、能替换引擎目录。
            // 签名与 versionCode 都不变，因此可以 `adb install -r` 覆盖正式包、数据不丢。
            // CI 不传该属性 → 正式产物仍是 debuggable=false。
            isDebuggable = (project.findProperty("debuggableRelease") == "true")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 28 会触发大量 lint 提示（前台服务类型、通知权限等），
        // 这些是刻意的兼容性决策，不阻断构建。
        abortOnError = false
    }

    packaging {
        // 7bd3ced6（PC 用 ssh 直连 Linux 容器）引入 org.apache.sshd:2.14.0 后，
        // sshd-core 与 sshd-common 各带一份 META-INF/DEPENDENCIES，mergeDebugJavaResource
        // 直接报 "2 files found with path 'META-INF/DEPENDENCIES'" —— **HEAD 因此构建不过**。
        // 这是 Apache 依赖清单文本，运行期无用，排除即可（AGP 官方推荐做法）。
        resources.excludes += "META-INF/DEPENDENCIES"
        jniLibs {
            // 真 PTY 终端：使用本仓库重编的 16 KB 页对齐 libtermux.so（jniLibs/ 下），
            // 覆盖 terminal-view AAR 依赖带入的 4 KB 页旧库（Android 15+ 16KB 页内核需对齐）。
            pickFirsts += listOf("**/libtermux.so")
        }
    }
}





// ── runtime / hardssh 资产（v1.4.20：生成式，不再改工作树）──────────────
// full:      把 runtime.zip 嵌入 assets（离线包）
// noruntime: 不嵌入；APK 首启按 MANIFEST 的 urls 下载运行时
// 两种形态都带 assets/hardssh/（内置插件，启动时由 HardsshInstaller 安装）
//
// 参数:
//   -Ppackage=full|noruntime（默认 full）
//   -PruntimeZip=<zip>        显式指定运行时 zip（CI 用）
//   -PruntimeVersion=<id>     运行时版本（noruntime 建议显式传；full 可从 BUILD.json 推）
//   -PruntimeUrlBase=<url>    下载前缀（tag: .../download/<tag>；否则默认 latest:
//                             .../releases/latest/download，本地构建即此默认，保证能下载）
//   -PproxyBase=https://gh-proxy.org/
//   -PhardsshDir=<dir>        内置插件目录（CI 用；默认找 src/main/assets/hardssh）
//   -Pabi=arm64-v8a|x86_64
// 输出: build/generated/runtimeAssets/{runtime.zip,hardssh/,runtime/MANIFEST.json}
val packageMode = ((project.findProperty("package") as String?) ?: "full").lowercase()
require(packageMode == "full" || packageMode == "noruntime") {
    "package must be full or noruntime, got: $packageMode"
}
val runtimeZipProp = (project.findProperty("runtimeZip") as String?)?.takeIf { it.isNotBlank() }
val runtimeVersionProp = (project.findProperty("runtimeVersion") as String?)?.takeIf { it.isNotBlank() }
val runtimeBuiltAtProp = (project.findProperty("runtimeBuiltAt") as String?)?.toLongOrNull() ?: 0L
val runtimeShaProp = (project.findProperty("runtimeSha256") as String?)?.takeIf { it.isNotBlank() }
val runtimeSizeProp = (project.findProperty("runtimeSize") as String?)?.toLongOrNull() ?: 0L
val runtimeUnpackedProp = (project.findProperty("runtimeUnpacked") as String?)?.toLongOrNull() ?: 0L
val hardsshDirProp = (project.findProperty("hardsshDir") as String?)?.takeIf { it.isNotBlank() }
// v1.5.0：内置「用户态插件」（mobile-layout 等）以 tgz 随 APK 分发，App 首启用
// `dsh plugin add <tgz>` 装进 profile，之后由 WebUI 插件管理页接管（可开关/升级/卸载）。
// 目录需含 bundle.json（version/tgz/sha256）与对应 .tgz。
//   -PmobileLayoutTgzDir=<dir>      CI 用（scripts/pack-profile-plugin.sh 的产物）
//   -PsolutionExplorerTgzDir=<dir>  同上，解决方案浏览器
//   本地覆盖优先级：-P 参数 > local-overrides/<资产目录名>/ > src/main/assets/<资产目录名>/
val mobileLayoutDirProp = (project.findProperty("mobileLayoutTgzDir") as String?)?.takeIf { it.isNotBlank() }
val solutionExplorerDirProp = (project.findProperty("solutionExplorerTgzDir") as String?)?.takeIf { it.isNotBlank() }

/**
 * 内置「用户态插件」资产表：assetRoot → 外部传入目录（可为 null）。
 *
 * 加一个新插件 = 这里加一行 + ProfilePluginProvisioner.REGISTRY 加一行 +
 * CI 的 pack job 加一步。App 侧与构建侧都不再为每个插件单独写逻辑。
 */
val bundledProfilePlugins: List<Pair<String, String?>> = listOf(
    "mobile-layout" to mobileLayoutDirProp,
    "solution-explorer" to solutionExplorerDirProp,
)
val proxyBaseProp = (project.findProperty("proxyBase") as String?) ?: "https://gh-proxy.org/"
val runtimeUrlBaseProp = (project.findProperty("runtimeUrlBase") as String?)?.takeIf { it.isNotBlank() }

fun inferredRuntimeUrlBase(): String {
    if (!runtimeUrlBaseProp.isNullOrBlank()) return runtimeUrlBaseProp
    val refType = System.getenv("GITHUB_REF_TYPE")
    val refName = System.getenv("GITHUB_REF_NAME")?.replace("/", "-")
    // 本地构建（无 CI 环境变量、未显式传 -PruntimeUrlBase）默认指向 latest 稳定运行时，
    // 这样本地编译出的 noruntime 包能通过签名索引（runtime-index.json，同样指向 latest）
    // 解析出 sha256 并下载，避免「编译出来的版本没有下载源」的问题。
    // CI 里 tag 构建钉到具体 tag；main / 分支 / PR 也指向 latest。
    return when {
        refType == "tag" && !refName.isNullOrBlank() ->
            "https://github.com/$updateRepo/releases/download/$refName"
        else ->
            "https://github.com/$updateRepo/releases/latest/download"
    }
}

val prepareRuntimeAssets by tasks.registering {
    val abi = targetAbi
    val mode = packageMode
    val projRoot = rootProject.projectDir
    val outDir = runtimeAssetsDir
    val urlBase = inferredRuntimeUrlBase()
    val unpinned = urlBase.contains("/latest/")
    val zipOverride = runtimeZipProp?.let { file(it) }
    val hardsshOverride = hardsshDirProp?.let { file(it) }
    val mobileLayoutOverride = mobileLayoutDirProp?.let { file(it) }
    val solutionExplorerOverride = solutionExplorerDirProp?.let { file(it) }
    // ★合并（2026-11-09）：两条线对「内置插件 tgz 放哪」给了不同答案 ——
    //   main 走通用 bundledProfilePlugins（mobile-layout 与 solution-explorer 同一套逻辑，
    //   来源 = -P 参数 > local-overrides/<资产名>/ > src/main/assets/<资产名>/）；
    //   feat 把 mobile-layout 的 tgz 挪进 vendor/mobile-layout/（避开 AGP 对 src/main/assets
    //   的自动合并，否则与本任务复制进 runtimeAssetsDir 的同一份触发 Duplicate resources）。
    //   两者不冲突也不等价：通用循环是结构，vendor/ 只是一个额外来源。
    //   故保留通用循环，并在它的候选列表里加入 vendor/<资产名>（见下方 pluginOverrides）。
    val legacyProfilePluginsDir = File(projRoot, "vendor")
    val legacyZip = file("src/main/assets/runtime.zip")
    val legacyHardssh = file("src/main/assets/hardssh")
    outputs.dir(outDir)
    // 运行时 zip / 版本 / URL 都通过属性传入，Gradle 无法可靠推断输入；每次都重建。
    outputs.upToDateWhen { false }
    doLast {
        outDir.deleteRecursively()
        outDir.mkdirs()

        val manifestScript = File(projRoot, "scripts/write-runtime-manifest.py")
        val runtimeOut = File(outDir, "runtime.zip")

        if (mode == "full") {
            val bakMain = File(
                projRoot,
                if (abi == "x86_64") "_runtime_bak/rt-x86_64-zodded.zip" else "_runtime_bak/rt-arm64-zodded.zip",
            )
            val bakRaw = File(
                projRoot,
                if (abi == "x86_64") "_runtime_bak/rt-x86_64-current.zip" else "_runtime_bak/rt-arm64-work.zip",
            )
            val src = zipOverride
                ?: listOf(bakMain, bakRaw, legacyZip).firstOrNull { it.isFile }
                ?: throw GradleException(
                    "runtime: full package needs a runtime zip. Pass -PruntimeZip=<path> or run " +
                        "scripts/build-runtime.sh --abi $abi --out _runtime_bak/rt-$abi.zip",
                )

            fun zipText(zipFile: File, entry: String): String = runCatching {
                ZipFile(zipFile).use { zf ->
                    val e = zf.getEntry(entry) ?: return@use ""
                    zf.getInputStream(e).bufferedReader().readText()
                }
            }.getOrDefault("")

            // 补丁必须在运行时流水线里完成；这里只验，不现场打（避免云端与包内产物分叉）。
            val ptc = zipText(src, "lib/node_modules/@deepseek-ai/dsh-ptc-runtime-node/lib/index.js")
            val ptcOk = ptc.contains("\"LD_LIBRARY_PATH\"") &&
                ptc.contains("Inject the runtime library path unconditionally") &&
                ptc.contains("OPENSSL_CONF") &&
                ptc.contains("SSL_CERT_FILE") &&
                ptc.contains("\"MAGICK_CONFIGURE_PATH\"") &&
                // v1.4.33（issue #65）：bootstrap 路径映射兜底。缺了它时
                // processPathFromHostPath 返回 undefined → 每次 run_code 都报
                // "PTC runtime bootstrap is unavailable"，而文件其实就在那儿。
                ptc.contains("[dsh-android] bootstrap path fallback")
            if (!ptcOk) {
                throw GradleException(
                    "runtime: ${src.name} lacks the PTC patch. Build it with scripts/build-runtime.sh; " +
                        "APK builds must not patch runtime.zip anymore.",
                )
            }
            val poly = zipText(src, "lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html")
            // Do not hardcode the polyfill version here (v1.6.3, 4th site).
            // The same constant used to live in four places (patch script /
            // build-runtime.sh / runtime-build.yml / here); bumping it to v5 broke
            // CI three times in a row. Only patch-webview-polyfill.py owns the
            // version - every validator must accept any vN.
            val polyVer = Regex("legacy-webview polyfill v[0-9]+").find(poly)?.value
            val polyOk = polyVer != null &&
                poly.contains("throwIfAborted") &&
                poly.contains("AbortSignal.any") &&
                poly.contains("Promise.withResolvers")
            if (!polyOk) {
                throw GradleException(
                    "runtime: ${src.name} lacks the WebView polyfill" +
                        (if (polyVer != null) " (found $polyVer but capability markers are missing)" else "") +
                        ". Build it with scripts/build-runtime.sh.",
                )
            }
            logger.lifecycle("runtime: webview polyfill $polyVer")

            src.copyTo(runtimeOut, overwrite = true)
            logger.lifecycle("runtime: embedded ${src.name} (${runtimeOut.length() / 1048576} MB)")
        } else {
            if (legacyZip.isFile) {
                throw GradleException(
                    "noruntime build: app/src/main/assets/runtime.zip exists and would be packaged. " +
                        "Move it out of the source tree (or build the full package instead).",
                )
            }
            logger.lifecycle("runtime: noruntime package — no runtime.zip embedded")
        }

        // 内置 SSH 插件（与 runtime 解耦）
        val hsSrc = hardsshOverride ?: legacyHardssh
        val hsOut = File(outDir, "hardssh")
        if (hsSrc.isDirectory) {
            hsSrc.copyRecursively(hsOut, overwrite = true)
            check(File(hsOut, "lib/index.js").isFile) {
                "hardssh bundle is incomplete: ${hsOut.absolutePath}"
            }
            check(File(hsOut, "node_modules/ssh2/package.json").isFile) {
                "hardssh bundle is missing ssh2: ${hsOut.absolutePath}"
            }
            logger.lifecycle("hardssh: embedded ${hsSrc.absolutePath}")
        } else {
            logger.warn("hardssh: no bundle at ${hsSrc.absolutePath}; SSH workspace will be unavailable")
        }

        // 内置用户态插件 tgz（v1.5.0）：原样进 assets，App 首启交给 `dsh plugin add`。
        // 本地调试把 tgz 丢进 local-overrides/<资产目录>/ 即可覆盖，无需改 CI。
        val pluginOverrides = mapOf(
            "mobile-layout" to mobileLayoutOverride,
            "solution-explorer" to solutionExplorerOverride,
        )
        for ((assetRoot, overrideDir) in bundledProfilePlugins) {
            val src = listOfNotNull(
                pluginOverrides[assetRoot],
                File(projRoot, "local-overrides/$assetRoot").takeIf { it.isDirectory },
                // vendor/<资产名>/：feat 线把 mobile-layout 的 tgz 入库到这里，
                // 不放 src/main/assets/ —— 后者会被 AGP 自动合并一次，而本任务又把它
                // 复制进 runtimeAssetsDir，两份同名条目直接触发 "Duplicate resources"。
                File(legacyProfilePluginsDir, assetRoot).takeIf { it.isDirectory },
                file("src/main/assets/$assetRoot").takeIf { it.isDirectory },
            ).firstOrNull { it.isDirectory }
            val dst = File(outDir, assetRoot)
            if (src != null) {
                src.copyRecursively(dst, overwrite = true)
                check(File(dst, "bundle.json").isFile) {
                    "$assetRoot: bundle.json missing in ${src.absolutePath} " +
                        "(run scripts/pack-profile-plugin.sh)"
                }
                check(dst.listFiles()?.any { it.name.endsWith(".tgz") } == true) {
                    "$assetRoot: no .tgz in ${src.absolutePath}"
                }
                logger.lifecycle("$assetRoot: embedded ${src.absolutePath}")
            } else {
                logger.warn(
                    "$assetRoot: no tgz bundle (searched ${overrideDir ?: "-"}, " +
                        "${File(projRoot, "local-overrides/$assetRoot").absolutePath}, " +
                        "${File(legacyProfilePluginsDir, assetRoot).absolutePath}, " +
                        "${file("src/main/assets/$assetRoot").absolutePath}); " +
                        "this profile plugin will not be preinstalled",
                )
            }
        }

        // MANIFEST.json（生成到 build/，不再改写 src/main/assets）
        val manifestOut = File(outDir, "runtime/MANIFEST.json")
        val args = mutableListOf(
            pythonCmd,
            manifestScript.absolutePath,
            "--out", manifestOut.absolutePath,
            "--package", mode,
            "--abi", abi,
            "--repo", updateRepo,
            "--runtime-url-base", urlBase,
            "--proxy-base", proxyBaseProp,
        )
        if (hsSrc.isDirectory) args += listOf("--hardssh-dir", hsOut.absolutePath)
        if (mode == "full") args += listOf("--runtime-zip", runtimeOut.absolutePath)
        runtimeVersionProp?.let { args += listOf("--runtime-version", it) }
        // builtAt 对两种包都要传：noruntime 的 MANIFEST 也要写全运行时身份，
        // 否则 App 装完运行时只能记下 builtAt=0（见 write-runtime-manifest.py 注释）。
        if (runtimeBuiltAtProp > 0) args += listOf("--runtime-built-at", runtimeBuiltAtProp.toString())
        if (unpinned) {
            args += "--unpinned"
        } else {
            runtimeShaProp?.let { args += listOf("--runtime-sha256", it) }
            if (runtimeSizeProp > 0) args += listOf("--runtime-size", runtimeSizeProp.toString())
            if (runtimeUnpackedProp > 0) args += listOf("--runtime-unpacked", runtimeUnpackedProp.toString())
        }

        val rc = providers.exec {
            commandLine(args)
            isIgnoreExitValue = true
        }.result.get().exitValue
        if (rc != 0 || !manifestOut.isFile) {
            throw GradleException("write-runtime-manifest.py failed (exit=$rc)")
        }
        logger.lifecycle("manifest: ${manifestOut.absolutePath} (package=$mode, abi=$abi)")
    }
}
tasks.named("preBuild") { dependsOn(prepareRuntimeAssets) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareRuntimeAssets) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 扩展中心解包链路：Termux .deb 的 data.tar.xz 解码（纯 Java 实现，~110KB）
    implementation("org.tukaani:xz:1.10")
    // Shizuku 官方 API（m1.25）：bind 服务才能触发授权弹窗与真实 adb-shell 能力
    // aidl 提供 IShizukuService/IRemoteProcess（进程执行），api 提供授权与 binder 封装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // 微软 Edge 神经网络语音合成（v1.2.78）：客户端直连，需 WebSocket 客户端
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // 远程 SSH 开发环境（v1.7.2）：让 PC 用 `ssh -p 2222` 直接进 Linux 容器，
    // VS Code Remote-SSH 也能连。**不依赖容器内的 dropbear** —— 实测 dropbear
    // 能装能监听（0.0.0.0:2222 真的在宿主网络命名空间里），但它的登录子进程
    // 在 exec 登录 shell 时打 REEXEC: preload FAIL（loader 自我重执行路径的边角），
    // 而容器内普通 fork+exec、pty+setsid+dup2+execv 全都实测正常 —— 说明那是
    // dropbear 特有的启动序列踩到了 loader 的限制。
    //
    // 所以改为在 App 进程内跑 SSH 服务端，每条命令直接走 LinuxContainerRunner
    // （产线已用 40+ 轮的稳定路径），完全绕开容器内的进程启动边角。
    //
    // 为什么 MINA SSHD 而不是自己写协议：SSH 的密钥交换、加密、通道多路复用、
    // 认证协商都极易写错，而 VS Code Remote-SSH 会用到不少边角（肤纹、多通道、
    // 窗口调整、exit-status）。用成熟实现，我们只写「收到 exec 请求后丢给容器」。
    //
    // 排除项说明：
    //  · sshd-sftp 不引 —— 它要额外的文件系统抽象，而 scp/sftp 可以在容器内
    //    用 `ssh exec` 跑 `cat` 代替（VS Code Remote-SSH 不用 sftp）；
    //  · bcrypt/pkcs11/slf4j 都不需要：用明文密码 + 现有 benyHeader 认证。
    implementation("org.apache.sshd:sshd-core:2.14.0") {
        // Android 没有 slf4j 绑定，留着只会启动时刷一堆 no-op 警告
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    // Android 没有 java.security 的 Ed25519 实现（API 33 才有），MINA SSHD 默认
    // 会尝试用它来生成 host key。加这个纯 Java 实现兜底，否则在 Android 12/13
    // 上生成 host key 会抛 NoSuchAlgorithmException。
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // 离线语音识别已移除（v1.2.75）：small 模型质量差、大模型 228MB 不划算。
    // 语音通道 = 系统识别 → 云端 API（免费或自建，OpenAI 兼容），见 CloudAsr。
    // 离线 OCR（v1.2.123）：ML Kit 中文文本识别 bundled 版——模型随 APK 分发，
    // 不依赖 Google Play 服务（国内 ROM 可用）。无障碍节点树缺失的场景
    // （WebView 不暴露节点、自绘/Canvas 界面）从截图提取文字作为 dump 兜底。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 真 PTY 终端（对齐 DSHA）：Termux 拆出的 terminal-view / terminal-emulator
    // （Apache 2.0）。坐标是 JitPack 多模块真实 groupId（com.termux.termux-app）。
    // AAR 自带的 jni/.../libtermux.so 为 4 KB 页对齐，Android 15+ 的 16 KB 页内核
    // 真机上 dlopen 会失败 —— 本仓库用 scripts/termux-jni 重编了同版本 16 KB 页
    // 对齐的 libtermux.so（jniLibs/ 下），由 packaging.pickFirsts 覆盖 AAR 旧库。
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    // Material 3 Expressive（v1.6.x）：设置体系全部改用原生 MD3 组件。
    // 1.14.0 是首个把 M3E 组件（MaterialButtonGroup / LoadingIndicator /
    // MaterialShapes 等）并入正式线的稳定版；compileSdk 36 满足其要求。
    // 组件随库分发，不依赖 Play 服务，国内 ROM 可用。
    implementation("com.google.android.material:material:1.14.0")
    //
    // ★合并说明（2026-11-09）：两侧都有这条依赖但来源不同 ——
    //   main 经 PR #97 的 MD3 界面需要它；feat 的 MD3 设置页重构也需要它。
    //   合并后 MD3 主题整包保留，因此这条依赖必须留下，否则 MaterialCardView /
    //   MaterialAlertDialogBuilder 等全部编译不过。
}

// ---------------------------------------------------------------------------
// 把 Kotlin 编译器自身的 classpath 打印成绝对路径（v1.7.15）。
//
// 为什么需要它：`scripts/run-a11y-jvm-test.sh` 需要 kotlin-compiler-embeddable
// 及它的运行时依赖才能在本机直接驱动 K2JVMCompiler。原实现是去 Gradle 缓存里
// 按 artifact 名猜路径 —— 但 `gradle :app:dependencies` **只解析元数据、不下载
// artifact**，干净 runner 上缓存里只有 pom 没有 jar，脚本于是整体 SKIP。
// 版本号更不能写死：kotlinCompilerClasspath 里 kotlin-reflect 是 1.6.10、
// annotations 是 13.0，与项目的 Kotlin 1.9.24 无关。
//
// `configuration.files` 会强制下载 artifact，再把绝对路径打印出来，
// 是唯一不依赖缓存布局的做法。用法（工作流里就是这么调的）：
//   gradle -p . -q :app:printKotlinCompilerClasspath > /tmp/kcp.txt
//   KOTLIN_CP_FILE=/tmp/kcp.txt bash scripts/run-a11y-jvm-test.sh
tasks.register("printKotlinCompilerClasspath") {
    group = "verification"
    description = "打印 kotlinCompilerClasspath 的 jar 绝对路径（供 run-a11y-jvm-test.sh 使用）"
    val jars = configurations.named("kotlinCompilerClasspath")
    doLast {
        jars.get().files
            .filter { it.name.endsWith(".jar") }
            .forEach { println(it.absolutePath) }
    }
}
