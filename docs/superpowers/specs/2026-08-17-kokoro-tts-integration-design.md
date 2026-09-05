# Kokoro 中文神经 TTS 集成进 legado-with-MD3 — 设计文档

> ⚠️ **已废弃**：Kokoro 引擎已从代码库中移除。当前 TTS 仅使用 sherpa-onnx（小雅、超雯、Baker 三组音色）。本文档仅保留作为历史记录。

日期：2026-08-17
状态：**已废弃**（Kokoro 引擎已移除，当前仅用 sherpa-onnx）

## 1. 目标

将 Kokoro 82M 中文神经 TTS（onnxruntime-android 推理，输出 PCM 音频）作为**本地朗读引擎**集成进 legado-with-MD3 阅读器，使用户在朗读设置中可选 Kokoro 音色，实现离线高质量中文 TTS。

## 2. 背景与已验证事实

- Kokoro 中文模型为 onnx（`model_uint8.onnx`，int8，约 127MB）。
- 每个音色一只 `.bin` 文件（如 `zf_001.bin`，522,240 字节 = 510 组 × 256 float32 style 向量）。
- `tokenizer.json`（4,944 字节）提供音素→id 映射。
- 模型 IO（已在 JVM probe 中实际推理确认）：

  ```
  input_ids : int64  [1, N]       首尾补 pad(0)，N ≤ 510
  style     : float32 [1, 256]    音色文件第 len(tokens) 组
  speed     : float32 [1]         朗读速度（0.5~2.0）
  → waveform : float32 [N]        PCM 音频（float32）
  → duration : int64   [N]        每帧时长（未用）
  ```

- Windows 上 onnxruntime-jvm >= 1.21 与部分 JDK 的 VC 运行库冲突导致 `ERROR_DLL_INIT_FAILED`，JVM 单测须用 **onnxruntime-jvm 1.20.0**；Android 运行时（`onnxruntime-android` 1.27.0）不受影响。

## 3. 集成架构

**复用 `HttpReadAloudService` 的文件合成管线**，Kokoro 作为新的"本地文件合成引擎"。不新建 Service、不改 AndroidManifest。

```
朗读请求 → PrepareChapterSpeechPlan → ReadAloudPlaybackQueue (cue)
   ↓
HttpReadAloudService.downloadAndPlayAudios()
   ↓ when(routedVoice.engineType)
ENGINE_KOKORO 分支
   ↓
KokoroTtsFileSynthesizer (仿 SystemTtsFileSynthesizer)
   → 句子级分段(≤510 token) → TextFrontend 音素化
   → onnxruntime 推理 → float32→int16 PCM → WAV 落盘(cache)
   ↓
现有 ExoPlayer 播放 WAV + 现有缓存/预合成/进度/翻章逻辑
```

### 3.1 两条现成管线对比

| | TTSReadAloudService | HttpReadAloudService（选用） |
|---|---|---|
| 引擎 | Android 系统 TextToSpeech | httpTTS / 云TTS / 系统TTS文件合成 |
| 音频 | 系统内部出声（无 AudioTrack） | 合成→文件→ExoPlayer |
| 多角色/缓存/预合成 | ✗ | ✓（全有） |
| 集成 Kokoro 改动面 | 大（需自建） | 小（~6 处） |

## 4. 设计决策（已确认）

1. **播放路径**：合成为 WAV 文件 → 现有 ExoPlayer 播放（主用，改动最稳）。
2. **模型分发**：int8 模型 + zf_001.bin + tokenizer.json **内置进 APK assets**（APK 增 ~130MB），安装即用；设置页支持额外下载其他音色 `.bin`。
3. **音色**：单模型多音色 bin。`zf_001`（中文女声）内置，其余可下载。
4. **长文**：逐段合成；段落文本按句切块，保证单次推理 token ≤ 510（模型 context 512）。
5. **语速**：legado `ttsSpeechRate` 映射为 onnx `speed` 输入（0.5~2.0），同一声调档位。
6. **注音前端**：移植现有 `TextFrontend`（pinyin4j，已通过 probe 验证）。

## 5. 改动清单

### 5.1 新增

| 文件 | 内容 |
|---|---|
| `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroTtsFileSynthesizer.kt` | 引擎核心：上下文、分段、推理、PCM→WAV。接口仿 `SystemTtsFileSynthesizer`（suspend synthesize） |
| `app/src/main/java/io/legado/app/help/readaloud/playback/KokoroEngine.kt`（可选拆分） | onnxruntime 会话、输入组装、`style` 向量读取 |
| 移植：`TextFrontend`（汉字→注音→token、数字转汉字、截断） | 逻辑不改，包名改 `io.legado.app.help.readaloud.playback` |
| 移植：模型/音色管理逻辑（下载、镜像、断点续传、校验） | 适配 legado 的目录/仓库惯例 |

### 5.2 修改

| 位置 | 改动 |
|---|---|
| `domain/model/readaloud/ReadAloudSpeechModels.kt` | `ReadAloudVoice` companion 加 `ENGINE_KOKORO = "kokoro"`；音色目录条目（zf_001 等） |
| `service/HttpReadAloudService.kt` | `downloadAndPlayAudios` / `synthesizeSingleCue` / `hasFileSynthesisCue` / `sourceKeyForCue` / `voiceForCue` 加 `ENGINE_KOKORO` 分支 |
| `model/ReadAloud.kt` | `getReadAloudClass()` 命中 Kokoro → 返回 `HttpReadAloudService` |
| `ui/book/readaloud/cloudtts/CloudTtsViewModel.kt` / `CloudTtsScreen.kt` | 支持选择/下载 Kokoro 引擎与音色（仿 `ENGINE_SYSTEM` 的 `SelectItem` 形式） |
| `ui/book/read/ReadAloudDelegate.kt` | `syncConfiguredTtsVoices()` 把 Kokoro 音色加入目录 |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | 加 `com.microsoft.onnxruntime:onnxruntime-android`(1.27.0)、`pinyin4j`；模型 assets 配置（noCompress） |

### 5.3 不动的部分

- `BaseReadAloudService`：所有进度/通知/翻章逻辑复用，不在播放层复制。
- `HttpReadAloudService` 的 httpTTS/云TTS 分支、`CloudTtsProvider` 抽象、`SpeechVoiceRouter`、缓存索引：全部不动。

## 6. 音频与采样率

- onnx 输出 `waveform` float32 → 转 int16（`Short.MAX_VALUE * sample`）。
- 模型采样率 24000 Hz，单声道。
- WAV 头：RIFF + PCM (16-bit, mono, 24000)。

## 7. 错误处理

- 合成失败 → 返回失败信号，交现有 `HttpReadAloudService` 的段落错误处理（跳过/停止由用户配置决定）。
- 模型缺失/损坏 → 合成前校验大小，失败提示"下载/导入模型"。
- `speed` 越界 → clamp 到 [0.5, 2.0]。

## 8. 测试

- **kotlin 单测（JVM）**：
  - `TextFrontendTest`：音素映射、数字转汉字、截断、标点（移植现有）。
  - `KokoroTtsEngineTest`：对短句（如"你好，世界"）真实推理产出非空 WAV（JVM 用 `onnxruntime-jvm` 1.20.0，规避 VC 冲突）。模型路径用测试资源或环境变量注入。
- **Android 集成**：真机朗读设置选 Kokoro → 出声 + 翻章正常。

## 9. 范围外（YAGNI）

- 不实现 AudioTrack 实时流播。
- 不移植 misaki。
- 不实现云端 kokoro。
- 不实现多方言模型（仅中文）。