# 问题反馈通道

## 为什么自己做

GitHub Issue 要注册账号，群里反馈又靠人肉转述。这个通道把「组织语言 + 贴日志」两件苦活
自动化，剩下「点一下」。四个出口共用同一份文本，所以**预览看到什么，发出去就是什么**。

## 服务端

| 项 | 值 |
|---|---|
| 端点 | `POST https://soodok.online/bug/report.php` |
| 健康检查 | `GET` 同一路径 → `{"ok":true,"service":"dsh-bug-report","proto":1}` |
| 主机 | 美国 VPS（Racknerd，96.44.135.142），`racknerd-62a3a23` |
| 代码 | `/var/www/html/bug/report.php`（采集）、`admin.php`（查看） |
| 数据 | `/var/www/html/.bugdata/reports.db`（SQLite，WAL） |
| 密钥 | `/var/www/html/.bugdata/config.php`（`report_key` / `admin_user` / `admin_pass` / `ip_salt`） |

`.bugdata` 在 webroot 里，但 nginx 的 `location ~ /\.` 会把任何含 `/.` 的路径 403；
实测 `GET /.bugdata/config.php` → **403**。

### 管理端

```bash
# 列表（Basic auth）
curl -u dsh:<pass> https://soodok.online/bug/admin.php
# 单条 Markdown（可直接贴成 GitHub Issue）
curl -u dsh:<pass> "https://soodok.online/bug/admin.php?action=md&id=<12位id>"
```

### 服务端约束（都已实测）

| 行为 | 结果 |
|---|---|
| 缺 `X-DSH-Report-Key` | 403 |
| 无正文 | 400 `body must be a JSON object` |
| 300 KB 载荷 | 413 |
| 同安装 10 分钟内同内容 | 200 + 同 id，`duplicate:true` |
| 同 IP 24h > 20 次 / 同安装 > 5 次 | 429 |

`report_key` 打进 APK，**它本来就是公开的**，只挡无脑扫描；真正的防线是限流与 200 KB 上限。

## 客户端

- `app/src/main/java/app/dsh/mobile/ReportBundle.kt`：取数、脱敏、渲染、POST（无界面）
- `app/src/main/java/app/dsh/mobile/ReportActivity.kt`：预览 + 四个出口
- `app/src/main/res/layout/activity_report.xml`：页面
- 入口：设置 → 反馈问题（`rowReport`）

### 红线（改代码前先读）

- **不采集**：IMEI/序列号/Android ID/精确位置/通讯录/已安装应用列表/剪贴板
- 设备型号**可关**；关掉只报 `abi + Android 版本`
- 出网前所有文本过 `ReportBundle.redact`：X-DSH-Token / Bearer / sk-* / gh*_ / AKIA /
  `_authToken` `api_key` `secret` `password` / 手机号 / 身份证号 → `[redacted]`、`[phone]`、`[id]`
- `install_id` 是本机随机 UUID，不关联账号；可重置

### 报告正文用英文小节标题

`## What happened` / `## Environment` / `## Logs` —— 这份文本是给开发者读的技术产物，
不是界面文案。界面上的按钮与标签仍然是六语种本地化的。

## 探针

```bash
python scripts/probes/_test_report_bundle.py     # 46 项
```

三处反向验证均已确认变红：端点改 `http://` → A1；删手机号脱敏规则 → C；
XHTML id 改名 `reportAttachDevice`→`X` → E3。
