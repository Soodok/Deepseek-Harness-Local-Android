# Git 工作流规范

> 这份文档解决一个具体问题：**我们的推送通道曾经会重写提交 SHA**，导致下游
> fork 每次同步都得 force-push。现在已从根上修掉，本文记录规范与操作方法。
>
> 推送上游前的**强制合并测试流程**与红线清单见 [`COLLABORATION.md`](COLLABORATION.md)
> ——本文聚焦推送通道技术细节，两者配合使用。

## 一、核心原则

### 1. 永远不重写已推送的历史

- 已推到 `main` 的提交，**不改写、不 squash、不 rebase**。
- 撤销错误提交用 `git revert`（新增一个反向提交），不要 `reset --hard` + 强推。

### 2. 合并 PR 用 `--no-ff`，保留提交线路

```bash
git fetch origin
git merge <fork-remote>/<branch> --no-ff -m "merge: <PR 号> <标题>（@作者）"
```

**不要 squash。** 协作者的每个提交都应保留自己的 SHA 与作者信息 —— 这是
GitHub 上 PR 能显示「已合并」、贡献者的贡献记录能正确归属的前提。

### 3. 推送方式优先级

| 顺序 | 方式 | 说明 |
| --- | --- | --- |
| 1 | `git push origin main` | 本机网络能直连 github.com:443 时，最简单也最正确 |
| 2 | `python scripts/push-via-api.py` | 直连被阻断时的通道，**已支持保持原始 SHA** |
| 3 | ~~重建提交式推送~~ | **已废弃，不要再用** |

`scripts/push-via-api.py` 现在的行为：
- 复用每个提交的**原始 tree SHA**；
- 带完整 `author` **和** `committer`；
- 父提交沿用原始 SHA；
- 推完本地与远端 SHA 完全一致（`git rev-list --left-right --count` 应为 `0 0`）。

### 4. fetch 走代理，push 不走

本机（中国大陆网络）实测：

| 操作 | 直连 github.com:443 | gh-proxy 前缀代理 |
| --- | --- | --- |
| `git fetch` / `ls-remote` | ✗ 连接重置 | ✓ 可用 |
| `git push`（receive-pack） | ✗ | ✗ 不支持 POST |
| GitHub API (api.github.com) | ✓ 可用 | — |

所以：**fetch 用代理，push 用 API 脚本**。脚本已内置这个逻辑，无需手工配置。

## 二、日常操作

### 收协作者的 PR

```bash
# 1) 拉远端最新（脚本会自动走代理）
git fetch origin

# 2) 拉协作者 fork（首次需要 add remote）
git remote add <name> https://github.com/<user>/<repo>.git
git fetch <name> main

# 3) 确认分叉与可合并性
git merge-base --is-ancestor origin/main HEAD && echo "本地领先，安全"
git merge-tree --write-tree origin/main <name>/main   # 无 CONFLICT 即可干净合并

# 4) 审代码（重点看：是否改动别人文件、是否引入硬编码色、逻辑是否合理）
git diff origin/main...<name>/main

# 5) --no-ff 合并
git merge <name>/main --no-ff -m "merge: PR #<n> <标题>（@<author>）"

# 6) 全量验证（见下节）

# 7) 推送
git push origin main || python scripts/push-via-api.py

# 8) 核对 SHA 是否保持一致
git fetch origin && git rev-parse origin/main main   # 两个值应相同

# 9) 回复并关闭 PR
```

### 发版

```bash
# 1) 升版本（versionCode +1，versionName +1）
# 2) 提交并推送
# 3) 打 tag —— 必须指向远端 main 的实际 SHA
git fetch origin
git tag v<version> origin/main
git push origin v<version> || python scripts/push-via-api.py   # 脚本会推 refs
```

**为什么必须走 tag**：`runtime-index.json` 的签名私钥只在 CI secrets 里
（`RELEASE_KEYSTORE_BASE64`），本地签不出来。而 App 的 `RuntimeIndexClient`
验签失败会**直接拒绝**。所以正式发布只能由 CI 的 `publish-release` job 完成。

## 三、全量验证清单（发版前必跑）

```bash
python scripts/check-i18n.py                      # 三/四语串同步
for f in scripts/probes/_test_*.py; do python "$f"; done
gradle :app:compileDebugKotlin -Pabi=x86_64 -Ppackage=noruntime
gradle :app:assembleDebug -Pabi=x86_64 -Ppackage=noruntime
gradle :app:assembleDebug -Pabi=arm64-v8a -Ppackage=full -PruntimeZip=<zip>
```

已知预期失败（不算回归）：
- `_test_corepack_hardlink.py` —— 它读 `app/src/main/assets/runtime.zip`，而该文件
  已按 PR #48 架构挪到 `build/generated/runtimeAssets/`。探针本身待更新。

模拟器冒烟（可选但推荐）：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop app.dsh.mobile
adb shell am start -W -n app.dsh.mobile/.MainActivity
adb shell pidof app.dsh.mobile          # 应返回 pid
adb logcat -d | grep -E "FATAL|AndroidRuntime"   # 应为空
```

## 四、探针（probes）的维护约定

探针是**行为契约的守卫**，不是快照。当设计**有意变更**时，要更新断言而不是删掉它：

```python
# 旧设计
check("bubble: tail proportional to font", "tailH = (em * 0.54f)" in bubble)
# 新设计（对齐 web 端 CSS border 三角）—— 守卫的是「随字号缩放」这个性质
check("bubble: tail proportional to font", "tailH = (em * 0.3736f)" in bubble)
```

判断标准：**断言要表达意图，不要表达实现细节。** 上面两项都是「尾巴随字号缩放」，
系数变了但意图没变，所以改系数保留断言。

## 五、CI 注意事项

- `gradle.properties` 的 `org.gradle.jvmargs` 现在是 **6G**。`compressDebugAssets`
  要处理 ~126MB 的 runtime.zip，2G 必挂（run 39063013662），4G 仍偶发
  （run 40543616917）。GitHub hosted runner 16GB，6G 安全。
- 并发规则 `cancel-in-progress: main`：推新提交会取消 main 上正在跑的旧构建，
  这是预期行为，不是失败。
- `publish-release` / `publish-preview` 在非 tag 的 push 上显示 skipped，正常。

## 六、历史事故与教训

| 时间 | 事故 | 教训 |
| --- | --- | --- |
| 2026-10-04 | 工作区 CRLF 推进远端，CI `bad interpreter` | 推送内容必须从 git 对象库读（`cat-file`），不读工作区 |
| 2026-10-27 | API 推送覆盖远端新提交，丢 32 文件 | **推送前必须 fetch 并确认本地含远端最新** |
| 2026-10-27 | 整文件取一侧解决冲突，丢主题接线 | rebase/merge 冲突**绝不能整文件取一侧** |
| 2026-10-28 | API 推送重写 SHA，下游 fork 需 force-push | 推送要按对象身份原样上传，保留 tree/author/committer |
