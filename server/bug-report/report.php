<?php
declare(strict_types=1);

/**
 * dsh bug report collector.
 *
 *   POST /bug/report.php   客户端上报（JSON，需头 X-DSH-Report-Key）
 *   GET  /bug/report.php   健康检查：只回答服务在线与协议版本
 *
 * 数据与密钥都在 ../.bugdata/（nginx 的 `location ~ /\.` 会把任何含 "/." 的
 * 路径 403 掉，所以即使目录在 webroot 里也取不到）。
 */

const MAX_BODY          = 204800;   // 200 KiB
const RATE_IP_DAY       = 20;       // 单个 IP 每天最多
const RATE_INSTALL_DAY  = 5;        // 单个安装 ID 每天最多
const DEDUPE_SEC        = 600;      // 10 分钟内同内容视为重复
const PROTO_VERSION     = 1;

$DATA = dirname(__DIR__) . '/.bugdata';
$CFG  = $DATA . '/config.php';

function jout(int $code, array $payload): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    jout(200, ['ok' => true, 'service' => 'dsh-bug-report', 'proto' => PROTO_VERSION]);
}

if (!is_file($CFG)) {
    jout(503, ['ok' => false, 'error' => 'server not configured']);
}
$cfg = require $CFG;

$key = $_SERVER['HTTP_X_DSH_REPORT_KEY'] ?? '';
if (!is_string($key) || $key === '' || !hash_equals((string)$cfg['report_key'], $key)) {
    jout(403, ['ok' => false, 'error' => 'bad or missing X-DSH-Report-Key']);
}

$declared = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
if ($declared > MAX_BODY) {
    jout(413, ['ok' => false, 'error' => 'payload too large', 'limit' => MAX_BODY, 'got' => $declared]);
}

$raw = file_get_contents('php://input', false, null, 0, MAX_BODY + 1);
if ($raw === false || $raw === '') {
    jout(400, ['ok' => false, 'error' => 'empty body']);
}
if (strlen($raw) > MAX_BODY) {
    jout(413, ['ok' => false, 'error' => 'payload too large', 'limit' => MAX_BODY]);
}

$in = json_decode($raw, true, 8);
if (!is_array($in)) {
    jout(400, ['ok' => false, 'error' => 'body must be a JSON object']);
}

/** 取字符串字段并截断；非字符串一律当空。 */
function f_str(array $in, string $k, int $max): string
{
    $v = $in[$k] ?? '';
    if (!is_string($v)) {
        return '';
    }
    $v = str_replace("\0", '', $v);
    return mb_substr($v, 0, $max, 'UTF-8');
}

/** 取整数字段。 */
function f_int(array $in, string $k): int
{
    $v = $in[$k] ?? 0;
    if (is_int($v)) {
        return $v;
    }
    if (is_string($v) && preg_match('/^\d{1,12}$/', $v)) {
        return (int)$v;
    }
    return 0;
}

$installId = f_str($in, 'install_id', 64);
if ($installId !== '' && !preg_match('/^[A-Za-z0-9_-]{8,64}$/', $installId)) {
    jout(400, ['ok' => false, 'error' => 'install_id must be 8-64 chars of [A-Za-z0-9_-]']);
}

$title = f_str($in, 'title', 200);
$body  = f_str($in, 'body', 8000);
$logs  = f_str($in, 'logs', 65536);
if ($title === '' && $body === '') {
    jout(400, ['ok' => false, 'error' => 'title or body required']);
}

$ua      = substr((string)($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 300);
$ipRaw   = (string)($_SERVER['REMOTE_ADDR'] ?? '');
$ipHash  = hash('sha256', ($cfg['ip_salt'] ?? 'dsh') . '|' . $ipRaw);
$bodyHash = hash('sha256', $title . "\n" . $body . "\n" . $logs);
$now     = time();

try {
    if (!is_dir($DATA)) {
        @mkdir($DATA, 0700, true);
    }
    $db = new PDO('sqlite:' . $DATA . '/reports.db', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    ]);
    $db->exec('PRAGMA journal_mode=WAL');
    $db->exec('PRAGMA busy_timeout=5000');
    $db->exec(
        'CREATE TABLE IF NOT EXISTS reports (
            id TEXT PRIMARY KEY,
            ts INTEGER NOT NULL,
            install_id TEXT,
            body_hash TEXT,
            app_version TEXT,
            version_code INTEGER,
            abi TEXT,
            android TEXT,
            device TEXT,
            mode TEXT,
            title TEXT,
            body TEXT,
            logs TEXT,
            contact TEXT,
            ip_hash TEXT,
            ua TEXT,
            size INTEGER
        )'
    );
    $db->exec('CREATE INDEX IF NOT EXISTS idx_reports_ts ON reports(ts DESC)');
    $db->exec('CREATE INDEX IF NOT EXISTS idx_reports_inst ON reports(install_id, ts DESC)');

    // 幂等：同一安装 10 分钟内同样内容，直接回原来的 id。
    if ($installId !== '') {
        $q = $db->prepare(
            'SELECT id FROM reports WHERE install_id = ? AND body_hash = ? AND ts >= ? LIMIT 1'
        );
        $q->execute([$installId, $bodyHash, $now - DEDUPE_SEC]);
        $hit = $q->fetchColumn();
        if ($hit !== false && $hit !== null) {
            jout(200, ['ok' => true, 'id' => $hit, 'duplicate' => true]);
        }
    }

    $since = $now - 86400;
    $q = $db->prepare('SELECT COUNT(*) FROM reports WHERE ip_hash = ? AND ts >= ?');
    $q->execute([$ipHash, $since]);
    if ((int)$q->fetchColumn() >= RATE_IP_DAY) {
        jout(429, ['ok' => false, 'error' => 'rate limited (ip)', 'limit' => RATE_IP_DAY]);
    }
    if ($installId !== '') {
        $q = $db->prepare('SELECT COUNT(*) FROM reports WHERE install_id = ? AND ts >= ?');
        $q->execute([$installId, $since]);
        if ((int)$q->fetchColumn() >= RATE_INSTALL_DAY) {
            jout(429, ['ok' => false, 'error' => 'rate limited (install)', 'limit' => RATE_INSTALL_DAY]);
        }
    }

    $id = bin2hex(random_bytes(6));
    $ins = $db->prepare(
        'INSERT INTO reports (id, ts, install_id, body_hash, app_version, version_code, abi,
             android, device, mode, title, body, logs, contact, ip_hash, ua, size)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'
    );
    $ins->execute([
        $id,
        $now,
        $installId,
        $bodyHash,
        f_str($in, 'app_version', 40),
        f_int($in, 'version_code'),
        f_str($in, 'abi', 20),
        f_str($in, 'android', 40),
        f_str($in, 'device', 80),
        f_str($in, 'mode', 24),
        $title,
        $body,
        $logs,
        f_str($in, 'contact', 200),
        $ipHash,
        $ua,
        strlen($raw),
    ]);

    jout(200, ['ok' => true, 'id' => $id]);
} catch (Throwable $e) {
    error_log('[bug-report] ' . $e->getMessage());
    jout(500, ['ok' => false, 'error' => 'server error']);
}
