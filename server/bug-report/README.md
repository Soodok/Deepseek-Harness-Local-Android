# 问题反馈服务端（soodok.online/bug）

这个目录就是线上那几个 PHP 文件的**同一份源码**。放进来是为了让任何人能核对
「接收端到底做了什么」——不看这个目录，用户只能相信开发者的口头承诺。

## 部署

线上路径 `/var/www/html/bug/`，整目录与这里一一对应：

| 本目录 | 线上路径 | 作用 |
|---|---|---|
| `report.php` | `/bug/report.php` | 纯文本上报（JSON，≤200 KiB）+ 健康检查 |
| `consent.php` | `/bug/consent.php` | 签发一次性同意 token（120 秒有效） |
| `upload.php` | `/bug/upload.php` | 附件上传：白名单 + 内容审查 + 2 MiB/小时额度 |
| `admin.php` | `/bug/admin.php` | 后台（HTTP Basic auth），列表 / 详情 / 导出 Markdown / 删除 |
| `.user.ini` | `/bug/.user.ini` | 只调 PHP 的 `post_max_size` / `upload_max_filesize` |

数据与密钥在 `/var/www/html/.bugdata/`（`config.php` chmod 600、`reports.db` SQLite、
附件在 `up/`）。nginx 的 `location ~ /\.` 会把任何含 `/.` 的路径 403 掉，
所以这个目录**不可通过 HTTP 访问**。

部署就是复制：

```sh
scp server/bug-report/*.php <host>:/var/www/html/bug/
```

## 怎么自己核验线上跑的是不是这份代码

```sh
# 1. 拉一份仓库
git clone https://github.com/Soodok/Deepseek-Harness-Local-Android
# 2. 比对线上文件的哈希（脚本待补，先手工：）
curl -s https://soodok.online/bug/verify.php     # 返回每个已部署文件的 sha256
sha256sum server/bug-report/*.php
```

（`verify.php` 与自动比对脚本是本轮的 TODO —— 见仓库 issue。）

## 记账顺序（这条顺序是有意的）

```
1 认证 → 2 体积/类型 → 3 内容审查 → 4 额度 → 5 落盘并累加额度（同一事务）
```

**失败不占公共额度**：额度只在真正落库那一刻累加。用户点「拒绝」或 30 秒超时
根本到不了服务端，自然也不占。另有一个与额度无关的独立计数（每 IP 每小时
100 次失败尝试），用来防「反复失败、反复拿 findings」把审查器当探测工具。

## 内容审查的行为

只**拒绝并说明要剥什么**，不静默改写（静默改写会让上报方以为发成功了）：

```json
{"ok":false,"code":"REVIEW_REJECTED",
 "findings":[{"kind":"private_key","line":37,"hint":"PEM/OpenSSH private key block"}]}
```

类别：PEM/OpenSSH 私钥、`sk-`、`ghp_`/`gpo_` 等 GitHub token、`AKIA`、
JWT、18 位身份证号、16–19 位银行卡号（**过 Luhn 才判**，避免把普通数字误伤）、
成片手机号（≥3 行）、成片邮箱（≥5 个）。只回位置与类别，**不回显原文**——
审查器自己不能变成泄露通道。

## 已知限制（如实记录，别当没看见）

1. **nginx 的 `client_max_body_size` 是 1 MB**，实测 1.2 MB 的请求直接被 nginx
   以 413 打回（根本到不了 PHP）。所以「单个 2 MiB 压缩包」目前做不到，
   2 MiB 是**每小时累计**额度（实测 2×900 KB 通过、第三次 429）。
   要放开单文件 2 MiB，需要 root 在 `soodok.conf` 里加一行
   `client_max_body_size 6m;` —— 这台机器上 `www-data` 没有 sudo。
2. `admin.php` 目前只列纯文本上报，**附件列表 / 下载 / 删除还没做**
   （数据已入库，能查；只是没有界面）。
3. 没有定时清理任务：附件与上报记录不会自动过期删除。
