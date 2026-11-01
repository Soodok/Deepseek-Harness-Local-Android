package app.dsh.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.PermGrant
import org.json.JSONObject
import java.util.Locale

/**
 * 权限清单（v1.5.0-beta.10）。
 *
 * ## 为什么要有这一页
 * 2026-11-01 审计发现：权限中心只有一个「432 / 1358」的数字，用户和 AI 都**看不到
 * 具体拿到了哪些、还差哪些**。1358 个权限光靠肉眼翻不动，所以必须有搜索与筛选。
 *
 * ## 三个筛选维度
 * - 状态：全部 / 已拿到 / 还差的
 * - 类别：从权限名前缀推导（android.permission / com.oplus / com.oppo / 其它）
 * - 关键词：按名搜索
 *
 * ## 撤销
 * 长按任一行可以撤销该权限（需 root 或 Shizuku）。撤销后 App 会被系统杀掉
 * 重载 gid 表 —— 这是 `pm revoke` 的固有行为，不是我们做错了。撤销按钮里
 * 明确写了这一点，避免用户以为崩了。
 */
class PermissionListActivity : ThemedActivity() {

    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var tabs: TextView
    private lateinit var summary: TextView
    private lateinit var empty: TextView

    private var filter = 0 // 0=all 1=granted 2=missing
    private var keyword = ""

    private data class Row(val name: String, val granted: Boolean, val group: String) {
        val short: String get() = name.substringAfterLast('.')
    }

    private var all: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_list)

        list = findViewById(R.id.listPerms)
        search = findViewById(R.id.editPermSearch)
        tabs = findViewById(R.id.txtPermTabs)
        summary = findViewById(R.id.txtPermSummary)
        empty = findViewById(R.id.txtPermEmpty)

        findViewById<View>(R.id.btnPermBack).setOnClickListener { finish() }
        findViewById<View>(R.id.txtPermTabs).setOnClickListener { cycleFilter() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        list.adapter = Adapter()
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val row = shown.getOrNull(pos) ?: return@setOnItemLongClickListener true
            if (row.granted) confirmRevoke(row) else grantOne(row)
            true
        }

        reload()
    }

    private fun cycleFilter() {
        filter = (filter + 1) % 3
        applyFilter()
    }

    private fun reload() {
        val declared = PermGrant.declaredPermissions(this)
        all = declared.map { Row(it, isGranted(it), groupOf(it)) }
        applyFilter()
    }

    private fun applyFilter() {
        val kw = keyword.lowercase(Locale.ROOT)
        shown = all.filter { r ->
            val okState = when (filter) {
                1 -> r.granted
                2 -> !r.granted
                else -> true
            }
            okState && (kw.isEmpty() || r.name.lowercase(Locale.ROOT).contains(kw))
        }.sortedWith(compareByDescending<Row> { it.granted }.thenBy { it.name })

        val granted = all.count { it.granted }
        summary.text = getString(R.string.perm_list_summary, granted, all.size - granted)
        tabs.text = getString(
            when (filter) {
                1 -> R.string.perm_list_tab_granted
                2 -> R.string.perm_list_tab_missing
                else -> R.string.perm_list_tab_all
            },
        )
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun isGranted(name: String): Boolean =
        checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun groupOf(name: String): String = when {
        name.startsWith("android.permission.") -> getString(R.string.perm_group_android)
        name.startsWith("com.oplus.") || name.startsWith("com.oppo.") -> getString(R.string.perm_group_oplus)
        name.startsWith("com.android.") || name.startsWith("com.google.") -> getString(R.string.perm_group_google)
        else -> getString(R.string.perm_group_other)
    }

    private fun grantOne(row: Row) {
        Thread {
            val out = app.dsh.mobile.engine.Privilege.runPrivileged(
                this, "pm grant " + packageName + " " + row.name,
            )
            runOnUiThread {
                if (out == null) {
                    toast(getString(R.string.perm_list_no_channel))
                } else {
                    reload()
                    toast(if (isGranted(row.name)) getString(R.string.perm_list_granted_one) else getString(R.string.perm_list_grant_failed))
                }
            }
        }.start()
    }

    private fun confirmRevoke(row: Row) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.perm_list_revoke_title)
            .setMessage(getString(R.string.perm_list_revoke_msg, row.short))
            .setPositiveButton(R.string.perm_list_revoke) { _, _ -> revokeOne(row) }
            .setNegativeButton(R.string.btn_back, null)
            .show()
    }

    private fun revokeOne(row: Row) {
        Thread {
            val out = app.dsh.mobile.engine.Privilege.runPrivileged(
                this, "pm revoke " + packageName + " " + row.name,
            )
            runOnUiThread {
                if (out == null) {
                    toast(getString(R.string.perm_list_no_channel))
                } else {
                    toast(getString(R.string.perm_list_revoked))
                    reload()
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class Adapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@PermissionListActivity)

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, recycle: View?, parent: ViewGroup?): View {
            val v = recycle ?: inflater.inflate(R.layout.item_permission, parent, false)
            val row = shown[position]
            v.findViewById<TextView>(R.id.permName).text = row.short
            v.findViewById<TextView>(R.id.permFull).text = row.group
            val st = v.findViewById<TextView>(R.id.permState)
            st.text = getString(if (row.granted) R.string.perm_list_granted else R.string.perm_list_missing)
            st.setTextColor(
                getColor(if (row.granted) R.color.success else R.color.text_secondary),
            )
            return v
        }
    }
}
