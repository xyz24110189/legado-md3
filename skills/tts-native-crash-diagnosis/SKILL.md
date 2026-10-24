---
name: tts-native-crash-diagnosis
description: Use when the app crashes (闪退) when switching or using built-in TTS models, when a crash survives try/catch and shows no toast, when PiperEngine init fails for all models, or before proposing ANY fix for a TTS engine crash without logs.
---

# TTS 原生崩溃取证纪律

## Overview

**没有日志就没有修复。** 本项目曾连续多轮盲改（provider、模型配置、加载方式）全部无效，
因为崩溃根因在完全不同的层。核心原则：先取证定位层级，再动手。

## 第一步：判断崩溃类型

| 类型 | 特征 | 能否 try/catch |
|------|------|----------------|
| Java 异常 | 有 toast / AppLog 记录 / 堆栈 | ✅ |
| 原生 SIGABRT/SIGSEGV | 无任何 Java 日志，进程直接消失，logcat 见 `Fatal signal`/tombstone | ❌ |
| OOM 被杀 | logcat 见 lowmemorykiller / `OutOfMemory` | ❌ |

`PiperTtsFileSynthesizer.acquireEngine` 已 catch Throwable ⇒ **凡是被它包住的初始化失败只会 toast，
不会闪退。闪退必来自 native 层或调用链之外。**

## 日志取证路径（按优先级）

1. **adb logcat**：`adb devices` 先确认设备在线；抓 `Fatal signal`、`DEBUG`、tombstone。
   本项目 adb 在 `D:\Users\xyz24\AppData\Local\Android\Sdk\platform-tools\adb.exe`
2. **AppLog 落盘**：`<externalCacheDir>/logs/appLog-<yy-MM-dd>.txt`
   （需在应用设置开启"记录日志"；LogUtils.kt 的 AsyncFileHandler 写入）
3. **引擎诊断文件**：初始化失败时写入 `<externalCacheDir>/files/piper_diag.txt`（含 preflight 与堆栈）
4. 都拿不到时，让用户装 `noR8` 变体做 A/B，或连 USB 再查

## 模型配置事实（勿再猜）

- `xiao_ya` / `chaowen`：标准 piper（`.onnx.json` 里 `"espeak":{"voice":"zh"}`、pinyin），
  配置 = model + tokens + `dataDir=espeak-ng-data`，**不需要** lexicon/dictDir
- `baker`：matcha-icefall-zh-baker（扩散声学模型 + Vocos 声码器），目录含**两个 onnx**
  （acoustic `model-steps-3.onnx` + `vocos-22khz-univ.onnx`），
  配置 = `OfflineTtsMatchaModelConfig(acousticModel, vocoder, lexicon, tokens)`，
  官方 demo 不用 dictDir；引擎按 `PiperModelManager.ModelKind` 区分家族
- 加载方式用 `assetManager = context.assets` + asset 相对路径（sherpa-android 官方做法），不要提取到 filesDir

## ruleFsts 的路径形式铁律（2026-08 踩坑实锤）

`OfflineTts(assetManager=assets, config)` 模式下，`config.ruleFsts` **必须传 asset 相对路径**
（如 `piper/baker/date.fst`，原生层经 AAssetManager 读取——官方 SherpaOnnxTtsEngine demo 即此做法）。
**传绝对文件路径 → 原生层按 asset 打开失败 → SIGABRT 不可捕获 → 切模型闪退。**
本项目曾提取 fst 到 filesDir 再传绝对路径导致全模型闪退，回退后稳定；
改用官方姿势后数字/日期/电话朗读正常。勿再走"提取到 filesDir"的回头路。

## provider 自愈机制的坑

NNAPI 初始化失败常是**原生崩溃**，catch 不到。自愈标记必须用 `SharedPreferences.commit()`
（同步落盘）：`apply()` 是异步写，进程被 SIGABRT 杀死前很可能没落盘 ⇒ 标记丢失 ⇒
下次启动又走 NNAPI 又崩 ⇒ "每次都崩"的假象。本项目已改为 commit()，勿改回。

## Red Flags - 出现这些立刻停下回到取证

- 连续第 2 次在没有日志的情况下修改代码
- "应该是 NNAPI / 应该是模型坏了 / 应该是 xx"，但没有证据
- 修复后用户报告"还是崩"，而你上一轮也是猜的
- 3 次修复无效 ⇒ 停止逐症状打补丁，质疑架构假设（见 superpowers:systematic-debugging Phase 4.5）

## Common Mistakes

| 借口 | 现实 |
|------|------|
| "没设备连 adb，只能猜" | 还有 AppLog 文件、piper_diag.txt、noR8 A/B 三条路 |
| "模型重新下载就好了" | 本项目 SHA256 对比过：新旧字节一致，重下载零价值 |
| "改完应该能行，让用户试试" | 至少跑一次 `:app:compileAppDebugKotlin` 验证编译（本会话 edit 曾丢换行导致注释吞掉整行 val，编译才暴露） |
