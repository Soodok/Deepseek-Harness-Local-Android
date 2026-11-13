# PeerSync 协议 v1（手机 ↔ 电脑 会话同步）

> 本协议由 dsh-android 的 `PeerSync`（手机端客户端）与 `tools/peersync/pc-sync-server.mjs`（电脑端服务端）
> 共同实现。**两条数据方向共用一条连接**：手机永远是 HTTP 客户端，电脑永远是服务端；
> 「手机 → 电脑」是 PUT，「电脑 → 手机」是 GET。手机端**不需要**再开监听端口。

## 0. 为什么不用 PR #105 的写法

上游 PR 的同步按「`.jsonl` 后缀 + 字节前缀快进（isPrefix）」设计，两个前提都不成立：

1. **后缀错**：引擎默认 `compression = "zstd"`，会话日志真实文件名是
   `session.v4.jsonl.zstd`。按 `.jsonl` 过滤，实测 `~/.dsh/sessions` 命中 **0 / 465**。
2. **前缀错**：zstd 多帧容器每帧自带 checksum/字典，压缩后追加**不是**旧文件的字节前缀；
   实测 20 组「旧备份 vs 当前文件」，`new.startswith(old)` **0/20** 成立，
   解压后明文的严格前缀也 **0/4** 成立（压缩会改写早期帧）。
   所以「按字节长度跳过已同步部分」在 zstd 上**不可能**成立。

v1 因此改成**整文件对齐 + 三方比较**：文件级 sha256 + 本地记住上次同步的 sha256，
据此判定「哪边改了」，冲突时保留备份、绝不删除。

## 1. 传输

* 明文 HTTP（局域网），端口默认 **3091**（避开手机 3080 引擎 / 3083 能力桥 / 3090 WebUI 代理）。
* 所有 `/__dshm/sync/*` 请求必须带请求头 `x-dshm-sync: <password>`，否则 401。
* 请求体上限 64 MiB（`413` 超限）。

## 2. 路径

* `path` 是相对 `sessions/` 根的 POSIX 相对路径，例如
  `--E-tmp-rh-fresh--/session-4af5…/session.v4.jsonl.zstd`。
* 服务端必须拒绝：解码失败、空段、`..`、反斜杠、绝对路径、resolve 后逃出根目录 → `400 bad-path`。
* 允许的**唯一**文件形态（客户端与服务端同一正则）：
  `^session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$`
  （即 `session.v4.jsonl.zstd` / `session.v3.jsonl` / `session.jsonl.zstd`）。
  `session.lock`、`*.compact-bak`、`*.title-bak`、`*.merge-bak`、`*.peersync-bak-*`、`*.tmp*` **一律不参与同步**。

## 3. 端点

### GET /__dshm/sync/ping
```json
{ "ok": true, "version": 1, "root": "sessions", "files": 479, "bytes": 723517440 }
```

### GET /__dshm/sync/manifest
```json
{ "version": 1, "root": "sessions", "generatedAt": 1762968000000,
  "count": 2, "totalBytes": 90000,
  "files": [ { "path": "--E-tmp-rh-fresh--/session-…/session.v4.jsonl.zstd",
               "size": 51953, "mtimeMs": 1762..., "sha256": "…" } ] }
```
* 可选 `?scope=<路径前缀>` 过滤，只回该前缀下的文件（前缀按 `/` 边界匹配，不是字符串前缀）。
* sha256 必须**流式**计算（不要整文件进内存）。

### GET /__dshm/sync/stat?path=…
```json
{ "ok": true, "exists": true, "path": "…", "size": 51953, "mtimeMs": 1762…, "sha256": "…" }
```
不存在时 `{ "ok": true, "exists": false }`（HTTP 200，便于客户端区分「没有」和「出错」）。

### GET /__dshm/sync/file?path=…
原始字节流，`Content-Type: application/octet-stream`、`Content-Length` 精确。

### PUT /__dshm/sync/file?path=…
* 请求体 = 原始字节。可选请求头 `x-dshm-sha256: <hex>`：给了就**必须**校验，不符回 `409 sha-mismatch` 且不落盘。
* 落盘协议：写 `<path>.tmp-<pid>-<ts>` → `fsync` → `rename` 原子替换。
* **覆盖前备份**：目标已存在且内容不同，先 `rename` 成 `<path>.peersync-bak-<yyyyMMdd-HHmmss>`，
  同目录同后缀备份**只保留最近 3 份**，更老的才删（这是唯一允许的删除动作，且只删自己的备份）。
* 成功：`{ "ok": true, "path": "…", "size": 51953, "sha256": "…", "replaced": true, "backup": "…bak-…" }`

### 其它
* 未知路径 → `404 { "error": "unknown-sync-endpoint" }`
* 非 `/__dshm/sync/*` 的请求 → `404`（本服务只做同步，不代理 WebUI）。

## 4. 鉴权退避

密码错误按来源 IP 计数：10 分钟内连续失败 5 次锁 10 分钟，锁定期间任何请求回 `429` + `Retry-After`。
成功则清零。

**比对顺序是「先比密码，只有错了才计入退避」**，而不是「先查退避」：
早期版本先查退避，结果是用户输错一次后立刻改对也要吃 500ms 的 429 ——
手机上看到的现象是「密码明明对了还是连不上」。而爆破防护并不因此削弱：
攻击者猜不对，每次错都在累积；锁定期间正确密码仍可通过并清零计数。
退避计数只在**密码不符**分支累加，所以 429 的响应时间不泄露「密码对不对」。

## 5. 客户端（手机端）对齐规则

本地与远端各取 `{size, mtimeMs, sha256}`，再叠加**上次同步成功时记录的 sha256**（本地 `peersync-state.json`）：

| 本地 | 远端 | 判定 |
|---|---|---|
| 无 | 有 | **pull**（下载） |
| 有 | 无 | **push**（上传） |
| 有 | 有，sha 相同 | 跳过（同时刷新基线） |
| 有 | 有，sha 不同 | 有基线：`本地变` → push；`远端变` → pull；`两边都变/都没变` → **冲突** |
| 有 | 有，sha 不同 | 无基线（首次）：`mtime` 差 > 2s 时新的赢；否则 **冲突** |

冲突处理策略（`conflict` 参数）：`backup-newer`（默认：保留较新一侧，另一侧改名 `*.peersync-conflict-<stamp>` 存本地）、
`prefer-local`、`prefer-remote`、`skip`（只报告，不动文件）。

**任何模式都不删除会话**：只新增、覆盖（覆盖前有服务端备份 + 本地冲突备份）。

## 6. 模式

`direction`：
* `both`（默认）
* `pull`：只允许 电脑 → 手机（本地独有文件只报告，不推）
* `push`：只允许 手机 → 电脑（远端独有文件只报告，不拉）

`scope`：只同步某个相对前缀（如 `--E-tmp-rh-fresh--`），空 = 全部。
`maxFileBytes`：超过就跳过并计入 `skipped_large`（默认 32 MiB），避免一次拉几百 MB。

## 7. 错误码约定

| HTTP | code | 含义 |
|---|---|---|
| 400 | `bad-path` | 路径非法（越界/非白名单文件名） |
| 401 | `sync-unauthorized` | 密码错 |
| 404 | `not-found` | 文件不存在 |
| 409 | `sha-mismatch` | PUT 的 sha 与收到字节不符 |
| 413 | `too-large` | 超过 64 MiB |
| 429 | `rate-limited` | 退避/锁定中 |
| 500 | `…` | 服务端故障（`error` 字段带 message） |
