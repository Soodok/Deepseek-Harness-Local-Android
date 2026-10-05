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
    val url: String,
    /** 模型 zip 解压后根目录下的子目录名（Vosk 的 Model() 构造参数） */
    val dirName: String,
)

object AsrModelCatalog {

    /** 手机端可用的高质量小模型（按推荐排序） */
    val models = listOf(
        AsrModel(
            id = "small-cn",
            label = "中文 · 轻量版",
            language = "zh-CN",
            sizeMb = 42,
            url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            dirName = "vosk-model-small-cn-0.22",
        ),
        AsrModel(
            id = "small-en-us",
            label = "English · Compact",
            language = "en-US",
            sizeMb = 40,
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            dirName = "vosk-model-small-en-us-0.15",
        ),
        AsrModel(
            id = "small-en-in",
            label = "English (India) · Compact",
            language = "en-IN",
            sizeMb = 36,
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            dirName = "vosk-model-small-en-in-0.4",
        ),
    )

    /** 按语言筛选可用模型 */
    fun forLanguage(lang: String): List<AsrModel> =
        models.filter { it.language.startsWith(lang.take(2)) }
}
