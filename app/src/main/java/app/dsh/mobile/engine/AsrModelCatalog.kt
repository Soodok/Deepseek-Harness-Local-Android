package app.dsh.mobile.engine

/**
 * 可下载的离线 ASR 模型目录（v1.2.59）。
 *
 * 来源：Vosk（https://alphacephei.com/vosk/models），Apache 2.0 许可。
 * 设备没有系统语音识别服务时，可从这里下载模型做离线识别 fallback。
 *
 * 模型文件下载到 `${'$'}filesDir/asr-models/<id>/` 目录。
 */
data class AsrModel(
    val id: String,
    val label: String,
    val language: String,
    val sizeMb: Int,
    /** 主源（Vosk 官方）；国内网络可能不可达 */
    val url: String,
    /** 国内镜像（hf-mirror.com；实测可达，作为主源 fallback） */
    val mirrorUrl: String,
    /** 模型 zip 解压后根目录下的子目录名（Vosk 的 Model() 构造参数） */
    val dirName: String,
    /**
     * 下载完成后校验用：模型目录内必须存在的关键文件（相对 dirName）。
     * 防止「下完了但内容残缺/解压中断」——Vosk 的 Model() 会因此抛异常或识别乱码。
     */
    val requiredFiles: List<String> = listOf(
        "am/final.mdl", "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst",
    ),
    /** zip 期望字节数（官方 Content-Length，用于下载完整性校验；0 = 跳过） */
    val expectedBytes: Long = 0L,
)

object AsrModelCatalog {

    /**
     * 手机端可用的离线模型。
     *
     * ⚠️ 只保留**实测可下载**的两个（2026-10-05 模拟器实测）：
     *  · small-cn    —— 官方 + hf-mirror 双源可用
     *  · small-en-us —— 官方 + hf-mirror 双源可用
     * 曾有的 small-en-in（印度英语）**已移除**：hf-mirror 上不存在该模型
     * （rhasspy/vosk-models 的 en/ 目录只有 en-us；HF 上同名仓库是 970MB 完整版
     * 或与 en-us 字节数相同的不可信副本），只能走官方源，而官方源在国内实测
     * 仅 ~55KB/s（36MB 要 ~11 分钟），用户实测「点了没反应/下不下来」。
     * 宁缺毋滥：不提供下载不了的选项。
     */
    val models = listOf(
        AsrModel(
            id = "small-cn",
            label = "Chinese · Compact",
            language = "zh-CN",
            sizeMb = 42,
            url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            mirrorUrl = "https://hf-mirror.com/rhasspy/vosk-models/resolve/main/zh/vosk-model-small-cn-0.22.zip",
            dirName = "vosk-model-small-cn-0.22",
            // 官方 Content-Length（实测 2026-10-05）
            expectedBytes = 43898754L,
        ),
        AsrModel(
            id = "small-en-us",
            label = "English · Compact",
            language = "en-US",
            sizeMb = 40,
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            mirrorUrl = "https://hf-mirror.com/rhasspy/vosk-models/resolve/main/en/vosk-model-small-en-us-0.15.zip",
            dirName = "vosk-model-small-en-us-0.15",
            expectedBytes = 41205931L,
        ),
    )

    /** 按语言筛选可用模型 */
    fun forLanguage(lang: String): List<AsrModel> =
        models.filter { it.language.startsWith(lang.take(2)) }
}
