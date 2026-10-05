package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务（m1.30 读屏升级 v1.1.0）：
 *  - 手势注入：tap(x,y) / swipe —— 模拟点击与滑动
 *  - 读屏：dumpScreenJson() 遍历可见节点树，输出文本+坐标+可点击性（"非盲"能力）
 *  - 按文本点击：tapText("确定") —— 在节点树里找含该文本的可点击节点并点它
 *
 * 权限边界（privacy-first）：读屏能力由 canRetrieveWindowContent 开关（xml）授权；
 * 服务必须由用户在系统设置手动开启（Android 安全模型），关闭即所有能力失效。
 *
 * Agent 调用入口：AgentBridge (127.0.0.1:3083) 的 GET /screen、POST /tap，
 * 引擎内经 `scr` 包装器使用。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 订阅触摸交互事件（v1.2.54）：用于检测"人突然接管"。
        // 服务 xml 里 accessibilityEventTypes 已含 typeAllMask，但触摸类事件
        // （typeTouchInteractionStart/End）还需要在运行时显式加入过滤列表，
        // 否则部分 ROM 不会派发（实测需要 setServiceInfo 重新声明）。
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
            serviceInfo = info
        }.onFailure { android.util.Log.w("DshA11y", "touch event subscribe failed: ${it.message}") }
        // 悬浮窗（v1.2.57）：服务连上即显示，让用户随时看到 AI 在做什么。
        // 用 TYPE_ACCESSIBILITY_OVERLAY，无需额外授权。
        //
        // ⚠️ v1.2.66：先 resetHidden()。长按悬浮球会调 hide()，那会把 hidden 置 true 并
        // **粘住整个进程**（show() 直接早退）——而 resetHidden() 此前**没有任何调用点**，
        // 于是「长按隐藏后，悬浮窗在本进程内再也回不来」（实测：服务重连、切前台都不恢复）。
        // 服务重连 = 系统层面重新装配无障碍能力，此时恢复显示是符合直觉的语义。
        runCatching {
            StatusOverlay.resetHidden()
            StatusOverlay.show(this)
        }.onFailure { android.util.Log.w("DshA11y", "overlay show failed: ${it.message}") }
        // 语音输入（v1.2.65）：麦克风回调由服务自己持有 —— 旧版在 MainActivity 里赋值，
        // 主界面没活着时点麦克风完全无响应（且面板因窗口 token 不合法从未显示过）。
        runCatching { StatusOverlay.onMicClick = { startVoiceInput() } }
            .onFailure { android.util.Log.w("DshA11y", "mic wiring failed: ${it.message}") }
    }

    // ==================== 语音输入编排（v1.2.65） ====================
    //
    // 由无障碍服务托管，不依赖 MainActivity 存活：
    //  点悬浮窗麦克风 → 检查权限/离线模型 → 弹底部输入面板 → 实时识别 → 编辑后发送。
    // 发送经 VoiceBridge 转给 MainActivity 的 WebView 注入（主界面不在时给明确提示）。

    @Volatile private var voicePanel: VoicePanel? = null

    /**
     * 语音会话代次（v1.2.65）。
     *
     * 用于作废「在途的异步回调」：用户点麦克风时，模型可能正在后台加载
     * （`loadVoskAsync`），加载完成后的回调会启动识别。若用户在这期间点了取消，
     * 那个迟到的回调仍会把识别拉起来 —— 表现为「点了取消，过几秒又开始听」。
     * 每次 stop 都自增，回调里比对代次，不一致就直接丢弃。
     */
    @Volatile private var voiceGeneration = 0

    /**
     * 音频资源释放的**单线程**执行器。
     *
     * `stop()` 会 join 识别线程（阻塞），必须在后台做；用单线程串行还能保证
     * 「先 stop 旧的、再 start 新的」不会交错（否则新会话可能被迟到的 stop 干掉）。
     */
    private val voiceStopExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "voice-audio-ctl").apply { isDaemon = true }
        }

    /** 面板是否已显示（供外部查询） */
    fun isVoicePanelVisible(): Boolean = voicePanel?.isVisible == true

    /**
     * 麦克风点击入口 —— **开关语义**（v1.2.65）。
     *
     * 点一次开始聆听；聆听中再点一次 = 取消聆听**并关闭下方语音栏**。
     * 旧版这里只有「开始」语义，再点会重新走一遍流程，导致
     * 上方的 listening 状态取消了、下方面板还开着（用户实测反馈的 UI 不同步）。
     */
    fun startVoiceInput() {
        // 已在聆听 / 面板已显示 → 本次点击是「取消」
        if (voicePanel?.isVisible == true || StatusOverlay.isListening()) {
            Log.i("DshA11y", "mic toggle: cancelling")
            stopListening()
            voicePanel?.hide(stopAudio = false)   // 已停过，避免重复
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            StatusOverlay.flashNotice(getString(R.string.voice_need_permission), 4_000L)
            return
        }
        // 有离线模型（哪怕还没加载进内存）或系统识别可用，就放行 ——
        // 加载交给 listenIntoPanel 处理（它会等加载完自动开始）。
        val usable = VoiceBridge.hasDownloadedModel(this) || AsrManager.isAvailable(this)
        if (!usable) {
            StatusOverlay.flashNotice(getString(R.string.voice_no_service), 4_000L)
            return
        }
        showVoicePanelAndListen()
    }

    private fun showVoicePanelAndListen() {
        val panel = voicePanel ?: VoicePanel(this).also { voicePanel = it }
        // 面板隐藏（任何路径：Send / Close / 返回键）都必须停掉麦克风，
        // 否则系统一直显示「正在使用麦克风」——用户实测反馈的真 bug
        panel.onHide = { stopListening() }
        panel.show(
            onSend = { text ->
                stopListening()
                VoiceBridge.send(this, text)
            },
            onRetry = {
                // 重说：VoskRecognizer.start 内部已会先 stopAll 释放上一轮，
                // 这里只需复位 UI 状态（重复 stop 会造成 stopped/listening 抖动）
                StatusOverlay.setListening(false)
                listenIntoPanel()
            },
            onClose = { stopListening() },
        )
        listenIntoPanel()
    }

    /**
     * 停止一切识别并**释放麦克风**，复位悬浮窗状态。
     *
     * ⚠️ 三件事缺一不可（AudioRecord 在 VoskRecognizer 里，必须走它的 stopAll）：
     *  · AsrManager.stop() —— 系统识别的释放
     *  · VoskRecognizer.stopAll() —— stop + shutdown（release AudioRecord）+ close
     *  · StatusOverlay.setListening(false) —— 悬浮窗状态点回灰色
     * 漏掉第二条，系统状态栏的「麦克风占用」提示就不会消失。
     */
    /**
     * 停止一切识别并**释放麦克风**，复位悬浮窗状态。
     *
     * ## 为什么分两步（v1.2.65 二次修复）
     * `SpeechService.stop()` 内部是 `interrupt()` + **`join()`** —— 它会**阻塞调用线程**
     * 直到识别线程退出；而识别线程可能正阻塞在 `AudioRecord.read()` 上（缓冲区时长）。
     * 在主线程调用 = 主线程被卡住 + 麦克风迟迟不释放（用户实测「取消后还要等几秒」）。
     *
     * 所以：
     *  ① **立刻**在调用线程做 UI 复位与代次作废（用户即时看到状态变化）
     *  ② 把真正耗时的 `stop/shutdown/close` 丢到后台串行线程执行（不阻塞 UI）
     *
     * ⚠️ 三件事缺一不可（AudioRecord 在 VoskRecognizer 里）：
     *  · AsrManager.stop()      —— 系统识别的释放
     *  · VoskRecognizer.stopAll() —— stop + shutdown（release AudioRecord）+ close
     *  · StatusOverlay.setListening(false) —— 悬浮窗状态点回灰色
     */
    private fun stopListening() {
        voiceGeneration++          // 作废所有在途回调（加载完成/识别结果）
        stopWatchdog()
        // ① 立即反馈（调用线程可能就是主线程）
        runCatching { AsrManager.stop() }
        StatusOverlay.setListening(false)
        Log.i("DshA11y", "voice: stop requested (gen=$voiceGeneration)")
        // ② 后台串行释放音频资源（stop() 会 join 识别线程，不能在主线程等）
        voiceStopExecutor.execute {
            runCatching { app.dsh.mobile.engine.VoskRecognizer.stopAll() }
            Log.i("DshA11y", "voice: mic released (background)")
        }
    }

    // ==================== 麦克风占用兜底（v1.2.65） ====================
    //
    // 用户实测：「只要打开过一次语音，右上角就持续提示占用了语音通道」。
    // 根因：面板消失有多条路径（点 Close / 点 Send / 返回键 / 系统移除窗口），
    // 只有走 VoicePanel.hide() 的才会触发 onHide 回调；返回键等路径直接由系统
    // 移除窗口，回调不触发 → 识别继续跑 → AudioRecord 一直占着麦克风。
    //
    // 兜底：识别期间起一个看门狗，每 1.5s 检查面板是否还在；
    // 面板不在了（无论什么原因消失）就立即停识别释放麦克风。

    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdog: Runnable? = null

    private fun startWatchdog() {
        stopWatchdog()
        val r = object : Runnable {
            override fun run() {
                val panelAlive = voicePanel?.isVisible == true
                if (!panelAlive) {
                    Log.i("DshA11y", "watchdog: panel gone → releasing mic")
                    stopListening()
                    return
                }
                watchdogHandler.postDelayed(this, 1_500L)
            }
        }
        watchdog = r
        watchdogHandler.postDelayed(r, 1_500L)
    }

    private fun stopWatchdog() {
        watchdog?.let { watchdogHandler.removeCallbacks(it) }
        watchdog = null
    }

    /**
     * 开始一次识别（离线模型优先），结果写进面板。
     *
     * ⚠️ v1.2.65 修复「第一次点不显示 listening，要点第二次」：
     * 旧逻辑是 `isModelLoaded() || ensureVoskLoaded()` —— 模型在后台加载时
     * `ensureVoskLoaded` 返回 false，代码就直接回落到系统识别；而系统识别
     * （模拟器/无 Google App 设备）会立刻报错并把 listening 状态清掉，
     * 用户看到的就是「点了没反应，再点一下才行」（第二次模型已加载完）。
     *
     * 现在：模型加载中 → 显示「正在加载离线模型」并**等它加载完自动开始识别**，
     * 不再错误回落到系统识别。
     */
    private fun listenIntoPanel() {
        val panel = voicePanel

        // 1) 模型已就绪 → 直接走离线识别
        if (app.dsh.mobile.engine.VoskRecognizer.isModelLoaded()) {
            startVoskListening(panel)
            return
        }

        // 2) 已下载模型但未加载 → 后台加载，完成后自动开始（不回落）
        val hasModel = VoiceBridge.hasDownloadedModel(this)
        if (hasModel) {
            panel?.setHint(getString(R.string.voice_model_loading))
            StatusOverlay.setListening(true)   // 立刻进入「聆听」态，避免观感是"没反应"
            // ⚠️ 代次守卫：模型加载期间用户若点了取消，这个迟到回调不能再启动识别
            //（否则表现为「点了取消，过几秒又开始听」—— 用户实测反馈）
            val gen = voiceGeneration
            VoiceBridge.loadVoskAsync(this) { ok ->
                if (gen != voiceGeneration) {
                    Log.i("DshA11y", "vosk load callback dropped (gen $gen != $voiceGeneration)")
                    return@loadVoskAsync
                }
                if (ok) startVoskListening(panel)
                else {
                    panel?.setHint(getString(R.string.asr_model_load_failed))
                    StatusOverlay.setListening(false)
                }
            }
            return
        }

        // 3) 没有离线模型 → 用系统识别（无服务时给明确提示）
        if (!AsrManager.isAvailable(this)) {
            panel?.setHint(getString(R.string.voice_no_service))
            StatusOverlay.setListening(false)
            return
        }
        // 系统识别同样丢到音频控制线程（SpeechRecognizer 也要求主线程创建，
        // 故这里用 runOnUi 回主线程建会话，但 stop 的阻塞动作仍归执行器管）
        StatusOverlay.setListening(true)
        startWatchdog()   // 同 Vosk 路径：面板消失即释放麦克风
        AsrManager.start(
            ctx = this,
            onPartial = { t -> panel?.setText(t, final = false) },
            onFinal = { t ->
                panel?.setText(t, final = true)
                StatusOverlay.setListening(false)
                stopWatchdog()
            },
            onError = { msg ->
                panel?.setHint(msg)
                StatusOverlay.setListening(false)
                stopWatchdog()
            },
            // 结束（含识别失败/静默）：同 Vosk 路径 —— 有文字就留面板给用户编辑发送
            onEnd = {
                StatusOverlay.setListening(false)
                stopWatchdog()
                if (voicePanel?.currentText().isNullOrEmpty()) {
                    voicePanel?.hide(stopAudio = false)
                }
            },
        )
    }

    /** 启动离线识别（模型已加载的前提下） */
    /** 启动离线识别（模型已加载的前提下） */
    private fun startVoskListening(panel: VoicePanel?) {
        // 会话代次守卫：取消后，先前排队的异步回调不能再启动识别
        val gen = ++voiceGeneration
        StatusOverlay.setListening(true)
        panel?.setHint(getString(R.string.overlay_speak_now))
        startWatchdog()   // 面板一旦消失（任何路径）就释放麦克风
        // ⚠️ 在音频控制线程里串行启动：
        //  VoskRecognizer.start 内部会先 stopAll()（阻塞式 join 上一轮识别线程），
        //  放主线程会卡 UI；放这里还保证与 stopListening 的释放动作不会交错。
        voiceStopExecutor.execute {
            if (gen != voiceGeneration) {
                Log.i("DshA11y", "start skipped (gen $gen != $voiceGeneration)")
                return@execute
            }
            app.dsh.mobile.engine.VoskRecognizer.start(
                onPartial = { t -> if (gen == voiceGeneration) panel?.setText(t, final = false) },
                onFinal = { t ->
                    if (gen == voiceGeneration) {
                        panel?.setText(t, final = true)
                        StatusOverlay.setListening(false)
                        stopWatchdog()
                    }
                },
                // 静音超时/出错：本轮**识别**结束（麦克风已释放），但面板不一定要关。
                // 面板里已经有文字时必须留着 —— 它的存在意义就是"发之前可以改"。
                // 旧版无条件 hide：说完话静默 3 秒面板自己消失，用户来不及点发送，
                // 表现为「识别出来了但发不出去 / 无作用」（实测轨迹：onFinal 给文字
                // → 3s 后 idle → 面板被关掉）。
                // 没有文字（误触、没听清）才收起来，避免留个空面板挡屏幕。
                onEnd = {
                    if (gen == voiceGeneration) {
                        StatusOverlay.setListening(false)
                        stopWatchdog()
                        if (voicePanel?.currentText().isNullOrEmpty()) {
                            voicePanel?.hide(stopAudio = false)
                        }
                    }
                },
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 记录"有人碰了屏幕"。无法区分是 AI 的注入手势还是真手指
                // （注入的手势同样产生触摸事件），故只记录时间戳与计数，
                // 由调用方结合"AI 自己刚派发了几次手势"来扣除自身动作。
                lastTouchAt = System.currentTimeMillis()
                touchCount++
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                lastTouchEndAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // ⚠️ v1.2.56：window change **不能**作为"用户接管"的判据 ——
                // 应用自己启动、弹出联想下拉、切页都会触发它（用户实测：
                // 启动 Edge、弹下拉时 lastWindowChangeAt 变了，但 touchCount=0、
                // lastTouchAt=0）。这里只更新前台包名缓存供 pkg 查询，
                // 干预判定一律以 touchCount 为准（见 interferedSince）。
                lastWindowChangeAt = System.currentTimeMillis()
                foregroundPkgCache = e.packageName?.toString() ?: foregroundPkgCache
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        // 释放麦克风：服务被销毁（无障碍被关闭/系统回收）时若还在识别，
        // AudioRecord 会随进程残留，系统一直显示麦克风占用
        stopListening()
        runCatching { voicePanel?.hide() }
        voicePanel = null
        runCatching { StatusOverlay.hide(this) }
        super.onDestroy()
    }

    // ==================== 外部干预感知（v1.2.54） ====================
    //
    // 用户反馈："有的时候人操作突然接管，AI 也不知道"。
    // 真实场景：AI 正在跑多步流程，用户拿过手机自己点了两下 —— AI 继续按原计划
    // 操作，结果点在完全不同的界面上，或者把用户的输入覆盖掉。
    //
    // 机制：记录触摸/窗口变化的时间戳。调用方（AI）在关键步骤前后比对，
    // 若在自己"没做动作"的时间窗内出现了触摸或窗口切换，即判定为外部干预。

    @Volatile private var lastTouchAt: Long = 0L
    @Volatile private var lastTouchEndAt: Long = 0L
    @Volatile private var lastWindowChangeAt: Long = 0L
    @Volatile private var touchCount: Int = 0
    @Volatile private var foregroundPkgCache: String? = null

    /** 干预快照：交给调用方做前后比对 */
    data class InterferenceSnapshot(
        val lastTouchAt: Long,
        val lastWindowChangeAt: Long,
        val touchCount: Int,
        val pkg: String?,
    )

    /** 取当前干预快照 */
    fun interferenceSnapshot(): InterferenceSnapshot = InterferenceSnapshot(
        lastTouchAt = lastTouchAt,
        lastWindowChangeAt = lastWindowChangeAt,
        touchCount = touchCount,
        pkg = foregroundPackage(),
    )

    /**
     * 判断自 [since] 以来是否有外部干预（**只看触摸，不看窗口变化**）。
     *
     * ⚠️ v1.2.56 修复（用户实测假阳性）：旧实现把 `lastWindowChangeAt` 也算作
     * 干预信号，但应用自己启动/弹联想下拉/切页都会触发 window change ——
     * 实测启动 Edge、弹下拉时 `interfered:true` 但 `touchCount:0`、`lastTouchAt:0`，
     * 属误判。**"人是否接管"的唯一可靠信号是触摸**，窗口变化只反映界面在动。
     *
     * ⚠️ 自身手势的排除（时序问题）：注入手势产生的 TOUCH_INTERACTION_START 事件
     * 到达时间**晚于** markSelfAction() 打的标记，用时间戳排除会漏掉这批事件、
     * 把自己的动作误判成用户干预。改用**计数**：AI 每派发一次手势就
     * `expectSelfTouch()` +1，判定时从总触摸数里扣除（见 selfTouchCredit）。
     *
     * @param since 起始时间戳（毫秒）；该时刻之前的触摸不计
     * @param ignoreUntil 兼容保留（时间窗下界），当前实现以计数扣除为主
     */
    fun interferedSince(since: Long, ignoreUntil: Long): Boolean {
        // 只看触摸：窗口变化是应用自身行为，不能作为"人接管"的证据
        if (lastTouchAt <= since) return false
        // 扣除 AI 自身手势：待核销的预期触摸数 > 0 时，优先认定为自己的动作
        val credit = selfTouchCredit.get()
        if (credit > 0) {
            // 核销一次（该触摸是我们自己发的）
            selfTouchCredit.decrementAndGet()
            return false
        }
        // 时间窗兜底：标记之后极短时间内（<250ms）的触摸视为自身手势回声
        if (lastSelfActionAt > ignoreUntil && lastTouchAt - lastSelfActionAt < 250L) return false
        return true
    }

    /** AI 自己刚派发过动作 → 记下时刻 */
    @Volatile private var lastSelfActionAt: Long = 0L
    fun markSelfAction() {
        lastSelfActionAt = System.currentTimeMillis()
    }
    fun lastSelfAction(): Long = lastSelfActionAt

    /**
     * AI 预期自己会产生一次触摸事件（派发手势前调用）。
     * 用**计数**而非时间戳排除自身手势 —— 注入手势的事件到达晚于标记时间，
     * 时间窗会漏掉它们（v1.2.56 修复）。
     */
    private val selfTouchCredit = java.util.concurrent.atomic.AtomicInteger(0)
    fun expectSelfTouch() {
        selfTouchCredit.incrementAndGet()
    }

    /** 当前待核销的自身触摸数（诊断用） */
    fun pendingSelfTouches(): Int = selfTouchCredit.get()

    // ==================== 读屏 ====================

    /**
     * 紧凑读屏（v1.2.55）：**治"慢"的真正关键**。
     *
     * 为什么需要：完整 JSON 每节点 11 个字段（含 cls/rid/w/h/四个布尔），
     * 200 节点实测约 44KB ≈ **1.5 万 tokens**。每一轮对话都要把这坨数据重新
     * 过一遍注意力 —— 用户实测"平均五六秒才动一次"，瓶颈就在这（不是动作慢，
     * 是喂给模型的上下文太肥）。
     *
     * 紧凑格式只保留**定位必需**的信息，实测压缩 6.8 倍（44KB → 6.5KB）：
     *   每行一个节点：`序号 文本 @x,y [标志]`
     *   标志：c=可点击  s=可滚动  e=可编辑  d=用 desc 而非 text
     *
     * 示例：
     *   ```
     *   1080x2400 12nodes
     *   0 设置 @540,1200 c
     *   1 搜索 @540,300 ce
     *   2 d:返回 @80,150 c
     *   ```
     * 坐标是节点中心，可直接喂给 `scr tap`。desc-only 节点（图标按钮）标 `d:`。
     *
     * @param filter "all" | "clickable"（含 editable）| "editable"
     */
    fun dumpScreenCompact(filter: String = "all"): String {
        val sb = StringBuilder()
        var count = 0
        val filterRestricted = filter != "all"
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val editable = node.isEditable
                val clickable = node.isClickable
                if ((text.isNotEmpty() || desc.isNotEmpty() || clickable) &&
                    (!filterRestricted || when (filter) {
                        "clickable" -> clickable || editable
                        "editable" -> editable
                        else -> true
                    })
                ) {
                    val scrollable = node.isScrollable || node.actionList.any { a ->
                        a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                            a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                    }
                    val flags = buildString {
                        if (clickable) append('c')
                        if (scrollable) append('s')
                        if (editable) append('e')
                    }
                    // 文本优先；无文本时用 desc 并标 d:
                    // ⚠️ v1.2.68 读屏瘦身（内置 AI 建议）：超长文本截断。
                    // 实测百度结果页混进 2KB+ 的广告 URL 节点，一次 dump 里一半是垃圾；
                    // prompt 变小 → prefill 线性变快。保留长度提示，模型仍知道"这是长文本"。
                    val label = when {
                        text.isNotEmpty() -> text.replace('\n', ' ')
                        desc.isNotEmpty() -> "d:" + desc.replace('\n', ' ')
                        else -> "-"
                    }.let { if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS) + "…(${it.length})" else it }
                    lines.add(
                        "$count $label @${rect.centerX()},${rect.centerY()}" +
                            if (flags.isNotEmpty()) " $flags" else "",
                    )
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        sb.append("${resources.displayMetrics.widthPixels}x$rootHeight ${lines.size}nodes\n")
        lines.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    /**
     * 遍历活跃窗口可见节点，输出 JSON：
     * {"ok":true,"width":..,"height":..,"count":N,
     *  "nodes":[{"index":0,"text":"..","desc":"..","cls":"..","rid":"..",
     *            "x":..,"y":..,"w":..,"h":..,"clickable":true,
     *            "scrollable":false,"editable":false}]}
     * 只保留「有文本/描述」或「可点击」的节点，上限 200 个防超大界面。
     *
     * ⚠️ 体积提示（v1.2.55）：本格式每节点 11 字段，200 节点 ≈ 44KB ≈ 1.5 万 tokens，
     * 会显著拖慢每轮推理。**日常自动化优先用 dumpScreenCompact()**（压缩 6.8 倍）；
     * 本函数保留给需要 cls/rid/精确尺寸的场景（如按 resource-id 定位）。
     *
     * v1.2.30 增强：新增 rid（viewIdResourceName）/scrollable/editable/index（遍历序，
     * 同一 UI 状态下稳定，可作点击定位的次优选择）；可选只输出可点击节点（省 token）。
     * @param clickableOnly true = 只输出可点击节点
     */
    fun dumpScreenJson(filter: String = "all"): String {
        val arr = JSONArray()
        var count = 0
        val filterRestricted = filter != "all"
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if ((text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) &&
                    (!filterRestricted || when (filter) {
                        "clickable" -> node.isClickable
                        "editable" -> node.isEditable
                        else -> true
                    })
                ) {
                    arr.put(JSONObject().apply {
                        put("index", count)
                        put("text", text)
                        put("desc", desc)
                        put("cls", node.className?.toString() ?: "")
                        put("rid", node.viewIdResourceName ?: "")
                        put("x", rect.centerX())
                        put("y", rect.centerY())
                        put("w", rect.width())
                        put("h", rect.height())
                        put("clickable", node.isClickable)
                        // Compose 的滚动容器常不置 isScrollable，用滚动 action 探测兜底
                        put("scrollable", node.isScrollable ||
                            node.actionList.any { a ->
                                a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                                    a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                            })
                        put("editable", node.isEditable)
                    })
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return JSONObject()
            .put("ok", true)
            .put("width", resources.displayMetrics.widthPixels)
            .put("height", rootHeight)
            .put("count", count)
            .put("nodes", arr).toString()
    }

    /**
     * 原始节点树转储（保留父子层级，供调用方判断"哪个容器可滚动"等结构信息）。
     * 每行一个节点：缩进即深度；含 cls/id/bounds/三开关/text/desc。
     * viewIdResourceName 在上游应用未混淆 id 时可直接用于精准定位。
     */
    fun screenXml(): String {
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val attrs = buildList {
                add("cls=\"${node.className ?: ""}\"")
                add("id=\"${node.viewIdResourceName ?: ""}\"")
                add("bounds=\"${rect.left},${rect.top},${rect.right},${rect.bottom}\"")
                add("clickable=${node.isClickable}")
                add("scrollable=${node.isScrollable}")
                add("editable=${node.isEditable}")
                val t = node.text?.toString()
                if (!t.isNullOrEmpty()) add("text=\"$t\"")
                val d = node.contentDescription?.toString()
                if (!d.isNullOrEmpty()) add("desc=\"$d\"")
            }.joinToString(" ")
            sb.append("  ".repeat(depth)).append("<node ").append(attrs).append("/>\n")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(rootInActiveWindow, 0)
        return sb.toString()
    }

    /**
     * 截取当前屏幕为 PNG 并返回 (宽, 高, base64)。**API 30+ 可用**（takeScreenshot）。
     * 同步等待结果（AgentBridge 的请求线程阻塞在此，默认超时 3s）。
     * 返回 null = 平台不支持或截图失败。
     */
    fun screenshotBase64(timeoutMs: Long = 3000L): Triple<Int, Int, String>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                }
                screenshot.hardwareBuffer.close()
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, { it.run() }, callback)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val bitmap = bmp ?: return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return Triple(bitmap.width, bitmap.height, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
    }

    /** 屏幕上是否存在文本/描述包含 q 的节点（/wait 的服务端判定） */
    fun screenContains(q: String): Boolean {
        val query = q.trim()
        val root = rootInActiveWindow ?: return false
        var found = false
        fun walk(node: AccessibilityNodeInfo?) {
            if (found || node == null) return
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.contains(query, true) || d.contains(query, true)) {
                found = true
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    private val rootHeight: Int
        get() = resources.displayMetrics.heightPixels

    // ==================== 点击 ====================

    /** 按文本查找可点击节点并点击（text+desc、trim、忽略大小写；完全>前缀>包含）。
     *
     *  ⚠️ v1.2.56 修复（用户实测）：此前匹配到文字节点后，若它存在可点击祖先，
     *  会**返回那个祖先并点它的中心** —— 当祖先是整个列表/下拉容器时，中心落在
     *  完全不同的条目上，于是出现「返回 ok:true 但页面毫无变化」。
     *  实测案例：搜索下拉建议里的 TextView "Dsh mobile"（clickable=false），
     *  父容器是外层 LinearLayout；点祖先中心无效，点文字自身坐标才真正提交。
     *  现改为**点命中节点自身中心**（触摸事件会自然冒泡到可点击祖先），
     *  仅当命中节点尺寸为 0 时才回退到祖先矩形。 */
    fun tapText(text: String): Boolean {
        val hit = findNodeByText(text) ?: return false
        return tapNode(hit)
    }

    /** 按 contentDescription 查找并点击（侧边栏图标按钮等 desc-only 节点） */
    fun tapDesc(desc: String): Boolean {
        val hit = findNodeByText(desc, byDesc = true) ?: return false
        return tapNode(hit)
    }

    /**
     * 点击一个节点的"可点位置"：优先节点**自身中心**（用户手指就是这么点的，
     * 触摸会冒泡到可点击祖先）；仅当自身尺寸为 0（部分容器节点如此）时，
     * 才回退到最近的可点击祖先矩形。
     */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val own = Rect().also { node.getBoundsInScreen(it) }
        val rect = if (own.width() > 0 && own.height() > 0) {
            own
        } else {
            val anc = clickableAncestorOrNull(node)
            if (anc != null) Rect().also { anc.getBoundsInScreen(it) } else own
        }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitterFor(rect))
    }

    /** 最近的可点击祖先；没有则 null（与 clickableAncestor 不同，后者回退到自身） */
    private fun clickableAncestorOrNull(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** 按节点尺寸决定抖动半径：短边/4 是安全上限（中心 ± 该值仍在节点内） */
    private fun jitterFor(rect: Rect): Int {
        val shortSide = minOf(rect.width(), rect.height())
        return minOf(DEFAULT_TAP_JITTER, shortSide / 4).coerceAtLeast(0)
    }

    /** 匹配打分：完全相等 > 前缀 > 包含（全部 trim + 忽略大小写，text 与 desc 同权） */
    private fun matchScore(value: String, query: String): Int {
        val v = value.lowercase()
        val q = query.lowercase()
        return when {
            v == q -> 3
            v.startsWith(q) -> 2
            v.contains(q) -> 1
            else -> 0
        }
    }

    private fun findNodeByText(query: String, byDesc: Boolean = false): AccessibilityNodeInfo? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestClickable = false

        fun consider(node: AccessibilityNodeInfo) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val score = maxOf(
                if (byDesc) 0 else matchScore(t, q),
                matchScore(d, q),
            )
            if (score == 0) return
            val clickable = node.isClickable || hasClickableAncestor(node)
            // 更高分优先；同分优先「本身就是可点击节点」
            if (score > bestScore || (score == bestScore && clickable && !bestClickable)) {
                best = node
                bestScore = score
                bestClickable = clickable
            }
        }

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            consider(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        if (best == null) return null
        // ⚠️ v1.2.56：**返回命中节点自身**，不再返回可点击祖先。
        // 旧实现返回祖先 → 点击时用祖先中心 → 祖先若是整个列表/下拉容器，
        // 中心落在别的条目上（用户实测："Dsh mobile" 建议项 clickable=false，
        // 点祖先中心无效，点文字坐标才生效）。
        // 位置计算交给 tapNode()：它优先用命中节点自身中心（触摸会冒泡到祖先）。
        return best
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null) {
            if (cur.isClickable) return true
            cur = cur.parent
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo = node
        while (!cur.isClickable) {
            cur = cur.parent ?: return node
        }
        return cur
    }

    // ==================== companion ====================

    companion object {
        @Volatile
        internal var instance: DshAccessibilityService? = null

        /** 服务是否已启用（用户在系统设置开启后为 true） */
        fun isEnabled(): Boolean = instance != null

        /** 是否支持手势注入（API 24+ 且服务已连接） */
        fun canTap(): Boolean = instance != null && Build.VERSION.SDK_INT >= 24

        /** dumpScreen 的最大节点数（防超大界面卡顿） */
        private const val MAX_NODES = 200

        /**
         * 紧凑读屏里单个节点文本的最大长度（超出截断 + 标注原长）。
         * 实测长 URL/广告文案节点可达 2KB+，一次 dump 里一半 token 花在这种噪音上。
         */
        private const val MAX_LABEL_CHARS = 100

        /** 滚动后等待动画结束的时间（节点树读到中间态会导致误判"没找到"） */
        private const val SCROLL_SETTLE_MS = 260L

        /** 默认点击抖动半径（像素）：小屏设备约 2–6px，兼顾拟人化与命中率 */
        private const val DEFAULT_TAP_JITTER = 4

        /**
         * 模拟点击屏幕 (x, y)（物理像素坐标）。
         * @param jitterPx 落点随机偏移半径；0 = 精确点击
         * @return true 表示已成功派发合成点击
         */
        fun tap(x: Float, y: Float, jitterPx: Int = DEFAULT_TAP_JITTER): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < 24) return false
            return svc.dispatchTap(x, y, jitterPx)
        }
    }

    /** 实例内手势派发（companion.tap 与 tapText 共用）。
     *
     *  拟人化（v1.2.54）：默认在目标点附近做小幅随机偏移。
     *  机器特征里最容易识别的一条就是「每次都点同一个像素」——真实手指
     *  落点天然有分布。偏移半径 [jitterPx]，0 = 精确（需要像素级操作时用）。
     *  ⚠️ 诚实说明：这削弱的是「模式识别」，不是「指纹识别」——
     *  无障碍注入的手势在系统层仍有固有特征（deviceId、pressure 恒为 1.0），
     *  要彻底隐形需 Root 层 uinput 注入，不在本版范围。 */
    internal fun dispatchTap(x: Float, y: Float, jitterPx: Int = DEFAULT_TAP_JITTER): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val (jx, jy) = if (jitterPx > 0) {
            val r = kotlin.random.Random
            val dx = r.nextInt(-jitterPx, jitterPx + 1).toFloat()
            val dy = r.nextInt(-jitterPx, jitterPx + 1).toFloat()
            // 限制在屏幕内，避免抖出边界导致手势无效
            val (sw, sh) = screenSize()
            (x + dx).coerceIn(1f, sw - 2f) to (y + dy).coerceIn(1f, sh - 2f)
        } else x to y
        val path = Path().apply { moveTo(jx, jy) }
        // 按压时长也在人类范围内抖动（真实点按约 50–130ms，恒定 60ms 是机器特征）
        val duration = if (jitterPx > 0) kotlin.random.Random.nextLong(55L, 135L) else 60L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        // 标记"这是我自己发的"——注入手势也会产生 TYPE_TOUCH_INTERACTION_START，
        // 不打标记的话下一轮干预检测会把自己的动作误判成用户接管。
        // 同时**预期**一次触摸事件（计数核销比时间戳可靠：事件到达晚于标记）
        markSelfAction()
        expectSelfTouch()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 任意滑动/手势（无障碍 dispatchGesture，无需 Shizuku/Root 权限）。
     *
     * ⚠️ v1.2.68：坐标**先钳进屏幕**再建 Path。
     * 内置 AI 实测反馈「向上滚动报 `Path bounds must not be negative`，只能向下找」——
     * `Path`/`StrokeDescription` 遇到负坐标会直接抛异常（整条手势失败），
     * 而调用方（引擎侧的 scr / batch 里的 swipe 步）可能按"元素位置 ± 偏移"算出负值。
     * 这里钳制后至少"贴着边缘滑动"仍能滚动；越界时打日志便于定位是哪个调用方。
     */
    internal fun dispatchSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val (sw, sh) = screenSize()
        val cx1 = x1.coerceIn(1f, sw - 2f); val cy1 = y1.coerceIn(1f, sh - 2f)
        val cx2 = x2.coerceIn(1f, sw - 2f); val cy2 = y2.coerceIn(1f, sh - 2f)
        if (cx1 != x1 || cy1 != y1 || cx2 != x2 || cy2 != y2) {
            Log.w("DshA11y", "swipe coords clamped: ($x1,$y1)->($x2,$y2) => ($cx1,$cy1)->($cx2,$cy2)")
        }
        val path = Path().apply { moveTo(cx1, cy1); lineTo(cx2, cy2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按（单点长时长 stroke） */
    internal fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 全局动作（back/home/recents/notifications/quick_settings）。
     *  ⚠️ 实测本应用内 back 会把整个 Activity 弹到桌面而非关闭弹层，
     *  调用方（sctl 焦点守卫）应检测前台包名并提示恢复。 */
    fun performGlobalActionByName(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    // ==================== 文字输入（v1.2.54） ====================
    //
    // 为什么必须有：此前只有 tap/swipe/key，AI 能点开搜索框却打不了字 ——
    // 搜索、登录、发消息、填表全部卡死在这一步（用户反馈"无障碍基本用不了"
    // 的头号原因）。文字输入是自动化闭环的最后一块拼图。

    /**
     * 向当前聚焦的可编辑节点写入文本。
     *
     * 两条路径，按可靠性排序：
     *  ① `ACTION_SET_TEXT` —— 无障碍原生接口，直接设定内容，不走 IME、不受
     *    输入法语言/联想干扰（首选）。
     *  ② 剪贴板 + `ACTION_PASTE` —— ①失败时回退（部分自绘输入框/WebView
     *     不实现 ①，但支持粘贴）。
     *
     * @param text 要写入的文本
     * @param append true = 追加到现有内容末尾，false = 覆盖
     * @param targetText 可选的定位文本：先找到该节点（如输入框的提示文字）并聚焦，
     *        再写入。省略则直接用当前焦点节点。
     * @return true 表示已成功写入
     */
    fun inputText(text: String, append: Boolean = false, targetText: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        // 定位目标节点：显式目标 > 当前焦点 > 首个可编辑节点
        val target: AccessibilityNodeInfo? = when {
            !targetText.isNullOrBlank() -> findEditableByText(root, targetText)
            else -> null
        } ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)

        val node = target ?: return false

        // 未聚焦时先请求焦点（否则 SET_TEXT 可能作用不到）
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val finalText = if (append) {
            (node.text?.toString().orEmpty()) + text
        } else {
            text
        }

        // ① ACTION_SET_TEXT
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // ② 剪贴板 + 粘贴（SET_TEXT 不被支持时的回退）
        return runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", finalText))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /** 按文本/描述找到可编辑节点（输入框常以 hint 文本暴露） */
    private fun findEditableByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (hit != null || node == null) return
            val t = node.text?.toString()?.trim().orEmpty().lowercase()
            val d = node.contentDescription?.toString()?.trim().orEmpty().lowercase()
            val h = if (Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.trim().orEmpty().lowercase()
            } else ""
            if (node.isEditable && (t.contains(q) || d.contains(q) || h.contains(q))) {
                hit = node
                return
            }
            // 命中的容器内含可编辑子节点（如 hint 在父布局上）
            if (t.contains(q) || d.contains(q) || h.contains(q)) {
                findFirstEditable(node)?.let { hit = it; return }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return hit
    }

    /** 深度优先找第一个可编辑节点 */
    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ==================== 滚动查找（v1.2.54） ====================
    //
    // 为什么必须有：目标在屏幕外时，此前只能「swipe → dump → 没找到 → 再 swipe」
    // 手动循环，一次找元素烧 5-10 个来回。滚动查找把整个循环压到服务端一次调用。

    /**
     * 在可滚动容器内滚动查找文本，找到则返回命中节点的矩形。
     *
     * 返回 Rect（而非仅坐标）是为了让调用方能按**节点尺寸**决定点击抖动半径 ——
     * 小控件不能被抖出边界（见 jitterFor）。
     *
     * @param query 目标文本（含 contentDescription，忽略大小写）
     * @param maxSwipes 最多滚动次数（默认 8）
     * @param forward true = 向下/向前滚动，false = 向上/向后
     * @return 找到时返回节点屏幕矩形；未找到返回 null
     */
    fun scrollToFind(query: String, maxSwipes: Int = 8, forward: Boolean = true): Rect? {
        val q = query.trim()
        if (q.isEmpty()) return null

        // 先在当前屏找
        findNodeByText(q)?.let { return Rect().also { rr -> it.getBoundsInScreen(rr) } }

        val root = rootInActiveWindow ?: return null
        val scroller = findScrollable(root) ?: return null

        repeat(maxSwipes.coerceIn(1, 30)) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (!scroller.performAction(action)) return null
            // 等滚动动画结束再读节点树（否则读到中间态）
            Thread.sleep(SCROLL_SETTLE_MS)
            findNodeByText(q)?.let { return Rect().also { rr -> it.getBoundsInScreen(rr) } }
        }
        return null
    }

    /** 找第一个真正可滚动的容器（Compose 常不置 isScrollable，用 action 探测兜底） */
    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val canScroll = node.isScrollable || node.actionList.any { a ->
            a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                a.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        }
        if (canScroll) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ==================== 等待界面稳定（v1.2.54） ====================
    //
    // 为什么必须有：点击后立刻读屏会拿到「旧界面」，AI 以为没生效 → 重复点。
    // 此前只能靠 wait <文本> 猜或硬 sleep。稳定检测让"点完等它安静下来"变成一次调用。

    /** 当前节点树签名（文本+描述+坐标的稳定摘要），用于判断界面是否变化 */
    private fun treeSignature(): String {
        val sb = StringBuilder()
        var n = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || n >= MAX_NODES) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || node.isClickable) {
                sb.append(t).append('|').append(d).append('|')
                    .append(r.centerX()).append(',').append(r.centerY()).append(';')
                n++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString()
    }

    /**
     * 阻塞等待界面稳定：连续 [stableReads] 次读取签名不变即认为稳定。
     *
     * ⚡ v1.2.58 提速（用户反馈"有时候还是偏慢"）：
     *  · **快路径**：若**首次**采样就与上一次已知签名相同（界面本来就没动），
     *    立即返回 —— 不必再等满 [stableReads] 轮。多数"点完就静止"的场景
     *    （开关、选中、页面已加载完）因此从 ~360ms 降到 ~50ms。
     *  · **采样间隔下调**：节点树读取本身耗时 20–50ms，原 120ms 间隔是纯浪费；
     *    降到 60ms 仍能可靠捕捉动画（Android 动画帧间隔 ~16ms，一次采样可跨多帧）。
     *  · **签名计算复用**：把上次签名作为起点传入，避免第一次比较必然"变化"。
     *
     * @param timeoutMs 总超时上限（到点即使未稳定也返回，交由调用方判断）
     * @param quietMs 每次采样间隔
     * @param stableReads 需要连续几次不变才算稳定
     * @param knownSignature 调用方已知的上一状态签名（用于快路径判定；null = 无）
     * @return true = 已稳定，false = 超时（界面仍在变）
     */
    fun waitForIdle(
        timeoutMs: Long = 3000L,
        quietMs: Long = 60L,
        stableReads: Int = 2,
        knownSignature: String? = null,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = knownSignature ?: ""
        var sameCount = if (knownSignature != null) 1 else 0
        while (System.currentTimeMillis() < deadline) {
            val sig = treeSignature()
            if (sig.isNotEmpty() && sig == last) {
                sameCount++
                if (sameCount >= stableReads) return true
            } else {
                sameCount = 0
                last = sig
            }
            Thread.sleep(quietMs)
        }
        return false
    }

    /** 当前前台应用包名（供调用方确认"是否还在目标界面"） */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** 屏幕尺寸（供坐标换算/比例点击） */
    fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    /**
     * 点击一个节点矩形（供桥接层在"滚动找到"后点击）。
     * 抖动半径按矩形尺寸自适应，小控件不会被抖出边界。
     */
    fun dispatchTapRect(rect: Rect): Boolean {
        val jitter = minOf(DEFAULT_TAP_JITTER, minOf(rect.width(), rect.height()) / 4)
            .coerceAtLeast(0)
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitter)
    }

    // ==================== 批量动作执行（v1.2.54） ====================
    //
    // 用户反馈的核心问题："太慢"。根因不是单次动作慢（桥接往返只有几十毫秒），
    // 而是**每个动作都要 AI 往返一次**——dump → LLM 思考（秒级）→ 点 → dump → …
    // 一个 10 步流程就是 10 次 LLM 调用。
    //
    // 解法：一次调用执行一串动作，只在最后回一次结果。
    // AI 只思考一次（"打开设置 → 点 WLAN → 等它稳定"），剩下的在服务端跑完。

    /** 批量动作的一步执行结果 */
    data class StepResult(
        val index: Int,
        val action: String,
        val ok: Boolean,
        val detail: String,
    )

    /**
     * 执行一串动作，每步之间自动等待界面稳定。
     *
     * 支持的步骤类型（step 是 Map，字段与桥接层 JSON 一致）：
     *   {"type":"tap","text":"WLAN"} / {"type":"tap","x":1,"y":2}
     *   {"type":"input","text":"hello"}
     *   {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *   {"type":"key","action":"back"}
     *   {"type":"wait","text":"已连接","timeoutMs":5000}
     *   {"type":"idle","timeoutMs":2000}
     *
     * 每步默认等界面稳定（`settleMs`），避免"点了立刻读旧界面"导致的连锁失败。
     * 任一步失败即停止（除非该步标了 `"optional":true`），返回已执行的结果列表。
     *
     * @param steps 动作序列
     * @param settleMs 每步后等待稳定的上限
     * @param stopOnError 失败是否中止（默认 true）
     * @return 每步的结果
     */
    fun runBatch(
        steps: List<Map<String, Any?>>,
        settleMs: Long = 2_000L,
        stopOnError: Boolean = true,
        /** 每步稳定后回调（v1.2.70：桥接层用它做单步截图） */
        onAfterStep: ((Int) -> Unit)? = null,
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        steps.forEachIndexed { i, step ->
            val type = (step["type"] as? String).orEmpty()
            val optional = step["optional"] == true
            var ok = false
            var detail = ""

            try {
                ok = when (type) {
                    "tap" -> {
                        val text = step["text"] as? String
                        val desc = step["desc"] as? String
                        when {
                            !desc.isNullOrBlank() -> tapDesc(desc)
                            !text.isNullOrBlank() -> tapText(text)
                            else -> {
                                val x = (step["x"] as? Number)?.toFloat()
                                val y = (step["y"] as? Number)?.toFloat()
                                if (x != null && y != null) dispatchTap(x, y) else false
                            }
                        }
                    }
                    "long_press" -> {
                        val x = (step["x"] as? Number)?.toFloat()
                        val y = (step["y"] as? Number)?.toFloat()
                        val ms = (step["durationMs"] as? Number)?.toLong() ?: 600L
                        if (x != null && y != null) dispatchLongPress(x, y, ms) else false
                    }
                    "swipe" -> {
                        val x1 = (step["x1"] as? Number)?.toFloat()
                        val y1 = (step["y1"] as? Number)?.toFloat()
                        val x2 = (step["x2"] as? Number)?.toFloat()
                        val y2 = (step["y2"] as? Number)?.toFloat()
                        val ms = (step["durationMs"] as? Number)?.toLong() ?: 300L
                        if (x1 != null && y1 != null && x2 != null && y2 != null) {
                            dispatchSwipe(x1, y1, x2, y2, ms)
                        } else false
                    }
                    "input" -> {
                        val t = step["text"] as? String ?: ""
                        inputText(t, step["append"] == true, step["target"] as? String)
                    }
                    // ⚠️ v1.2.57：字段名**两种都接受**。用户实测踩坑：
                    // 单条命令是 `scr key home`，照此写成 {"type":"key","key":"home"}
                    // → 字段名不匹配 → ok:false → **整条 batch 中断**。
                    // 宽进严出：key/action 任给其一即可，避免这类"文档没写清"的翻车。
                    "key" -> performGlobalActionByName(
                        ((step["action"] ?: step["key"]) as? String).orEmpty(),
                    )
                    "scroll_find" -> {
                        // 字段名宽容：text/query 都接受（同类踩坑预防）
                        val t = ((step["text"] ?: step["query"]) as? String) ?: ""
                        val forward = step["back"] != true
                        val maxSwipes = ((step["maxSwipes"] ?: step["max"]) as? Number)?.toInt() ?: 8
                        val hit = scrollToFind(t, maxSwipes, forward)
                        if (hit != null) {
                            detail = "found at ${hit.centerX()},${hit.centerY()}"
                            if (step["tap"] == true) dispatchTapRect(hit) else true
                        } else false
                    }
                    "wait" -> {
                        val t = ((step["text"] ?: step["query"]) as? String) ?: ""
                        val gone = step["gone"] == true
                        val timeout = (step["timeoutMs"] as? Number)?.toLong() ?: 5_000L
                        if (t.isBlank()) { detail = "wait needs \"text\""; false }
                        else waitForText(t, gone, timeout)
                    }
                    "idle" -> {
                        val timeout = (step["timeoutMs"] as? Number)?.toLong() ?: settleMs
                        waitForIdle(timeout)
                    }
                    "sleep" -> {
                        Thread.sleep(((step["ms"] as? Number)?.toLong() ?: 500L).coerceIn(0L, 10_000L))
                        true
                    }
                    else -> {
                        // 未知步骤类型：把**收到的字段名**回给调用方，
                        // 让 AI 一眼看出是拼写问题还是字段名不对（同类踩坑预防）
                        detail = "unknown step type \"$type\"; fields=${step.keys.joinToString(",")}"
                        false
                    }
                }
            } catch (e: Exception) {
                ok = false
                detail = e.message.orEmpty()
            }

            // 失败但没有具体原因时，补一条通用提示（避免只有 ok:false 无法定位）
            if (!ok && detail.isEmpty()) {
                detail = when (type) {
                    "tap" -> "no matching node (check the dump: exact label? still on screen?)"
                    "input" -> "no editable field focused (tap the field first, or pass \"target\")"
                    "key" -> "unknown action; use back|home|recents|notifications|quick_settings"
                    "scroll_find" -> "not found after scrolling"
                    else -> "step failed"
                }
            }

            results.add(StepResult(i, type, ok, detail))

            if (!ok && !optional) {
                if (stopOnError) return results
            }
            // ⚡ 稳定等待只对"会改变界面"的步骤做（v1.2.58）：
            //  · wait/idle/sleep 本身就是同步语义，自己已经等过了
            //  · key（返回/主页）与 scroll_find 已经内置了等待或自带目标判定
            //  · 非首步的 input 往往紧跟在同一界面，无需再等
            // 只有 tap / long_press / swipe / input 需要"等它安静下来"。
            // 这一步省掉后，10 步 batch 的固定开销约减半（~3.6s → ~1.8s）。
            val needsSettle = ok && type in setOf("tap", "long_press", "swipe", "input")
            if (needsSettle) {
                // 稳定判定放宽到 2 轮 × 60ms（配合快路径，静止场景约 50ms 返回）
                waitForIdle(settleMs, quietMs = 60L, stableReads = 2)
            }
            // 每步截图回调（放在稳定等待之后 = 截到"这一步做完后的界面"）
            runCatching { onAfterStep?.invoke(i) }
        }
        return results
    }

    /** 等待文本出现/消失（批量步骤与 /wait 共用） */
    fun waitForText(text: String, gone: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = screenContains(text)
            if (found != gone) return true
            Thread.sleep(150)
        }
        return false
    }
}
