# 协作开发规范（Collaboration Workflow）

> **一句话核心规则：不管是谁（维护者、外部协作者或 AI Agent），只要要把提交
> 推送到本仓库的 `main` 分支，都必须先在本地完成「拉取最新 → 合并 → 验证」
> 三步，确认本地已包含远端最新内容后才能推送。禁止在落后的本地历史上直接
> 推送。维护者本人同样遵守，无豁免。**
>
> 本规范防的是真实发生过的事故：PR 被覆盖丢失、API 推送覆盖远端 32 个文件
> （见 [§七 事故记录](#七事故记录与教训)）。配套文档：`docs/git-workflow.md`
> （推送通道与 SHA 保持细则）。

## 一、仓库拓扑与远程配置

本仓库采用 **单主仓 + fork 协作** 模型：

| 角色 | 工作方式 |
| --- | --- |
| 维护者 | `origin` 指向本仓库主仓，验证后直推 `main` |
| 外部协作者 | Fork 本仓库 → 在自己 fork 上开发 → 向本仓库 `main` 发 PR |
| AI Agent | 与人类相同，受本文全部条款约束 |

- `origin/main` 要定期 fetch，**任何推送前必须重新 fetch**。
- 网络注意：`fetch` 走代理可用，`push` 直连或走 `scripts/push-via-api.py`
  （详见 `docs/git-workflow.md` §一.4）。

## 二、分支与提交规范

1. **集成分支只有 `main`**。日常开发在本地 `main` 上做小步提交即可（本仓库
   惯例）；大特性建议开 `feat/<topic>` 分支，验证后再合回或发 PR。
2. **提交信息**：第一行 `<type>(<scope>): <一句话中文摘要>`，正文说明动机、
   根因与验证方式。type 取 `feat / fix / docs / chore / revert / merge`。
   `merge` 提交保留 `--no-ff` 生成的默认格式并补中文说明。
3. **永远不重写已推送的历史**：已推送到任何远端 `main` 的提交不改写、不
   squash、不 rebase；撤销用 `git revert`。
4. **版本号统一由维护者在主仓管理**：协作者与 AI 的提交**永不修改**
   `app/build.gradle.kts` 的 `versionCode` / `versionName`，PR 中出现版本号
   变更应由维护者单独提交，避免「同版本号、内容不同」的发布撞车。

## 三、推送 main 前的强制合并测试（核心流程）

> **适用对象：所有人，包括仓库维护者本人 —— 维护者身份不构成豁免**，
> 历史上全部覆盖事故（§七）都发生在维护者自己的推送通道上。AI Agent 同样
> 受此约束。触发条件：`git push` / `push-via-api.py` 指向本仓库 `main`
> 之前。跳过任何一步都算违规推送。

```bash
# 0) 提交本地工作，确认工作区干净
git status -sb                       # 应无未提交改动

# 1) 拉取远端最新
git fetch origin --prune

# 2) 确认本地是否包含远端全部提交（落后即为「本地过期」）
git rev-list --count origin/main..main    # 本地领先数
git rev-list --count main..origin/main    # >0 = 本地落后，必须先合并

# 3) 合并所有落后部分（--no-edit，禁止 force-push 绕行）
git merge origin/main --no-edit      # 无新提交则跳过

#    3a) 若有冲突：逐块手工解决，**绝不能整文件取一侧**
#        （2026-10-27 事故：整文件取一侧丢掉主题接线，见 §七）
git diff --name-only --diff-filter=U # 列出冲突文件
#    …逐文件编辑后…
git add <file> && git commit

# 4) 合并后全量验证（任何一项不过就修到过，不许带病推送）
python scripts/check-i18n.py                       # 四语串同步
for f in scripts/probes/_test_*.py; do python "$f"; done   # 探针全绿
gradle :app:compileDebugKotlin -Pabi=x86_64 -Ppackage=noruntime
gradle :app:assembleDebug      -Pabi=x86_64 -Ppackage=noruntime

# 5) 推送（维护者直推；协作者推自己 fork 后发 PR）
git push origin main \
  || python scripts/push-via-api.py

# 6) 推送后核对：本地与远端 SHA 必须完全一致
git fetch origin && git rev-parse origin/main main   # 两个值应相同
```

**外部协作者回流**：fork 上推好分支 → 向本仓库开 PR → 维护者按本文
§三.3-4 验证后 `--no-ff` 合并。若 PR 与 `main` 冲突，由贡献者自行
rebase/merge 后更新 PR。

## 四、禁止事项（红线）

| # | 禁止行为 | 后果/原因 |
| --- | --- | --- |
| 1 | **force-push 到 `main`**（`push -f` / `--force-with-lease` 绕过落后检查） | 覆盖他人提交，SHA 全体下游错乱 |
| 2 | **本地落后时直接推送**（含 `push-via-api.py`） | 非快照推送会被拒绝或覆盖远端（PR 丢失事故的根因） |
| 3 | **冲突时整文件取一侧** | 会丢掉另一侧的接线/文案（2026-10-27 实际丢过主题接线） |
| 4 | **改写已推送历史**（rebase/squash/reset+强推） | 下游 fork 被迫 force-push，贡献归属丢失 |
| 5 | **跳过验证直接推送** | 探针守卫的是行为契约，红灯说明有回归 |
| 6 | **非维护者修改版本号** | 版本权威在主仓维护者；私改会制造「同版本号、内容不同」的撞车 |
| 7 | **用旧 clone / 离线多日后直接推** | 先 `git fetch` + `git status -sb` 看 ahead/behind，再决定流程 |

## 五、外部协作者 PR 规范

1. Fork 本仓库 → 从 `main` 切分支开发 → 提交遵循 §二.2 信息规范；
2. PR 前自行完成 §三.4 的编译与探针验证，PR 描述写明动机/根因/验证结果；
3. **PR 保持原始提交**，不要自行 squash —— 维护者用 `--no-ff` 合并，
   保留每个提交的 SHA 与作者信息（GitHub「已合并」状态与贡献归属的前提）；
4. 维护者评审要点：是否改动无关文件、是否引入硬编码色/硬编码文案、
   冲突解决是否取单侧、是否动版本号；
5. 若 PR 与 `main` 冲突，**由贡献者自行 rebase/merge 后更新 PR**，评审者不代改。

## 六、快速检查清单（推送前 30 秒版）

```
[ ] git status -sb 干净
[ ] git fetch origin 完成
[ ] main..origin/main 的 rev-list 计数为 0（或已合并）
[ ] 探针 + 编译全绿
[ ] push 后 rev-parse 双端 SHA 一致
[ ] 未使用 -f / 未跳过任何一步
```

## 七、事故记录与教训

| 时间 | 事故 | 教训（已固化为本文条款） |
| --- | --- | --- |
| 2026-10-04 | 工作区 CRLF 推进远端，CI `bad interpreter` | 推送内容必须从 git 对象库读（`cat-file`），不读工作区 |
| 2026-10-27 | API 推送覆盖远端新提交，丢 32 文件 | → §三 步骤 1-2（推送前 fetch 并确认本地含远端最新） |
| 2026-10-27 | 整文件取一侧解决冲突，丢主题接线 | → §四.3 |
| 2026-10-28 | API 推送重写 SHA，下游 fork 被迫 force-push | → `docs/git-workflow.md` §一.3（推送保留原始 SHA） |
| 2026-10-30 | PR #75（自动更新体系 P0）被覆盖丢失，补「强制覆盖护栏」后恢复 | → §四.2 + push-via-api 覆盖护栏（拒绝非快照推送） |

**发现新的覆盖类事故时：先恢复数据，再把根因补进本表和对应条款。**
