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
        versionCode = 336
        // v1.5.0 起进入 1.5 系列：设备能力（蓝牙报文/WiFi 底层/定时提醒）+ AI 开关 UI。
        // versionName 允许带后缀（Android 不校验格式）。v1.5.0 起 CI 支持两种 tag 形态
        // （见 android-build.yml:68-86）：v1.5.0（正式版，prerelease: false）与
        // v1.5.0-beta.2（试发行，prerelease: true），但 tag 必须与 versionName 完全拼得上
        // （publish-release 的 `test "$GITHUB_REF_NAME" = "v$V"`），所以打 tag 前先确认这里。
        versionName = "1.5.0-beta.4"

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
//   -PmobileLayoutTgzDir=<dir>   CI 用（scripts/pack-mobile-layout.sh 的产物）
//   本地覆盖优先级：-P 参数 > local-overrides/mobile-layout/ > src/main/assets/mobile-layout/
val mobileLayoutDirProp = (project.findProperty("mobileLayoutTgzDir") as String?)?.takeIf { it.isNotBlank() }
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
    val localMobileLayout = File(projRoot, "local-overrides/mobile-layout")
    val legacyMobileLayout = file("src/main/assets/mobile-layout")
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
            val polyOk = poly.contains("legacy-webview polyfill v4") &&
                poly.contains("throwIfAborted") &&
                poly.contains("AbortSignal.any") &&
                poly.contains("Promise.withResolvers")
            if (!polyOk) {
                throw GradleException(
                    "runtime: ${src.name} lacks the WebView polyfill v4. Build it with scripts/build-runtime.sh.",
                )
            }

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
        // 本地调试把 tgz 丢进 local-overrides/mobile-layout/ 即可覆盖，无需改 CI。
        val mlSrc = listOfNotNull(
            mobileLayoutOverride,
            localMobileLayout.takeIf { it.isDirectory },
            legacyMobileLayout.takeIf { it.isDirectory },
        ).firstOrNull { it.isDirectory }
        val mlOut = File(outDir, "mobile-layout")
        if (mlSrc != null) {
            mlSrc.copyRecursively(mlOut, overwrite = true)
            check(File(mlOut, "bundle.json").isFile) {
                "mobile-layout: bundle.json missing in ${mlSrc.absolutePath} " +
                    "(run scripts/pack-mobile-layout.sh)"
            }
            check(mlOut.listFiles()?.any { it.name.endsWith(".tgz") } == true) {
                "mobile-layout: no .tgz in ${mlSrc.absolutePath}"
            }
            logger.lifecycle("mobile-layout: embedded ${mlSrc.absolutePath}")
        } else {
            logger.warn(
                "mobile-layout: no tgz bundle (searched ${mobileLayoutOverride ?: "-"}, " +
                    "${localMobileLayout.absolutePath}, ${legacyMobileLayout.absolutePath}); " +
                    "the mobile layout plugin will not be preinstalled",
            )
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
}
