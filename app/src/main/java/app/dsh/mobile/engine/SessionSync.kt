package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 手机端会话同步客户端（DSH-MOBILE sync protocol v1）。
 *
 * 电脑端由 DSH-MOBILE 的 LAN/公网代理暴露：
 *   GET  /__dshm/sync/ping
 *   GET  /__dshm/sync/manifest
 *   GET  /__dshm/sync/file?path=...
 *   PUT  /__dshm/sync/file?path=...
 *
 * 只同步 $DSH_HOME/sessions/**/*.jsonl；不碰凭证、插件、工作区文件。
 */
object SessionSync {
    private const val TAG = "SessionSync"
    private const val MAX_BYTES = 64 * 1024 * 1024

    data class Result(
        val downloaded: Int,
        val uploaded: Int,
        val conflicts: Int,
        val skipped: Int,
        val remoteFiles: Int,
        val localFiles: Int,
    )

    private data class Info(
        val path: String,
        val size: Long,
        val mtimeMs: Long,
        val sha256: String,
    )

    fun sync(ctx: Context, baseUrl: String, code: String, direction: String): Result {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "电脑地址必须以 http:// 或 https:// 开头" }
        require(code.isNotBlank()) { "密码不能为空" }

        val root = File(EngineConfig.dshHome(ctx), "sessions")
        root.mkdirs()
        val backupRoot = File(EngineConfig.dshHome(ctx), "sync-conflicts").apply { mkdirs() }

        val ping = JSONObject(getText(base, "/__dshm/sync/ping", code))
        require(ping.optInt("version", 0) == 1) { "电脑端同步协议版本不匹配" }

        val remote = parseManifest(getText(base, "/__dshm/sync/manifest", code))
        val local = localManifest(root)
        val localByPath = local.associateBy { it.path }
        val remoteByPath = remote.associateBy { it.path }
        val all = (localByPath.keys + remoteByPath.keys).sorted()

        var downloaded = 0
        var uploaded = 0
        var conflicts = 0
        var skipped = 0

        for (rel in all) {
            val l = localByPath[rel]
            val r = remoteByPath[rel]
            if (l != null && r != null && l.sha256 == r.sha256) {
                skipped++
                continue
            }

            if (direction != "push" && r != null && l == null) {
                writeLocal(root, rel, getBytes(base, "/__dshm/sync/file?path=" + urlEncode(rel), code))
                downloaded++
                continue
            }
            if (direction != "pull" && l != null && r == null) {
                putBytes(base, rel, readLocal(root, rel), code)
                uploaded++
                continue
            }

            if (l != null && r != null) {
                val localBytes = readLocal(root, rel)
                val remoteBytes = getBytes(base, "/__dshm/sync/file?path=" + urlEncode(rel), code)
                if (direction != "push" && isPrefix(localBytes, remoteBytes)) {
                    writeLocal(root, rel, remoteBytes)
                    downloaded++
                    continue
                }
                if (direction != "pull" && isPrefix(remoteBytes, localBytes)) {
                    putBytes(base, rel, localBytes, code)
                    uploaded++
                    continue
                }

                conflicts++
                val stamp = System.currentTimeMillis()
                val backup = File(backupRoot, rel.replace('/', File.separatorChar) + ".$stamp.local.jsonl")
                backup.parentFile?.mkdirs()
                backup.writeBytes(localBytes)
                if (direction != "pull" && r.mtimeMs > l.mtimeMs) {
                    writeLocal(root, rel, remoteBytes)
                    downloaded++
                } else if (direction != "pull") {
                    putBytes(base, rel, localBytes, code)
                    uploaded++
                }
                Log.w(TAG, "conflict $rel; local backup ${backup.absolutePath}")
            }
        }

        Log.i(TAG, "sync done: down=$downloaded up=$uploaded conflict=$conflicts skip=$skipped")
        return Result(downloaded, uploaded, conflicts, skipped, remote.size, local.size)
    }

    // ---------------------------------------------------------------- manifest

    private fun parseManifest(text: String): List<Info> {
        val files = JSONObject(text).getJSONArray("files")
        val out = ArrayList<Info>(files.length())
        for (i in 0 until files.length()) {
            val o = files.getJSONObject(i)
            out.add(
                Info(
                    path = o.getString("path"),
                    size = o.getLong("size"),
                    mtimeMs = o.getLong("mtimeMs"),
                    sha256 = o.getString("sha256"),
                )
            )
        }
        return out
    }

    private fun localManifest(root: File): List<Info> {
        val out = ArrayList<Info>()
        fun walk(dir: File) {
            val entries = dir.listFiles() ?: return
            for (f in entries) {
                if (f.name.startsWith(".") || f.name.endsWith(".tmp")) continue
                if (f.isDirectory) {
                    walk(f)
                } else if (f.isFile && f.name.endsWith(".jsonl")) {
                    val bytes = f.readBytes()
                    out.add(
                        Info(
                            path = f.relativeTo(root).path.replace(File.separatorChar, '/'),
                            size = bytes.size.toLong(),
                            mtimeMs = f.lastModified(),
                            sha256 = sha256(bytes),
                        )
                    )
                }
            }
        }
        walk(root)
        return out.sortedBy { it.path }
    }

    // ---------------------------------------------------------------- files

    private fun readLocal(root: File, rel: String): ByteArray =
        File(root, rel.replace('/', File.separatorChar)).readBytes()

    private fun writeLocal(root: File, rel: String, bytes: ByteArray) {
        val f = File(root, rel.replace('/', File.separatorChar))
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp-" + System.nanoTime())
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun isPrefix(short: ByteArray, long: ByteArray): Boolean {
        if (short.size > long.size) return false
        for (i in short.indices) if (short[i] != long[i]) return false
        return true
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- HTTP

    private fun open(base: String, path: String, code: String, method: String): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 45_000
        c.setRequestProperty("x-dshm-sync", code)
        c.setRequestProperty("Accept", "application/json, application/x-ndjson")
        return c
    }

    private fun getText(base: String, path: String, code: String): String {
        val c = open(base, path, code, "GET")
        try {
            val status = c.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status: ${c.errorStream?.bufferedReader()?.readText().orEmpty().take(200)}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun getBytes(base: String, path: String, code: String): ByteArray {
        val c = open(base, path, code, "GET")
        try {
            val status = c.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val len = c.contentLengthLong
            if (len > MAX_BYTES) throw IllegalStateException("会话文件超过 64 MiB")
            val bytes = c.inputStream.readBytes()
            if (bytes.size > MAX_BYTES) throw IllegalStateException("会话文件超过 64 MiB")
            return bytes
        } finally {
            c.disconnect()
        }
    }

    private fun putBytes(base: String, rel: String, bytes: ByteArray, code: String) {
        val c = open(base, "/__dshm/sync/file?path=" + urlEncode(rel), code, "PUT")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-ndjson")
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
        } finally {
            c.disconnect()
        }
    }
}
