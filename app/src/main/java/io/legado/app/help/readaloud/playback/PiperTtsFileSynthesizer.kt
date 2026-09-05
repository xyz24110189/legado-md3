package io.legado.app.help.readaloud.playback

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Piper 合成输出 WAV 文件，供 HttpReadAloudService 的 ExoPlayer 播放。
 *
 * 引擎实例由进程级 [PiperEnginePool] 持有（跨服务销毁重建复用），
 * 这里只用 [Semaphore] 限制合成并发，避免多个推理请求叠加内存/CPU 压力。
 */
class PiperTtsFileSynthesizer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val semaphore = Semaphore(1)

    /** 合成一段文本为 WAV 文件。modelKey 对应 [PiperModelManager.MODELS] 的 key。 */
    suspend fun synthesize(
        text: String,
        output: File,
        speed: Float = 1f,
        modelKey: String = PiperModelManager.MODELS.first().key,
    ): Boolean = semaphore.withPermit {
        val engine = withContext(Dispatchers.IO) { PiperEnginePool.acquire(appContext, modelKey) }
        if (engine == null) return@withPermit false
        withContext(Dispatchers.IO) {
            runCatching {
                val samples = engine.synthesize(text, speed)
                if (samples.isEmpty()) {
                    AppLog.put("PiperTtsFileSynthesizer: 空采样 text='${text.take(50)}'")
                    return@runCatching false
                }
                output.parentFile?.mkdirs()
                val wav = pcm16ToWav(floatToPcm16(samples), engine.sampleRate())
                output.writeBytes(wav)
                output.length() > 0L
            }.getOrElse {
                AppLog.put("PiperTtsFileSynthesizer: 合成异常 ${it.localizedMessage}", it)
                false
            }
        }
    }

    /**
     * 原有的引擎生命周期随服务销毁而结束；现由进程级 [PiperEnginePool] 管理，
     * 服务停止不再释放引擎，下次朗读免去数秒的模型重新加载。
     */
    suspend fun close() {
    }

    /** 模型输出转换：float32 → int16 PCM */
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            buf.putShort(v.toShort())
        }
        return out
    }

    private fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1.toShort()).putShort(1.toShort())
        out.putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
        out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return out.array()
    }
}
