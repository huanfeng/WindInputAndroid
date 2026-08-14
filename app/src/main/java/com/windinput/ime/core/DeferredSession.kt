package com.windinput.ime.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.windinput.ime.keyboard.KeyAction
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 延迟构造的会话：**核心在后台线程构造，构造期照常收键**。
 *
 * # 为什么必须异步
 *
 * `WindCore` 的构造不是轻活：三层配置加载、打开 redb、把词库 yaml 编译成 wdat
 * （首装时 wubi86 + 英文库合计数百毫秒到数秒）。此前它跑在 `onCreate` 的主线程上，
 * 方案数量一多就突破了系统的输入分发超时——真机/模拟器实测直接 ANR
 * （`Input dispatching timed out … Waited 5001ms for FocusEvent`）。
 *
 * 光把词库准备挪到后台是不够的（那是核心的 `prepare`），**构造本身**也在主线程上。
 *
 * # 未就绪期间的行为
 *
 * `keyPressed` 返回 `false`，宿主执行默认行为——字母直接上屏。这比「吞掉按键」或
 * 「卡住等待」都好：用户至多是头一两秒打出的字没经过中文引擎，而不是键盘假死。
 * 就绪后自动切换到真会话，无需重启输入法。
 */
class DeferredSession(
    private val dataDir: File,
    private val rustRoot: File,
    /**
     * 构造前的准备工作（出厂数据拷出），**同样在后台线程执行**。
     *
     * 别把它留在 `onCreate` 里：assets 有数 MB（五笔词库 5MB + 英文库 385KB），
     * 逐文件流式拷贝在模拟器上要好几秒，而且版本锚点含 `lastUpdateTime`，
     * 每次重装都会全量重拷——这是压垮输入分发超时的最后一根稻草。
     */
    private val prepareData: () -> Unit,
    /** 就绪后回调（主线程），供 UI 撤掉「准备中」提示 */
    private val onReady: (ImeSession) -> Unit,
) : ImeSession {

    private companion object {
        const val TAG = "DeferredSession"
    }

    override var listener: ((UiEvent) -> Unit)? = null
        set(value) {
            field = value
            delegate?.listener = value
        }

    @Volatile
    private var delegate: ImeSession? = null

    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已就绪（UI 据此显示准备中提示） */
    val ready: Boolean get() = delegate != null

    /** 启动后台构造（幂等） */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                prepareData()
            } catch (e: Throwable) {
                Log.e(TAG, "出厂数据准备失败，引擎可能读不到方案", e)
            }
            val installed = System.currentTimeMillis() - t0
            val session = RustSession.getOrCreate(dataDir, rustRoot)
            val cost = System.currentTimeMillis() - t0
            Log.i(TAG, "数据准备 ${installed}ms，核心构造 ${cost - installed}ms")
            if (session == null) {
                Log.e(TAG, "核心构造失败（${cost}ms），保持直输")
                return@Thread
            }
            mainHandler.post {
                session.listener = listener
                delegate = session
                Log.i(TAG, "核心就绪，耗时 ${cost}ms")
                onReady(session)
            }
        }, "wind-core-init").start()
    }

    override fun focusGained() {
        delegate?.focusGained()
    }

    override fun focusLost() {
        delegate?.focusLost()
    }

    /** 未就绪 → 交还宿主（字母直接上屏），绝不吞键 */
    override fun keyPressed(action: KeyAction, shifted: Boolean): Boolean =
        delegate?.keyPressed(action, shifted) ?: false

    override fun candidateSelected(index: Int): Boolean =
        delegate?.candidateSelected(index) ?: false

    override fun reset() {
        delegate?.reset()
    }

    override fun schemas(): List<SchemaEntry> = delegate?.schemas() ?: emptyList()

    override fun shuangpinLayouts(): List<LayoutEntry> =
        delegate?.shuangpinLayouts() ?: emptyList()

    override fun runCommand(cmd: EngineCmd) {
        delegate?.runCommand(cmd)
    }
}
