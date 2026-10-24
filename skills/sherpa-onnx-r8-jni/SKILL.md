---
name: sherpa-onnx-r8-jni
description: Use when a release/minified Android build crashes (SIGABRT or instant crash) when loading or switching sherpa-onnx models, when JNI throws UnsatisfiedLinkError, or when model/provider/config changes fail to fix the crash. Also use before enabling R8 minification in any project that includes sherpa-onnx.
---

# sherpa-onnx × R8 混淆：JNI 按名反射崩溃

## Overview

sherpa-onnx 的 JNI 层**按名字**（GetFieldID/GetMethodID）反射读取 Kotlin 数据类的字段和方法
（`OfflineTtsConfig.model.provider`、`OfflineTtsVitsModelConfig.vits/lexicon/dictDir`、native 方法 `newFromAsset/newFromFile` 等）。

R8 重命名这些类和字段后，原生代码拿到 null field ID 继续使用 → ART 直接 **SIGABRT**，
**Java try/catch 完全无法捕获** → 表现为"一切到该模型就闪退"。

核心原则：**sherpa-onnx 的 aar 自带 consumer proguard 规则是空文件，keep 规则必须由宿主 app 提供。**

## 必做的 keep 规则

加入 app 的 proguard-rules.pro：

```proguard
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
```

## 症状识别

| 症状 | 结论 |
|------|------|
| release 崩，debug 正常（debug 默认 isMinifyEnabled=false） | 几乎必然是本问题 |
| 三个模型全崩，与选哪个模型无关 | 崩在解析 config 那一步，先查混淆 |
| 改 provider（nnapi/xnnpack/cpu）、改模型配置全部无效 | 方向错了，回来看混淆 |
| catch (Throwable) 拦不住、没有 toast 只有闪退 | 原生 SIGABRT，不是 Java 异常 |

## 本项目事实（2026-08 排查结论）

- `app/build.gradle.kts`：release 开启 `isMinifyEnabled = true` + `isShrinkResources = true`
- `app/libs/sherpa-onnx-1.13.6.aar` 解压后 `proguard.txt` 为 **0 字节**
- 已在 `app/proguard-rules.pro` 末尾追加上述 keep 规则（勿删）
- debug 与 `noR8` 变体不混淆；项目内置 `noR8` buildType 可用于 A/B 验证根因：
  旧代码 + noR8 不崩、旧代码 + release 崩 ⇒ 混淆问题实锤

## Common Mistakes

| 误判 | 真相 |
|------|------|
| "模型文件损坏/不是官方的" | 用 SHA256 对比后再下结论；本项目曾对比为字节一致 |
| "NNAPI 导致崩溃，改成 cpu 就好" | cpu 也崩 ⇒ 与 provider 无关 |
| "初始化失败是 Java 异常，加个 fallback 就行" | SIGABRT 发生在 native 层，fallback 永远执行不到 |

## Real-World Impact

本项目排查耗时多轮：反复改 provider/config 无效，最终定位为混淆。
一条 keep 规则即修复。遇到"release 独有崩溃"，第一反应查混淆规则。
