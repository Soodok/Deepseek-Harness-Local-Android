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
            // v1.2.182（用户要求）：**原「试验性功能」开关已移除**，悬浮窗常驻——
            // 开关取消后不再有"默认不显示"的语义，服务连上即展示（用户随时看到 AI 在做什么）。
            StatusOverlay.show(this)
            // ⚠️ v1.2.106：**流式悬浮条暂时下线**（主人 2026-10-09 决定）。
            // 真机上仍报「session 解压失败」（诊断已能显示具体文件与原因，但根因
            // 未定：模拟器上用严格真实格式的多帧会话可正常渲染，真机不行，差异
            // 尚未定位）。功能不成熟就先摘掉，免得用户看到空白浮条或误导性诊断；
            // 代码整体保留，排查清楚后解开下面两行注释即可恢复。
            // tickerBar().show()
            // tickerBar().startPolling()
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

        // 本地快速决策层自动就绪（v1.2.123，真机反馈修复）
        // 此前 /local/ensure 只有外部显式调用才会触发 —— UI、云端提示词、
        // gates 脚本里**都没有调用点**，于是本地嵌入模型永远不会下载、
        // llama-server 永不启动，FastPicker 语义层恒空转（真机实测
        // 「基本没用到本地判决 AI」）。服务连接 = 用户在用了，此时后台
        // 静默就绪：失败不打扰（扩展未装时返回 false 只记日志）。
        startLocalModelAutoReady()
    }

    /**
     * 后台预热。
     *
     * v1.2.182（用户要求「把无障碍功能 max+ 删了」）：原先受 max+ 开关控制的
     * **本地模型自动下载/启动**一并移除 —— 那段行为需要用户知情同意（读 API key +
     * 下载 15MB 模型），开关取消后不能再静默执行。引擎若需要，仍可显式调用
     * `POST /local/ensure`（AgentBridge 提供，属主动请求）。
     *
     * OCR 预热与开关无关（模型随 APK 分发、不联网、不读凭证），始终执行。
     */
    private fun startLocalModelAutoReady() {
        Thread({
            // OCR 预热：ML Kit 模型加载（首次 ~1-2s）提前到后台，
            // 用户首次真实使用不再付 5s 冷启动（真机实测 5.1s → 热态 ~0.5s）
            runCatching {
                Thread.sleep(2000)
                ScreenOcr.warmUp(this)
                Log.i("DshA11y", "ocr warmed up")
            }
        }, "ocr-warmup").apply { isDaemon = true }.start()
    }

    // ==================== 语音输入编排（v1.2.65） ====================
    //
    // 由无障碍服务托管，不依赖 MainActivity 存活：
    //  点悬浮窗麦克风 → 检查权限/离线模型 → 弹底部输入面板 → 实时识别 → 编辑后发送。
    // 发送经 VoiceBridge 转给 MainActivity 的 WebView 注入（主界面不在时给明确提示）。

    @Volatile private var voicePanel: VoicePanel? = null

    /** 事件线程：追加一条变化（计数器 O(1)，不用 deque.size() 的 O(n) 遍历） */
    private fun addChange(entry: String) {
        changeLog.addFirst(entry)
        if (changeCount.incrementAndGet() > CHANGE_KEEP) {
            changeLog.pollLast()
            changeCount.decrementAndGet()
        }
    }

    /** 事件线程：文本去重 + 记录；changeSeen 设上限防长期运行 OOM */
    private fun recordTextChanges(texts: List<String>, now: Long) {
        texts.forEach { txt ->
            val prev = changeSeen[txt]
            if (prev == null || now - prev > CHANGE_DEDUP_MS) {
                changeSeen[txt] = now
                addChange("+ $txt")
            }
        }
        if (changeSeen.size > 1000) changeSeen.clear()
    }

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
                // 流式悬浮条已下线，见 onServiceConnected 里的说明（v1.2.106）
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
                    // 引擎前台服务不在 → 拉起来，**但必须尊重用户的显式退出**。
                    //
                    // ⚠️ v1.4.0 修复（用户反馈「通知栏退出后 root 模式持续空转」）：
                    // 下面这句注释「除非用户显式退出过」在 v1.2.91 就写了，但代码里
                    // **从来没有对应的判断**。后果是一条完整的复活循环：
                    //
                    //   用户在通知栏点「退出」
                    //     → exitCompletely() 停引擎、stopSelf()、结束 Activity
                    //     → 但无障碍服务是系统级组件，进程还活着，看门狗继续跑
                    //     → 60 秒后发现 EngineService 不在，**重新把它拉起来**
                    //     → 引擎重启 → 再退出 → 再拉起 …… 无限循环
                    //
                    // root 模式下每次复活都要经 su 拉进程，空转代价更高（用户实测
                    // 持续吃 CPU）。现在先查 supervisor.isUserStopped()：
                    // 用户显式退出后不再自动拉起，直到用户下次主动打开 App
                    //（EngineService.start → supervisor.start 会把 userStop 清零）。
                    val sup = (application as DshApp).supervisor
                    val exitedByUser = sup.isUserStopped() ||
                        app.dsh.mobile.DshApp.userExitedExplicitly(this@DshAccessibilityService)
                    if (exitedByUser) {
                        Log.i("DshA11y", "keepalive: user exited explicitly, not restarting")
                    } else if (!app.dsh.mobile.service.EngineService.isRunning()) {
                        Log.i("DshA11y", "keepalive: engine service not running, restarting")
                        app.dsh.mobile.service.EngineService.start(this@DshAccessibilityService)
                    }
                    Unit
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
        // ⚠️ 必须把「重新排进去的那个 Runnable」写回 [watchdog] 字段。
        // 旧实现只在首次 post 前存一次字段，run() 里却用 postDelayed(this) 重排 ——
        // 队列里挂着的已不再是字段持有的对象，stopWatchdog() 的 removeCallbacks 对它无效，
        // 于是每次用完语音都会遗留一条每 1.5s 无条件执行 stopListening() 的永久轮询
        // （CloudAsr.stop() 是 join 识别线程的重操作，多开几次语音就累积多条）。
        // 对照 keepAlive（上面那段）：它重排的就是字段持有的同一个对象，所以没有这个问题。
        val r: Runnable = object : Runnable {
            override fun run() {
                val panelAlive = voicePanel?.isVisible == true
                if (!panelAlive) {
                    Log.i("DshA11y", "watchdog: panel gone → releasing mic")
                    stopListening()
                    return
                }
                watchdog = this
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
                // ⚠️ v1.2.182（用户反馈「语音输入完会闪一下」）：这里**不再**
                // 立即 setListening(false)。
                // 根因：setListening(false) → setIdle() → 若悬浮窗是"因干活自动展开"
                // 的状态，会立刻 expandLevel=0 收起 —— 视觉上就是识别出文字的那一瞬
                // 悬浮球/面板猛地缩一下（用户说的"闪一下"）。
                // 现在保持聆听态直到面板隐藏（发送/关闭时 onHide → stopListening），
                // 状态切换与面板退出动画同步发生，不再有突兀的收起。
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
                stopWatchdog()
                // 空文本（误触/没听清）→ 收起面板并回空闲；
                // 有文本 → 保持聆听态让用户编辑/发送，避免"闪一下"（见 onFinal 注释）
                if (voicePanel?.currentText().isNullOrEmpty()) {
                    voicePanel?.hide(stopAudio = false)
                    StatusOverlay.setListening(false)
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
                    stopWatchdog()
                    // 同系统通道（v1.2.182）：有文本就保持聆听态，等用户发送/关闭；
                    // 空文本才收起并回空闲 —— 避免识别结束时悬浮窗突然收起（"闪一下"）。
                    if (voicePanel?.currentText().isNullOrEmpty()) {
                        voicePanel?.hide(stopAudio = false)
                        StatusOverlay.setListening(false)
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
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // v1.2.141：缓存通知内容供桥的 /notif 读取（任务完成通知、
                // 验证"是否弹了通知"等场景；无需额外的 NotificationListenerService）
                val pkg = e.packageName?.toString().orEmpty()
                val texts = e.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }
                if (!texts.isNullOrEmpty()) {
                    val line = "$pkg: ${texts.joinToString(" | ")}"
                    synchronized(notifLock) {
                        notifLog.addFirst(line)
                        while (notifLog.size > 20) notifLog.removeLast()
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // ⚠️ v1.2.114：主线程只提取文本（见字段区说明），其余投事件线程。
                val types = e.contentChangeTypes
                if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) {
                    val texts = e.text?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotEmpty() }
                    if (!texts.isNullOrEmpty()) {
                        changeHandler.post {
                            recordTextChanges(texts, System.currentTimeMillis())
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // ⚠️ v1.2.56：window change **不能**作为「用户接管」的判据 ——
                // 应用自己启动、弹出联想下拉、切页都会触发它（用户实测：
                // 启动 Edge、弹下拉时 lastWindowChangeAt 变了，但 touchCount=0、
                // lastTouchAt=0）。这里只更新前台包名缓存供 pkg 查询，
                // 干预判定一律以 touchCount 为准（见 interferedSince）。
                lastWindowChangeAt = System.currentTimeMillis()
                foregroundPkgCache = e.packageName?.toString() ?: foregroundPkgCache
                // 窗口切换进变化摘要（主线程只取包名，记录在事件线程）
                val pkg = foregroundPkgCache
                changeHandler.post { addChange("window: $pkg") }
            }
        }
    }

    /**
     * 旋转 / 分屏时转发给悬浮条（v1.2.106）。
     *
     * 悬浮条过去把「离屏幕边缘的内缩」算在 WindowManager 参数里，而那些值只在
     * 创建时算一次 —— 转横屏后宽度仍是竖屏的，卡片不居中、右边空一块
     * （主人实测）。现在宽度交给 MATCH_PARENT 自适应，但顶部偏移需跟着状态栏
     * 高度重算，所以必须有这个回调把它叫醒。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { ticker?.onConfigChanged() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        ticker?.stopPolling()
        if (instance === this) instance = null
        // 释放麦克风：服务被销毁（无障碍被关闭/系统回收）时若还在识别，
        // AudioRecord 会随进程残留，系统一直显示麦克风占用
        stopListening()
        stopKeepAliveWatchdog()
        runCatching { voicePanel?.dispose() }   // v1.2.188：同步移除，不等 180ms 动画
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
    // ---- 事件驱动变化感知（v1.2.113）----
    // 用无障碍事件流实时维护「屏幕最近发生了什么」摘要，AI 调 `scr changed`
    // 毫秒级拿到（窗口切换 / 文本出现），不必每次整树 dump —— 豆包式低延迟的
    // 核心：把「每步确认」从 dump 的秒级降到事件推送级。
    private val changeLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val changeSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val CHANGE_KEEP = 30            // 最多保留的事件条数
    private val CHANGE_DEDUP_MS = 800L      // 同一文本去重窗口（CONTENT_CHANGED 高频）
    // ⚠️ v1.2.114：事件处理线程 —— onAccessibilityEvent 跑在主线程，而 CONTENT_CHANGED
    // 在真机上每秒上百（WebView 活动页尤甚）；此前在主线程做去重 map + 记录，
    // 且 ConcurrentLinkedDeque.size() 是 O(n) 遍历 —— 主线程被事件流拖死 → App
    // 直接卡退（主人真机复现「屡次停止运行」，模拟器事件稀疏不复现）。
    // 现在主线程只提取文本（事件数据回调返回后失效，必须就地取），
    // 去重/记录全部在本线程做；计数器 O(1) 替代 size() 遍历。
    private val changeThread = android.os.HandlerThread("dsh-a11y-events").apply { start() }
    private val changeHandler = android.os.Handler(changeThread.looper)
    private val changeCount = java.util.concurrent.atomic.AtomicInteger()
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
     * `expectSelfTouch()` 登记一个带时间戳的 credit，判定时从总触摸数里扣除（见 selfTouchCredits）。
     *
     * @param since 起始时间戳（毫秒）；该时刻之前的触摸不计
     * @param ignoreUntil 兼容保留（时间窗下界），当前实现以计数扣除为主
     */
    /**
     * 事件驱动变化摘要（v1.2.113）：返回「自上次调用以来屏幕发生了什么」并清空。
     * 只记「文本出现/更新 + 窗口切换」，毫秒级累积，AI 问变化时零 dump 成本。
     * 返回即消费（清空），避免重复汇报。
     * @return 多行摘要；无变化返回空串
     */
    fun changedSummary(): String {
        if (changeLog.isEmpty()) return ""
        val items = ArrayList<String>()
        while (true) {
            val x = changeLog.pollLast() ?: break
            if (!items.contains(x)) items.add(x)
        }
        return items.reversed().joinToString("\n")
    }

    fun interferedSince(since: Long, ignoreUntil: Long): Boolean {
        // 只看触摸：窗口变化是应用自身行为，不能作为「人接管」的证据
        if (lastTouchAt <= since) return false
        // 扣除 AI 自身手势：只核销**本次观察窗口内**（since 之后）登记的那些。
        // ⚠️ 旧实现只要 credit>0 就无条件核销并返回 false，问题是 credit 是全局的、
        // 可能来自窗口开始之前（例如上一轮的手势、或 AI 连发两次 tap 但事件只到了一条），
        // 于是它会「偷走」用户的真实触摸 → 真干预被误判成自带动作（假阴性）；
        // 反过来若 AI 的 credit 晚于用户触摸登记，又会把用户触摸算成自己的。
        // 现在每个 credit 带登记时间戳，只有落在 (since, now] 内的才算本窗口的自身手势。
        val now = System.currentTimeMillis()
        var consumed = false
        synchronized(selfTouchCredits) {
            val it = selfTouchCredits.iterator()
            while (it.hasNext()) {
                val at = it.next()
                if (at <= since) {
                    // 窗口开始前遗留的 credit 已过期：直接丢弃，不参与本次判断
                    it.remove()
                } else if (!consumed && at <= now) {
                    it.remove()
                    consumed = true
                }
            }
        }
        if (consumed) return false
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
     *
     * 每个 credit 记录登记时刻：判定时只核销观察窗口内（since 之后）登记的那些，
     * 窗口前的残留 credit 直接丢弃（见 interferedSince 的说明）。
     */
    private val selfTouchCredits = java.util.ArrayList<Long>()
    fun expectSelfTouch() {
        val now = System.currentTimeMillis()
        synchronized(selfTouchCredits) {
            // 防御性上限：正常流程下这里只有个位数；异常泄漏（派发失败没核销）时
            // 不能让列表无限增长，保留最近 64 条足够覆盖任何并发窗口。
            if (selfTouchCredits.size >= 64) selfTouchCredits.removeAt(0)
            selfTouchCredits.add(now)
        }
    }

    /** 当前待核销的自身触摸数（诊断用） */
    fun pendingSelfTouches(): Int = synchronized(selfTouchCredits) { selfTouchCredits.size }

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
    /** OCR 跳过顶部比例（v1.2.156）：实测状态栏(3%)与浏览器地址栏(9%)都在 12% 内，
     *  且这两条在 dump 里只产出 `o0 5:06 3G` 这类噪声行——跳过它们既减像素提速，
     *  又少噪声；再往下是真内容区，不裁（canvas/游戏的内容可能就贴在顶部）。 */
    private val OCR_TOP_SKIP = 0.12f

    fun dumpScreenCompact(filter: String = "all", ocrFallback: Boolean = true): String {
        // v1.2.152 审查修复：本函数经 withScreenTimeout 在 2s 上限内运行，而图标
        // 识别要**额外截一帧**（1.5s 预算）——叠加后会把 /screen 顶成 504，调用方
        // 误判"无障碍卡死"。改为按已耗时动态让路：剩余时间不够就跳过图标识别
        // （图标是加分项，读屏本身是必需项）。
        val tDumpStart = System.nanoTime() / 1_000_000
        val sb = StringBuilder()
        var count = 0
        // v1.2.123 实测修正：用**内容区**（屏幕 25% 高度以下）的文本量判定，
        // 而非全屏——Chrome/浏览器类界面的顶部 UI 壳（地址栏/标签按钮）
        // 常有上百字符，但页面内容区（WebView/canvas）一个文本节点都没有。
        // 全屏判定会漏掉真正需要 OCR 的场景（实测：canvas 页 97 字符不触发）。
        var contentChars = 0
        val contentTop = rootHeight * 0.25
        val filterRestricted = filter != "all"
        val lines = mutableListOf<String>()
        // v1.2.142 图标上下文关联（"看得懂图标"的零成本一步）：收集结构化行，
        // walk 结束后为**无文本且可点击**的节点（图标）找最近文本作推断标签。
        // 实测缺口：国产 App 的图标常无 contentDescription，dump 里只剩
        // `- @x,y c`，AI 无从判断；空间关系（同行/邻近）是最可靠的语义信号。
        data class ConvRow(
            val label: String, val x: Int, val y: Int,
            val w: Int, val h: Int,
            val flags: String, val hasText: Boolean,
        )
        val rows = ArrayList<ConvRow>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                var text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val editable = node.isEditable
                val clickable = node.isClickable
                // v1.2.153 实测修复（靶场发现）：**空输入框的 placeholder 只在
                // hintText 里**（Chromium/WebView 尤其如此：`<input placeholder="搜索商品">`
                // 的节点 text 为空、desc 为空，dump 里只剩 `- @x,y ce [edit]`）。
                // 后果：AI 既认不出这个框是干什么的，也点不准它——真实世界的
                // 登录/搜索/验证码框全部中招。用 `p:` 前缀暴露，与 `d:`（desc）同构。
                if (text.isEmpty() && desc.isEmpty() && android.os.Build.VERSION.SDK_INT >= 26) {
                    val hint = runCatching { node.hintText?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    if (hint.isNotEmpty()) text = "p:$hint"
                }
                // v1.2.126：可勾选节点即使无文本也保留（详情页的 Switch 控件常无文本，
                // 其标签在旁边的 TextView 上——过滤掉就看不到开关状态了）
                if ((text.isNotEmpty() || desc.isNotEmpty() || clickable || node.isCheckable) &&
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
                        // v1.2.126：勾选状态（开关/复选框）——v=选中 o=未选中。
                        // 此前开关状态在 dump 里完全不可见：① 验证只能靠帧差猜；
                        // ② 场景记忆因"点击前后指纹相同"而反复命中同一动作（反复点开关）。
                        if (node.isCheckable) append(if (node.isChecked) 'v' else 'o')
                    }
                    // 控件角色（v1.2.131，AI 体验反馈驱动）：实测 AI 把导航行
                    // `d:深色主题 @969,1765 co` 误判成开关 → 点了没反应 → 4.4s
                    // escalation。角色后缀让它第一刀切对。只标有区分价值的角色
                    // （text/image 静默，避免每行加噪音）。
                    val role = when {
                        node.isCheckable -> "switch"
                        node.isEditable -> "edit"
                        else -> {
                            val cn = node.className?.toString() ?: ""
                            when {
                                cn.contains("Button") -> "button"
                                cn.contains("SeekBar") -> "slider"
                                cn.contains("RecyclerView") || cn.contains("ListView") -> "list"
                                cn.contains("ScrollView") -> "scroll"
                                clickable -> "row"     // 可点击的非上述控件 = 导航行/行项目
                                else -> ""
                            }
                        }
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
                    if ((text.isNotEmpty() || desc.isNotEmpty()) && rect.centerY() > contentTop) {
                        contentChars += label.length
                    }
                    // v1.2.126 实测修复：节点**部分可见**时中心坐标可能越界——
                    // 「GET STARTED」节点中心 @540,2211 超出屏高 2209 → 点击落空
                    // （dispatchGesture 仍返回 true，静默失败）。改用**可见区域中心**
                    // （节点与屏幕求交后的中心），坐标必然落在屏内且仍在控件上。
                    val vis = Rect(rect)
                    val cx: Int
                    val cy: Int
                    if (vis.intersect(0, 0, resources.displayMetrics.widthPixels, rootHeight)) {
                        cx = vis.centerX()
                        cy = vis.centerY()
                    } else {
                        cx = rect.centerX()
                        cy = rect.centerY()
                    }
                    // ⚠️ 括号必须显式加（v1.2.134 实测坑）：Kotlin 里
                    // `a + if (x) "p" else "" + if (y) "q" else ""` 会被解析成
                    // `a + if (x) "p" else ("" + if (y) "q" else "")` —— else 分支
                    // 吞掉后续拼接，于是**所有带 flags 的节点都丢了 role**
                    // （AI 实测反馈「dump 里没有 [switch] 标记」的根因）。
                    rows.add(ConvRow(label, cx, cy, vis.width(), vis.height(), flags, text.isNotEmpty() || desc.isNotEmpty()))
                    lines.add(
                        "$count $label @$cx,$cy" +
                            (if (flags.isNotEmpty()) " $flags" else "") +
                            (if (role.isNotEmpty()) " [$role]" else ""),
                    )
                    count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(rootInActiveWindow)
        // ---- v1.2.150 图标识别（用户需求：至少识别齿轮=设置、放大镜=搜索）----
        // 无标签的可点击小区域 → 截一帧做 NCC 模板匹配（36 个 Material 官方图标）。
        // 命中补 `=名称`（识别）；未命中的留给下面的 `~最近文本`（空间推断）兜底。
        // 成本：仅在有候选图标时截屏一次（~50ms）+ 每图标 ~3ms 匹配。
        runCatching {
            val cands = ArrayList<Pair<Int, Rect>>()
            for (i in rows.indices) {
                val r = rows[i]
                if (!r.hasText && r.flags.contains('c') &&
                    r.w in IconMatcher.MIN_ICON_PX..IconMatcher.MAX_ICON_PX &&
                    r.h in IconMatcher.MIN_ICON_PX..IconMatcher.MAX_ICON_PX
                ) {
                    cands.add(i to Rect(r.x - r.w / 2, r.y - r.h / 2, r.x + r.w / 2, r.y + r.h / 2))
                }
            }
            val elapsed = System.nanoTime() / 1_000_000 - tDumpStart
            if (cands.isNotEmpty() && elapsed < 900L) {
                // 剩余预算 ~1.1s：截图上限 700ms（超时即放弃图标，保住本次读屏）
                val shot = screenshotArgb(700L)
                if (shot != null) {
                    for ((idx, rect) in cands) {
                        val hit = IconMatcher.identify(this, shot, rect) ?: continue
                        lines[idx] = lines[idx] + " =" + hit.name
                    }
                }
            }
        }.onFailure { Log.w("DshA11y", "icon match failed: ${it.message}") }
        // ---- v1.2.142 图标上下文关联 ----
        // 无文本且可点击的节点（图标）→ 用最近的文本节点作**推断标签**，标为
        // `~标签`（波浪号 = 推断而非真实文本）。同行优先（y 距离加权 2x），
        // 距离 < 160px 才关联——避免给孤立图标安上无关标签。
        val textRows = rows.filter { it.hasText }
        if (textRows.isNotEmpty()) {
            for (i in rows.indices) {
                val r = rows[i]
                if (r.hasText || !r.flags.contains('c')) continue
                val near = textRows.minByOrNull { t ->
                    kotlin.math.abs(t.y - r.y) * 2 + kotlin.math.abs(t.x - r.x)
                } ?: continue
                if (kotlin.math.abs(near.y - r.y) < 160) {
                    lines[i] = lines[i] + " ~" + near.label.removePrefix("d:").take(24)
                }
            }
        }
        // ---- v1.2.123 OCR 兜底 ----
        // 无障碍节点树并非总有文字：OPPO/ColorOS 自带浏览器、部分 WebView、
        // 自绘（Canvas/SurfaceView）界面**完全不暴露文本节点**（真机实测：
        // 整个屏幕 0 个文本行）→ 从截图 OCR 补足，让云端与本地快速层
        // 都能"看见"这些界面。结果缓存与节流冷却在 ScreenOcr 内部
        // （takeScreenshot 有系统节流，不能每帧调用）。
        var ocrInfo = ""
        if (ocrFallback && filter == "all" && contentChars < OCR_MIN_TEXT_CHARS) {
            val ocr = ScreenOcr.recognize(this, topSkip = OCR_TOP_SKIP)
            if (ocr.blocks.isNotEmpty()) {
                lines.addAll(ScreenOcr.toDumpLines(ocr.blocks, count))
                ocrInfo = " ocr=${ocr.blocks.size}lines/${ocr.costMs}ms" + if (ocr.cached) "(cached)" else ""
            } else if (ocr.unavailable) {
                ocrInfo = " ocr=unavailable"
            }
        }
        sb.append("${resources.displayMetrics.widthPixels}x$rootHeight ${lines.size}nodes$ocrInfo\n")
        lines.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    /** OCR 兜底触发阈值：**内容区**（屏幕 25% 高度以下）文本总字符低于此值
     *  视为「节点树没有可用内容」——顶部 UI 壳的文字不计入（见 dumpScreenCompact）。 */
    private val OCR_MIN_TEXT_CHARS = 40

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
    fun dumpScreenJson(filter: String = "all", ocrFallback: Boolean = true): String {
        val arr = JSONArray()
        var count = 0
        var contentChars = 0
        val contentTop = rootHeight * 0.25
        val filterRestricted = filter != "all"
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val visible = rect.width() > 0 && rect.height() > 0 &&
                rect.top < rootHeight && rect.bottom > 0
            if (visible) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                // v1.2.153：空输入的 placeholder（见 compact dump 处说明）
                val hint = if (text.isEmpty() && desc.isEmpty() &&
                    android.os.Build.VERSION.SDK_INT >= 26
                ) {
                    runCatching { node.hintText?.toString()?.trim().orEmpty() }.getOrDefault("")
                } else ""
                if ((text.isNotEmpty() || desc.isNotEmpty()) && rect.centerY() > contentTop) {
                    contentChars += text.length + desc.length
                }
                if ((text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isCheckable) &&
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
                        put("hint", hint)      // v1.2.153：空输入的 placeholder
                        put("cls", node.className?.toString() ?: "")
                        put("rid", node.viewIdResourceName ?: "")
                        // v1.2.126：部分可见节点的中心可能越界 → 用可见区域中心
                        // （与 dumpScreenCompact 同判据；越界坐标点击会静默落空）
                        val vis = Rect(rect)
                        val onScreen = vis.intersect(0, 0, resources.displayMetrics.widthPixels, rootHeight)
                        put("x", if (onScreen) vis.centerX() else rect.centerX())
                        put("y", if (onScreen) vis.centerY() else rect.centerY())
                        put("w", rect.width())
                        put("h", rect.height())
                        put("clickable", node.isClickable)
                        put("checkable", node.isCheckable)
                        put("checked", node.isChecked)
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
        // ---- v1.2.123 OCR 兜底（与 dumpScreenCompact 同判据）----
        // verify 的 anyText/checkedText 用本函数读屏；若只有 compact 版带 OCR，
        // 会出现「pick 能用 OCR 文本、verify 却永远匹配不到」——实测 canvas
        // 场景点击成功但验证必然失败（无节点树应用同理）。
        if (ocrFallback && filter == "all" && contentChars < OCR_MIN_TEXT_CHARS) {
            val ocr = ScreenOcr.recognize(this, topSkip = OCR_TOP_SKIP)
            for (b in ocr.blocks) {
                arr.put(JSONObject().apply {
                    put("index", count)
                    put("text", b.text)
                    put("desc", "")
                    put("cls", "ocr")
                    put("rid", "")
                    put("x", b.cx)
                    put("y", b.cy)
                    put("w", b.right - b.left)
                    put("h", b.bottom - b.top)
                    put("clickable", true)
                    put("checkable", false)
                    put("checked", false)
                    put("scrollable", false)
                    put("editable", false)
                })
                count++
            }
        }
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
    /**
     * 截图为 ARGB 字节数组（v1.2.122，Framer 专用）—— 跳过 PNG 压缩与 base64 编码。
     *
     * 帧差验证每秒可能采几十帧，走 screenshotBase64() 那条路（PNG 压缩 + base64 +
     * 调用方再解码回 ARGB）会把大部分时间花在无谓的编解码上。这里直接出
     * IntArray（ARGB_8888 逐像素），调用方自行降采样。
     *
     * @return (像素数组, 宽, 高)；失败返回 null
     */
    fun screenshotArgb(timeoutMs: Long = 5000L): Triple<IntArray, Int, Int>? {
        if (Build.VERSION.SDK_INT < 30) return null
        // v1.2.156 实测修复：系统对 takeScreenshot 有节流（errorCode=3
        // INTERVAL_TIME_SHORT）。实测**间隔 5 秒仍被拒**——因为一次读屏里 OCR 与
        // 图标识别各截一帧、叠加其它消费者，节流窗口被持续占用。此前一拒就放弃，
        // 于是 OCR 长期显示 `ocr=unavailable`（画布/无文本界面等于读瞎）。
        // 改为短退避重试：失败等 450ms 再来（最多 3 次），把"偶发被拒"与
        // "真的不可用"分开。
        //
        // 更进一步（同一批实测）：节流的根因是**一次读屏里有两个消费者**
        // （OCR 与图标识别各截一帧），互相把配额吃掉 → 两边都撞 errorCode=3，
        // 各付 450ms 退避。这里加 700ms 的"近期帧复用"：同一个 dump 里的第二次
        // 取帧直接吃缓存，整次读屏只消耗一次系统配额。
        val now = System.currentTimeMillis()
        lastShotCache?.let { c ->
            if (now - lastShotAt < SHOT_REUSE_MS) return c     // 复用帧：无需避让（已有帧里没有悬浮窗）
        }
        // ⚠️ v1.2.164（用户反馈"截图会带上我们自己的悬浮窗，遮挡 AI 视野"）：
        // 截图前**临时隐藏**悬浮窗，截完恢复。悬浮窗是 app 自己的 UI，被截进去会让
        // AI 把它当成页面内容（状态球/文字混进识别结果）→ 误判。
        // 用 try/finally 保证任何异常路径都能恢复，绝不把用户的悬浮窗"吞掉"。
        val svcForAvoid = instance
        StatusOverlay.beginCaptureAvoid(svcForAvoid)
        try {
            repeat(3) { attempt ->
                screenshotArgbOnce(timeoutMs)?.let {
                    lastShotCache = it
                    lastShotAt = System.currentTimeMillis()
                    return it
                }
                if (attempt < 2) Thread.sleep(450L)
            }
            return null
        } finally {
            StatusOverlay.endCaptureAvoid(svcForAvoid)
        }
    }

    /** 近期帧（见 screenshotArgb 顶部说明）：同一个 dump 内的第二次取帧复用它 */
    @Volatile private var lastShotCache: Triple<IntArray, Int, Int>? = null
    @Volatile private var lastShotAt = 0L
    private val SHOT_REUSE_MS = 700L

    private fun screenshotArgbOnce(timeoutMs: Long): Triple<IntArray, Int, Int>? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var bmp: Bitmap? = null
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                }
                screenshot.hardwareBuffer.close()
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) {
                // ⚠️ 诊断（v1.2.124 实测）：连续高频调用时 takeScreenshot 会因
                // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(=3) 被系统节流拒绝——
                // 这是"每帧截图"路线的根本障碍，正解是 MediaProjection 帧流。
                Log.w("DshA11y", "screenshotArgb failed: errorCode=$errorCode " +
                    "(3=INTERVAL_TIME_SHORT: throttled; 1=INTERNAL; 2=NO_ACCESSIBILITY_ACCESS; 4=INVALID_DISPLAY)")
                latch.countDown()
            }
        }
        // v1.2.164：截图前临时隐藏自己的悬浮窗（同 screenshotArgb，见其注释）
        val svcForAvoid = instance
        StatusOverlay.beginCaptureAvoid(svcForAvoid)
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, { it.run() }, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            StatusOverlay.endCaptureAvoid(svcForAvoid)
        }
        val bitmap = bmp ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return Triple(pixels, w, h)
    }

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

    /**
     * 双指捏合缩放（v1.2.148）：地图/图片缩放——无障碍 dispatchGesture 多笔划。
     * @param scale >1 放大（双指外扩），<1 缩小（内收）
     */
    fun dispatchPinch(scale: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        val startGap = minOf(w, h) * 0.15f
        val endGap = (startGap * scale).coerceIn(minOf(w, h) * 0.05f, minOf(w, h) * 0.45f)
        val path1 = android.graphics.Path().apply {
            moveTo(cx - startGap, cy); lineTo(cx - endGap, cy)
        }
        val path2 = android.graphics.Path().apply {
            moveTo(cx + startGap, cy); lineTo(cx + endGap, cy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, 320))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, 320))
            .build()
        markSelfAction()
        expectSelfTouch()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 按 resource-id 找节点（v1.2.141，AI 体验反馈「更多无障碍功能」）。
     * 比文本定位更稳：不受界面语言、OCR 误差、同文本重复的影响，
     * UI 改版时 id 通常也不变。默认后缀/包含匹配（完整 rid 很长）。
     */
    fun findNodeById(rid: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val q = rid.trim()
        if (q.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (hit != null || node == null) return
            val id = node.viewIdResourceName ?: ""
            if (id.isNotEmpty() && (if (exact) id == q else id.endsWith(q) || id.contains(q))) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) { hit = node; return }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return hit
    }

    /** 按 id 点击（v1.2.141）：先试节点 ACTION_CLICK，回退坐标注入。返回 (x, y, via) */
    fun tapById(rid: String): Triple<Int, Int, String>? {
        val node = findNodeById(rid) ?: return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return Triple(r.centerX(), r.centerY(), "action")
        }
        return if (dispatchTapRect(r)) Triple(r.centerX(), r.centerY(), "tap") else null
    }

    /** 滚动到边界（v1.2.141）：优先 SCROLL action；无明确容器（Compose/自绘）回退滑动手势。返回执行次数 */
    fun scrollToEdge(forward: Boolean, maxSwipes: Int = 12): Int {
        val root = rootInActiveWindow ?: return 0
        val scroller = findScrollable(root)
        var n = 0
        if (scroller != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            repeat(maxSwipes) {
                if (!scroller.performAction(action)) return n
                n++
                Thread.sleep(120)
            }
            return n
        }
        // v1.2.141 兜底（实测：设置页等场景 findScrollable 返回 null，swipes=0 无效果）：
        // 用滑动手势滚到边界。惯性滚动可能过头，由调用方用 verify 复核。
        val (w, h) = screenSize()
        val yFrom = if (forward) h * 0.7f else h * 0.3f
        val yTo = if (forward) h * 0.2f else h * 0.8f
        repeat(maxSwipes) {
            val ok = dispatchSwipe(w / 2f, yFrom, w / 2f, yTo, 280L)
            if (!ok) return n
            n++
            Thread.sleep(150)
        }
        return n
    }

    // ---- 通知事件缓存（v1.2.141）：桥的 /notif 读取用 ----
    private val notifLog = java.util.ArrayDeque<String>()
    private val notifLock = Any()

    /** 最近的通知条目（"包名: 文本 | 文本"，新到旧） */
    fun recentNotifications(limit: Int = 10): List<String> =
        synchronized(notifLock) { notifLog.take(limit) }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {        var cur: AccessibilityNodeInfo? = node.parent
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
        } else {
            // v1.2.126：jitter=0 时同样钳制——越界坐标的手势被系统静默丢弃
            // （实测：节点中心 @540,2211 超出屏高 2209，dispatchGesture 返回
            // true 但实际没点中）。钳进屏内至少让点击落在控件可见部分。
            val (sw, sh) = screenSize()
            x.coerceIn(1f, sw - 2f) to y.coerceIn(1f, sh - 2f)
        }
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
        // ⚠️ v1.2.152 审查修复（P1）：此前漏打自身标记——注入的手势产生
        // TYPE_TOUCH_INTERACTION_START 但无 credit 可核销 → batch 的
        // abortOnInterference 误判"用户接管"、/batch 响应误报 userInterference。
        markSelfAction()
        expectSelfTouch()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 拟人化滑动（v1.2.149，用户需求「模拟真实人类操作」）。
     *
     * 真人滑动的三个特征，直线匀速 swipe 一个都没有（机器特征明显）：
     *  ① **弧线**——手指划过的路径不是直线（贝塞尔控制点垂直偏移，方向随机）；
     *  ② **变速**——起手加速、收手减速（GestureDescription 沿路径匀速移动，
     *     故把路径点按 easeInOut 非均匀分布 = 速度曲线）；
     *  ③ **起止抖动**——落点/抬点不精确（±jitter 像素），途中还有手部微抖。
     *
     * 用途：让自动化交互更接近真人手感（滑动列表、翻页）；需要像素级精确的
     * 拖拽用 human=false 走 dispatchSwipe。
     */
    fun dispatchSwipeHuman(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 300L,
        curvature: Float = 0.06f,
        jitterPx: Int = 10,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val (sw, sh) = screenSize()
        val rnd = kotlin.random.Random
        // ① 起止抖动（真人落点不精确）
        val sx = (x1 + rnd.nextInt(-jitterPx, jitterPx + 1)).coerceIn(1f, sw - 2f)
        val sy = (y1 + rnd.nextInt(-jitterPx, jitterPx + 1)).coerceIn(1f, sh - 2f)
        val ex = (x2 + rnd.nextInt(-jitterPx, jitterPx + 1)).coerceIn(1f, sw - 2f)
        val ey = (y2 + rnd.nextInt(-jitterPx, jitterPx + 1)).coerceIn(1f, sh - 2f)
        // ② 弧线控制点（沿路径垂直方向偏移，方向随机）
        val dx = ex - sx
        val dy = ey - sy
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val perpX = -dy / len
        val perpY = dx / len
        val bulge = len * curvature * (if (rnd.nextBoolean()) 1f else -1f)
        val ctrlX = (sx + ex) / 2f + perpX * bulge
        val ctrlY = (sy + ey) / 2f + perpY * bulge
        // ③ 变速：二次贝塞尔采样 + easeInOut 时间映射（点密度 = 速度）
        val path = android.graphics.Path()
        val steps = 24
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val te = t * t * (3f - 2f * t)     // smoothstep：慢-快-慢
            val mt = 1f - te
            val px = mt * mt * sx + 2f * mt * te * ctrlX + te * te * ex
            val py = mt * mt * sy + 2f * mt * te * ctrlY + te * te * ey
            val nx = px + rnd.nextInt(-1, 2)
            val ny = py + rnd.nextInt(-1, 2)
            if (i == 0) path.moveTo(nx, ny) else path.lineTo(nx, ny)
        }
        // ④ 时长随机（真人的滑动时长有分布）
        val dur = (durationMs + rnd.nextLong(-70L, 110L)).coerceIn(120L, 2000L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        markSelfAction()
        expectSelfTouch()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按（单点长时长 stroke） */
    internal fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        markSelfAction()
        expectSelfTouch()
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
     * 触发软键盘「回车/前往」（v1.2.123）。
     *
     * 为什么需要：浏览器地址栏、搜索框、表单输入后必须**提交**——此前只能
     * 靠点击建议项或找"前往"按钮，通用性差（本地闭环实测：Chrome 输入网址后
     * 无按钮可点，只能走建议项）。ACTION_IME_ENTER 是 API 30+ 的标准无障碍
     * 能力，作用于当前输入焦点节点，等价于按软键盘的回车键。
     */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        // 注意：常量在公开 API 里叫 AccessibilityAction.ACTION_IME_ENTER
        // （AccessibilityNodeInfo.ACTION_IME_ENTER 是 @hide 隐藏 API，编译不过）
        return focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

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

        // v1.2.155 实测提速（基准：scroll_find 单步 8.6-9.9s，占长任务 57%）：
        // 旧实现每轮 `ACTION_SCROLL_FORWARD` + 固定 sleep —— 系统默认步长很小
        // （长列表要滑十几次），加上每轮固定等待，一次"滚动查找"要十秒。
        // 改为①**真手势**、一次覆盖滚动区约 75% 高度（一次顶四五次系统滚动）；
        // ②**自适应等待**：滑完先抢读（命中即走），只在没命中时才等落定后复读。
        val vis = Rect().also { scroller.getBoundsInScreen(it) }
        if (vis.width() <= 0 || vis.height() <= 0) return null
        val cx = vis.centerX().toFloat().coerceIn(60f, (screenSize().first - 60).toFloat())
        val nearY = (vis.top + vis.height() * 0.78f).coerceIn(80f, (screenSize().second - 80).toFloat())
        val farY = (vis.top + vis.height() * 0.25f).coerceIn(80f, (screenSize().second - 80).toFloat())
        repeat(maxSwipes.coerceIn(1, 30)) {
            val ok = if (forward) dispatchSwipeHuman(cx, nearY, cx, farY, 240L)
            else dispatchSwipeHuman(cx, farY, cx, nearY, 240L)
            if (!ok) return null
            // 抢读：滚动刚落地目标常已在树里（多数轮次命中，省掉整套等待）
            repeat(4) {
                findNodeByText(q)?.let { return Rect().also { rr -> it.getBoundsInScreen(rr) } }
                Thread.sleep(70)
            }
            // 未命中：等动画落定后再读一次（避免读到中间态而漏过目标）
            Thread.sleep(140)
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
        var emptyReads = 0
        while (System.currentTimeMillis() < deadline) {
            val sig = treeSignature()
            if (sig.isEmpty()) {
                // 节点树不可用（WebView 不暴露节点 / 自绘界面）：签名恒空，
                // 「连续不变」永远无法成立 —— 此前每次动作前白等满超时
                // （真机实测：浏览器类界面 900ms × 每步）。连续两次空读
                // 即判定节点路径无意义，立即返回让调用方走帧差兜底。
                emptyReads++
                if (emptyReads >= 2) return false
            } else if (sig == last) {
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

    /**
     * 当前前台应用包名（供调用方确认「是否还在目标界面」）。
     *
     * ⚠️ v1.2.146 实测修正：此前只用 `rootInActiveWindow` —— 它是**同步 IPC**，
     * 页面加载/重绘期间会阻塞甚至超时（用户实测：Chrome 明明已在前台，
     * verify.foregroundPkg 却判定失败、白等 7 秒）。改三级策略：
     *  ① `windows` 里 active 窗口的包名（较轻）；
     *  ② `rootInActiveWindow`（回退）；
     *  ③ 上次成功的值（避免瞬时读取失败造成误判）。
     */
    fun foregroundPackage(): String? {
        // ⚠️ 只取**应用类型**窗口——实测教训：Chrome 地址栏激活时输入法窗口
        // 也是 isActive，直接取 active 会拿到 com.google.android.inputmethod.*
        // → verify.foregroundPkg 持续误判（25 次轮询全失败）。
        //
        // v1.2.152 实测修复：**系统对话框也是应用窗口**。Chrome 弹「没有响应/关闭
        // 应用/等待」的 ANR 框时，窗口列表顶部是 package=`android` 的对话框窗口，
        // 于是 foregroundPackage() 返回 "android" —— 调用方的
        // {"foregroundPkg":"com.android.chrome"} 会把整个预算耗在失败上
        // （实测 8s 全灭，而 Chrome 页面就在对话框底下活着），AI 拿到 "android"
        // 也完全无从下手。权限弹窗同理。这类"系统壳层"不是用户眼里的前台应用，
        // 跳过它取下面真正的前台应用；ism 壳层下面确实没有应用时才回落。
        val appPkgs = runCatching {
            windows.filter {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }.mapNotNull { w -> runCatching { w.root?.packageName?.toString() }.getOrNull() }
        }.getOrNull().orEmpty()
        val fromWindows = appPkgs.firstOrNull { it.isNotBlank() && it !in SYSTEM_SHELL_PKGS }
            ?: appPkgs.firstOrNull { it.isNotBlank() }
        if (!fromWindows.isNullOrBlank()) { lastFgPkg = fromWindows; return fromWindows }
        val fromRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!fromRoot.isNullOrBlank() && fromRoot !in SYSTEM_SHELL_PKGS) {
            lastFgPkg = fromRoot; return fromRoot
        }
        return lastFgPkg
    }

    /** 系统壳层包名：ANR/崩溃对话框、系统 UI、权限弹窗——不是"用户眼里的前台应用" */
    private val SYSTEM_SHELL_PKGS = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    @Volatile private var lastFgPkg: String? = null

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
                    } else if (passed && !ok) {
                        // 断言通过、但步骤本身没成功（未知 type、点击找不到目标等）。
                        // 旧实现此处不写任何说明，结果里就出现 `ok:false` 与 `asserted:true`
                        // 并存且 detail 只有 "unknown step type"，读起来像自相矛盾。
                        // 这里显式说明：asserted 描述的是**断言条件**，不代表该步执行成功。
                        detail = "asserted: \"$aText\" ${if (aGone) "gone" else "present"}" +
                            " (but this step did not succeed: ${detail.ifEmpty { "step failed" }})"
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
