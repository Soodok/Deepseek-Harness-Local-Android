<?php
declare(strict_types=1);

/**
 * dsh bug report — 线上自证（公开，无需认证）。
 *
 * 「服务端源码在仓库里」这句话本身不构成保证：线上跑的完全可以是另一份。
 * 这个端点把每个已部署文件的 sha256 公开出来，任何人可以
 *
 *   curl -s https://soodok.online/bug/verify.php
 *   sha256sum server/bug-report/*.php
 *
 * 自己比对。对不上就说明线上与仓库不一致 —— 那是需要解释的事，不是小毛病。
 *
 * 刻意只报**代码文件**：`config.php`（密钥）不在列表里，
 * 既不给它的存在性做背书，也不泄露任何指纹。
 */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$here = __DIR__;
$files = ['report.php', 'consent.php', 'upload.php', 'admin.php', 'uploads.php', '.user.ini'];
$hashes = [];
$missing = [];
foreach ($files as $f) {
    $path = $here . '/' . $f;
    if (is_file($path)) {
        $hashes[$f] = hash_file('sha256', $path);
    } else {
        $missing[] = $f;
    }
}

// 部署自哪个提交由部署时写进 .bugdata/deployed.txt（一行 sha）。没有就是 null。
$commit = null;
$marker = dirname(__DIR__) . '/.bugdata/deployed.txt';
if (is_file($marker)) {
    $line = trim((string)@file_get_contents($marker));
    if (preg_match('/^[a-f0-9]{7,40}$/', $line)) {
        $commit = $line;
    }
}

echo json_encode([
    'ok' => true,
    'service' => 'dsh-bug-report',
    'proto' => 1,
    'commit' => $commit,
    'php' => PHP_VERSION,
    'files' => $hashes,
    'not_deployed' => $missing,
    'how_to_verify' => 'git clone https://github.com/Soodok/Deepseek-Harness-Local-Android && '
        . 'sha256sum server/bug-report/*.php   # compare with files above',
], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
