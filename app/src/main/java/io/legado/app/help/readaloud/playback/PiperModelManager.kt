package io.legado.app.help.readaloud.playback

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 管理 assets 内置的 Piper（sherpa-onnx VITS/espeak）中文模型资源，
 * 首次使用拷贝到 filesDir。每个模型位于 `assets/piper/<key>/` 子目录，
 * espeak-ng-data 为共享的树形目录。
 */
object PiperModelManager {

    const val ESPEAK_DATA = "espeak-ng-data"

    /** 模型家族：决定 PiperEngine 如何构造 sherpa 配置。 */
    enum class ModelKind {
        /** VITS + espeak-ng-data 前端（piper 系列）。 */
        VITS_ESPEAK,

        /** Matcha 扩散声学模型 + 声码器 + lexicon 前端。 */
        MATCHA,
    }

    data class PiperModel(
        val key: String,
        val displayName: String,
        val kind: ModelKind = ModelKind.VITS_ESPEAK,
    )

    val MODELS = listOf(
        PiperModel("xiao_ya", "小雅"),
        PiperModel("chaowen", "超雯"),
        PiperModel("baker", "Baker 播音女声", ModelKind.MATCHA),
    )

    fun kindOf(key: String): ModelKind =
        MODELS.firstOrNull { it.key == key }?.kind ?: ModelKind.VITS_ESPEAK

    fun modelDir(context: Context): File = File(context.filesDir, "piper")

    fun ensureAssets(context: Context): File = synchronized(this) {
        val dir = modelDir(context)
        dir.mkdirs()
        copyTree(context, "piper", dir)
        dir
    }

    /** 递归拷贝 assets/piper 下所有文件到 targetDir；已存在且非空则跳过。 */
    private fun copyTree(context: Context, assetPrefix: String, targetDir: File) {
        val names = context.assets.list(assetPrefix) ?: arrayOf()
        names.forEach { name ->
            val assetPath = "$assetPrefix/$name"
            val target = File(targetDir, name)
            val children = context.assets.list(assetPath)
            if (children != null && children.isNotEmpty()) {
                target.mkdirs()
                copyTree(context, assetPath, target)
            } else {
                if (target.length() == 0L) {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun modelSubDir(context: Context, key: String): File = File(modelDir(context), key)

    /** 返回该模型目录下的 .onnx 文件（每个模型仅一个）。 */
    fun onnx(context: Context, key: String): File? {
        val dir = modelSubDir(context, key)
        return dir.listFiles()?.firstOrNull { it.extension == "onnx" }
    }

    /** 返回该模型目录下的 tokens 文件。 */
    fun tokens(context: Context, key: String): File =
        File(modelSubDir(context, key), "tokens.txt")

    /** sherpa 需要 dataDir 指向 espeak-ng-data 所在目录（含 dict 与 cmn_dict 等）。 */
    fun espeakDataDir(context: Context): File = File(modelDir(context), ESPEAK_DATA)

    fun assetsReady(context: Context, key: String): Boolean =
        onnx(context, key)?.isFile == true

    fun isSupported(key: String): Boolean = MODELS.any { it.key == key }
}
