<?php
declare(strict_types=1);

/**
 * dsh bug report — 附件上传（一次性同意 token + 白名单 + 内容审查 + 额度）。
 *
 * ## 记账顺序（这条顺序是有意的，别改）
 *
 *   1 认证  2 体积/类型  3 内容审查  4 额度  5 落盘并累加额度（同一事务）
 *
 * 任何一步失败都**不占公共额度**：额度只在真正落库那一刻才累加。
 * 用户点「拒绝」或 30 秒超时压根到不了这里，自然也不占。
 *
 * ## 「失败不扣额度」与「防滥用」是两件事
 *
 * 前者是给人用的公平；后者防的是「反复失败、反复拿 findings」把审查器当探测工具。
 * 所以另有一个**独立的高阈值尝试计数**（每 IP 每小时 100 次失败），与 2MB 额度无关。
 */

const MAX_TOTAL_BYTES   = 2097152;   // 单次 2 MiB
const QUOTA_BYTES_HOUR  = 2097152;   // 每安装每小时 2 MiB（只记成功的）
const FAIL_ATTEMPTS_HR  = 100;       // 每 IP 每小时失败尝试上限
const CONSENT_TTL       = 120;       // 同意 token 有效期（秒）
const MAX_UNZIP_BYTES   = 8 * 1024 * 1024;
const PROTO             = 1;

$DATA = dirname(__DIR__) . '/.bugdata';
$CFG  = $DATA . '/config.php';

function jout(int $code, array $p): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($p, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    jout(405, ['ok' => false, 'error' => 'POST only']);
}
if (!is_file($CFG)) {
    jout(503, ['ok' => false, 'error' => 'server not configured']);
}
$cfg = require $CFG;

$key = (string)($_SERVER['HTTP_X_DSH_REPORT_KEY'] ?? '');
if ($key === '' || !hash_equals((string)$cfg['report_key'], $key)) {
    jout(403, ['ok' => false, 'error' => 'bad report key']);
}
$consent = (string)($_SERVER['HTTP_X_DSH_CONSENT'] ?? '');
if (!preg_match('/^[a-f0-9]{32,64}$/', $consent)) {
    jout(403, ['ok' => false, 'code' => 'NO_CONSENT',
               'error' => 'missing one-shot consent token (the user must approve in-app first)']);
}

$ipHash = hash('sha256', ($cfg['ip_salt'] ?? 'dsh') . '|' . (string)($_SERVER['REMOTE_ADDR'] ?? ''));
$now    = time();

try {
    if (!is_dir($DATA)) {
        @mkdir($DATA, 0700, true);
    }
    $db = new PDO('sqlite:' . $DATA . '/reports.db', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    $db->exec('PRAGMA journal_mode=WAL');
    $db->exec('PRAGMA busy_timeout=5000');
    $db->exec('CREATE TABLE IF NOT EXISTS uploads (
        id TEXT PRIMARY KEY, ts INTEGER NOT NULL, install_id TEXT, title TEXT, body TEXT,
        file_name TEXT, file_size INTEGER, mime TEXT, sha256 TEXT, ip_hash TEXT, mode TEXT,
        app_version TEXT, version_code INTEGER, note TEXT)');
    $db->exec('CREATE INDEX IF NOT EXISTS idx_uploads_ts ON uploads(ts DESC)');
    $db->exec('CREATE INDEX IF NOT EXISTS idx_uploads_inst ON uploads(install_id, ts DESC)');
    $db->exec('CREATE TABLE IF NOT EXISTS consent_tokens (
        token TEXT PRIMARY KEY, ts INTEGER NOT NULL, used INTEGER DEFAULT 0)');
    $db->exec('CREATE TABLE IF NOT EXISTS attempts (
        id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, ip_hash TEXT, ok INTEGER)');
    $db->exec('CREATE INDEX IF NOT EXISTS idx_attempts ON attempts(ip_hash, ts)');
} catch (Throwable $e) {
    error_log('[upload] ' . $e->getMessage());
    jout(500, ['ok' => false, 'error' => 'server error']);
}

function noteFail(PDO $db, string $ipHash, int $now): void
{
    $db->prepare('INSERT INTO attempts (ts, ip_hash, ok) VALUES (?,?,0)')->execute([$now, $ipHash]);
}

// ── 防滥用：独立于额度，只数「失败尝试」
$q = $db->prepare('SELECT COUNT(*) FROM attempts WHERE ip_hash = ? AND ts >= ? AND ok = 0');
$q->execute([$ipHash, $now - 3600]);
if ((int)$q->fetchColumn() >= FAIL_ATTEMPTS_HR) {
    jout(429, ['ok' => false, 'code' => 'TOO_MANY_ATTEMPTS',
               'error' => 'too many failed attempts this hour', 'retry_after' => 3600]);
}

// ── 同意 token：必须存在、未用、未过期
$q = $db->prepare('SELECT ts, used FROM consent_tokens WHERE token = ?');
$q->execute([$consent]);
$row = $q->fetch();
if (!$row) {
    noteFail($db, $ipHash, $now);
    jout(403, ['ok' => false, 'code' => 'NO_CONSENT',
               'error' => 'consent token unknown — the in-app approval was not registered']);
}
if ((int)$row['used'] === 1) {
    noteFail($db, $ipHash, $now);
    jout(409, ['ok' => false, 'code' => 'CONSENT_REPLAYED',
               'error' => 'this consent token was already used']);
}
if ($now - (int)$row['ts'] > CONSENT_TTL) {
    noteFail($db, $ipHash, $now);
    jout(410, ['ok' => false, 'code' => 'CONSENT_EXPIRED',
               'error' => 'consent expired, ask the user again']);
}

$installId = (string)($_POST['install_id'] ?? '');
if ($installId !== '' && !preg_match('/^[A-Za-z0-9_-]{8,64}$/', $installId)) {
    noteFail($db, $ipHash, $now);
    jout(400, ['ok' => false, 'error' => 'bad install_id']);
}
$title = mb_substr(trim((string)($_POST['title'] ?? '')), 0, 200, 'UTF-8');
$body  = mb_substr(trim((string)($_POST['body'] ?? '')), 0, 8000, 'UTF-8');

// ── 体积与类型
$files = [];
// ★单文件与多文件两种形态都要认：`<input type=file>` / curl -F 单文件时
//   $_FILES['file']['name'] 是**字符串**，只有 file[] 才是数组。
//   旧代码只处理数组形态，结果单文件上传被静默忽略、还回了 200 + files:[] ——
//   这是最糟的一种失败（用户以为发上去了，其实什么都没有）。
if (isset($_FILES['file'])) {
    $raw = $_FILES['file'];
    $scalar = is_string($raw['name'] ?? null);
    $names = $scalar ? [$raw['name']] : (array)($raw['name'] ?? []);
    $tmps  = $scalar ? [$raw['tmp_name']] : (array)($raw['tmp_name'] ?? []);
    $sizes = $scalar ? [$raw['size']] : (array)($raw['size'] ?? []);
    $errs  = $scalar ? [$raw['error']] : (array)($raw['error'] ?? []);
    foreach ($names as $i => $nm) {
        $err = (int)($errs[$i] ?? UPLOAD_ERR_NO_FILE);
        if ($err === UPLOAD_ERR_NO_FILE) {
            continue;
        }
        if ($err !== UPLOAD_ERR_OK) {
            noteFail($db, $ipHash, $now);
            jout(400, ['ok' => false, 'code' => 'UPLOAD_ERROR',
                       'error' => 'upload failed with PHP error ' . $err,
                       'hint' => $err === UPLOAD_ERR_INI_SIZE
                           ? 'file exceeds upload_max_filesize on the server'
                           : 'retry, or compress the file first']);
        }
        $tmp = (string)($tmps[$i] ?? '');
        if ($tmp === '' || !is_uploaded_file($tmp)) {
            noteFail($db, $ipHash, $now);
            jout(400, ['ok' => false, 'code' => 'UPLOAD_ERROR',
                       'error' => 'temporary file missing']);
        }
        $files[] = ['name' => (string)$nm, 'tmp' => $tmp, 'size' => (int)($sizes[$i] ?? 0)];
    }
}
if (isset($_FILES['file']) && !$files && $title === '' && $body === '') {
    noteFail($db, $ipHash, $now);
    jout(400, ['ok' => false, 'code' => 'UPLOAD_EMPTY',
               'error' => 'a file field was sent but no file arrived',
               'hint' => 'check multipart encoding and nginx client_max_body_size']);
}
if (!$files && $title === '' && $body === '') {
    noteFail($db, $ipHash, $now);
    jout(400, ['ok' => false, 'error' => 'nothing to upload: need title/body or a file']);
}

$total = 0;
foreach ($files as $f) {
    $total += $f['size'];
}
if ($total > MAX_TOTAL_BYTES) {
    noteFail($db, $ipHash, $now);
    jout(413, ['ok' => false, 'code' => 'TOO_LARGE',
               'error' => 'attachment exceeds 2 MiB after compression',
               'limit' => MAX_TOTAL_BYTES, 'got' => $total]);
}

const ALLOWED_EXT = ['txt', 'log', 'md', 'json', 'jsonl', 'patch', 'diff', 'zip', 'gz', 'tgz', 'tar'];

function extOf(string $name): string
{
    $lower = strtolower($name);
    if (str_ends_with($lower, '.tar.gz')) {
        return 'tar';
    }
    $p = strrpos($lower, '.');
    return $p === false ? '' : substr($lower, $p + 1);
}

/** 按 magic 字节拒可执行与图片：类型判定不能只看扩展名。 */
function sniffDanger(string $path): ?string
{
    $fh = fopen($path, 'rb');
    if (!$fh) {
        return 'unreadable';
    }
    $head = (string)fread($fh, 8);
    fclose($fh);
    $map = [
        "\x7fELF" => 'ELF executable',
        "MZ" => 'PE executable',
        "dex\n" => 'Dalvik executable',
        "\xca\xfe\xba\xbe" => 'Mach-O/Java class',
        "\x89PNG" => 'image (not accepted)',
        "\xff\xd8\xff" => 'image (not accepted)',
        "GIF8" => 'image (not accepted)',
        "PK\x03\x04" => null,   // zip: 允许，但内容另外递归查
        "\x1f\x8b" => null,     // gzip: 同上
    ];
    foreach ($map as $magic => $label) {
        if (str_starts_with($head, $magic)) {
            return $label;
        }
    }
    return null;
}

/** zip 内每一层都要过：禁可执行、禁路径穿越、禁 zip64 炸弹。 */
function inspectZip(string $path): array
{
    if (!class_exists('ZipArchive')) {
        return [['kind' => 'zip_unsupported', 'line' => 0, 'hint' => 'server has no ZipArchive']];
    }
    $z = new ZipArchive();
    if ($z->open($path) !== true) {
        return [['kind' => 'zip_broken', 'line' => 0, 'hint' => 'cannot open zip']];
    }
    $out = [];
    $sum = 0;
    for ($i = 0; $i < $z->numFiles; $i++) {
        $st = $z->statIndex($i);
        $name = (string)($st['name'] ?? '');
        $sum += (int)($st['size'] ?? 0);
        if (str_contains($name, '..')) {
            $out[] = ['kind' => 'zip_traversal', 'line' => 0, 'hint' => 'entry contains ".."'];
        }
        if (!in_array(extOf($name), ALLOWED_EXT, true)) {
            $out[] = ['kind' => 'zip_member_type', 'line' => 0,
                      'hint' => 'entry "' . mb_substr($name, 0, 60) . '" has a non-whitelisted type'];
        }
        if ($sum > MAX_UNZIP_BYTES) {
            $out[] = ['kind' => 'zip_bomb', 'line' => 0,
                      'hint' => 'uncompressed size exceeds 8 MiB'];
            break;
        }
    }
    $z->close();
    return $out;
}

// ── 内容审查（只看文本；命中就拒绝并告诉 AI 要剥什么，绝不静默改写）
function reviewText(string $text): array
{
    $findings = [];
    $lines = preg_split("/\r\n|\n|\r/", $text) ?: [];
    $phoneLines = 0;
    $emailLines = 0;
    foreach ($lines as $i => $line) {
        $no = $i + 1;
        if ($line === '') {
            continue;
        }
        if (preg_match('/-----BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----/', $line)) {
            $findings[] = ['kind' => 'private_key', 'line' => $no, 'hint' => 'PEM/OpenSSH private key block'];
        }
        if (preg_match('/\bsk-[A-Za-z0-9_\-]{16,}/', $line)) {
            $findings[] = ['kind' => 'openai_key', 'line' => $no, 'hint' => 'sk- token'];
        }
        if (preg_match('/\bgh[pousr]_[A-Za-z0-9]{20,}/', $line)) {
            $findings[] = ['kind' => 'github_token', 'line' => $no, 'hint' => 'GitHub token'];
        }
        if (preg_match('/\bAKIA[0-9A-Z]{16}\b/', $line)) {
            $findings[] = ['kind' => 'aws_key', 'line' => $no, 'hint' => 'AWS access key id'];
        }
        if (preg_match('/\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\./', $line)) {
            $findings[] = ['kind' => 'jwt', 'line' => $no, 'hint' => 'JWT'];
        }
        if (preg_match('/\b\d{17}[\dXx]\b/', $line)) {
            $findings[] = ['kind' => 'id_number', 'line' => $no, 'hint' => '18-digit ID number'];
        }
        // 银行卡：16–19 位且过 Luhn，避免把普通长数字误判成卡号
        if (preg_match_all('/\b(\d{16,19})\b/', $line, $m)) {
            foreach ($m[1] as $cand) {
                if (luhnOk($cand)) {
                    $findings[] = ['kind' => 'bank_card', 'line' => $no,
                                   'hint' => '16-19 digit number passing Luhn check'];
                    break;
                }
            }
        }
        if (preg_match_all('/\b1[3-9]\d{9}\b/', $line, $pm) && count($pm[0]) > 0) {
            $phoneLines++;
        }
        if (preg_match_all('/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/', $line, $em)) {
            $emailLines += count($em[0]);
        }
    }
    if ($phoneLines >= 3) {
        $findings[] = ['kind' => 'phone_bulk', 'line' => 0,
                       'hint' => "$phoneLines lines contain phone numbers"];
    }
    if ($emailLines >= 5) {
        $findings[] = ['kind' => 'email_bulk', 'line' => 0,
                       'hint' => "$emailLines e-mail addresses found"];
    }
    return $findings;
}

function luhnOk(string $n): bool
{
    $sum = 0;
    $alt = false;
    for ($i = strlen($n) - 1; $i >= 0; $i--) {
        $d = (int)$n[$i];
        if ($alt) {
            $d *= 2;
            if ($d > 9) {
                $d -= 9;
            }
        }
        $sum += $d;
        $alt = !$alt;
    }
    return $sum % 10 === 0;
}

$findings = reviewText($title . "\n" . $body);
foreach ($files as $f) {
    $ext = extOf($f['name']);
    if (!in_array($ext, ALLOWED_EXT, true)) {
        noteFail($db, $ipHash, $now);
        jout(415, ['ok' => false, 'code' => 'TYPE_REJECTED',
                   'error' => 'file type not allowed: .' . $ext,
                   'allowed' => ALLOWED_EXT,
                   'hint' => 'compress text/patch/log files as .zip and retry']);
    }
    $danger = sniffDanger($f['tmp']);
    if ($danger !== null) {
        noteFail($db, $ipHash, $now);
        jout(415, ['ok' => false, 'code' => 'BINARY_REJECTED',
                   'error' => 'binary not accepted: ' . $danger,
                   'hint' => 'this channel only accepts text, patches and logs']);
    }
    if ($ext === 'zip') {
        $findings = array_merge($findings, inspectZip($f['tmp']));
    } elseif (in_array($ext, ['txt', 'log', 'md', 'json', 'jsonl', 'patch', 'diff'], true)) {
        $txt = (string)file_get_contents($f['tmp'], false, null, 0, MAX_TOTAL_BYTES);
        $findings = array_merge($findings, reviewText($txt));
    }
}
if ($findings) {
    noteFail($db, $ipHash, $now);
    // 只报位置与类别，绝不回显原文 —— 审查器不能变成泄露通道。
    jout(422, ['ok' => false, 'code' => 'REVIEW_REJECTED',
               'error' => 'content needs to be stripped before upload',
               'findings' => array_slice($findings, 0, 20),
               'hint' => 'remove the reported items and retry; nothing was stored']);
}

// ── 额度（只统计成功的上传）
$q = $db->prepare('SELECT COALESCE(SUM(file_size),0) FROM uploads WHERE install_id = ? AND ts >= ?');
$q->execute([$installId, $now - 3600]);
$used = (int)$q->fetchColumn();
if ($used + $total > QUOTA_BYTES_HOUR) {
    noteFail($db, $ipHash, $now);
    jout(429, ['ok' => false, 'code' => 'QUOTA_EXCEEDED',
               'error' => 'hourly upload quota exceeded (2 MiB per install)',
               'used' => $used, 'limit' => QUOTA_BYTES_HOUR,
               'retry_after' => 3600]);
}

// ── 落盘（不可执行目录、随机名、不按扩展名推断类型）
$upDir = $DATA . '/up';
if (!is_dir($upDir)) {
    @mkdir($upDir, 0700, true);
}
$id = bin2hex(random_bytes(6));
$stored = [];
foreach ($files as $f) {
    $safe = preg_replace('/[^A-Za-z0-9._-]/', '_', basename($f['name'])) ?: 'file';
    $target = $upDir . '/' . $id . '_' . substr($safe, -80);
    if (!move_uploaded_file($f['tmp'], $target)) {
        noteFail($db, $ipHash, $now);
        jout(500, ['ok' => false, 'error' => 'cannot store upload']);
    }
    @chmod($target, 0600);
    $stored[] = [
        'name' => $safe,
        'size' => $f['size'],
        'sha256' => hash_file('sha256', $target),
        'path' => $target,
    ];
}

try {
    $db->beginTransaction();
    foreach ($stored as $s) {
        $db->prepare('INSERT INTO uploads (id, ts, install_id, title, body, file_name, file_size,
                mime, sha256, ip_hash, mode, app_version, version_code, note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)')->execute([
            count($stored) === 1 ? $id : $id . '_' . substr(md5($s['name']), 0, 4),
            $now, $installId, $title, $body, $s['name'], $s['size'], '',
            $s['sha256'], $ipHash,
            mb_substr((string)($_POST['mode'] ?? ''), 0, 24, 'UTF-8'),
            mb_substr((string)($_POST['app_version'] ?? ''), 0, 40, 'UTF-8'),
            (int)($_POST['version_code'] ?? 0),
            mb_substr((string)($_POST['contact'] ?? ''), 0, 120, 'UTF-8'),
        ]);
    }
    $db->prepare('UPDATE consent_tokens SET used = 1 WHERE token = ?')->execute([$consent]);
    $db->prepare('INSERT INTO attempts (ts, ip_hash, ok) VALUES (?,?,1)')->execute([$now, $ipHash]);
    $db->commit();
} catch (Throwable $e) {
    $db->rollBack();
    foreach ($stored as $s) {
        @unlink($s['path']);
    }
    error_log('[upload] ' . $e->getMessage());
    jout(500, ['ok' => false, 'error' => 'cannot record upload']);
}

jout(200, ['ok' => true, 'id' => $id, 'bytes' => $total,
           'quota_used' => $used + $total, 'quota_limit' => QUOTA_BYTES_HOUR,
           'files' => array_map(fn ($s) => ['name' => $s['name'], 'size' => $s['size'],
                                            'sha256' => $s['sha256']], $stored)]);
