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
import app.dsh.mobile.engine.CloudAsr
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务（m1.30 读屏升级 v1.1.0）：
 *  - 手势注入：tap(x,y) / swipe —— 模拟点击与滑动
 *  - 读屏：dumpScreenJson() 遍历可见节点树，输出文本+坐标+可点击性（「非盲」能力）
 *  - 按文本点击：tapText(「确定」) —— 在节点树里找含该文本的可点击节点并点它
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
        // 订阅触摸交互事件（v1.2.54）：用于检测「人突然接管」。
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
        // ⚠️ v1.2.96：悬浮窗归入**实验性功能**管辖（主人要求）——开关关闭（默认）时
        // 悬浮窗不出现，只保留读屏/点击等 Agent 能力。开关实时生效见 setOverlayEnabled。
        //
        // ⚠️ v1.2.66：先 resetHidden()。长按悬浮球会调 hide()，那会把 hidden 置 true 并
        // **粘住整个进程**（show() 直接早退）——而 resetHidden() 此前**没有任何调用点**，
        // 于是「长按隐藏后，悬浮窗在本进程内再也回不来」（实测：服务重连、切前台都不恢复）。
        // 服务重连 = 系统层面重新装配无障碍能力，此时恢复显示是符合直觉的语义。
        runCatching {
            StatusOverlay.resetHidden()
            if (DshApp.experimentalOn(this)) {
                StatusOverlay.show(this)
                // 流式悬浮条（v1.2.99）：与悬浮球同受实验性开关管辖
                tickerBar().show()
                tickerBar().startPolling()
            } else {
                Log.i("DshA11y", "overlay suppressed: experimental features off")
            }
        }.onFailure { android.util.Log.w("DshA11y", "overlay show failed: ${it.message}") }
        // 语音输入（v1.2.65）：麦克风回调由服务自己持有 —— 旧版在 MainActivity 里赋值，
        // 主界面没活着时点麦克风完全无响应（且面板因窗口 token 不合法从未显示过）。
        runCatching { StatusOverlay.onMicClick = { startVoiceInput() } }
            .onFailure { android.util.Log.w("DshA11y", "mic wiring failed: ${it.message}") }

        // 保活看门狗（v1.2.91，主人要求「利用无障碍/悬浮窗机制保活」）
        //
        // ## 为什么用无障碍服务做保活
        // 无障碍服务由系统持有、随系统启动、**极难被 ROM 杀掉**（比前台服务更稳）。
        // 它天然适合当"保活哨兵"：定期检查引擎前台服务是否还活着，不活就拉起来。
        //
        // ## 为什么只是"检查 + 拉起"而不是更激进的手段
        //  · 本项目 targetSdk 28（sideload 分发），前台服务 + 无障碍已经足够
        //  · 双服务互拉（AlarmManager 心跳等）耗电明显，且属灰色手段
        //  · 这里只在**确实掉了**的时候补一刀，正常情况零开销
        startKeepAliveWatchdog()
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
     * （`CloudAsr` 启动），回调会启动识别。若用户在这期间点了取消，
     * 那个迟到的回调仍会把识别拉起来 —— 表现为「点了取消，过几秒又开始听」。
     * 每次 stop 都自增，回调里比对代次，不一致就直接丢弃。
     */
    @Volatile private var voiceGeneration = 0

    /** 是否处于"已震过开始"的聆听态（用于停止时配对震动，避免重复触发） */
    @Volatile private var recordingForBuzz = false

    /**
     * 云识别**本轮已并入面板的完整文本**（v1.2.92）。
     *
     * 取代了旧的 [lastCloudSegment] 单段比较：那个只记"上一段"，遇到 A→B→A 的
     * 重复回调就失效了。这里记的是**累积台账**，能同时处理增量语义（追加）与
     * 累积语义（整段替换），也能识别"这段已经并过了"。
     */
    @Volatile private var cloudCommitted: String = ""

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
     * 实验性开关切换时由设置页调用（v1.2.96）：实时显隐悬浮窗。
     *
     * · 开：resetHidden + show（resetHidden 必须先行——hide 会把 hidden 置 true
     *   粘住进程，直接 show 会早退）
     * · 关：hide（语音入口在悬浮球上，随之一并收起；读屏/点击等 Agent 能力不受影响）
     * 服务未连接时静默跳过——下次 onServiceConnected 会按开关决定是否显示。
     */
    fun setOverlayEnabled(on: Boolean) {
        runCatching {
            if (on) {
                StatusOverlay.resetHidden()
                StatusOverlay.show(this)
                tickerBar().show()
                tickerBar().startPolling()
            } else {
                StatusOverlay.hide(this)
                tickerBar().hide()
                tickerBar().stopPolling()
            }
        }.onFailure { Log.w("DshA11y", "overlay toggle failed: ${it.message}") }
    }

    /** 流式悬浮条（懒建，v1.2.99） */
    private var ticker: TickerBar? = null
    private fun tickerBar(): TickerBar = ticker ?: TickerBar(this).also { ticker = it }

    // ==================== 保活看门狗（v1.2.91） ====================
    //
    // 主人实测：「回到后台时通过语音发送消息，消息发出去了但模型不响应，
    // 切回前台才开始响应」+「能不能利用无障碍服务和悬浮窗机制保活」。
    //
    // 分两层解决：
    //  ① WebView 生命周期（见 MainActivity.onResume/onPause）—— 治"后台 JS 被冻结"
    //  ② **本看门狗** —— 治"进程/服务被系统回收"
    //
    // 无障碍服务是系统级长驻组件，用它当哨兵最稳；每 60 秒检查一次前台服务存活，
    // 掉了就拉起来。正常情况开销可忽略（一次 isRunning 判断）。

    private val keepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var keepAlive: Runnable? = null

    private fun startKeepAliveWatchdog() {
        stopKeepAliveWatchdog()
        val r = object : Runnable {
            override fun run() {
                runCatching {
                    // 引擎前台服务不在 → 拉起来（除非用户显式退出过）
                    if (!app.dsh.mobile.service.EngineService.isRunning()) {
                        Log.i("DshA11y", "keepalive: engine service not running, restarting")
                        app.dsh.mobile.service.EngineService.start(this@DshAccessibilityService)
                    }
                }.onFailure { Log.w("DshA11y", "keepalive check failed: ${it.message}") }
                keepAliveHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
            }
        }
        keepAlive = r
        keepAliveHandler.postDelayed(r, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepAliveWatchdog() {
        keepAlive?.let { keepAliveHandler.removeCallbacks(it) }
        keepAlive = null
    }

    /**
     * 语音开关的触感反馈（v1.2.73，主人要求）。
     *
     * 开始聆听 = 短促单震；结束/取消 = 双震（不同节奏让用户"闭着眼也能分辨状态"）。
     * 需要 `VIBRATE` 权限（已补进 manifest）；无震动马达或权限缺失时静默跳过。
     * 用 API 26+ 的 VibrationEffect；更老系统退回已废弃的 vibrate(ms)。
     */
    private fun buzz(pattern: LongArray) {
        runCatching {
            // 用 VIBRATOR_SERVICE 即可（API 31+ 虽标记废弃，但仍返回默认马达）——
            // 本项目编译用的是精简 android stub（Context.VIBRATOR_MANAGER /
            // VibratorManager 不在其中），走废弃路径可避免依赖新常量。
            @Suppress("DEPRECATION")
            val v = getSystemService(android.content.Context.VIBRATOR_SERVICE)
                as? android.os.Vibrator ?: return@runCatching
            if (!v.hasVibrator()) return@runCatching
            val effect = if (pattern.size == 1) {
                android.os.VibrationEffect.createOneShot(
                    pattern[0], android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                )
            } else {
                android.os.VibrationEffect.createWaveform(pattern, -1)
            }
            v.vibrate(effect)
        }.onFailure { Log.w("DshA11y", "vibrate failed: ${it.message}") }
    }

    /** 开始聆听：单短震 */
    private fun buzzStart() = buzz(longArrayOf(40L))

    /** 结束/取消聆听：双震（长-短，与开始区分） */
    private fun buzzStop() = buzz(longArrayOf(0L, 30L, 70L, 30L))

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
        // 三个通道任一可用就放行（优先级见 listenIntoPanel）：
        // 系统识别 → 云端 API（已配 key）
        val usable = AsrManager.isAvailable(this) || CloudAsr.isConfigured(this)
        if (!usable) {
            StatusOverlay.flashNotice(getString(R.string.voice_no_service), 4_000L)
            return
        }
        buzzStart()   // 开始聆听：短震一次（主人要求"打开语音加震动"）
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
                // 重说：先复位 UI 状态，再重新起一轮识别
                //（重复 stop 会造成 stopped/listening 抖动，故只复位不重停）
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
     * ## 为什么分两步（v1.2.65 二次修复）
     * `SpeechService.stop()` 内部是 `interrupt()` + **`join()`** —— 它会**阻塞调用线程**
     * 直到识别线程退出；而识别线程可能正阻塞在 `AudioRecord.read()` 上（缓冲区时长）。
     * 在主线程调用 = 主线程被卡住 + 麦克风迟迟不释放（用户实测「取消后还要等几秒」）。
     *
     * 所以：
     *  ① **立刻**在调用线程做 UI 复位与代次作废（用户即时看到状态变化）
     *  ② 把真正耗时的 `stop/shutdown/close` 丢到后台串行线程执行（不阻塞 UI）
     *
     * ⚠️ 三件事缺一不可：
     *  · AsrManager.stop()      —— 系统识别的释放
     *  · CloudAsr.stop()        —— 云端通道（含录音线程 + AudioRecord）
     *  · StatusOverlay.setListening(false) —— 悬浮窗状态点回灰色
     */
    private fun stopListening() {
        voiceGeneration++          // 作废所有在途回调（加载完成/识别结果）
        stopWatchdog()
        // 只有"确实在聆听中"才震 —— stopListening 会被多条路径重复调用（幂等收口），
        // 不加这个判断会出现连点麦克风时连环震动
        if (recordingForBuzz) {
            recordingForBuzz = false
            buzzStop()             // 结束/取消聆听：双震（与开始区分）
        }
        // ① 立即反馈（调用线程可能就是主线程）
        runCatching { AsrManager.stop() }
        StatusOverlay.setListening(false)
        Log.i("DshA11y", "voice: stop requested (gen=$voiceGeneration)")
        // ② 后台串行释放音频资源（stop() 会 join 识别线程，不能在主线程等）
        voiceStopExecutor.execute {
            runCatching { CloudAsr.stop() }   // 云端通道（含录音线程）
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
     * 开始一次识别，结果写进面板。通道优先级见下方注释。
     *
     * ⚠️ 历史坑（v1.2.65）：旧逻辑在模型后台加载时错误回落到系统识别，
     * 而系统识别在无 Google App 的设备上会立刻报错并清掉 listening 状态，
     * 表现为「点了没反应，再点一下才行」。现在不存在这个分支（本地模型已移除）。
     */
    private fun listenIntoPanel() {
        val panel = voicePanel

        // 通道优先级（v1.2.75，主人决定去掉本地模型）：
        //   ① 系统自带识别（零配置、离线、质量好）—— 有就用它
        //   ② 系统没有 → 云端 API（免费或自建，只要 OpenAI 兼容即可）
        // 不再保留离线 Vosk：small 模型质量太差，大模型（228MB）又不划算，
        // 本项目追求"干更多事"而非塞满体积（主人决策）。
        if (AsrManager.isAvailable(this)) {
            startSystemListening(panel)
            return
        }
        if (CloudAsr.isConfigured(this)) {
            startCloudListening(panel)
            return
        }

        // 两条路都不可用：明确告诉用户去配云端 API（设置页有入口）
        panel?.setHint(getString(R.string.voice_no_service))
        StatusOverlay.setListening(false)
    }

    /**
     * ① 系统自带识别（首选）。
     *
     * 系统 SpeechRecognizer 通常自带离线包且质量好、零配置、不联网 ——
     * 只要设备有识别服务就用它（主人指定「软件优先使用系统自带识别」）。
     */
    private fun startSystemListening(panel: VoicePanel?) {
        StatusOverlay.setListening(true)
        recordingForBuzz = true   // 与 buzzStart() 配对
        startWatchdog()           // 面板消失即释放麦克风
        AsrManager.start(
            ctx = this,
            onPartial = { t -> panel?.setText(t, final = false) },
            onFinal = { t ->
                panel?.setText(t, final = true)
                StatusOverlay.setListening(false)
                stopWatchdog()
            },
            onError = { msg ->
                // 系统识别中途失败（如没装离线包）→ 若配了云端，自动切过去接着听
                Log.w("DshA11y", "system asr error: $msg")
                if (CloudAsr.isConfigured(this)) {
                    // ⚠️ v1.2.92：切通道前**清空面板里系统识别的残留**。
                    // 云端通道是「追加」语义，旧版直接切过去 → 系统识别留下的半句
                    // 会和云端结果拼在一起，主人实测「一次性复制两段发出去」。
                    panel?.setText("", final = false)
                    cloudCommitted = ""      // 新通道从零开始记账
                    panel?.setHint(getString(R.string.voice_switching_cloud))
                    stopWatchdog()
                    startCloudListening(panel)
                } else {
                    panel?.setHint(msg)
                    StatusOverlay.setListening(false)
                    stopWatchdog()
                }
            },
            onEnd = {
                StatusOverlay.setListening(false)
                stopWatchdog()
                if (voicePanel?.currentText().isNullOrEmpty()) {
                    voicePanel?.hide(stopAudio = false)
                }
            },
        )
    }

    /**
     * ② 云端识别（系统识别不可用时）。
     *
     * 准流式：录音期间按停顿切段、每段立刻上传，文字边录边追加到面板
     * （实测硅基流动没有 WebSocket 流式接口，见 CloudAsr 的注释）。
     */
    private fun startCloudListening(panel: VoicePanel?) {
        val gen = ++voiceGeneration
        recordingForBuzz = true
        cloudCommitted = ""     // 本轮已并入面板的文本台账
        StatusOverlay.setListening(true)
        panel?.setHint(getString(R.string.voice_cloud_listening))
        startWatchdog()
        CloudAsr.start(
            ctx = this,
            onSegment = { text ->
                if (gen != voiceGeneration) return@start
                val seg = text.trim()
                if (seg.isEmpty()) return@start
                // ⚠️ v1.2.92 重写合并逻辑（旧版只比较"上一段"→ A→B→A 挡不住，
                // 且无法区分「本段是新增」还是「整句重发」）。
                //
                // 按**已并入台账**判断，两种语义都能正确处理：
                //  · 增量接口（每段只是新句）→ 台账里没有 → 追加
                //  · 累积接口（每段是到目前为止的全文）→ 本段以台账开头 → 整段替换
                //  · 完全重复的一段（重试/边界）→ 台账里已有 → 丢弃
                val committed = cloudCommitted
                val merged = when {
                    committed.isEmpty() -> seg
                    seg == committed -> {
                        Log.i("DshA11y", "cloud segment identical, skipped: '$seg'")
                        return@start
                    }
                    seg.startsWith(committed) -> {
                        // 累积语义：本段已包含之前全部内容 → 直接替换，避免"你好 你好世界"
                        Log.i("DshA11y", "cloud segment cumulative, replacing")
                        seg
                    }
                    committed.contains(seg) -> {
                        Log.i("DshA11y", "cloud segment already merged, skipped: '$seg'")
                        return@start
                    }
                    else -> "$committed $seg"
                }
                cloudCommitted = merged
                panel?.setText(merged, final = false)
            },
            onStatus = { msg ->
                if (gen == voiceGeneration) panel?.setHint(msg)
            },
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 记录「有人碰了屏幕」。无法区分是 AI 的注入手势还是真手指
                // （注入的手势同样产生触摸事件），故只记录时间戳与计数，
                // 由调用方结合「AI 自己刚派发了几次手势」来扣除自身动作。
                lastTouchAt = System.currentTimeMillis()
                touchCount++
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                lastTouchEndAt = System.currentTimeMillis()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // ⚠️ v1.2.56：window change **不能**作为「用户接管」的判据 ——
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
        ticker?.stopPolling()
        if (instance === this) instance = null
        // 释放麦克风：服务被销毁（无障碍被关闭/系统回收）时若还在识别，
        // AudioRecord 会随进程残留，系统一直显示麦克风占用
        stopListening()
        stopKeepAliveWatchdog()
        runCatching { voicePanel?.hide() }
        voicePanel = null
        runCatching { StatusOverlay.hide(this) }
        super.onDestroy()
    }

    // ==================== 外部干预感知（v1.2.54） ====================
    //
    // 用户反馈：「有的时候人操作突然接管，AI 也不知道」。
    // 真实场景：AI 正在跑多步流程，用户拿过手机自己点了两下 —— AI 继续按原计划
    // 操作，结果点在完全不同的界面上，或者把用户的输入覆盖掉。
    //
    // 机制：记录触摸/窗口变化的时间戳。调用方（AI）在关键步骤前后比对，
    // 若在自己「没做动作」的时间窗内出现了触摸或窗口切换，即判定为外部干预。

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
     * 属误判。**「人是否接管」的唯一可靠信号是触摸**，窗口变化只反映界面在动。
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
        // 只看触摸：窗口变化是应用自身行为，不能作为「人接管」的证据
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
     * 紧凑读屏（v1.2.55）：**治「慢」的真正关键**。
     *
     * 为什么需要：完整 JSON 每节点 11 个字段（含 cls/rid/w/h/四个布尔），
     * 200 节点实测约 44KB ≈ **1.5 万 tokens**。每一轮对话都要把这坨数据重新
     * 过一遍注意力 —— 用户实测「平均五六秒才动一次」，瓶颈就在这（不是动作慢，
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
                    // prompt 变小 → prefill 线性变快。保留长度提示，模型仍知道「这是长文本」。
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
     * 原始节点树转储（保留父子层级，供调用方判断「哪个容器可滚动」等结构信息）。
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
     * 点击一个节点的「可点位置」：优先节点**自身中心**（用户手指就是这么点的，
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

    /**
     * 匹配结果：命中节点 + 同分候选（v1.2.82）。
     *
     * 主人实测反馈（无障碍提案 P1-4）：`tap-text "7"` 在计算器上同时匹配显示区与键盘，
     * 旧实现**静默取第一个**（"这次恰好对，纯属运气"）。现在把候选一并带出来，
     * 由调用方决定是报错、还是按 index 选。
     */
    data class Match(
        val node: AccessibilityNodeInfo,
        val score: Int,
        /** 该节点的可读标签（text 或 desc） */
        val label: String,
        /** 是否为 desc 命中（计算器运算符是 contentDescription，如 d:加） */
        val byDesc: Boolean,
        val clickable: Boolean,
        /** 同分候选数量（>1 表示有歧义） */
        val ties: Int,
    )

    /**
     * 收集所有匹配节点（按分数降序），供候选提示与歧义检测使用。
     * @param limit 最多收集多少个候选
     */
    fun findMatches(query: String, byDesc: Boolean = false, limit: Int = 8): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val hits = ArrayList<Match>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val ts = if (byDesc) 0 else matchScore(t, q)
            val ds = matchScore(d, q)
            val score = maxOf(ts, ds)
            if (score > 0) {
                hits += Match(
                    node = node,
                    score = score,
                    label = (if (t.isNotEmpty()) t else d).replace('\n', ' '),
                    byDesc = ds > ts,
                    clickable = node.isClickable || hasClickableAncestor(node),
                    ties = 0,
                )
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        if (hits.isEmpty()) return emptyList()
        // 排序：分数降序 → 可点击优先
        val sorted = hits.sortedWith(
            compareByDescending<Match> { it.score }.thenByDescending { it.clickable },
        )
        // 标记同分候选数（同分且都命中同一批 = 有歧义）
        val topScore = sorted.first().score
        val ties = sorted.count { it.score == topScore }
        return sorted.take(limit).map { it.copy(ties = ties) }
    }

    /**
     * 编辑距离（Levenshtein）—— 用于"没找到时给出最接近的候选"。
     *
     * 提案原文：报错只给 `no matching node`，AI 还得再花一整轮 dump 去找；
     * 而真相常常是"目标在 contentDescription 里"（计算器的 `d:加`、`d:等于`）。
     * 有了距离排序，报错信息里直接列出最像的几个节点，省掉一轮往返。
     */
    private fun editDistance(a: String, b: String): Int {
        val s1 = a.lowercase(); val s2 = b.lowercase()
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        var prev = IntArray(s2.length + 1) { it }
        var cur = IntArray(s2.length + 1)
        for (i in 1..s1.length) {
            cur[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[s2.length]
    }

    /**
     * 没找到目标时的「近似候选」（提案 P0-2）。
     *
     * ⚠️ 排序不能只看编辑距离：单字查询下「加」与「7」距离都是 1，纯距离排序会全是噪音
     * （实测）。改用**复合评分**：
     *  ① 子串/包含关系优先（`加` ⊂ `加法` 比 `加` vs `7` 有意义得多）
     *  ② 再看编辑距离（对较长查询有效）
     *  ③ 长度差作为惩罚（避免用超长文本淹没短查询）
     * 过滤掉明显不相关的（距离 > 查询长度的一半 + 1）。
     */
    fun nearestCandidates(query: String, limit: Int = 5): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        // (复合分, 距离, 标签, 是否desc)  —— 分越小越靠前
        val cands = ArrayList<Array<Any>>()
        val maxDist = (q.length / 2) + 1

        fun consider(label: String, isDesc: Boolean) {
            if (label.isBlank()) return
            val low = label.lowercase()
            val ql = q.lowercase()
            val dist = editDistance(low, ql)
            // 包含关系 → 视为高相关（距离按 0.5 计，排在纯距离相同者前面）
            val contains = low.contains(ql) || ql.contains(low)
            if (!contains && dist > maxDist) return
            val score = (if (contains) 0.5 else dist.toDouble()) +
                kotlin.math.abs(label.length - q.length) * 0.1   // 长度差惩罚
            cands += arrayOf(score, dist, label, isDesc)
        }

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.trim()?.let { consider(it, false) }
            node.contentDescription?.toString()?.trim()?.let { consider(it, true) }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return cands.sortedBy { it[0] as Double }.take(limit).map { row ->
            val label = (row[2] as String).replace('\n', ' ').take(24)
            val isDesc = row[3] as Boolean
            val d = row[1] as Int
            // 标签用英文（这是给 AI 读的诊断信息，走 i18n 资源没必要）
            (if (isDesc) "d:$label" else label) + "(dist=$d)"
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
        /** 保活检查间隔（v1.2.91）：60 秒一次，正常情况开销可忽略 */
        private const val KEEPALIVE_INTERVAL_MS = 60_000L

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

        /** 滚动后等待动画结束的时间（节点树读到中间态会导致误判「没找到」） */
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
        // 标记「这是我自己发的」——注入手势也会产生 TYPE_TOUCH_INTERACTION_START，
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
     * 而调用方（引擎侧的 scr / batch 里的 swipe 步）可能按「元素位置 ± 偏移」算出负值。
     * 这里钳制后至少「贴着边缘滑动」仍能滚动；越界时打日志便于定位是哪个调用方。
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
    // 搜索、登录、发消息、填表全部卡死在这一步（用户反馈「无障碍基本用不了」
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
    // 此前只能靠 wait <文本> 猜或硬 sleep。稳定检测让「点完等它安静下来」变成一次调用。

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
     * ⚡ v1.2.58 提速（用户反馈「有时候还是偏慢」）：
     *  · **快路径**：若**首次**采样就与上一次已知签名相同（界面本来就没动），
     *    立即返回 —— 不必再等满 [stableReads] 轮。多数「点完就静止」的场景
     *    （开关、选中、页面已加载完）因此从 ~360ms 降到 ~50ms。
     *  · **采样间隔下调**：节点树读取本身耗时 20–50ms，原 120ms 间隔是纯浪费；
     *    降到 60ms 仍能可靠捕捉动画（Android 动画帧间隔 ~16ms，一次采样可跨多帧）。
     *  · **签名计算复用**：把上次签名作为起点传入，避免第一次比较必然「变化」。
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

    /** 当前前台应用包名（供调用方确认「是否还在目标界面」） */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** 屏幕尺寸（供坐标换算/比例点击） */
    fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    /**
     * 点击一个节点矩形（供桥接层在「滚动找到」后点击）。
     * 抖动半径按矩形尺寸自适应，小控件不会被抖出边界。
     */
    fun dispatchTapRect(rect: Rect): Boolean {
        val jitter = minOf(DEFAULT_TAP_JITTER, minOf(rect.width(), rect.height()) / 4)
            .coerceAtLeast(0)
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY(), jitter)
    }

    // ==================== 批量动作执行（v1.2.54） ====================
    //
    // 用户反馈的核心问题：「太慢」。根因不是单次动作慢（桥接往返只有几十毫秒），
    // 而是**每个动作都要 AI 往返一次**——dump → LLM 思考（秒级）→ 点 → dump → …
    // 一个 10 步流程就是 10 次 LLM 调用。
    //
    // 解法：一次调用执行一串动作，只在最后回一次结果。
    // AI 只思考一次（「打开设置 → 点 WLAN → 等它稳定」），剩下的在服务端跑完。

    /** 批量动作的一步执行结果 */
    data class StepResult(
        val index: Int,
        val action: String,
        val ok: Boolean,
        val detail: String,
        /** 重试次数（v1.2.82；0 = 未重试） */
        val retries: Int = 0,
        /** 断言结果：null = 该步没配 assert；true/false = 断言通过/失败（v1.2.82） */
        val asserted: Boolean? = null,
    )

    /**
     * 执行一串动作，每步之间自动等待界面稳定。
     *
     * 支持的步骤类型（step 是 Map，字段与桥接层 JSON 一致）：
     *   {"type":"tap","text":"WLAN"} / {"type":"tap","x":1,"y":2}
     *   {"type":"input","text":"hello"}
     *   {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"durationMs":300}
     *   {"type":"key","action":"back"}
     *   {"type":"wait","text":「已连接」,"timeoutMs":5000}
     *   {"type":"idle","timeoutMs":2000}
     *
     * 每步默认等界面稳定（`settleMs`），避免「点了立刻读旧界面」导致的连锁失败。
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
        /**
         * 每步**执行前**检测真人触摸，发现即中止（v1.2.82，无障碍提案 P2-5）。
         * 提案原文：「interference 只能事后发现用户接管；批次应在下一步之前检测并中止，
         * 而不是跑完再报」。默认关闭，避免影响现有调用方。
         */
        abortOnInterference: Boolean = false,
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        var interferenceMark = System.currentTimeMillis()
        steps.forEachIndexed { i, step ->
            val type = (step["type"] as? String).orEmpty()
            val optional = step["optional"] == true
            var ok = false
            var detail = ""
            var retries = 0
            var asserted: Boolean? = null

            // P2-5：执行**之前**就检查用户是否接管了屏幕（而不是跑完才报）
            if (abortOnInterference && i > 0) {
                val snap = interferenceSnapshot()
                if (interferedSince(interferenceMark, snap.lastTouchAt)) {
                    results.add(StepResult(i, type, false, "aborted: user touched the screen before this step"))
                    return results
                }
            }
            interferenceMark = System.currentTimeMillis()

            // ── 重试与断言（v1.2.82，无障碍提案 P0-1）────────────────────────
            // 提案原文：批次失败**不回滚** —— step 0 已产生副作用、step 1 失败中止后，
            // 若盲目重跑会**执行两次**（转账/提交订单场景直接出事）。
            //
            // 因此：
            //  · **非幂等步骤默认禁止重试**（tap/input/key/long_press 都可能产生副作用）；
            //    要重试必须显式 `"idempotent": true` 声明它安全。
            //  · `retry: {"times":N, "backoffMs":M}` 才启用重试；且**断言失败也重试**前
            //    会先检查幂等性，避免"以为没生效其实已生效"导致重复提交。
            //  · `assert: {"text":"已提交","gone":false,"timeoutMs":3000}` ——
            //    跑完必须出现（或消失）指定文本才算成功，而不是"手势已派发"。
            val idempotent = step["idempotent"] == true
            val retryCfg = step["retry"] as? Map<*, *>
            val maxRetries = if (retryCfg != null && idempotent) {
                ((retryCfg["times"] as? Number)?.toInt() ?: 0).coerceIn(0, 5)
            } else 0
            val backoffMs = ((retryCfg?.get("backoffMs") as? Number)?.toLong() ?: 400L)
                .coerceIn(0L, 5_000L)
            val assertCfg = step["assert"] as? Map<*, *>

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
                    // 宽进严出：key/action 任给其一即可，避免这类「文档没写清」的翻车。
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

            // ── 断言校验（v1.2.82）：跑完必须满足条件才算成功 ────────────────
            // 提案原文：「加 assert：跑完必须出现指定文本才算成功，而非'手势已派发'」。
            // 手势派发成功 ≠ 界面真的响应了（这正是 ok:true 最误导人的地方）。
            if (assertCfg != null) {
                val aText = (assertCfg["text"] ?: assertCfg["query"]) as? String
                if (!aText.isNullOrBlank()) {
                    val aGone = assertCfg["gone"] == true
                    val aTimeout = ((assertCfg["timeoutMs"] as? Number)?.toLong() ?: 3_000L)
                        .coerceIn(200L, 15_000L)
                    val passed = waitForText(aText, aGone, aTimeout)
                    asserted = passed
                    if (!passed && ok) {
                        ok = false
                        detail = "assert failed: text \"$aText\" ${if (aGone) "still present" else "not found"} within ${aTimeout}ms"
                    } else if (passed && ok) {
                        detail = "asserted: \"$aText\" ${if (aGone) "gone" else "present"}"
                    }
                }
            }

            // ── 重试（v1.2.82）：只对**幂等**步骤生效 ──────────────────────
            // ⚠️ 这是 P0-1 的核心防线：非幂等步骤（tap 可能已提交订单、input 可能已发送）
            // 若因"断言失败"就重跑，会**执行两次**。所以默认不重试，
            // 必须显式 `"idempotent": true` 声明安全后才允许。
            var attempt = 0
            while (!ok && attempt < maxRetries) {
                attempt++
                retries = attempt
                Thread.sleep(backoffMs)
                // 重试前重新执行同一步（仅限幂等步骤）
                ok = runCatching { executeStep(step, type, settleMs) }
                    .getOrElse { detail = it.message.orEmpty(); false }
                if (ok && assertCfg != null) {
                    val aText = (assertCfg["text"] ?: assertCfg["query"]) as? String
                    if (!aText.isNullOrBlank()) {
                        val aGone = assertCfg["gone"] == true
                        val aTimeout = ((assertCfg["timeoutMs"] as? Number)?.toLong() ?: 3_000L)
                            .coerceIn(200L, 15_000L)
                        val passed = waitForText(aText, aGone, aTimeout)
                        asserted = passed
                        if (!passed) { ok = false; detail = "assert failed after retry #$attempt" }
                    }
                }
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
            // 非幂等 + 配了重试意图 → 明确告知为何没重试（避免 AI 误以为已重试过）
            if (!ok && retryCfg != null && !idempotent) {
                detail += " | retry skipped: step is not marked \"idempotent\" (would risk running twice)"
            }

            results.add(StepResult(i, type, ok, detail, retries, asserted))

            if (!ok && !optional) {
                if (stopOnError) return results
            }
            // ⚡ 稳定等待只对「会改变界面」的步骤做（v1.2.58）：
            //  · wait/idle/sleep 本身就是同步语义，自己已经等过了
            //  · key（返回/主页）与 scroll_find 已经内置了等待或自带目标判定
            //  · 非首步的 input 往往紧跟在同一界面，无需再等
            // 只有 tap / long_press / swipe / input 需要「等它安静下来」。
            // 这一步省掉后，10 步 batch 的固定开销约减半（~3.6s → ~1.8s）。
            val needsSettle = ok && type in setOf("tap", "long_press", "swipe", "input")
            if (needsSettle) {
                // 稳定判定放宽到 2 轮 × 60ms（配合快路径，静止场景约 50ms 返回）
                waitForIdle(settleMs, quietMs = 60L, stableReads = 2)
            }
            // 每步截图回调（放在稳定等待之后 = 截到「这一步做完后的界面」）
            runCatching { onAfterStep?.invoke(i) }
        }
        return results
    }

    /**
     * 执行单个批量步骤（v1.2.82 抽出，供重试复用）。
     * 逻辑与 runBatch 内的 when(type) 一致；重试时直接再调它。
     */
    private fun executeStep(step: Map<String, Any?>, type: String, settleMs: Long): Boolean {
        return when (type) {
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
            "key" -> performGlobalActionByName(
                ((step["action"] ?: step["key"]) as? String).orEmpty(),
            )
            "scroll_find" -> {
                val t = ((step["text"] ?: step["query"]) as? String) ?: ""
                val forward = step["back"] != true
                val maxSwipes = ((step["maxSwipes"] ?: step["max"]) as? Number)?.toInt() ?: 8
                val hit = scrollToFind(t, maxSwipes, forward)
                if (hit != null) {
                    if (step["tap"] == true) dispatchTapRect(hit) else true
                } else false
            }
            "wait" -> {
                val t = ((step["text"] ?: step["query"]) as? String) ?: ""
                if (t.isBlank()) false else waitForText(t, step["gone"] == true,
                    (step["timeoutMs"] as? Number)?.toLong() ?: 5_000L)
            }
            "idle" -> waitForIdle((step["timeoutMs"] as? Number)?.toLong() ?: settleMs)
            "sleep" -> {
                Thread.sleep(((step["ms"] as? Number)?.toLong() ?: 500L).coerceIn(0L, 10_000L))
                true
            }
            else -> false
        }
    }

    /**
     * 滚动查找并让目标**居中**（v1.2.82，无障碍提案 P2-6）。
     *
     * 提案原文：「现有 find --tap 是'滚到就点'，点完该项可能在屏幕边缘，
     * 长按/拖拽会打偏。缺'滚到居中'」。
     *
     * 做法：先 scrollToFind 找到目标；若命中点离屏幕上下边缘过近（< 20% 高度），
     * 补一次小幅滑动把它带到中间区域，再返回最终矩形。
     */
    fun scrollToCenter(query: String, maxSwipes: Int = 8, forward: Boolean = true): Rect? {
        val hit = scrollToFind(query, maxSwipes, forward) ?: return null
        val screenH = resources.displayMetrics.heightPixels
        val cy = hit.centerY()
        val top = screenH * 0.2f
        val bottom = screenH * 0.8f
        if (cy in top.toInt()..bottom.toInt()) return hit   // 已在中间区域，不必再动

        // 目标贴近边缘 → 反向小幅滑动把它带向中间
        val needDown = cy < top                       // 太靠上 → 内容下移
        val step = (screenH * 0.25f).toInt()
        val x = hit.centerX()
        val from = if (needDown) screenH * 0.35f else screenH * 0.65f
        val to = if (needDown) from + step else from - step
        dispatchSwipe(x.toFloat(), from, x.toFloat(), to, 260L)
        Thread.sleep(SCROLL_SETTLE_MS)
        // 重新定位（滑动后节点位置变了）
        return scrollToFind(query, 3, forward) ?: hit
    }

    /**
     * 界面差分（v1.2.82，无障碍提案 P1-3）。
     *
     * 提案原文：「判断'界面变没变'目前只能整屏重 dump 再肉眼比 —— 这正是
     * `ok:true ≠ 界面有反应` 难验证的根因。只回变化节点，等待列表加载从全量 dump
     * 降到几十字节」。
     *
     * 实现：保存上次的「标签@坐标」集合，本次对比后只回新增/消失的节点。
     */
    private var lastDiffSnapshot: Set<String>? = null

    /** 当前屏的节点签名集合（供 diff 用） */
    private fun snapshot(): Set<String> {
        val out = HashSet<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || out.size >= MAX_NODES) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty()) {
                out += (if (t.isNotEmpty()) t else "d:$d").replace('\n', ' ').take(60) +
                    "@${r.centerX()},${r.centerY()}"
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    /**
     * 与上次快照对比，只返回变化。
     * @param reset true = 重新建立基线（首次调用）
     */
    fun diffScreen(reset: Boolean = false): Pair<Set<String>, Set<String>> {
        val now = snapshot()
        if (reset || lastDiffSnapshot == null) {
            lastDiffSnapshot = now
            return emptySet<String>() to emptySet()
        }
        val prev = lastDiffSnapshot!!
        lastDiffSnapshot = now
        val added = now - prev
        val removed = prev - now
        return added to removed
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
