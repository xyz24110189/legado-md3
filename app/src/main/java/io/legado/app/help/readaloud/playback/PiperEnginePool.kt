package io.legado.app.help.readaloud.playback

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 进程级 Piper 引擎池。
 *
 * 引擎按 modelKey 缓存于伴生单例，存活期与应用进程一致：
 * - 朗读服务（HttpReadAloudService）销毁重建时不再重复加载模型（此前每次停止朗读都会 free 掉引擎，
 *   下次启动要重新做一次数秒的 ORT 会话初始化）；
 * - 支持在用户选择模型时预热（见 [prewarm]），把引擎初始化与翻书/选模型的时间重叠掉。
 *
 * 内存代价：每个已用过的模型常驻一个 ORT 会话（约 60MB+），随进程退出回收。
 */
object PiperEnginePool {

    private val mutex = Mutex()
    private val engines = mutableMapOf<String, PiperEngine>()
    private val initErrors = mutableMapOf<String, String>()

    suspend fun acquire(context: Context, key: String): PiperEngine? = mutex.withLock {
        engines[key]?.let { return it }
        initErrors[key]?.let { return null }
        val eng = try {
            PiperEngine(context.applicationContext, key)
        } catch (e: Throwable) {
            initErrors[key] = e.message ?: e.localizedMessage ?: "unknown"
            AppLog.put("PiperEngine($key) 初始化失败: ${initErrors[key]}", e, toast = true)
            null
        }
        if (eng != null) engines[key] = eng
        eng
    }

    /** 后台预热指定模型的引擎，供选择模型时提前调用。 */
    suspend fun prewarm(context: Context, key: String) {
        withContext(Dispatchers.IO) {
            runCatching { acquire(context, key) }
                .onFailure { AppLog.put("PiperEnginePool 预热失败($key): ${it.localizedMessage}", it) }
        }
    }
}
