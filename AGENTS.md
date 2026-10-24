# AGENTS.md

## 先读这些

- `CLAUDE.md` — 架构、Compose/MVI 规范、依赖注入、导航、主题、硬性约束（总纲，勿重复实现）。
- `skills/legado-release-build/SKILL.md` — release 构建/签名的已知坑。
- `skills/legado-github-release/SKILL.md` — 用户说"发版"时按此执行（tag 触发 CI 发布）。
- `skills/sherpa-onnx-r8-jni/SKILL.md` — release 独有闪退优先查 R8 keep 规则。
- `skills/tts-native-crash-diagnosis/SKILL.md` — TTS 闪退必须先取证再改代码。

## 常用命令

```bash
# 最快的编译验证（改完 Kotlin 必跑一次）
.\gradlew.bat :app:compileAppDebugKotlin

# release（必须带 ksp.incremental=false，否则 :app:kspAppReleaseKotlin 必挂）
cmd /c ".\gradlew.bat assembleAppRelease -Pksp.incremental=false"

# 不混淆版本（排查 release 独有崩溃的 A/B 对照）
.\gradlew.bat assembleAppNoR8

.\gradlew.bat test          # JVM 单测（CI 跑的就是这个）
.\gradlew.bat lint

# 只跑单个单测（单测都在 app/src/test，无 book/rhino 单测）
.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.utils.HtmlFormatterTest"
```

- **所有 app 任务带 `App` flavor 前缀**：`assembleAppRelease`、`compileAppDebugKotlin`；写 `assembleRelease`/`compileReleaseKotlin` 会报 ambiguous 或找不到。
- PowerShell 直接把 `-Pksp.incremental=false` 传进 `gradlew.bat` 会被解析成任务名；用参数数组传，或改用 `cmd /c`。
- JDK 21：若 `JAVA_HOME` 指向 17 会构建失败，设 `$env:JAVA_HOME='D:\Users\xyz24\jdk21\jdk-21.0.6+7'`。SDK 在 `D:\Users\xyz24\AppData\Local\Android\Sdk`。
- 不要用 `--offline`（缓存不全必失败）。

## 构建产物与变体

- 包名 `io.legado.md3`；debug 为 `io.legado.md3.debug`。namespace 是 `io.legado.app`。
- 默认按 ABI split（`armeabi-v7a`/`arm64-v8a` + universal），可用 `-PenableAbiSplits=false` 关闭。
- 产物：`app/build/outputs/apk/app/release/app-app-<abi>-release.apk`；mapping 在 `app/build/outputs/mapping/appRelease/mapping.txt`（约 250MB，别往仓库塞）。
- 版本号：默认读 `app/version.properties`；`APP_VERSION_NAME` / `COMMIT_NUMBER` 环境变量可覆盖（versionCode = 10000 + COMMIT_NUMBER）。
- `:baselineprofile`（目录 `baselineProfile/`）生成 Baseline Profile，需要连接设备，别放进常规构建或 CI。

## 签名与本地必需文件（均被 .gitignore）

- `app/google-services.json` — google-services 插件必需，缺失则任何 app 构建失败。
- 签名只在存在 `RELEASE_STORE_FILE` 属性时启用；本地库为 `D:\Users\xyz24\legado_release_new.jks`（alias `legado`）。换签名后覆盖安装前必须卸载旧版。
- 为 CI 特意入库、**勿删勿重新 ignore** 的二进制：`gradle/wrapper/gradle-wrapper.jar`、`app/cronetlib/*.jar`、`app/libs/sherpa-onnx-1.13.6.aar`。
- **未入库**的大文件：`app/src/main/assets/piper/`（TTS 模型，~267MB，本地需自备，release 构建必需）、`sherpa-onnx-models/`（上游源码+模型副本，仅参考，不属于本工程）。

## CI/CD（GitHub Actions）

- `.github/workflows/ci.yml`：`main` push / PR → 解码 `google-services.json` + `:app:compileAppDebugKotlin` + `test`。
- `.github/workflows/release.yml`：push 形如 `3.2.1` / `v3.2.1` 的 tag → 下载 Piper 模型（校验 SHA256）→ 签名构建 → 发布 Release（3 个 APK + `mapping.txt`）。
- 发版流程：改 `app/version.properties` → commit → `git tag 3.2.1; git push origin 3.2.1`。可在 `.github/release-notes/<version>.md` 预置说明，否则用自动生成。
- 仓库 Secrets：`GOOGLE_SERVICES_JSON`（json 的 base64）、`SIGNING_KEY`（jks 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。缺失时 CI 在解码步骤直接失败。
- Piper 模型托管在 Release `deps-piper-v1`（asset `piper-assets-v1.tar.gz`）；更新模型需发布新 asset 并同步改 `release.yml` 里的 URL 与 SHA256。

## 容易踩的其它点

- 不要升级 jsoup（>1.16.2 破坏 `AnalyzeByJSoup`/JsoupXpath）与 hutool（>5.8.22）。
- `gradle.properties` 里配置缓存的开关有两条（第 31 行 legacy 关闭、第 48 行开启）；CI 显式 `--no-configuration-cache`，别依赖默认值。
- `modules/web/` 是独立 Vue 3 前端（pnpm），不参与 Android 构建；`docs/` 是 VitePress。
