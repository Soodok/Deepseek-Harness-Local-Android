# 仓库卫生与迭代节奏规范（Repository Hygiene）

> **一句话核心规则：每个合并动作都要有对应的收尾动作——分支合完就删、worktree
> 用完就移、产物验证完就清。不留"以后再说"的残留。**
>
> 本文防的是 2026-10-31 暴露的一类系统性问题：一晚上合并了多个 PR，分支、
> worktree、APK、截图、runtime 缓存全部堆在仓库里，最后连"哪个分支还有
> 未合入的提交"都说不清。清理时一次扫出 **19 个本地分支 / 7 个 worktree /
> 4GB+ 产物**。
>
> 配套文档：[`COLLABORATION.md`](COLLABORATION.md)（合并流程红线）、
> [`git-workflow.md`](git-workflow.md)（推送通道与 SHA 保持）。

## 一、为什么"合并没做好会引发 bug"

三类问题，因果链都真实发生过：

| 现象 | 机制 | 本次实例 |
| --- | --- | --- |
| **合并后行为回退** | 本地改了 A，PR 也改了 A，`--no-ff` 合并时选了 PR 版，本地修复静默消失 | mobile-layout 0.1.99 的抽屉描边改版，被 PR #82 换成上游 0.2.11 后丢失 |
| **残留物互相污染** | 旧 worktree 连着旧分支，在其中构建会用错代码；旧 APK 装了不知道是哪版 | 7 个 worktree 中 4 个在 `%TEMP%` 下，指向 detached HEAD |
| **版本线断裂** | 分支上钉的版本号 npm 上不存在，CI 打包直接 404 | `MOBILE_LAYOUT_VERSION=0.1.99` → `npm error code E404` |

**共同根因**：合并被视为一次性动作，而不是"合并 + 收尾"的完整事务。

## 二、合并事务的标准收尾（四步，缺一不可）

```bash
# 1) 合并（--no-ff 保留提交线路，见 COLLABORATION.md §三）
git merge --no-ff <branch> -m "merge: <说明>"

# 2) 立刻验证（不是"稍后验证"）
python scripts/check-i18n.py
python scripts/probes/_test_plugin_install_errors.py
python scripts/probes/_test_builtin_plugins.py
gradle.bat -p . -Ppackage=noruntime compileReleaseKotlin -q

# 3) 删掉已合入的分支（内容已在 main，删了不丢东西）
git branch -d <branch>

# 4) 删掉用完的 worktree（先确认工作区干净）
git -C <worktree> status --porcelain   # 必须为空
git worktree remove <worktree>
git worktree prune
```

### 关于"有独有提交的分支"

删除前必须确认独有提交是否还需要。判断顺序：

```bash
git rev-list --count main..<branch>        # 独有提交数
git cherry main <branch> | grep -c '^-'    # 其中已有等价补丁的数量
git branch -r --contains <branch>          # 远端是否有备份
```

- 独有提交 **= 0** → `git branch -d` 直接删。
- 独有提交 > 0 但**远端有分支** → 可删，历史在远端。
- 独有提交 > 0 且**仅本地存在** → **先打 bundle 备份再删**：

```bash
git bundle create ../branches_backup_$(date +%Y%m%d).bundle \
    <branch1> <branch2> ...
git bundle verify ../branches_backup_*.bundle   # 必须显示 "records a complete history"
git branch -D <branch>                          # 备份确认后再强删
```

2026-10-31 的实际执行：8 个未合并分支里 7 个仅本地存在，先打 15MB bundle
（含全部历史）再删，避免丢失抽屉改版、沉浸式状态栏、登录弹窗修复等提交。

## 三、本地产物的清理阈值

仓库根目录的产物**不进 git**，但会无限增长。`scripts/git-hygiene.py` 已内建
检测，超过下列阈值会告警：

| 产物 | 阈值 | 处置 |
| --- | --- | --- |
| `dsh-android-*.apk` | > 60 个 | 全部删除（随时可重新构建） |
| `_*.png` | > 30 张 | 全部删除（临时截图） |
| `hs_err_pid*.log` | > 1 个 | 删除（JVM 崩溃日志，含环境信息不适合留） |
| `_runtime_dl/` | > 1 GB | 删除（脚本会重新下载） |
| `_rel/` | > 0.5 GB | 删除（dedup 脚本重新生成） |

**两个需要人工判断的目录**（不自动告警，删前问）：

- `_runtime_bak/`：runtime zip 的历史备份，可能数 GB。只留最近一份即可回滚。
- `_bench/`：竞品 APK 研究样本，删了要重新找。
- `_debcheck/`：deb 缓存，重跑 `scripts/audit-extension-closures.py` 会再下。

## 四、迭代节奏：fix 与 feat 的配比

2026-10-31 统计最近 60 个提交：**fix 22 条 : feat 14 条 ≈ 1.57 : 1**。用户反馈
的原话是「bug 越修越多、新功能一直没落地，整体质量与竞争力持续下滑」。

### 配比红线

| 时间窗 | 目标 | 说明 |
| --- | --- | --- |
| 任意连续 20 个提交 | **fix 占比 ≤ 60%** | 超过说明在还技术债，暂停接新需求先稳 |
| 任意连续 20 个提交 | **feat ≥ 5 条** | 保底名额，防止"永远在修 bug" |
| 单个发版 | **fix 集中在同一子系统** | 散弹式修复（一版改插件+主题+i18n）是回归高发区 |

### 批量发版的红线

- **同一天不打两个正式 tag**。tag 触发的 CI 会构建 8 个 APK + 2 个 runtime zip，
  连续两次 tag 意味着第一批产物还没验证完第二批就出去了。
- **UI 细节类改动不进正式 tag**，攒到下一个版本一起发（降级为 `chore`/`style`）。
- **能力型新功能单独发版**，不与 bug 修复混在同一次 tag 里——出问题时无法判断
  是新增能力引入的还是修复引入的。

## 五、自检命令

```bash
# Windows 控制台必须先设 UTF-8，否则 ✓ 字符会 UnicodeEncodeError
PYTHONIOENCODING=utf-8 python scripts/git-hygiene.py
```

检查项（前 5 项为原有，后 3 项为 2026-10-31 新增）：

1. 工作区干净
2. 本地含远端最新
3. 不处于 rebase/merge 中间态
4. 无 CRLF 污染的对象
5. `scripts/probes/` 未被误加
6. **无已合并的残留分支**
7. **只有主 worktree**（或列出额外 worktree 提醒清理）
8. **无超标本地产物**

## 六、事故记录

| 时间 | 事故 | 已固化的条款 |
| --- | --- | --- |
| 2026-10-31 | 一晚多次合并后残留 19 分支 / 7 worktree / 4GB+ 产物，清理耗时且需逐个确认独有提交 | → §二 合并事务四步 + `git-hygiene.py` 检查项 6-8 |
| 2026-10-31 | `MOBILE_LAYOUT_VERSION=0.1.99` npm 不存在，打包 404 | → §一 版本线断裂；钉版本前先 `npm view <pkg> versions` |
| 2026-10-31 | PR #82 合入后本地 mobile-layout 视觉改版丢失 | → §一 合并后行为回退；合并前先 `git diff --stat` 比对双方改动文件 |
