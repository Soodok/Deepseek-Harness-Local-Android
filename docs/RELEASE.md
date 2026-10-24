# 发布与签名（Android）

本文覆盖 **正式包签名材料的保管**、**GitHub Actions Secrets 配置**、**CI 构建 debug + release** 与 **tag 发布**。

> **签名即身份。** Android 只允许同包名、同证书的 APK 覆盖安装。
> 一旦对外发过正式包，**私钥必须永久保管**；换新 key 等于换应用身份，老用户只能卸载重装。
> 当前仓库**尚未用这把新 key 发布过任何版本**——如果还能找到旧的 `dsh-release.keystore`
>（证书 `CN=DSH Mobile`，SHA-256 `d28ca4f5…92c8`），应优先改用旧 key，避免老用户被迫卸载。

---

## 1. 本机签名材料（绝不入库）

全部位于 `keystore/`（`.gitignore` 已忽略）：

| 文件 | 说明 |
|---|---|
| `dsh-release.keystore` | PKCS12，RSA 4096 / SHA256withRSA，有效期 10000 天，alias `dsh-release` |
| `dsh-release.credentials.txt` | 口令、alias、证书指纹（已限制为当前用户可读） |
| `dsh-release.keystore.base64` | 给 GitHub Secret 用的单行 base64（添加后可从工作目录删除，但要确保另有加密备份） |
| `release.properties` | 本地 Gradle 签名配置（`assembleRelease` 自动签名） |

当前证书 SHA-256 **不写入仓库**：值在本地 `keystore/dsh-release.credentials.txt` 的
`certSHA256` 行（GitHub 变量 `RELEASE_CERT_SHA256` 填它；公开指纹，不是私钥）。
获取方式：

```bash
apksigner verify --print-certs app-release.apk | grep 'SHA-256 digest'
# 或
keytool -list -v -keystore keystore/dsh-release.keystore | grep SHA256
```

> ⚠️ **GitHub Secrets 不是备份**：Secret 保存后无法再读回，Secret 丢失 + 本机磁盘损坏 =
> 私钥永久丢失。请把整个 `keystore/` 至少备份到 **两处**（密码管理器附件 / 加密压缩包 +
> 离线介质），并用不同密码保护。备份里必须包含 `dsh-release.keystore` 和口令。

本地构建正式包（可选）：

```powershell
# keystore/release.properties 已就绪，Gradle 会自动读取
gradle -p . assembleRelease -Pabi=arm64-v8a
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

CI 使用环境变量覆盖本地配置：`DSH_RELEASE_STORE_FILE` / `DSH_RELEASE_STORE_PASSWORD` /
`DSH_RELEASE_KEY_ALIAS` / `DSH_RELEASE_KEY_PASSWORD`。

---

## 2. 在 GitHub 添加（一次性）

仓库 → **Settings → Secrets and variables → Actions**。

### 2.1 Repository secrets（必填，构建正式签名包用）

| Secret | 值 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `keystore/dsh-release.keystore.base64` 的全部内容（单行） |
| `RELEASE_STORE_PASSWORD` | `dsh-release.credentials.txt` 里的 `storePassword` |
| `RELEASE_KEY_ALIAS` | `dsh-release` |
| `RELEASE_KEY_PASSWORD` | 同 `storePassword`（PKCS12 密钥库两者相同） |

用 GitHub CLI 添加（本机未安装 `gh`，可在任意装有 `gh` 的机器执行）：

```bash
gh secret set RELEASE_KEYSTORE_BASE64 -R XiaoPb/Deepseek-Harness-Local-Android \
  < keystore/dsh-release.keystore.base64
gh secret set RELEASE_STORE_PASSWORD -R XiaoPb/Deepseek-Harness-Local-Android
gh secret set RELEASE_KEY_ALIAS -R XiaoPb/Deepseek-Harness-Local-Android --body dsh-release
gh secret set RELEASE_KEY_PASSWORD -R XiaoPb/Deepseek-Harness-Local-Android
```

重新生成 base64（换 key 或误删时）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore/dsh-release.keystore")) |
  Set-Content keystore/dsh-release.keystore.base64 -NoNewline
```

```bash
# Linux / macOS
base64 < keystore/dsh-release.keystore | tr -d '\n' > keystore/dsh-release.keystore.base64
```

### 2.2 Repository variable（建议，防签错 key）

**Settings → Secrets and variables → Actions → Variables → New repository variable**

| Variable | 值 |
|---|---|
| `RELEASE_CERT_SHA256` | 本地 `keystore/dsh-release.credentials.txt` 的 `certSHA256`（公开指纹，不是私钥） |

CI 每次 tag 构建都会用 `apksigner verify --print-certs` 比对实际签名证书；不一致直接失败，
杜绝「secret 被换成了另一把 key 却照常发布」。

### 2.3 可选加固：Environment 保护

若要防止误发，可建 **Settings → Environments → `release`**，加 Required reviewers，
并把上面 4 个 secret 移到该 Environment（此时需要把 workflow 的 `build-apk` 或新发布 job
指定 `environment: release`）。当前实现使用 Repository secrets，保持简单。

---

## 3. CI 行为

| 触发 | 行为 |
|---|---|
| push 分支（非 main）/ PR | 只构建 **debug**（正式签名材料不存在时不跑 `assembleRelease`，不会失败） |
| push `main` | 构建 debug + release（Gradle 自动签名、`apksigner` 校验证书），更新 **edge 预发布**；缺 secrets 时 edge 任务失败并提示配置（不会发布半套） |
| push tag `v*` | **必须有** `RELEASE_*` secrets：构建 debug + release、校验证书指纹，缺 secret 直接失败 |
| tag 且通过 | `publish-release` 发布双架构 × 双变体到正式 GitHub Release |

产物命名（文件名带当前 `versionName`）：

```
# main / edge（ref = main，版本段 -v<version>）
dsh-android-main-arm64-v8a-v1.4.17-debug.apk
dsh-android-main-arm64-v8a-v1.4.17-release.apk
dsh-android-main-x86_64-v1.4.17-debug.apk
dsh-android-main-x86_64-v1.4.17-release.apk

# tag 发布（ref 本身是 v<version>，不再重复版本段）
dsh-android-v1.4.18-arm64-v8a-debug.apk
dsh-android-v1.4.18-arm64-v8a-release.apk
dsh-android-v1.4.18-x86_64-debug.apk
dsh-android-v1.4.18-x86_64-release.apk
```

> - 每次发布完成后会**清理 Release 中不在本次产物列表里的旧文件名**（edge 的滚动资产因此
>   不会残留旧命名）。
> - `*-release.apk`：正式签名，可覆盖升级；`*-debug.apk`：debug 签名，仅测试，不能覆盖正式版。
> - **edge** 是一个滚动 pre-release（tag `edge`），每次 `main` 推送覆盖同名资产，方便用户随时下载；
>   `v*` tag 才是正式版本。
> - `publish-release` / `publish-edge` 只对 `XiaoPb/Deepseek-Harness-Local-Android` 生效
>   （硬门禁，避免 fork 误发）。旧 `release` job（debug 自动发布）仅用于
>   `jahdaganj02ki-sketch/...`，两者互不影响。

---

## 4. 发版步骤

1. **升版本**（同一版本只能有一个正式包）：
   `app/build.gradle.kts` 的 `versionCode` +1、`versionName` 改为新版本。
2. 跑本地检查：`python scripts/check-i18n.py`、测试、`assembleDebug`。
3. 提交并推送 `main` —— CI 会自动更新 **edge** 预发布（debug + release），用户可先装。
4. 正式发版：打 tag 并推送（tag 必须等于 `v<versionName>`，否则 publish 阶段失败）：

   ```bash
   git tag v1.4.18
   git push origin v1.4.18
   ```

5. CI 完成后在 Releases 查看：`*-release.apk` 为正式包，`*-debug.apk` 仅测试。

### 发布说明（自动生成）

`publish-release` / `publish-edge` 都会调用
[scripts/generate-release-notes.sh](../scripts/generate-release-notes.sh)，通过 `body_path`
写入 Release 说明，不需要手写 `RELEASE_NOTES_*.md`：

- **变更范围**：tag 发布取「上一个语义化版本 tag → 当前 tag」；edge 取 `edge → HEAD`；
  没有旧 tag 时取最近 30 个提交。
- **变更分类**：按 conventional commit 前缀分为 新功能 / 问题修复 / 性能优化 / 重构 /
  构建与依赖 / CI/CD / 文档 / 测试 / 代码风格 / 其他。
- **版本信息**：tag、`versionName`、`versionCode`、提交短 SHA、提交数量、构建时间（UTC）、
  架构、签名方式、内置 `dsh-hardssh` 的 `HARDSSH_REF`。
- **安装包表**：每个 APK 的架构（arm64-v8a / x86_64）、类型（release / debug）、大小、
  SHA-256，方便下载后校验。
- **签名与安装**：正式 / debug 签名区别、旧证书升级失败时的处理、`apksigner` 校验命令；
  末尾附「本次更新贡献者」与「项目累计 Top 10」两张清单，以及 GitHub compare 链接；CI 通过 GitHub API（compare / contributors）把贡献者映射为 GitHub 账号链接，API 不可用时回退纯 git 名称。

> 该脚本只读 git 历史与 `apk/` 产物，不接触任何签名材料；tag 发布仍会先校验
> tag 与 `versionName` 一致。

---

## 5. 签名校验（任何拿到 APK 的人都能做）

```bash
apksigner verify --print-certs dsh-android-v1.4.18-arm64-v8a-release.apk
# Signer #1 certificate SHA-256 digest: <应与 RELEASE_CERT_SHA256 完全一致>
```

应输出与 `RELEASE_CERT_SHA256` 一致的值；`apksigner verify` 同时校验 v1/v2/v3 完整性。

---

## 6. 换 key / 丢 key

- **换 key**：替换 `keystore/dsh-release.keystore` 与 4 个 secret、更新 `RELEASE_CERT_SHA256`；
  已装旧版的用户**无法覆盖升级**，需在 Release 说明中明确要求卸载重装。
- **丢 key**：无法恢复。能做的只有生成新 key、换应用身份，并承担用户卸载成本。
- **保管要点**：两处备份、加密存储、口令与 keystore 分开存放；不要提交到任何 git 仓库，
  不要发到聊天工具；CI 只在 tag 构建时解码，构建结束后的临时文件随 runner 销毁。
