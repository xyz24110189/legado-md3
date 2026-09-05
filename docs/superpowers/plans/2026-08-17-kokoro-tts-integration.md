# Kokoro 中文神经 TTS 集成进 legado-with-MD3 — 实施计划

> ⚠️ **已废弃**：Kokoro 引擎已从代码库中移除。当前 TTS 仅使用 sherpa-onnx（小雅、超雯、Baker 三组音色）。本文档仅保留作为历史记录。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Kokoro 82M 中文神经 TTS 作为新朗读引擎集成进 legado-with-MD3。（**此目标已取消**，改用 sherpa-onnx）

**Architecture:** Kokoro 作为"本地文件合成引擎"挂在现有 `HttpReadAloudService` 之后：`KokoroTtsFileSynthesizer`（仿 `SystemTtsFileSynthesizer`）内部用 onnxruntime-android 推理出 float PCM，转 int16 WAV 落盘到 `externalCacheDir/httpTTS/`，由现成 ExoPlayer 播放。新引擎类型 `ENGINE_KOKORO = "kokoro"` 在 `ReadAloudVoice` 定义，路由回到 `HttpReadAloudService`，选择/下载音色融入现有 CloudTtsScreen 的引擎列表。

**Tech Stack:** Kotlin 2.4 / AGP 9.2.1 / Compose (BOM 2026.06.01) / onnxruntime-android 1.27.0 / pinyin4j 2.5.1 / Media3 ExoPlayer / Koin DI。模型文件内置 assets/kokoro/（127MB onnx + zf_001.bin + tokenizer.json）。

**前置事实（已 JVM probe 验证）：**
- `input_ids` int64 [1,N]（首尾补 pad 0，N≤510）；`style` float32 [1,256]（音色 bin 第 len(content_tokens) 组）；`speed` float32 [1]
- 输出 `waveform` float32 [samples]，采样率 24000Hz 单声道。
- Windows 上 onnxruntime-jvm >=1.21 冲突，JVM 单测用 1.20.0；Android 用 1.27.0。

**测试约定：** `.\gradlew.bat :app:testAppDebugUnitTest --tests "<class>.<method>"`（flavor=app, buildType=debug）。编译速查 `.\gradlew.bat :app:compileAppDebugKotlin`。

---

### Task 1: 添加依赖与构建配置

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 在 `gradle/libs.versions.toml` 的 `[versions]` 加三个版本号**

在 `timber = "5.0.1"` 行后插入：

```toml
onnxruntime = "1.27.0"
onnxruntimeJvm = "1.20.0"
pinyin4j = "2.5.1"
```

- [ ] **Step 2: 在 `[libraries]` 加四个库**

在 `timber = { ... }` 行后插入：

```toml
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
onnxruntime-jvm = { group = "com.microsoft.onnxruntime", name = "onnxruntime", version.ref = "onnxruntimeJvm" }
pinyin4j = { group = "com.belerweb", name = "pinyin4j", version.ref = "pinyin4j" }
```

- [ ] **Step 3: 在 `app/build.gradle.kts` 的 `dependencies` 块加三项**

在 `implementation(libs.timber)` 行之后加：

```kotlin
    implementation(libs.onnxruntime.android)
    implementation(libs.pinyin4j)
    // 仅 JVM 单元测试需要（PCM 推理验证）；Android 运行时用 onnxruntime-android
    testImplementation(libs.onnxruntime.jvm)
```

- [ ] **Step 4: 配置 assets 不压缩（onnx 等已否压缩格式不可二次压缩）**

在 `android { defaultConfig { } }` 之后、`buildFeatures` 之前加：

```kotlin
    androidResources {
        noCompress += listOf("onnx", "bin", "json")
    }
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(kokoro): add onnxruntime and pinyin4j dependencies"
```

---

### Task 2: 内置模型三件套到 assets

**Files:**
- Create (binary copy): `app/src/main/assets/kokoro/kokoro-v1.1-zh-int8.onnx` (127,356,597 B)
- Create (binary copy): `app/src/main/assets/kokoro/voices-zf_001.bin` (522,240 B)
- Create: `app/src/main/assets/kokoro/tokenizer.json` (4,944 B)

模型源（已有）：`D:\Users\xyz24\AndroidStudioProjects\MyApplication\app\src\main\assets\kokoro\`

- [ ] **Step 1: 拷贝三个文件**

```powershell
New-Item -ItemType Directory -Path "app/src/main/assets/kokoro" -Force | Out-Null
Copy-Item "D:\Users\xyz24\AndroidStudioProjects\MyApplication\app\src\main\assets\kokoro\kokoro-v1.1-zh-int8.onnx" "app/src/main/assets/kokoro/"
Copy-Item "D:\Users\xyz24\AndroidStudioProjects\MyApplication\app\src\main\assets\kokoro\voices-zf_001.bin" "app/src/main/assets/kokoro/"
Copy-Item "D:\Users\xyz24\AndroidStudioProjects\MyApplication\app\src\main\assets\kokoro\tokenizer.json" "app/src/main/assets/kokoro/"
```

Run from repo root: `D:\Users\xyz24\AndroidStudioProjects\legado-with-MD3\legado-with-MD3-main`

- [ ] **Step 2: 校验字节数**

```powershell
Get-ChildItem "app/src/main/assets/kokoro" | Select-Object Name, Length
```
Expected: onnx=127356597, bin=522240, json=4944

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/kokoro
git commit -m "feat(kokoro): bundle kokoro zh model, voice bin and tokenizer as assets"
```

> 注意：127MB 二进制进 git 会有一次大提交，属预期。若团队用 LFS 可后续迁移，本计划不强求。

---

### Task 3: 移植 TextFrontend（中文→注音→token）

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/playback/TextFrontend.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/playback/TextFrontendTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextFrontendTest {

    private fun loadFrontend(): TextFrontend =
        TextFrontend.fromTokenizerJson(File("src/main/assets/kokoro/tokenizer.json").readText())

    @Test
    fun `chinese maps to bopomofo with tone`() {
        val f = loadFrontend()
        assertEquals("ㄓ ㄨ ㄥ 1 ㄨ ㄣ 2", f.phrasesOf("中文"))
    }

    @Test
    fun `numbers become chinese words`() {
        val f = loadFrontend()
        val out = f.phrasesOf("2025年读了108本书")
        assertTrue(out.contains("ㄌ ㄧ ㄥ 2"))
        assertTrue(out.contains("ㄅ ㄚ 1"))
    }

    @Test
    fun `ids are padded and bounded`() {
        val f = loadFrontend()
        val ids = f.toIds("中文的拼音转换测试。1234567890 ABC")
        assertEquals(0, ids.first())
        assertEquals(0, ids.last())
        for (id in ids) assertTrue("id=$id out of range", id in 0..177)
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.TextFrontendTest"`
Expected: compilation error `Unresolved reference: TextFrontend`

- [ ] **Step 3: 移植实现**

从 `D:\Users\xyz24\AndroidStudioProjects\MyApplication\app\src\main\java\com\example\kokorotts\tts\TextFrontend.kt` 全文复制，改：
- `package com.example.kokorotts.tts` → `package io.legado.app.help.readaloud.playback`
- 将公开 API 改为下面三个方法名（保持内部逻辑不变）：
  - `textToPhonemeGroups(rawText)` → `toPhonemeGroups(rawText)`（List<List<String>>）
  - `phonemeGroupsToIds(groups)` → `phrasesToIds(groups)`（IntArray）
  - 新增辅助：

```kotlin
    /** 便于可读断言：扁平化音素序列 */
    fun phrasesOf(rawText: String): String =
        toPhonemeGroups(rawText).flatten().joinToString(" ")

    /** 音素到 id（含截断与首尾 pad），供引擎使用 */
    fun toIds(rawText: String): IntArray =
        phrasesToIds(toPhonemeGroups(rawText))
```

- [ ] **Step 4: 运行验证通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.TextFrontendTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/help/readaloud/playback/TextFrontend.kt app/src/test/java/io/legado/app/help/readaloud/playback/TextFrontendTest.kt
git commit -m "feat(kokoro): port chinese TextFrontend with bopomofo mapping"
```

---

### Task 4: 音色与模型管理器（assets→files 拷贝、style 向量读取）

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroVoice.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroModelManager.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/playback/KokoroVoiceTest.kt`

- [ ] **Step 1: 写失败测试（style 向量布局）**

```kotlin
package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

class KokoroVoiceTest {

    @Test
    fun `voice bin has 256-dim float styles per entries`() {
        val bytes = File("src/main/assets/kokoro/voices-zf_001.bin").readBytes()
        val floats = KokoroVoice.toFloatArray(bytes)
        assertEquals(510, floats.size / 256)
        assertEquals(0, floats.size % 256)
        // 第 0 组首元素可读（非全无穷）
        org.junit.Assert.assertTrue(floats.isNotEmpty())
    }

    @Test
    fun `styleAt returns row times 256`() {
        val bytes = File("src/main/assets/kokoro/voices-zf_001.bin").readBytes()
        val f = KokoroVoice.toFloatArray(bytes)
        val style = KokoroVoice.styleAt(f, 0, 256)
        assertArrayEquals(f.copyOfRange(0, 256), style, 1e-6f)
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected, actual)
}
```

- [ ] **Step 2: 运行验证失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroVoiceTest"`
Expected: `Unresolved reference: KokoroVoice`

- [ ] **Step 3: 实现 `KokoroVoice.kt`**

```kotlin
package io.legado.app.help.readaloud.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 音色文件工具：.bin 内容为小端 float32，每行 [perStyle] 维 style 向量。 */
object KokoroVoice {
    const val PER_STYLE = 256

    fun toFloatArray(bytes: ByteArray): FloatArray {
        val n = bytes.size / 4
        val out = FloatArray(n)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = buf.float
        return out
    }

    fun styleAt(floats: FloatArray, index: Int, perStyle: Int = PER_STYLE): FloatArray {
        val start = (index % (floats.size / perStyle)) * perStyle
        return floats.copyOfRange(start, start + perStyle)
    }

    /** 模型输出转换：float32 → int16 PCM */
    fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            buf.putShort(v.toShort())
        }
        return out
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroVoiceTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 实现 `KokoroModelManager.kt`（assets→files 内置 + 校验）**

```kotlin
package io.legado.app.help.readaloud.playback

import android.content.Context
import java.io.File

/** 管理 assets 内置的 Kokoro 模型/音色资源，首次使用拷贝到 filesDir。 */
object KokoroModelManager {

    object ModelFile {
        const val ONNX = "kokoro-v1.1-zh-int8.onnx"
        const val VOICE_ZF = "voices-zf_001.bin"
        const val TOKENIZER = "tokenizer.json"
    }

    val requiredFiles: List<Pair<String, Long>> = listOf(
        ModelFile.ONNX to 127_356_597L,
        ModelFile.VOICE_ZF to 522_240L,
        ModelFile.TOKENIZER to 4_944L,
    )

    fun modelDir(context: Context): File = File(context.filesDir, "kokoro")

    fun ensureAssets(context: Context): File = synchronized(this) {
        val dir = modelDir(context)
        dir.mkdirs()
        requiredFiles.forEach { (name, expected) ->
            val target = File(dir, name)
            if (!target.isFile || target.length() != expected) {
                dir.mkdirs()
                target.delete()
                context.assets.open("kokoro/$name").use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        dir
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/legado/app/help/readaloud/playback/KokoroVoice.kt app/src/main/java/io/legado/app/help/readaloud/playback/KokoroModelManager.kt app/src/test/java/io/legado/app/help/readaloud/playback/KokoroVoiceTest.kt
git commit -m "feat(kokoro): voice bin parser and model asset manager"
```

---

### Task 5: Kokoro 推理引擎（onnxruntime 会话）

**Files:**
- Create: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroEngine.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/playback/KokoroEngineTest.kt`

- [ ] **Step 1: 写失败测试（真实推理极短句，JVM onnxruntime 1.20.0）**

```kotlin
package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KokoroEngineTest {

    @Test
    fun `short text zhen produces pcm samples`() {
        val engine = KokoroEngine.Jvm(
            modelFile = File("src/main/assets/kokoro/kokoro-v1.1-zh-int8.onnx"),
            voiceBytes = File("src/main/assets/kokoro/voices-zf_001.bin").readBytes(),
            textFrontend = TextFrontend.fromTokenizerJson(
                File("src/main/assets/kokoro/tokenizer.json").readText(),
            ),
        )
        val pcm = engine.synthesize("你好", speed = 1.0f)
        assertTrue("expected non-empty pcm, got ${pcm.size} samples", pcm.size > 100)
        engine.close()
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroEngineTest"`
Expected: `Unresolved reference: KokoroEngine`

- [ ] **Step 3: 实现 `KokoroEngine.kt`（android 与 jvm 两实现）**

```kotlin
package io.legado.app.help.readaloud.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro onnx 推理引擎。Jvm 实现供单测（onnxruntime-jvm），
 * Android 主实现走 OrtSession 相同 API。
 */
interface KokoroEngine {
    /** 输出 float32 PCM（24000Hz mono）。失败返回空数组。 */
    fun synthesize(text: String, speed: Float): FloatArray
    fun close()

    companion object {
        const val MAX_TOKENS = 510
        const val SAMPLE_RATE = 24000
    }

    class Jvm(
        modelFile: File,
        voiceBytes: ByteArray,
        private val textFrontend: TextFrontend,
    ) : KokoroEngine {
        private val env = OrtEnvironment.getEnvironment()
        private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        })
        private val voiceFloats = KokoroVoice.toFloatArray(voiceBytes)

        override fun synthesize(text: String, speed: Float): FloatArray {
            val ids = textFrontend.toIds(text)
            if (ids.size < 3) return floatArrayOf()
            val contentTokens = ids.size - 2 // 去掉首尾 pad
            val styleIdx = contentTokens.coerceAtMost(MAX_TOKENS - 1)
            val oneStyle = KokoroVoice.styleAt(voiceFloats, styleIdx)
            val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), longArrayOf(1, ids.size.toLong()))
            val style = OnnxTensor.createTensor(env, FloatBuffer.wrap(oneStyle), longArrayOf(1, oneStyle.size.toLong()))
            val spd = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed.coerceIn(0.5f, 2.0f))), longArrayOf(1))
            return session.run(mapOf("input_ids" to inputIds, "style" to style, "speed" to spd)).use { r ->
                val out = r.get(0) as OnnxTensor
                val fb = out.floatBuffer
                val samples = FloatArray(fb.limit())
                fb.get(samples)
                samples
            } ?: floatArrayOf()
        }

        override fun close() {
            session.close()
        }
    }
}
```

> 说明：Android 主实现 `KokoroEngine.Android(context)` 在 Task 6 中给出；此处先让 JVM 单测闭环。

- [ ] **Step 4: 运行验证通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroEngineTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/help/readaloud/playback/KokoroEngine.kt app/src/test/java/io/legado/app/help/readaloud/playback/KokoroEngineTest.kt
git commit -m "feat(kokoro): onnx synthesis engine with jvm test"
```

---

### Task 6: Android 版引擎 + WAV 合成器

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroEngine.kt`
- Create: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroTtsFileSynthesizer.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/playback/KokoroWavTest.kt`

- [ ] **Step 1: 写失败测试（PCM16→WAV 头）**

```kotlin
package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroWavTest {

    @Test
    fun `wav header is 44 bytes riff pcm mono 16bit`() {
        val wav = KokoroTtsFileSynthesizer.pcm16ToWav(byteArrayOf(1, 2, 3, 4), 24000)
        assertEquals(44 + 4, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(24000, ((wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8) or ((wav[26].toInt() and 0xff) shl 16) or ((wav[27].toInt() and 0xff) shl 24)))
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroWavTest"`
Expected: `Unresolved reference: KokoroTtsFileSynthesizer`

- [ ] **Step 3: 给 `KokoroEngine.kt` 补 Android 实现 + 合成器**

在 `KokoroEngine` interface 内增加一个 `Android` 实现（复用 Jvm 的逻辑，仅后端换成 android onnxruntime 相同 API；android jar 与 jvm jar 的 OrtSession API 一致）：

```kotlin
    class Android(
        context: android.content.Context,
        fileName: String = KokoroModelManager.ModelFile.ONNX,
        voiceName: String = KokoroModelManager.ModelFile.VOICE_ZF,
        tokenizerName: String = KokoroModelManager.ModelFile.TOKENIZER,
    ) : KokoroEngine {
        private val dir = KokoroModelManager.ensureAssets(context)
        private val delegate = Jvm(
            modelFile = File(dir, fileName),
            voiceBytes = File(dir, voiceName).readBytes(),
            textFrontend = TextFrontend.fromTokenizerJson(
                File(dir, tokenizerName).readText(),
            ),
        )
        override fun synthesize(text: String, speed: Float): FloatArray = delegate.synthesize(text, speed)
        override fun close() = delegate.close()
    }
```

新建 `KokoroTtsFileSynthesizer.kt`（挂到合成器层面，接口仿 SystemTtsFileSynthesizer）：

```kotlin
package io.legado.app.help.readaloud.playback

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 串行化 Kokoro 合成，输出 WAV 文件，供 HttpReadAloudService 的 ExoPlayer 播放。 */
class KokoroTtsFileSynthesizer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var engine: KokoroEngine? = null

    suspend fun synthesize(
        voiceKey: String = "zf_001",
        text: String,
        output: File,
        speed: Float = 1f,
    ): Boolean = mutex.withLock {
        runCatching {
            val e = ensureEngine()
            if (e == null) return@withLock false
            val samples = e.synthesize(text, speed)
            if (samples.isEmpty()) return@withLock false
            output.parentFile?.mkdirs()
            output.writeBytes(pcm16ToWav(KokoroVoice.toPcm16(samples), KokoroEngine.SAMPLE_RATE))
            output.length() > 0L
        }.getOrDefault(false)
    }

    suspend fun close() {
        mutex.withLock {
            engine?.close()
            engine = null
        }
    }

    private suspend fun ensureEngine(): KokoroEngine? {
        if (engine == null) {
            engine = KokoroEngine.Android(appContext)
        }
        return engine
    }

    companion object {
        fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val out = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            out.putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            out.put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return out.array()
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroWavTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/help/readaloud/playback/
git commit -m "feat(kokoro): android engine and wav file synthesizer"
```

---

### Task 7: 接入 HttpReadAloudService（5 处分支）

**Files:**
- Modify: `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt`
- Modify: `app/src/main/java/io/legado/app/domain/model/readaloud/ReadAloudSpeechModels.kt`

- [ ] **Step 1: 加引擎常量**

在 `ReadAloudSpeechModels.kt` companion object 加一行：

```kotlin
        const val ENGINE_KOKORO = "kokoro"
```

- [ ] **Step 2: 加 synthesizer 实例与 import**

在 `HttpReadAloudService.kt` 的 `systemTtsFileSynthesizer` 声明后加：

```kotlin
    private val kokoroTtsFileSynthesizer by lazy { KokoroTtsFileSynthesizer(this) }
```

import 块加：

```kotlin
import io.legado.app.help.readaloud.playback.KokoroTtsFileSynthesizer
```

- [ ] **Step 3: 主合成分支加 ENGINE_KOKORO（downloadAndPlayAudios）**

在 `when (routedVoice.engineType)` 里 `ReadAloudVoice.ENGINE_CLOUD -> { ... }` 分支后、`else ->` 前插入：

```kotlin
                                ReadAloudVoice.ENGINE_KOKORO -> {
                                    val output = getSpeakFileAsMd5(fileName)
                                    val globalRate = if (readAloudSettings.ttsFollowSys) {
                                        1f
                                    } else {
                                        (readAloudSettings.ttsSpeechRate + 5) / 10f
                                    }
                                    if (!kokoroTtsFileSynthesizer.synthesize(
                                            routedVoice.speakerId,
                                            speakText,
                                            output,
                                            globalRate,
                                        )
                                    ) {
                                        createSilentSound(fileName)
                                    }
                                }
```

- [ ] **Step 4: 预合成分支加 ENGINE_KOKORO（synthesizeSingleCue）**

在 `synthesizeSingleCue` 的 `when (routedVoice.engineType)` 里 `ReadAloudVoice.ENGINE_HTTP -> { ... }` 分支后、`else -> false` 前插入：

```kotlin
                ReadAloudVoice.ENGINE_KOKORO -> {
                    val output = getSpeakFileAsMd5(fileName)
                    val globalRate = if (readAloudSettings.ttsFollowSys) 1f
                    else (readAloudSettings.ttsSpeechRate + 5) / 10f
                    kokoroTtsFileSynthesizer.synthesize(
                        routedVoice.speakerId, speakText, output, globalRate,
                    )
                }
```

- [ ] **Step 5: 文件合成判定、缓存键、音色兜底各加分支**

- `hasFileSynthesisCue()` 的 `setOf(...)` 加 `ReadAloudVoice.ENGINE_KOKORO,`
- `sourceKeyForCue()` 的 when 里 `ReadAloudVoice.ENGINE_CLOUD -> {...}` 分支后加：

```kotlin
            ReadAloudVoice.ENGINE_KOKORO ->
                "kokoro:${routedVoice.id}:${routedVoice.revision}:${routedVoice.speakerId}:${routedVoice.traitsJson}"
```

- `voiceForCue()` 的 `supportedEngineTypes` 集合加 `ReadAloudVoice.ENGINE_KOKORO,`

- [ ] **Step 6: onDestroy 顺带关闭 kokoro 引擎**

在 `onDestroy()` 的 `systemTtsFileSynthesizer.close()` 后加：

```kotlin
            kokoroTtsFileSynthesizer.close()
```

- [ ] **Step 7: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 单测套装仍然全绿**

Run: `.\gradlew.bat :app:testAppDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/legado/app/service/HttpReadAloudService.kt app/src/main/java/io/legado/app/domain/model/readaloud/ReadAloudSpeechModels.kt
git commit -m "feat(kokoro): route ENGINE_KOKORO through http read aloud pipeline"
```

---

### Task 8: 引擎路由（ReadAloud.getReadAloudClass）

**Files:**
- Modify: `app/src/main/java/io/legado/app/model/ReadAloud.kt`

- [ ] **Step 1: 加分支**

在 `getReadAloudClass()` 里，`GSON.fromJsonObject<ReadAloudEngineSelection>(ttsEngine)...ENGINE_CLOUD` 分支之后、`ttsEngine.isNullOrBlank()` 分支之前插入：

```kotlin
        GSON.fromJsonObject<ReadAloudEngineSelection>(ttsEngine).getOrNull()
            ?.takeIf { it.engineType == ReadAloudVoice.ENGINE_KOKORO }
            ?.let { selection ->
                coordinatorDefaultEngineType = selection.engineType
                coordinatorDefaultEngineId = selection.engineId
                coordinatorDefaultSpeakerId = selection.speakerId
                httpTTS = HttpTTS(
                    id = Long.MIN_VALUE,
                    name = selection.displayName.ifBlank { "Kokoro TTS" })
                return HttpReadAloudService::class.java
            }
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/legado/app/model/ReadAloud.kt
git commit -m "feat(kokoro): route engine selection to http service for kokoro"
```

---

### Task 9: 设置页选/Kokoro 音色（ReadAloudDelegate + CloudTts 页）

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadAloudDelegate.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/readaloud/cloudtts/CloudTtsViewModel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/readaloud/cloudtts/CloudTtsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 加字符串资源**

在 `app/src/main/res/values/strings.xml` 加：

```xml
    <string name="kokoro_engine_name">Kokoro 中文 (本地神经网络)</string>
    <string name="kokoro_engine_summary">离线中文神经语音，内置模型</string>
    <string name="cloud_tts_kokoro_engines">Kokoro (Local)</string>
```

- [ ] **Step 2: ReadAloudDelegate.syncConfiguredTtsVoices 注册音色**

在 `buildList { ... }` 里 `httpTtsList.forEach { ... }` 之后加：

```kotlin
                // 内置 Kokoro 本地音色（后续可扩展为可下载多个音色）
                add(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_KOKORO,
                        engineId = "kokoro-lite",
                        speakerId = "zf_001",
                        displayName = context.getString(R.string.kokoro_engine_name),
                        sourceRevision = 1L,
                    )
                )
```

- [ ] **Step 3: CloudTtsViewModel 加 Kokoro 引擎条目的列表项**

在 `systemEngineItems()` 里的 `add(...)` 后追加一个 Kokoro 条目：

```kotlin
        add(
            TtsManagedEngineItemUi(
                engineType = ReadAloudVoice.ENGINE_KOKORO,
                engineId = "kokoro-lite",
                title = application.getString(R.string.kokoro_engine_name),
                summary = application.getString(R.string.kokoro_engine_summary),
                selected = isDefaultEngine(ReadAloudVoice.ENGINE_KOKORO, "kokoro-lite"),
            )
        )
```

- [ ] **Step 4: CloudTtsViewModel 判断/写入默认引擎**

- `isDefaultEngine()` 的 `when(engineType)` 里 `ReadAloudVoice.ENGINE_SYSTEM -> ...` 分支后加：

```kotlin
            ReadAloudVoice.ENGINE_KOKORO ->
                GSON.fromJsonObject<ReadAloudEngineSelection>(value)
                    .getOrNull()?.let { it.engineType == engineType && it.engineId == engineId } == true
```

- `setDefaultEngine()` 的 when 里 `ReadAloudVoice.ENGINE_CLOUD -> {...}` 分支后加：

```kotlin
            ReadAloudVoice.ENGINE_KOKORO -> {
                val speakerId = "zf_001"
                GSON.toJson(
                    ReadAloudEngineSelection(
                        engineType = engineType,
                        engineId = engineId,
                        speakerId = speakerId,
                        displayName = application.getString(R.string.kokoro_engine_name),
                    )
                ) to application.getString(R.string.kokoro_engine_name)
            }
```

- **注意**：Kokoro 无需 voices 检查（内置音色始终可用），因此与 CLOUD 分支不共享 `voices.firstOrNull` 前置校验，直接构造。

- `engineName()` 里加兜底分支（在 `else -> engineId` 前）：

```kotlin
        ReadAloudVoice.ENGINE_KOKORO -> application.getString(R.string.kokoro_engine_name)
```

- [ ] **Step 5: CloudTtsScreen 渲染引擎列表（system 段后加 Kokoro 段）**

在 `items(state.systemEngines, ...)` 块之后、LazyColumn 收尾前加：

```kotlin
                    item { EngineSectionTitle(R.string.cloud_tts_kokoro_engines) }
                    items(state.kokoroEngines, key = { "kokoro:${it.engineId}" }) { engine ->
                        TinyClickableSettingItem(
                            title = engine.title,
                            description = listOf(
                                engine.summary,
                                stringResource(R.string.read_aloud_current_default_summary).takeIf { engine.selected },
                            ).filterNotNull().filter(String::isNotBlank).joinToString(" | "),
                            onClick = {
                                onIntent(
                                    CloudTtsIntent.SetDefaultEngine(
                                        engine.engineType,
                                        engine.engineId,
                                    )
                                )
                            },
                        )
                    }
```

- [ ] **Step 6: CloudTtsContract 加 kokoroEngines 状态**

在 `CloudTtsContract.kt` 的 `systemEngines` 声明后加：

```kotlin
    val kokoroEngines: ImmutableList<TtsManagedEngineItemUi> = persistentListOf(),
```

并在 ViewModel `refreshEngineSelection()` 的 `systemEngines = systemEngineItems(),` 后更新：

```kotlin
                kokoroEngines = buildList {
                    add(
                        TtsManagedEngineItemUi(
                            engineType = ReadAloudVoice.ENGINE_KOKORO,
                            engineId = "kokoro-lite",
                            title = application.getString(R.string.kokoro_engine_name),
                            summary = application.getString(R.string.kokoro_engine_summary),
                            selected = isDefaultEngine(ReadAloudVoice.ENGINE_KOKORO, "kokoro-lite"),
                        )
                    )
                }.toImmutableList(),
```

> 说明：`kokoroEngines` 无 DB 持久化，直接由 UI state 提供即可。同步入口 `syncConfiguredTtsVoices`（Step 2）负责把音色写进 voices 目录供多角色路由使用。

- [ ] **Step 7: 编译 + 单测验证**

Run: `.\gradlew.bat :app:compileAppDebugKotlin`
Expected: `BUILD SUCCESSFUL`

Run: `.\gradlew.bat :app:testAppDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/legado/app/ui/book/read/ReadAloudDelegate.kt app/src/main/java/io/legado/app/ui/book/readaloud/cloudtts/ app/src/main/res/values/strings.xml
git commit -m "feat(kokoro): expose Kokoro engine and voice in read-aloud settings"
```

---

### Task 10: 模型下载器（其他音色 .bin，复用镜像下载）

**Files:**
- Modify: `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroModelManager.kt`
- Test: `app/src/test/java/io/legado/app/help/readaloud/playback/KokoroVoiceTest.kt`（追加用例，纯文件逻辑、不联网）

- [ ] **Step 1: 加纯逻辑单测（音色列表/解析不依赖网络）**

在 `KokoroVoiceTest` 追加：

```kotlin
    @Test
    fun `voice name parsing from file name`() {
        assertEquals("zf_001", KokoroModelManager.voiceNameFrom("kokoro/voices-zf_001.bin"))
    }

    private fun assertEquals(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
```

- [ ] **Step 2: 运行失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroVoiceTest"`
Expected: `Unresolved reference: voiceNameFrom`

- [ ] **Step 3: 实现下载器（封装 OkHttp，镜像优先）**

在 `KokoroModelManager` 加：

```kotlin
    private const val HUB = "HuggingFace hub repo (onnx-community/Kokoro-82M-v1.1-zh-ONNX)"
    private const val MIRRORS = listOf(
        "https://hf-mirror.com/onnx-community/Kokoro-82M-v1.1-zh-ONNX/resolve/main/",
        "https://huggingface.co/onnx-community/Kokoro-82M-v1.1-zh-ONNX/resolve/main/",
    ).joinToString()  // 占位——实际用 okHttpClient

    fun voiceNameFrom(assetPath: String): String =
        assetPath.substringAfterLast('/').removePrefix("voices-").removeSuffix(".bin")
```

详见实施时的联网下载函数（下载到 `context.filesDir/kokoro/voices-<name>.bin`，镜像逐个重试，`.part` 断点）。

- [ ] **Step 4: 单测通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.readaloud.playback.KokoroVoiceTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/legado/app/help/readaloud/playback/KokoroModelManager.kt
git commit -m "feat(kokoro): extra voice bin downloader scaffolding"
```

---

### Task 11: 端到端验证与文档

**Files:**
- Modify: `docs/superpowers/specs/2026-08-17-kokoro-tts-integration-design.md`（实现状态标注）

- [ ] **Step 1: 全量单元测试**

Run: `.\gradlew.bat :app:testAppDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 编译 release（不开 R8 的 noR8 变体跑一遍避免压缩误伤）**

Run: `.\gradlew.bat assembleAppNoR8`
Expected: `BUILD SUCCESSFUL`（APK 含模型；arm64-v8a/armeabi-v7a 双 ABI）

- [ ] **Step 3: 真机冒烟**

- 安装 debug APK → 打开任意书 → 阅读页菜单 → 朗读设置 → 引擎列表出现 "Kokoro 中文" → 点选设为默认 → 播放朗读出声，段落间连续、翻章正常。
- 验证默认引擎切换后 `ReadAloud.upReadAloudClass()` 重启服务用新引擎。

- [ ] **Step 4: 更新 spec 状态**

在 spec 文档头部状态行改为 `状态：已实现（见 docs/superpowers/plans/2026-08-17-kokoro-tts-integration.md）`，并提交。

```bash
git add docs/
git commit -m "docs(kokoro): mark integration implementation complete"
```

---

### 自审记录（写入计划时完成）

- **Spec 覆盖**：播放路径（Task 6/7）✓；内置+可下载（Task 2/10）✓；单模型多音色（Task 4/10）✓；逐段合成（Task 7 逐 cue 合成 + 510 token 截断 TextFrontend）✓；语速映射 speed（Task 5/7 的 `(ttsSpeechRate+5)/10f`）✓；pinyin4j（Task 3）✓。
- **占位符**：Task 10 涉及联网下载，实现细节需在实施时用 `okHttpClient` 补齐（该处明确标注）；其余每个变更给出完整代码。
- **类型一致**：`KokoroEngine`（interface + Jvm/Android）、`KokoroTtsFileSynthesizer`、`KokoroModelManager`、`KokoroVoice` 各 Task 间签名一致；`ENGINE_KOKORO`、`kokoro-lite`、`zf_001` 常量引用一致。