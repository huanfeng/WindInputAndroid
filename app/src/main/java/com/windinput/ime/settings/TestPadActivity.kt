package com.windinput.ime.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 输入测试台：**手动试打 + 自动化测试共用**的输入框集合。
 *
 * 两个用途一个页面，不做两份：
 * - 手动：从设置页进来试打，改完布局/主题/方案立刻能验，不必切到别的 App。
 * - 自动化：`scripts/emu-test.ps1` 按 `content-desc` 定位后按坐标敲键盘。
 *   键盘是 Canvas 自绘的、uiautomator 看不到按键节点，所以必须有一个
 *   **内容可 dump** 的输入框来回答「打进去的到底是什么」；借用系统设置或浏览器的
 *   输入框会受其自身 IME 交互（自动补全、场景切换）干扰。
 *
 * 多个输入框是**场景**而非装饰：`EditorInfo.inputType`/`imeOptions` 会改变输入法行为
 * （密码框强制英文、数字框切符号层、回车键变「搜索/发送」），这些分支只有在对应场景的
 * 输入框上才试得出来。
 */
class TestPadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        root.addView(
            label("普通文本（自动化测试用）"),
            wrap(),
        )
        root.addView(
            field(TEST_PAD_DESC, "测试输入框").apply { requestFocus() },
            wrap(),
        )

        root.addView(label("搜索框（回车键应显示「搜索」）"), wrap())
        root.addView(
            field("windinput-testpad-search", "搜索…").apply {
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setSingleLine(true)
            },
            wrap(),
        )

        root.addView(label("数字框（应初始切到数字层）"), wrap())
        root.addView(
            field("windinput-testpad-number", "数字").apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            },
            wrap(),
        )

        root.addView(label("密码框（应强制英文直输）"), wrap())
        root.addView(
            field("windinput-testpad-password", "密码").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
            wrap(),
        )

        setContentView(root)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF969696.toInt())
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** `contentDescription` 而非 id：代码设的 id 在 uiautomator dump 里不可见 */
    private fun field(desc: String, hintText: String) = EditText(this).apply {
        contentDescription = desc
        hint = hintText
        gravity = Gravity.TOP or Gravity.START
        setSingleLine(false)
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
