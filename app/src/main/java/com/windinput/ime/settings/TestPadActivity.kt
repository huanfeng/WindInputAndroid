package com.windinput.ime.settings

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 输入测试台：给模拟器自动化测试用的裸输入框（scripts/emu-test.ps1 驱动）。
 *
 * 存在理由：键盘是 Canvas 自绘的，uiautomator 看不到按键节点，只能按坐标点击；
 * 而验证「打进去的到底是什么」需要一个 **resource-id 稳定、内容可 dump** 的输入框。
 * 借用系统设置/浏览器的输入框做测试会受其自身 IME 交互（自动补全、场景切换）干扰。
 */
class TestPadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        root.addView(
            EditText(this).apply {
                // 脚本按 content-desc 定位（代码设的 id 在 uiautomator dump 里不可见）
                contentDescription = TEST_PAD_DESC
                hint = "测试输入框"
                gravity = Gravity.TOP or Gravity.START
                setSingleLine(false)
                requestFocus()
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
    }

    companion object {
        /** 与 scripts/emu-test.ps1 约定的定位锚点 */
        const val TEST_PAD_DESC = "windinput-testpad"
    }
}
