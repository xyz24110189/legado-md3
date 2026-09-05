package io.legado.app.help.readaloud.playback

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.legado.app.constant.AppLog

/**
 * Piper (sherpa-onnx VITS/Matcha) 中文推理引擎。
 * 通过 sherpa-onnx Kotlin API 执行，输出 float32 PCM。
 */
class PiperEngine(context: Context, modelKey: String) {

    private val appContext = context.applicationContext
    private val tts: OfflineTts

    /** 实际生效的 ORT provider（优先 NNAPI，其次 XNNPACK，最后 CPU）。 */
    val providerUsed: String

    init {
        val assetManager = appContext.assets
        val assetModelDir = "piper/$modelKey"
        // 直接从 assets 加载（sherpa-android 官方标准做法）。
        // 避免把 espeak-ng-data 这种大目录树提取到内部存储后再从文件加载时出现的读取/路径问题。
        val assetNames = assetManager.list(assetModelDir).orEmpty()
        // Matcha 目录含两个 onnx（声学模型 + 声码器），其余家族只有一个
        val onnxNames = assetNames.filter { it.endsWith(".onnx", ignoreCase = true) }
        val onnxName = onnxNames.firstOrNull()
            ?: throw IllegalArgumentException("Piper 模型缺失: $modelKey")
        val tokensPath = "$assetModelDir/tokens.txt"
        val espeakPath = "piper/${PiperModelManager.ESPEAK_DATA}"
        val lexiconPath = "$assetModelDir/lexicon.txt"

        // 文本规范化规则（数字/日期/电话读法）：使用官方 TTS 引擎 demo 的做法——
        // 在 assetManager 模式下传 **asset 相对路径**（原生层经 AAssetManager 读取）。
        // 注意：绝对文件路径在此模式下会打开失败并导致不可捕获的原生崩溃（历史踩坑）。
        val ruleFsts = assetNames
            .filter { it.endsWith(".fst", ignoreCase = true) }
            .sorted()
            .joinToString(",") { "$assetModelDir/$it" }

        val kind = PiperModelManager.kindOf(modelKey)
        val modelConfigFactory: (String) -> OfflineTtsModelConfig = when (kind) {
            PiperModelManager.ModelKind.MATCHA -> {
                val acousticName = onnxNames.firstOrNull { !it.contains("vocoder", true) && !it.contains("vocos", true) }
                    ?: onnxName
                val vocoderName = onnxNames.firstOrNull { it != acousticName }
                val preflight = "kind=matcha acoustic=$acousticName vocoder=$vocoderName lexicon=$lexiconPath ruleFsts=$ruleFsts"
                AppLog.put("PiperEngine preflight $preflight")
                val matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = "$assetModelDir/$acousticName",
                    vocoder = vocoderName?.let { "$assetModelDir/$it" }.orEmpty(),
                    lexicon = lexiconPath,
                    tokens = tokensPath,
                )
                val factory = { provider: String ->
                    OfflineTtsModelConfig(matcha = matcha, numThreads = threadsFor(assetManager, "$assetModelDir/$acousticName"), debug = false, provider = provider)
                }
                factory
            }

            PiperModelManager.ModelKind.VITS_ESPEAK -> {
                val onnxPath = "$assetModelDir/$onnxName"
                val dictPath = "$assetModelDir/dict"
                // vits-zh-hf 需要 lexicon + dictDir；普通 piper（小雅/超雯）使用 espeak-ng-data。
                val isVitsZhHf = assetManager.list(dictPath) != null
                AppLog.put("PiperEngine preflight kind=vits onnx=$onnxPath isVitsZhHf=$isVitsZhHf ruleFsts=$ruleFsts")
                val vits = if (isVitsZhHf) {
                    OfflineTtsVitsModelConfig(
                        model = onnxPath,
                        tokens = tokensPath,
                        lexicon = lexiconPath,
                        dictDir = dictPath,
                    )
                } else {
                    OfflineTtsVitsModelConfig(
                        model = onnxPath,
                        tokens = tokensPath,
                        dataDir = espeakPath,
                    )
                }
                val factory = { provider: String ->
                    OfflineTtsModelConfig(vits = vits, numThreads = threadsFor(assetManager, onnxPath), debug = false, provider = provider)
                }
                factory
            }
        }
        val preflight = "kind=$kind model=$modelKey"

        // provider 选择：优先 NNAPI（GPU/DSP/NPU），其次 XNNPACK（CPU 优化、稳定快速），最后 CPU。
        // NNAPI 在部分设备/模拟器上初始化会触发原生崩溃（不可被 Java 捕获），无法用 try/catch 回退。
        // 这里用 SharedPreferences 做“崩溃自愈”：若上次尝试某 provider 时进程崩溃（标记未清除），
        // 下次启动将其永久禁用，自动回退到更稳定的 provider（如 XNNPACK），避免反复崩溃。
        val prefs = appContext.getSharedPreferences("piper_tts_providers", Context.MODE_PRIVATE)
        val lastAttempt = prefs.getString("last_attempt", null)
        if (lastAttempt != null) {
            prefs.edit().putBoolean("bad_$lastAttempt", true).commit()
            prefs.edit().remove("last_attempt").commit()
            AppLog.put("PiperEngine 检测到 provider=$lastAttempt 上次原生崩溃，已自动禁用")
        }
        val isEmulator = Build.FINGERPRINT.contains("generic", true) ||
            Build.MODEL.contains("Emulator", true) ||
            Build.MODEL.contains("Android SDK", true)
        // NNAPI（GPU）默认关闭：部分设备初始化即原生崩溃。用户可在播放器里显式开启，
        // 开启后若崩溃仍会被下面的自愈机制标记并禁用。
        val nnapiEnabled = isNnapiEnabled(appContext)
        if (!nnapiEnabled) {
            AppLog.put("PiperEngine NNAPI(GPU) 未启用，跳过（可在播放器 GPU 加速开关中开启）")
        }
        val wanted = listOf("nnapi", "xnnpack", "cpu")
        val candidates = wanted.filter {
            !prefs.getBoolean("bad_$it", false) && !(it == "nnapi" && (!nnapiEnabled || isEmulator))
        }

        val failures = mutableListOf<String>()
        var usedProvider = "cpu"
        val ttsInstance = run {
            for (provider in candidates) {
                try {
                    prefs.edit().putString("last_attempt", provider).commit()
                    val config = OfflineTtsConfig(
                        model = modelConfigFactory(provider),
                        ruleFsts = ruleFsts,
                        maxNumSentences = 1,
                    )
                    AppLog.put("PiperEngine init 尝试 provider=$provider model=$modelKey")
                    val tt = OfflineTts(assetManager = assetManager, config = config)
                    prefs.edit().remove("last_attempt").commit()
                    usedProvider = provider
                    return@run tt
                } catch (e: Throwable) {
                    prefs.edit().remove("last_attempt").commit()
                    val detail = "provider=$provider: ${e.message}\n${e.stackTraceToString().take(2000)}"
                    failures.add(detail)
                    AppLog.put("PiperEngine $detail", e)
                }
            }
            null
        }
        val ok = ttsInstance ?: run {
            val summary = failures.joinToString("\n")
            val diagPath = writeDiag(appContext, preflight, summary)
            val firstLine = failures.firstOrNull()?.lineSequence()?.firstOrNull() ?: "unknown"
            val msg = "Piper 初始化失败[$preflight] 首错: ${firstLine.take(120)} 详情见 $diagPath"
            AppLog.put(msg, toast = true)
            throw IllegalArgumentException(msg)
        }
        tts = ok
        providerUsed = usedProvider
        lastProviderUsed = usedProvider
        AppLog.put("PiperEngine init ok provider=$providerUsed sampleRate=${tts.sampleRate()}")
    }

    /** 大模型用更多线程：官方 RTF 数据显示同类模型 4 线程比 2 线程快约 35%。 */
    private fun threadsFor(assetManager: android.content.res.AssetManager, onnxPath: String): Int {
        val sizeBytes = runCatching {
            assetManager.openFd(onnxPath).use { it.length }
        }.getOrDefault(0L)
        return if (sizeBytes >= 100L * 1024 * 1024) 4 else 2
    }

    private fun writeDiag(context: Context, preflight: String, detail: String): String {
        return runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "piper_diag.txt")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.writeText("时间: $time\npreflight: $preflight\n\n=== 初始化失败详情 ===\n$detail\n")
            file.absolutePath
        }.getOrDefault("写入诊断文件失败")
    }

    /** 合成文本为 float32 samples（22050Hz），失败返回空。 */
    fun synthesize(text: String, speed: Float): FloatArray = runCatching {
        val audio: GeneratedAudio = tts.generate(
            text = text,
            sid = 0,
            speed = speed.coerceIn(0.5f, 2.0f),
        )
        audio.samples
    }.getOrElse {
        AppLog.put("PiperEngine synthesize failed: ${it.localizedMessage}", it)
        FloatArray(0)
    }

    fun sampleRate(): Int = runCatching { tts.sampleRate() }.getOrDefault(22050)

    fun close() {
        runCatching { tts.free() }
    }

    companion object {
        const val SAMPLE_RATE = 22050
        private const val PREFS_NAME = "piper_tts_providers"

        /** 最近一次成功创建引擎所用的 provider（nnapi/xnnpack/cpu），供 UI 展示。 */
        @Volatile
        var lastProviderUsed: String? = null

        fun isNnapiEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("nnapi_enabled", false)

        /**
         * 开关 NNAPI(GPU)。重新开启时清除旧的崩溃自愈标记，允许再次探测；
         * 若设备仍会原生崩溃，下次启动会被再次自动禁用。
         */
        fun setNnapiEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("nnapi_enabled", enabled).commit()
            if (enabled) {
                prefs.edit().remove("bad_nnapi").commit()
                prefs.edit().remove("last_attempt").commit()
            }
        }
    }
}
