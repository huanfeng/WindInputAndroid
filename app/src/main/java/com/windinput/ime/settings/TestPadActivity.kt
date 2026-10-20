package com.windinput.ime.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 输入测试台：**手动试打 + 自动化测试共用**。
 *
 * # 为什么只有一个输入框
 *
 * 曾经做成「普通/搜索/数字/密码」四个框并排，结果**破坏了自动化的确定性**：
 * Activity 从后台回到前台时，Android 恢复的是**上次的焦点**而不是
 * `requestFocus()` 指定的那个；脚本点击目标框后 `InputConnection` 的重建还有延迟。
 * 于是「操作后再打字」类用例读到的是空框，看起来像功能坏了——实际字母进了另一个框。
 *
 * 现在改成**单框 + 场景切换按钮**：框只有一个，焦点无处可跑；
 * 场景（`inputType` / `imeOptions`）由按钮循环切换。两种用途都满足：
 * - 自动化：`content-desc` 恒定、焦点确定
 * - 手动：点按钮就能试密码框强制英文、数字框切符号层、回车键变「搜索」这些分支
 */
class TestPadActivity : Activity() {

    /** 场景：名称 → (inputType, imeOptions)。顺序即按钮循环顺序。 */
    private val scenes = listOf(
        Scene("普通文本（多行，回车换行）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED),
        Scene("搜索框（回车应显示「搜索」）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH),
        Scene("数字框（应切数字层）", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_UNSPECIFIED),
        // 用「可见密码」：同样走直输分支，但字不变成圆点，脚本读得出来
        Scene("密码框（可见密码，应强制英文直输）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, EditorInfo.IME_ACTION_DONE),
        // 聊天类应用的输入框大多带 CAP_SENTENCES：英文句首自动大写只在这种框里生效
        Scene("句子框（英文句首应自动大写）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED),
    )

    private data class Scene(val label: String, val inputType: Int, val imeOptions: Int)

    private var sceneIndex = 0
    private lateinit var field: EditText
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        label = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF969696.toInt())
        }
        root.addView(label, wrap())

        field = EditText(this).apply {
            // 脚本按 content-desc 定位（代码设的 id 在 uiautomator dump 里不可见）
            contentDescription = TEST_PAD_DESC
            hint = "测试输入框"
            gravity = Gravity.TOP or Gravity.START
            requestFocus()
        }
        root.addView(field, wrap())

        root.addView(
            Button(this).apply {
                text = "切换场景"
                setOnClickListener {
                    sceneIndex = (sceneIndex + 1) % scenes.size
                    applyScene()
                }
            },
            wrap(),
        )

        setContentView(root)
        applyScene()
    }

    /** 换场景要重启输入连接，否则 IME 拿到的还是旧的 EditorInfo */
    private fun applyScene() {
        val scene = scenes[sceneIndex]
        label.text = scene.label
        field.inputType = scene.inputType
        field.imeOptions = scene.imeOptions
        field.requestFocus()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.restartInput(field)
    }

    private fun wrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        /** 与 scripts/emu-test.ps1 约定的定位锚点 */
        const val TEST_PAD_DESC = "windinput-testpad"
    }
}
