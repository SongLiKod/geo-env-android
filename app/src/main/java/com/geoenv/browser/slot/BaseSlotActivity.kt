package com.geoenv.browser.slot

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.geoenv.browser.R
import org.mozilla.geckoview.GeckoView
import timber.log.Timber

/**
 * 槽位浏览器界面（每槽位进程一个：SlotActivity1 ~ SlotActivity5）。
 *
 * 与 [BaseSlotHostService] 同进程，直接持有 [SlotEngine]：
 *  - [SlotEngine.attachView] 把 GeckoView 挂到当前 session；
 *  - URL 栏 / 后退 / 前进 / 重载 / 停止 直接调用引擎；
 *  - 引擎状态变化经 [SlotEngine.addStateListener] 刷新标题、进度与地址栏。
 *
 * 采用经典 View 树而非 Compose：GeckoView 是 `View`，同进程直挂最简单可靠。
 */
abstract class BaseSlotActivity : Activity() {

    protected abstract val slotIndex: Int

    private lateinit var engine: SlotEngine
    private lateinit var geckoView: GeckoView
    private lateinit var urlInput: EditText
    private lateinit var titleText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    private var ignoreUrlUpdate = false
    private var uiListener: ((SlotEngine) -> Unit)? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SlotEngine.get(slotIndex, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }

        // 顶栏：标题 + 地址栏 + 操作按钮
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }

        titleText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#202124"))
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlInput = EditText(this).apply {
            hint = "输入网址或搜索词"
            textSize = 14f
            isSingleLine = true
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ ->
                navigateFromInput(); true
            }
        }
        urlRow.addView(urlInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val goBtn = Button(this).apply {
            text = "前往"
            textSize = 12f
            setOnClickListener { navigateFromInput() }
        }
        urlRow.addView(goBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(urlRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        fun btn(label: String, click: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { click() }
        }
        btnRow.addView(btn("后退") { engine.goBack() })
        btnRow.addView(btn("前进") { engine.goForward() })
        btnRow.addView(btn("刷新") { engine.reload() })
        btnRow.addView(btn("停止") { finish() })
        btnRow.addView(btn("主页") { engine.navigate(com.geoenv.browser.slot.SlotEngine.HOME_URL) })
        top.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        }
        top.addView(progress)

        statusText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, dp(2), 0, 0)
        }
        top.addView(statusText)

        root.addView(top, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        geckoView = GeckoView(this)
        root.addView(geckoView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        engine.attachView(geckoView)
        uiListener = { e -> runOnUiThread { render(e) } }
        engine.addStateListener(uiListener!!)
        render(engine)
    }

    @SuppressLint("SetTextI18n")
    private fun render(e: SlotEngine) {
        titleText.text = e.title.ifBlank { e.envName.ifBlank { "槽位 $slotIndex" } }
        if (!ignoreUrlUpdate && e.url.isNotBlank() && !urlInput.hasFocus()) {
            urlInput.setText(e.url)
            urlInput.setSelection(urlInput.text.length)
        }
        progress.progress = e.progress
        progress.visibility = if (e.progress in 1..99) View.VISIBLE else View.GONE
        val port = if (e.marionetteEnabled && e.marionettePort > 0) " · Marionette :${e.marionettePort}" else ""
        val har = if (e.harRecording) " · HAR录制中" else ""
        statusText.text = "${e.status}$port$har" + (e.lastError?.let { " · $it" } ?: "")
    }

    private fun navigateFromInput() {
        val text = urlInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        ignoreUrlUpdate = false
        engine.navigate(text)
        urlInput.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        // 进程可能刚被拉起、或视图曾被销毁：重新挂载
        engine.attachView(geckoView)
        uiListener?.let { engine.removeStateListener(it) }
        uiListener = { e -> runOnUiThread { render(e) } }
        engine.addStateListener(uiListener!!)
    }

    override fun onPause() {
        uiListener?.let { engine.removeStateListener(it) }
        uiListener = null
        super.onPause()
    }

    override fun onDestroy() {
        uiListener?.let { engine.removeStateListener(it) }
        uiListener = null
        engine.attachView(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (engine.canGoBack) {
            engine.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "SlotActivity"
    }
}
