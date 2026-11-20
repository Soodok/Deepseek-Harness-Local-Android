<?php
declare(strict_types=1);

/**
 * dsh bug report — 只读为主的查看页（HTTP Basic 认证）。
 *
 *   /bug/admin.php                列表（最近 100 条）
 *   /bug/admin.php?action=view&id= 单条详情
 *   /bug/admin.php?action=md&id=   导出 Markdown（单条）
 *   /bug/admin.php?action=del&id=  删除（需 POST 确认）
 */

$DATA = dirname(__DIR__) . '/.bugdata';
$CFG  = $DATA . '/config.php';
if (!is_file($CFG)) {
    http_response_code(503);
    echo 'not configured';
    exit;
}
$cfg = require $CFG;

$user = $_SERVER['PHP_AUTH_USER'] ?? '';
$pass = $_SERVER['PHP_AUTH_PW'] ?? '';
if (!hash_equals((string)$cfg['admin_user'], $user) || !hash_equals((string)$cfg['admin_pass'], $pass)) {
    header('WWW-Authenticate: Basic realm="dsh bug reports"');
    http_response_code(401);
    echo 'auth required';
    exit;
}

function db(string $data): PDO
{
    $db = new PDO('sqlite:' . $data . '/reports.db', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    $db->exec('PRAGMA busy_timeout=5000');
    return $db;
}

function e(string $s): string
{
    return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

$action = $_GET['action'] ?? 'list';
$id     = preg_match('/^[a-f0-9]{12}$/', (string)($_GET['id'] ?? '')) ? (string)$_GET['id'] : '';
$db     = db($DATA);

if ($action === 'del') {
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST' || $id === '') {
        http_response_code(405);
        echo 'POST + id required';
        exit;
    }
    $db->prepare('DELETE FROM reports WHERE id = ?')->execute([$id]);
    header('Location: admin.php');
    exit;
}

if ($action === 'md' && $id !== '') {
    $r = $db->prepare('SELECT * FROM reports WHERE id = ?');
    $r->execute([$id]);
    $row = $r->fetch();
    if (!$row) {
        http_response_code(404);
        echo 'not found';
        exit;
    }
    header('Content-Type: text/plain; charset=utf-8');
    echo "# " . $row['title'] . "\n\n";
    echo "- id: " . $row['id'] . "\n";
    echo "- 时间: " . date('Y-m-d H:i:s', (int)$row['ts']) . "\n";
    echo "- App: " . $row['app_version'] . " (" . $row['version_code'] . ")\n";
    echo "- 设备: " . $row['abi'] . " / Android " . $row['android'] . " / " . $row['device'] . "\n";
    echo "- 模式: " . $row['mode'] . "\n";
    echo "- 联系方式: " . ($row['contact'] !== '' ? $row['contact'] : '(未提供)') . "\n\n";
    echo "## 描述\n\n" . $row['body'] . "\n\n";
    echo "## 日志\n\n```\n" . $row['logs'] . "\n```\n";
    exit;
}

if ($action === 'view' && $id !== '') {
    $r = $db->prepare('SELECT * FROM reports WHERE id = ?');
    $r->execute([$id]);
    $row = $r->fetch();
    if (!$row) {
        http_response_code(404);
        echo 'not found';
        exit;
    }
    ?><!doctype html><meta charset="utf-8"><title><?= e($row['title']) ?></title>
    <style>body{font:14px/1.6 system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 16px}
    pre{background:#f5f5f5;padding:12px;overflow:auto;max-height:420px;white-space:pre-wrap}
    .kv{color:#555}textarea{width:100%;height:80px}</style>
    <p><a href="admin.php">← 列表</a> · <a href="admin.php?action=md&id=<?= e($row['id']) ?>">导出 Markdown</a></p>
    <h1><?= e($row['title']) ?></h1>
    <p class="kv">id <?= e($row['id']) ?> · <?= e(date('Y-m-d H:i:s', (int)$row['ts'])) ?> ·
    App <?= e($row['app_version']) ?> (<?= (int)$row['version_code'] ?>) ·
    <?= e($row['abi']) ?> / Android <?= e($row['android']) ?> / <?= e($row['device']) ?> ·
    模式 <?= e($row['mode']) ?> · <?= (int)$row['size'] ?> B</p>
    <p class="kv">联系方式：<?= $row['contact'] !== '' ? e($row['contact']) : '(未提供)' ?></p>
    <h2>描述</h2><pre><?= e($row['body']) ?></pre>
    <h2>日志</h2><pre><?= e($row['logs'] !== '' ? $row['logs'] : '(无)') ?></pre>
    <form method="post" action="admin.php?action=del&id=<?= e($row['id']) ?>"
          onsubmit="return confirm('真的删除？')">
      <button>删除这条</button>
    </form>
    <?php
    exit;
}

$rows = $db->query('SELECT id, ts, app_version, version_code, abi, android, device, mode, title, size
                    FROM reports ORDER BY ts DESC LIMIT 100')->fetchAll();
$total = (int)$db->query('SELECT COUNT(*) FROM reports')->fetchColumn();
?><!doctype html><meta charset="utf-8"><title>dsh 问题报告</title>
<style>body{font:14px/1.6 system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px}
table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #e5e5e5;padding:6px 8px;text-align:left;vertical-align:top}
th{background:#fafafa;font-weight:600}td.n{color:#666;font-variant-numeric:tabular-nums}</style>
<h1>dsh 问题报告 <span class="kv" style="font-size:14px;font-weight:400">共 <?= $total ?> 条，显示最近 <?= count($rows) ?> 条</span></h1>
<table><tr><th>时间</th><th>标题</th><th>App</th><th>设备</th><th>大小</th></tr>
<?php foreach ($rows as $r): ?>
<tr>
  <td class="n"><?= e(date('m-d H:i', (int)$r['ts'])) ?></td>
  <td><a href="admin.php?action=view&id=<?= e($r['id']) ?>"><?= e($r['title']) ?></a></td>
  <td class="n"><?= e($r['app_version']) ?> (<?= (int)$r['version_code'] ?>)</td>
  <td class="n"><?= e($r['abi']) ?> / A<?= e($r['android']) ?> / <?= e($r['device']) ?></td>
  <td class="n"><?= (int)$r['size'] ?> B</td>
</tr>
<?php endforeach; ?>
<?php if (!$rows): ?><tr><td colspan="5">还没有报告。</td></tr><?php endif; ?>
</table>
