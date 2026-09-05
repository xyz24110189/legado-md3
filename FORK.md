# Legado MD3 Fork 说明

## 与上游的关系

本仓库是 [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) 的非官方分支（fork）。
`legado-with-MD3` 本身 fork 自 [gedoor/legado](https://github.com/gedoor/legado)。
继承了所有上游核心功能（书源、阅读、替换规则、书架上手等），并在此基础上进行了
大量重构和新增。

## 分支独有变更

### 架构重构
- **MD3 主题系统**：使用 Material Design 3 + MaterialKolor + Monet 引擎全面重构
- **Jetpack Compose 迁移**：从传统 View 逐步迁移至 Compose（遵循 MVI/UDF 架构）
- **配置架构治理**：引入 Gateway 模式，`verifyConfigArchitecture` 护栏防止配置回退
- **Clean Architecture**：严格分层（data/domain/ui），禁止 UI 层直连 DAO

### 功能新增
- **有声书重构**：基于 sherpa-onnx 的本地 TTS 引擎，内置小雅、超雯、Baker 3 组音色
- **阅读记录增强**：时间轴视图、章节维度统计、阅读数据导出
- **书籍备注**：支持手动添加和自动同步的书籍备注
- **智能伴生分组**：自动归类已读/未读书籍
- **手柄支持**：手柄上下翻页、按键映射配置
- **发现页重构**：分类浏览、热门推荐
- **漫画阅读增强**：多页预览、缩放支持
- **书架布局增强**：多布局选择、平板端专门优化

### 依赖升级
- `compileSdk` / `targetSdk` 37
- Kotlin 2.4.0, AGP 9.2.1
- Navigation 3 (类型安全路由)
- Koin 4.2.2 (DI)

## 分支包名

- 命名空间：`io.legado.app`
- ApplicationId：`io.legado.md3`
- 调试包名：`io.legado.md3.debug`

## 构建

```bash
# 调试构建
./gradlew assembleAppDebug

# 关闭 R8 的调试构建（用于崩溃排查）
./gradlew assembleAppNoR8

# 仅编译 Kotlin（快速验证）
./gradlew :app:compileAppDebugKotlin
```

## 上游同步策略

1. 通过 git merge 策略同步上游变更
2. 分支独有代码存放在 `app/src/main/java/io/legado/app/` 的对应包下
3. `modules/` 目录为独立模块，上游和分支共用
4. 合并冲突优先保留分支架构（Completable、Gateway 模式）

## 注意事项

- 由于 Monet 引擎重构主题系统，官方版本的主题在此版本中不兼容
- Android 12 以下设备暂不支持自定义主题和动态取色
- 本分支已移除原 `io.legato.kazusa` 包名（该包名为上游遗留错误）
