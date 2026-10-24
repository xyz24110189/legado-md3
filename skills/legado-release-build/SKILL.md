---
name: legado-release-build
description: Use when building or signing a release APK in the legado-with-MD3 project, when gradle fails at kspAppReleaseKotlin with "this and base files have different roots" or glide-svg errors, when --offline build fails with missing cached dependencies, or when assembleRelease task names are ambiguous.
---

# legado-with-MD3 release 构建与签名

## Overview

本项目的 release 构建有三个已知坑：KSP 在 Windows 的 bug、离线依赖陷阱、签名属性。
核心原则：**构建命令必须带 `-Pksp.incremental=false`；不要用 `--offline`。**

## 标准构建命令

```bash
# 签名 release 包（必须带 ksp.incremental=false，否则 kspAppReleaseKotlin 必挂）
gradlew.bat assembleRelease -Pksp.incremental=false ^
  -PRELEASE_STORE_FILE=D:\Users\xyz24\legado_release_new.jks ^
  -PRELEASE_STORE_PASSWORD=legado-2026-release ^
  -PRELEASE_KEY_ALIAS=legado ^
  -PRELEASE_KEY_PASSWORD=legado-2026-release
```

产物：`app/build/outputs/apk/app/release/app-app-{arm64-v8a,armeabi-v7a,universal}-release.apk`
（项目按 ABI split，arm64 约 294MB。验证签名：
`build-tools\<ver>\apksigner.bat verify --print-certs <apk>`）

## 已知坑与对策

| 坑 | 症状 | 对策 |
|----|------|------|
| KSP Windows bug | `Execution failed for task ':app:kspAppReleaseKotlin'` + `IllegalArgumentException: this and base files have different roots ... glide-svg-4.0.2-api.jar!...` | 加 `-Pksp.incremental=false`。**每次**构建都要加，换 props 后配置缓存失效会复发 |
| `--offline` 缺依赖 | `No cached version of sh.calvin.reorderable:reorderable-android ... available for offline mode` 等 | 不用 `--offline`，联网让 gradle 下载 |
| 任务名歧义 | `Cannot locate tasks that match ':app:compileReleaseKotlin' as task 'compileReleaseKotlin' is ambiguous` | 用完整名 `:app:compileAppReleaseKotlin`（有 flavor 前缀 App） |
| 编译太慢超时 | 单次 Kotlin 编译可超 10 分钟 | 用 CLAUDE.md 里的 `:app:compileAppDebugKotlin` 做快速编译检查 |
| JAVA_HOME 无效 | apksigner/gradlew 报 invalid directory | `$env:JAVA_HOME='D:\Users\xyz24\jdk21\jdk-21.0.6+7'` |

## 签名

- keystore：`D:\Users\xyz24\legado_release_new.jks`（alias `legado`，有效期 30 年）
- **新签名与旧包不一致 ⇒ 覆盖安装前必须先卸载旧版**
- 想固化签名：把四个 RELEASE_* 属性写进 `gradle.properties`（项目根或 `~/.gradle/`），
  之后构建不用每次传参。build.gradle.kts 只在 `hasProperty("RELEASE_STORE_FILE")` 时才挂签名。

## 变体速查

| 变体 | 混淆 | 用途 |
|------|------|------|
| `release` | R8 + shrinkResources ✅ | 正式发布（sherpa keep 规则见 sherpa-onnx-r8-jni skill） |
| `noR8` | ❌ | 崩溃 A/B 验证、排查混淆问题 |
| `debug` | ❌，applicationId 带 `.debug` 后缀 | 日常开发 |

## Common Mistakes

- "改了代码没重新构建就说修好了" → 本会话真实教训：edit 引入的编译错误只有真正编译才暴露
- "构建失败一定是我的代码错了" → 先看失败任务是 KSP/R8 还是 compile；KSP 报 glide-svg 是工具链 bug
