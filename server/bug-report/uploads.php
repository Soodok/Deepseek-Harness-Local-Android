<?php
declare(strict_types=1);

/**
 * dsh bug report — 附件后台（HTTP Basic 认证）。
 *
 *   /bug/uploads.php                  列表（最近 100 条）
 *   /bug/uploads.php?action=dl&id=xx  下载（强制 attachment，绝不 inline）
 *   /bug/uploads.php?action=del&id=xx 删除（POST 确认，删行也删文件）
 *
 * ## 为什么要强制 attachment
 *
 * 上传内容在服务端是**不可信数据**。哪怕扩展名白名单只放文本，也不该让浏览器
 * 按内容猜类型去渲染 —— 一旦 inline，一个精心构造的 .txt 就可能变成
 * 执行上下文。所以一律 `Content-Disposition: attachment` + `nosniff`，
 * 并把扩展名再降级成 `.txt`，让它只能被下载、不能被当页面打开。
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
    header('WWW-Authenticate: Basic realm="dsh bug uploads"');
    http_response_code(401);
    echo 'auth required';
    exit;
}

function e(string $s): string
{
    return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

$db = new PDO('sqlite:' . $DATA . '/reports.db', null, null, [
    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
]);
$db->exec('PRAGMA busy_timeout=5000');
$db->exec('CREATE TABLE IF NOT EXISTS uploads (
    id TEXT PRIMARY KEY, ts INTEGER NOT NULL, install_id TEXT, title TEXT, body TEXT,
    file_name TEXT, file_size INTEGER, mime TEXT, sha256 TEXT, ip_hash TEXT, mode TEXT,
    app_version TEXT, version_code INTEGER, note TEXT)');

$action = $_GET['action'] ?? 'list';
$id = preg_match('/^[a-f0-9]{12}$/', (string)($_GET['id'] ?? '')) ? (string)$_GET['id'] : '';

// 「同一 id 前缀」的多文件是 id_abcd 形态，下载/删除都按前缀匹配，一次带走整组。
function rowsByPrefix(PDO $db, string $id): array
{
    $q = $db->prepare('SELECT * FROM uploads WHERE id = ? OR id LIKE ? ORDER BY id');
    $q->execute([$id, $id . '%']);
    return $q->fetchAll();
}

if ($action === 'del' && $id !== '') {
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
        http_response_code(405);
        echo 'POST required';
        exit;
    }
    $rows = rowsByPrefix($db, $id);
    foreach ($rows as $r) {
        $path = $DATA . '/up/' . $id . '_' . preg_replace('/[^A-Za-z0-9._-]/', '_', basename((string)$r['file_name']));
        if (is_file($path)) {
            @unlink($path);
        }
    }
    $db->prepare('DELETE FROM uploads WHERE id = ? OR id LIKE ?')->execute([$id, $id . '%']);
    // 留墓碑：知道「什么时候删过哪一组、多大」，但不留内容。
    $db->exec('CREATE TABLE IF NOT EXISTS tombstones (
        id TEXT PRIMARY KEY, ts INTEGER, size INTEGER, files INTEGER)');
    $size = 0;
    foreach ($rows as $r) {
        $size += (int)$r['file_size'];
    }
    $db->prepare('INSERT OR REPLACE INTO tombstones (id, ts, size, files) VALUES (?,?,?,?)')
       ->execute([$id, time(), $size, count($rows)]);
    header('Location: uploads.php');
    exit;
}

if ($action === 'dl' && $id !== '') {
    $rows = rowsByPrefix($db, $id);
    if (!$rows) {
        http_response_code(404);
        echo 'not found';
        exit;
    }
    foreach ($rows as $r) {
        $path = $DATA . '/up/' . $r['id'] . '_' .
            preg_replace('/[^A-Za-z0-9._-]/', '_', basename((string)$r['file_name']));
        if (!is_file($path)) {
            continue;
        }
        $name = preg_replace('/[^A-Za-z0-9._-]/', '_', (string)$r['file_name']);
        header('Content-Type: application/octet-stream');
        header('X-Content-Type-Options: nosniff');
        header('Content-Disposition: attachment; filename="' . $name . '.txt"');
        header('Content-Length: ' . filesize($path));
        readfile($path);
        exit;
    }
    http_response_code(410);
    echo 'file body gone (metadata present)';
    exit;
}

$rows = $db->query('SELECT * FROM uploads ORDER BY ts DESC LIMIT 100')->fetchAll();
$total = (int)$db->query('SELECT COUNT(*) FROM uploads')->fetchColumn();
$bytes = (int)$db->query('SELECT COALESCE(SUM(file_size),0) FROM uploads')->fetchColumn();
?><!doctype html><meta charset="utf-8"><title>dsh 附件</title>
<style>
body{font:14px/1.6 system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px}
table{border-collapse:collapse;width:100%}
th,td{border-bottom:1px solid #e5e5e5;padding:6px 8px;text-align:left;vertical-align:top}
th{background:#fafafa;font-weight:600}
td.n{color:#666;font-variant-numeric:tabular-nums}
a{color:#0b6bcb}
</style>
<h1>dsh 附件
  <span style="font-size:14px;font-weight:400;color:#666">
    共 <?= $total ?> 条 / <?= number_format($bytes / 1024, 1) ?> KiB，显示最近 <?= count($rows) ?> 条
  </span>
</h1>
<p style="color:#666">下载一律以 <code>attachment</code> + 改名 <code>.txt</code> 下发：上传内容是不可信数据，不能被浏览器按内容渲染。</p>
<table>
<tr><th>时间</th><th>标题</th><th>文件</th><th>大小</th><th>模式</th><th>操作</th></tr>
<?php foreach ($rows as $r): ?>
<tr>
  <td class="n"><?= e(date('m-d H:i', (int)$r['ts'])) ?></td>
  <td><?= e((string)$r['title']) ?>
    <?php if ((string)$r['body'] !== ''): ?>
      <div style="color:#666;font-size:12px"><?= e(mb_substr((string)$r['body'], 0, 120, 'UTF-8')) ?></div>
    <?php endif; ?>
  </td>
  <td class="n"><?= e((string)$r['file_name']) ?>
    <div style="color:#999;font-size:11px"><?= e(substr((string)$r['sha256'], 0, 16)) ?>…</div>
  </td>
  <td class="n"><?= (int)$r['file_size'] ?> B</td>
  <td class="n"><?= e((string)$r['mode']) ?></td>
  <td class="n">
    <?php $base = substr((string)$r['id'], 0, 12); ?>
    <a href="uploads.php?action=dl&amp;id=<?= e($base) ?>">下载</a>
    <form method="post" action="uploads.php?action=del&amp;id=<?= e($base) ?>"
          style="display:inline" onsubmit="return confirm('删除这一组（含文件）？')">
      <button>删除</button>
    </form>
  </td>
</tr>
<?php endforeach; ?>
<?php if (!$rows): ?><tr><td colspan="6">还没有附件。</td></tr><?php endif; ?>
</table>
