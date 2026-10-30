package app.dsh.mobile

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.AgentContextSeed

/**
 * 用户自定义指令页（v1.3.2，issue #19）。
 *
 * ## 背景
 * 用户希望「在现有提示词之上补写自己的指令」。引擎只加载
 * `$DSH_HOME/AGENTS.md` 这一个 user-global 指令文件（不会加载同目录其它文件名），
 * 所以这里的做法是：**该文件分成两段** —— App 管理的环境说明段（随版本升级整段替换）
 * 与用户自有段（App 永不覆盖）。本页只编辑用户段，因此：
 *   · 升级 App 不会丢用户写的内容；
 *   · 用户改指令也不会让环境说明变旧（两者互不干扰）。
 *
 * 页面底部展示「环境说明由 App 管理」的说明，避免用户以为改不了。
 */
class UserPromptActivity : ThemedActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4.18：跟随应用朝向偏好（横屏下编辑区域更宽，必要时仍可滚动）
        OrientationPrefs.forcePortrait(this)
        setContentView(R.layout.activity_user_prompt)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val editor = findViewById<EditText>(R.id.editorPrompt)
        val hint = findViewById<TextView>(R.id.txtPath)

        // 载入现有用户段（未写过则为空）
        val current = AgentContextSeed.readUserSection(this)
        editor.setText(current.orEmpty())
        if (current.isNullOrEmpty()) {
            editor.hint = getString(R.string.user_prompt_placeholder)
        }
        hint.text = getString(R.string.user_prompt_path)

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val text = editor.text.toString()
            AgentContextSeed.writeUserSection(this, text.ifBlank { null })
            Toast.makeText(this, getString(R.string.user_prompt_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
