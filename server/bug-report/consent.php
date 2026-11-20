<?php
declare(strict_types=1);

/**
 * dsh bug report — 一次性同意 token 的签发。
 *
 * 流程：App 先来这里领一个 token → 在无障碍浮窗上给用户看「要发什么」并要求
 * 30 秒内点同意 → 同意后把 token 交给 AI/CLI → 上传时带 X-DSH-Consent。
 *
 * token 本身不证明「用户真的点了同意」（App 里嵌的 key 本来就能被扒），
 * 它保证的是**一次授权只能用一次、且 120 秒内有效**——防重放，不是防伪造。
 * 真正的同意闸门在 App 的弹窗上，服务端没有能力也没有必要替用户做主。
 */

const CONSENT_TTL = 120;

$DATA = dirname(__DIR__) . '/.bugdata';
$CFG  = $DATA . '/config.php';

function jout(int $code, array $p): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
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

try {
    $db = new PDO('sqlite:' . $DATA . '/reports.db', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    ]);
    $db->exec('PRAGMA busy_timeout=5000');
    $db->exec('CREATE TABLE IF NOT EXISTS consent_tokens (
        token TEXT PRIMARY KEY, ts INTEGER NOT NULL, used INTEGER DEFAULT 0)');
    $db->prepare('DELETE FROM consent_tokens WHERE ts < ?')->execute([time() - 86400]);
    $token = bin2hex(random_bytes(16));
    $db->prepare('INSERT INTO consent_tokens (token, ts, used) VALUES (?,?,0)')
       ->execute([$token, time()]);
    jout(200, ['ok' => true, 'token' => $token, 'expires_in' => CONSENT_TTL]);
} catch (Throwable $e) {
    error_log('[consent] ' . $e->getMessage());
    jout(500, ['ok' => false, 'error' => 'server error']);
}
