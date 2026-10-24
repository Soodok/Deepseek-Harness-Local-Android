import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

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
    (System.getenv(envName) ?: releaseProps.getProperty(propName))?.takeIf { it.isNotBlank() }

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

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dsh.mobile"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        versionCode = 268
        versionName = "1.4.18"

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

    // 正式签名在材料齐全时启用；缺失时保持 unsigned（不阻断 debug/PR 构建）。
    signingConfigs {
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
}





// ── runtime 自动匹配 ABI（v1.2.127）─────────────────────────────────────
// 模拟器（x86_64）与真机（arm64）构建此前要手动替换 assets/runtime.zip（126MB）
// 并手改 MANIFEST —— 实测踩过"忘了换 → Android 14 报 libz.so EM_AARCH64"。
// 现按 -Pabi 自动选源（_runtime_bak/rt-{x86_64-current,arm64-work}.zip），
// 用 .abi 戳记避免重复复制大文件。
val prepareRuntime by tasks.registering {
    val abi = targetAbi
    val projRoot = rootProject.projectDir
    val assetsZip = file("src/main/assets/runtime.zip")
    val manifest = file("src/main/assets/runtime/MANIFEST.json")
    val stamp = file("src/main/assets/runtime/.abi")
    outputs.upToDateWhen { false }
    doLast {
        val last = if (stamp.exists()) stamp.readText().trim() else ""
        if (last == abi && assetsZip.isFile) {
            // v1.4.16（审查 P1）：skip 分支只免掉**大文件复制与去重**，
            // 不再整体 return —— 下面的 PTC 补丁强制校验必须每次构建都跑。
            // 旧实现 `return@doLast` 让 CI（.abi 随 git checkout 存在且恒为 arm64-v8a）
            // 永远跳过校验：v1.4.13 建的「缺补丁即中止构建」门禁在 CI 全链路不可达，
            // fresh clone 还会打出缺 runtime.zip 的空壳 APK 且构建成功。
            logger.lifecycle("runtime: 已是 " + abi + "，跳过复制/去重（仍执行补丁校验）")
        } else {
        val src = if (abi == "x86_64") File(projRoot, "_runtime_bak/rt-x86_64-current.zip")
                  else File(projRoot, "_runtime_bak/rt-arm64-work.zip")
        if (!src.exists()) {
            // 源缺失且 assets 里也没有可用包 → 这里必须硬失败而不是静默通过：
            // 否则 fresh clone（runtime.zip 被 gitignore）会打出没有引擎的 APK。
            if (!assetsZip.isFile) {
                throw GradleException(
                    "runtime: 源缺失 " + src.absolutePath + " 且 assets/runtime.zip 不存在 — " +
                        "会打出没有引擎的空壳 APK。请先运行 scripts/collect-termux-runtime.sh " +
                        "或从 Release 获取 runtime.zip。"
                )
            }
            logger.warn("runtime: 源缺失 " + src.absolutePath + "，使用 assets 现有包")
        } else {
        // ── 去重（v1.4.11）：上游 0.2.1-rc.2 改成了「逐包嵌套」依赖布局，
        // 每个 @deepseek-ai 包里都嵌了一份完整 zod（612 个文件）——全库 3 万多个
        // 冗余文件、200MB+。实测冷启动 runtime-install：
        //   · work（未去重）     41.99s
        //   · zodded（去重）     11.81s   ← 降 72%
        //   · trimmed（再裁 .d.ts/src）13.74s
        // 选 zodded 而不是 trimmed：trimmed 删掉了类型声明与源码，仓库里有 773 处
        // exports 指向这些文件；运行时虽然不加载，但用户插件做 tsc 校验时会缺文件。
        // zodded 只合并冗余副本（版本语义完全不变），风险最低。
        //
        // 去重产物按 abi 缓存。判断缓存是否可用用**实际内容**而不是时间戳：
        // 复制/解压都会改 mtime，时间戳比较在 CI 上不可靠。这里只检查缓存包
        // 里还有没有嵌套 zod —— 有就必须重建。
        val srcAbi = if (abi == "x86_64") "x86_64" else "arm64"
        val zodded = File(projRoot, "_runtime_bak/rt-" + srcAbi + "-zodded.zip")
        val dedupeScript = File(projRoot, "scripts/dedupe-zod.py")
        fun stillHasNestedZod(f: File): Boolean {
            if (!f.isFile) return true
            // 注意：Kotlin DSL 里 `java` 会被 Gradle 的 `java` extension 遮蔽，
            // 所以必须用完整 import 后的类型名（见文件顶部的 ZipFile import）。
            return ZipFile(f).use { zf ->
                zf.entries().asSequence().any { e ->
                    !e.isDirectory &&
                        e.name.contains("/node_modules/zod/") &&
                        !e.name.startsWith("lib/node_modules/zod/")
                }
            }
        }
        val cacheOk = zodded.isFile && !runCatching { stillHasNestedZod(zodded) }.getOrDefault(true)
        if (cacheOk) {
            logger.lifecycle("runtime: 使用去重包 " + zodded.name + "（无嵌套 zod）")
            zodded.copyTo(assetsZip, overwrite = true)
        } else if (dedupeScript.isFile) {
            logger.lifecycle("runtime: 正在生成去重包（约 1 分钟）…")
            val rc = providers.exec {
                commandLine("python", dedupeScript.absolutePath, src.absolutePath,
                    zodded.absolutePath, "--backup-dir", File(projRoot, "_runtime_bak").absolutePath)
                isIgnoreExitValue = true
            }.result.get().exitValue
            // v1.4.16 审查 P2：runCatching 与上面 cacheOk 分支一致 ——
            // dedupe 中途被杀留下的半成品 zip 会让 ZipFile 构造抛异常，
            // 未捕获会直接炸构建而不是走「回退未去重源」的兜底。
            val zoddedOk = zodded.isFile &&
                runCatching { !stillHasNestedZod(zodded) }.getOrDefault(false)
            if (rc == 0 && zoddedOk) {
                zodded.copyTo(assetsZip, overwrite = true)
                logger.lifecycle("runtime: 去重完成 " + zodded.name)
            } else {
                logger.warn("runtime: 去重失败（exit=" + rc + "），回退到未去重源（启动会明显变慢）")
                src.copyTo(assetsZip, overwrite = true)
            }
        } else {
            logger.warn("runtime: 去重脚本缺失，使用未去重源（启动会慢 2-3 倍）")
            src.copyTo(assetsZip, overwrite = true)
        }
        } // src.exists()
        } // stamp/else（skip 分支的另一半）
        // ── PTC 补丁校验（v1.4.13，v1.4.16 起对 skip 分支同样生效）：
        // **必须**在复制完之后、算 sha 之前执行，且无论上面走哪条分支都要跑。
        //
        // 血的教训：v1.4.12 我把构建切到去重包，而那份 zodded.zip 是**在我加 PTC
        // 补丁之前**生成的 —— 结果 run_code 子进程又拿不到 LD_LIBRARY_PATH，
        // 用户报「PTC 模式又用不了了」（CANNOT LINK EXECUTABLE ... libz.so.1 not found）。
        //
        // 根因不是补丁写错，而是**补丁只存在于某个 zip 产物里，没有进入构建流程**：
        // 一旦换了源包，补丁就静默消失。所以这里把「校验 + 必要时现场打补丁」变成
        // 构建的硬步骤：缺补丁就打（幂等），打完还缺就**中止构建**，绝不发坏包。
        // v1.4.16 审查 P1：旧实现把这段放在 skip 分支 return 之后，CI 上永不执行。
        val ptcScript = File(projRoot, "scripts/patch-ptc-runtime-android.py")
        val ptcTarget = "lib/node_modules/@deepseek-ai/dsh-ptc-runtime-node/lib/index.js"
        fun ptcPatched(f: File): Boolean {
            if (!f.isFile) return false
            return runCatching {
                ZipFile(f).use { zf ->
                    val e = zf.getEntry(ptcTarget) ?: return@use false
                    val text = zf.getInputStream(e).bufferedReader().readText()
                    // 五个标记都要在（v1.4.16 审查 P1-2：旧版只有 4 项，且不含
                    // v2 白名单标记 —— 一份只带 LD_LIBRARY_PATH 的 v1.4.13 形态
                    // 补丁也能通过校验，27 个扩展工具链变量缺失无告警）。
                    // MAGICK_CONFIGURE_PATH 是 v2 白名单的独有成员，用作版本标记
                    //（与 patch 脚本的 ALLOWLIST_V2_MARKER 对齐）。
                    text.contains("\"LD_LIBRARY_PATH\"") &&
                        text.contains("Inject the runtime library path unconditionally") &&
                        text.contains("OPENSSL_CONF") &&
                        text.contains("SSL_CERT_FILE") &&
                        text.contains("\"MAGICK_CONFIGURE_PATH\"")
                }
            }.getOrDefault(false)
        }
        if (!ptcPatched(assetsZip)) {
            if (ptcScript.isFile) {
                logger.lifecycle("runtime: PTC 补丁缺失，正在应用…")
                val rc = providers.exec {
                    commandLine("python", ptcScript.absolutePath, assetsZip.absolutePath)
                    isIgnoreExitValue = true
                }.result.get().exitValue
                if (rc != 0 || !ptcPatched(assetsZip)) {
                    throw GradleException(
                        "runtime: PTC 补丁应用失败（exit=$rc）。" +
                            "发出去的包会让 run_code 报 libz.so.1 not found —— 中止构建。"
                    )
                }
                logger.lifecycle("runtime: PTC 补丁已应用")
            } else {
                throw GradleException(
                    "runtime: 找不到 " + ptcScript.absolutePath +
                        "，且当前 runtime 没有 PTC 补丁 —— run_code 会失败。中止构建。"
                )
            }
        } else {
            logger.lifecycle("runtime: PTC 补丁已存在")
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(assetsZip.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        val updated = manifest.readLines().joinToString("\n") { line ->
            when {
                line.contains("\"version\"") -> "  \"version\": \"v1.2.102-" + abi + "\","
                line.contains("\"sha256\"") -> "  \"sha256\": \"" + sha + "\","
                else -> line
            }
        }
        manifest.writeText(updated)
        stamp.writeText(abi)
        logger.lifecycle("runtime: → " + abi)
    }
}
tasks.named("preBuild") { dependsOn(prepareRuntime) }

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
}