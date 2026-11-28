package app.dsh.mobile.workspace

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import app.dsh.mobile.engine.NativeWorkspaceScope
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/** Local directory picker only; Host session/create remains the workspace registry authority. */
class NativeWorkspaceBridge(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val currentScope: () -> NativeWorkspaceScope?,
) {
    private val channel = MethodChannel(messenger, "app.dsh.mobile/native_workspace")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private data class PendingPick(val scope: NativeWorkspaceScope, val result: MethodChannel.Result, val ticket: Long)
    private var pending: PendingPick? = null
    private val selections = NativeWorkspaceSelections()
    private val preferences by lazy { activity.getSharedPreferences("native_workspace_directories", Activity.MODE_PRIVATE) }
    private val root get() = File(activity.filesDir, "native-workspaces")

    init { channel.setMethodCallHandler(::handle) }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val scope = currentScope()
        if (closed || scope == null || !scope.matches(call.argument("domain"), call.argument("generation"))) {
            result.error("workspace/scope-unknown", "本机引擎已变化，请重新打开目录选择", null)
            return
        }
        when (call.method) {
            "listPrivateWorkspaces" -> run(scope, result) {
                val base = privateRoot()
                val path = call.argument<String>("path") ?: base.path
                val selected = NativeWorkspacePaths(base).selectPrivate(path)
                receipt(scope) + mapOf("root" to base.path, "path" to selected.path,
                    "directories" to NativeWorkspacePaths(base).listPrivate(selected.path).map {
                        mapOf("path" to it.path, "name" to it.name)
                    })
            }
            "createPrivateWorkspace" -> choosePrivate(scope, result) {
                val base = privateRoot()
                val parent = call.argument<String>("parent") ?: base.path
                val name = call.argument<String>("name") ?: error("文件夹名称为空")
                NativeWorkspacePaths(base).createPrivate(parent, name)
            }
            "selectPrivateWorkspace" -> choosePrivate(scope, result) {
                val path = call.argument<String>("path") ?: error("所选目录为空")
                NativeWorkspacePaths(privateRoot()).selectPrivate(path)
            }
            "validateWorkspaceDirectory" -> run(scope, result) {
                val path = call.argument<String>("path") ?: error("所选目录为空")
                when (call.argument<String>("kind")) {
                    "private" -> directory(scope, NativeWorkspacePaths(privateRoot()).selectPrivate(path), "private")
                    "shared" -> {
                        val uri = Uri.parse(call.argument<String>("uri") ?: error("系统目录授权已失效"))
                        directory(scope, validateShared(scope, path, uri, allowTemporary = true), "shared", uri)
                    }
                    else -> error("目录类型无效")
                }
            }
            "rememberWorkspaceDirectory" -> {
                val path = call.argument<String>("path").orEmpty()
                val kind = call.argument<String>("kind").orEmpty()
                val uri = call.argument<String>("uri")
                // Capture identity before IO: a later identical re-selection is still a different choice.
                val choice = selections.matching(scope.domain, scope.generation, path, kind, uri)
                if (choice == null) {
                    result.error("workspace/unreachable", "目录不是本次真实选择，请重新选择", null)
                } else run(scope, result,
                    complete = { selected: NativeWorkspaceSelections.Choice -> remember(scope, selected) }) {
                    when (kind) {
                        "private" -> NativeWorkspacePaths(privateRoot()).selectPrivate(path)
                        "shared" -> validateShared(scope, path, Uri.parse(uri ?: error("系统目录授权已失效")), allowTemporary = true)
                        else -> error("目录类型无效")
                    }
                    choice
                }
            }
            "validateRegisteredWorkspaceDirectory" -> run(scope, result) {
                val path = call.argument<String>("path") ?: error("工作区目录为空")
                val hasRecord = preferences.contains(path)
                NativeWorkspacePaths(root).validateRegistered(path, hasRecord,
                    if (hasRecord) preferences.getString(path, null) else null) { registeredPath, uri ->
                    validateShared(scope, registeredPath, Uri.parse(uri), allowTemporary = false)
                }
            }
            "pickSharedWorkspace" -> {
                if (pending != null) {
                    result.error("workspace/busy", "系统目录选择器已打开", null)
                    return
                }
                pending = PendingPick(scope, result, selections.begin())
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
                @Suppress("DEPRECATION")
                val opened = runCatching { activity.startActivityForResult(intent, REQUEST_TREE) }
                if (opened.isFailure) {
                    pending = null
                    result.error("workspace/picker-unavailable", "系统目录选择器不可用", null)
                }
            }

            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_TREE) return false
        val pick = pending ?: return true
        pending = null
        val scope = pick.scope
        val result = pick.result
        val uri = data?.data
        if (closed || currentScope() != scope) {
            result.error("workspace/scope-unknown", "本机引擎已变化，请重新选择目录", null)
            return true
        }
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return true
        }
        run(scope, result, complete = { choice: NativeWorkspaceSelections.Choice ->
            selections.select(pick.ticket, choice)
            directory(scope, File(choice.path), "shared", uri)
        }) {
            val flags = data.flags
            check((flags and REQUIRED_GRANTS) == REQUIRED_GRANTS && hasTemporaryGrant(uri)) { "系统没有授予此目录的读写访问" }
            // Keep only the Activity grant until Flutter confirms Host registration.
            val mapped = sharedDirectory(uri)
            NativeWorkspaceSelections.Choice(scope.domain, scope.generation, mapped.absolutePath, "shared",
                uri.toString(), (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
        }
        return true
    }


    private fun sharedDirectory(uri: Uri): File {
        check(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "系统返回的目录 URI 无效" }
        val directAccess = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        check(directAccess) { "引擎没有共享存储的直接文件访问；请到权限中心手动配置存储权限，或选择应用私有目录" }
        return try {
            NativeWorkspacePaths.sharedDirectory(uri.authority.orEmpty(),
                DocumentsContract.getTreeDocumentId(uri), Environment.getExternalStorageDirectory())
        } catch (failure: Exception) {
            throw IllegalStateException("共享目录不可达：" + failure.message + "；请到权限中心手动配置存储权限，或选择应用私有目录", failure)
        }
    }

    private fun hasTemporaryGrant(uri: Uri): Boolean = activity.checkUriPermission(uri,
        android.os.Process.myPid(), android.os.Process.myUid(), REQUIRED_GRANTS) == PackageManager.PERMISSION_GRANTED

    private fun persistedFlags(uri: Uri): Int = activity.contentResolver.persistedUriPermissions
        .filter { it.uri == uri }.fold(0) { flags, grant -> flags or
            (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) }

    private fun validateShared(scope: NativeWorkspaceScope, path: String, uri: Uri,
        allowTemporary: Boolean): File {
        val selected = selections.matching(scope.domain, scope.generation, path, "shared", uri.toString())
        val temporary = allowTemporary && selected != null && hasTemporaryGrant(uri)
        val remembered = preferences.contains(path) && preferences.getString(path, null) == uri.toString() &&
            (persistedFlags(uri) and REQUIRED_GRANTS) == REQUIRED_GRANTS
        check(temporary || remembered) { "系统目录授权已失效，请重新选择；也可到权限中心手动配置存储权限，或选择应用私有目录" }
        val mapped = sharedDirectory(uri)
        check(mapped.absolutePath == path) { "共享目录路径与授权不匹配，请重新选择" }
        return mapped
    }

    private fun choosePrivate(scope: NativeWorkspaceScope, result: MethodChannel.Result, action: () -> File) {
        val ticket = selections.begin()
        run(scope, result, complete = { choice: NativeWorkspaceSelections.Choice ->
            selections.select(ticket, choice)
            directory(scope, File(choice.path), "private")
        }) {
            val mapped = action()
            NativeWorkspaceSelections.Choice(scope.domain, scope.generation, mapped.absolutePath, "private")
        }
    }

    /** Called only after Flutter confirms both Host registration and the workspace stream. */
    private fun remember(scope: NativeWorkspaceScope, choice: NativeWorkspaceSelections.Choice): Boolean {
        val uri = choice.uri?.let(Uri::parse)
        val previous = if (uri != null) preferences.getString(choice.path, null) else null
        val addedFlags = if (uri != null) REQUIRED_GRANTS and persistedFlags(uri).inv() else 0
        var grantAttempted = false
        var recordAttempted = false
        selections.commit(choice, { !closed && currentScope() == scope }, { guard ->
            if (uri != null) {
                check(choice.kind == "shared" && hasTemporaryGrant(uri)) { "本次系统目录授权已失效，请重新选择" }
                check(sharedDirectory(uri).absolutePath == choice.path) { "共享目录路径与授权不匹配，请重新选择" }
                guard()
                if (addedFlags != 0) {
                    check(choice.persistable) { "系统没有提供可保留的目录授权，请重新选择" }
                    grantAttempted = true
                    activity.contentResolver.takePersistableUriPermission(uri, REQUIRED_GRANTS)
                }
                guard()
                check((persistedFlags(uri) and REQUIRED_GRANTS) == REQUIRED_GRANTS) { "系统未保留目录读写授权，请重新选择" }
                guard()
                recordAttempted = true
                check(preferences.edit().putString(choice.path, uri.toString()).commit()) { "无法保存工作区授权记录，请重试" }
            }
        }, {
            try {
                if (recordAttempted) {
                    val editor = preferences.edit()
                    if (previous == null) editor.remove(choice.path) else editor.putString(choice.path, previous)
                    check(editor.commit()) { "无法回滚工作区授权记录" }
                }
            } finally {
                if (grantAttempted && uri != null && addedFlags != 0) {
                    activity.contentResolver.releasePersistableUriPermission(uri, addedFlags)
                }
            }
        })
        return true
    }


    private fun privateRoot(): File {
        val target = root
        check(!Files.isSymbolicLink(target.toPath())) { "应用私有工作区根目录不能是符号链接" }
        check(target.isDirectory || target.mkdir()) { "应用私有工作区根目录不可用" }
        check(target.canonicalFile.parentFile == activity.filesDir.canonicalFile) { "应用私有工作区越界" }
        return target.canonicalFile
    }

    private fun directory(scope: NativeWorkspaceScope, mapped: File, kind: String, uri: Uri? = null): Map<String, Any> =
        receipt(scope) + mapOf("path" to mapped.absolutePath, "name" to mapped.name, "kind" to kind) +
            (uri?.let { mapOf("uri" to it.toString()) } ?: emptyMap())

    private fun receipt(scope: NativeWorkspaceScope): Map<String, Any> =
        mapOf("domain" to scope.domain, "generation" to scope.generation)

    private fun <T> run(scope: NativeWorkspaceScope, result: MethodChannel.Result,
        complete: (T) -> Any? = { it }, action: () -> T) {
        val queued = runCatching {
            io.execute {
                val value = runCatching {
                    check(!closed && currentScope() == scope) { "本机引擎已变化，请重新选择目录" }
                    val answer = action()
                    check(!closed && currentScope() == scope) { "本机引擎已变化，请重新选择目录" }
                    answer
                }
                main.post {
                    if (!closed) {
                        val delivered = value.mapCatching {
                            check(!closed && currentScope() == scope) { "本机引擎已变化，请重新选择目录" }
                            complete(it)
                        }
                        delivered.fold(result::success) {
                            result.error("workspace/unreachable", it.message ?: "所选目录不可达", null)
                        }
                    }
                }
            }
        }.isSuccess
        if (!queued) result.error("workspace/unavailable", "目录服务已关闭，请重试", null)
    }


    fun close() {
        if (closed) return
        closed = true
        selections.close()
        pending?.result?.error("workspace/cancelled", "目录选择已关闭", null)
        pending = null
        channel.setMethodCallHandler(null)
        io.shutdown()
    }

    companion object {
        const val REQUEST_TREE = 0x4457
        private const val REQUIRED_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
