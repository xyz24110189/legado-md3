# Changelog

## [Unreleased]

### Fork 新增特性

- **MD3 主题系统**：基于 Material Design 3 + Monet 引擎重构全部主题，支持动态取色、预测性返回手势、共享元素动画
- **Jetpack Compose 迁移**：逐步从传统 View 迁移至 Compose，已完成主框架、设置、搜索、书架等核心页面
- **有声书重构**：基于 sherpa-onnx 重构本地 TTS 引擎，支持 VITS/Matcha 两种模型，共 3 组内置音色（小雅、超雯、Baker）
- **阅读记录增强**：支持时间轴视图、章节维度统计、阅读数据导出
- **书架布局增强**：新增多种布局选择，平板端专门优化，智能伴生分组（自动归类已读/未读）
- **书籍备注**：新增书籍备注功能，支持手动添加和自动同步
- **手柄支持**：支持手柄上下翻页、按键映射配置
- **漫画阅读增强**：更健全的漫画阅读体验，支持多页预览、缩放
- **发现页重构**：全新发现界面，支持分类浏览、热门推荐
- **配置架构治理**：通过 `verifyConfigArchitecture` 护栏禁止配置回退、DAO 直连等架构腐败

### 依赖更新
- 升级至 Android SDK 37 (targetSdk 37)
- 升级至 Kotlin 2.4.0
- 升级至 AGP 9.2.1
- Cronet 更新至 128.0.6613.40
- sherpa-onnx 1.13.6，内置小雅、超雯（VITS/espeak）、Baker（Matcha）3 组音色

---

## [3.2.0] - 2026-09-05

### 新增
- 本地 TTS 基于 sherpa-onnx 重构，支持 VITS/Matcha 模型（小雅、超雯、Baker 3 组音色）
- 阅读记录时间轴视图
- 书籍备注功能
- 手柄翻页支持
- Compose 阅读器渲染器（Lab 模式）

### 变更
- 主题系统全面重构为 Material Design 3 + Monet
- 从 View 系统迁移至 Jetpack Compose（主框架、设置、搜索等）
- 包名从 `io.legato.kazusa` 修正为 `io.legado.md3`
- 应用名从 `Legado` 更名为 `Legado MD3`

### 修复
- 修复 TTS 多音色切换崩溃问题
- 修复阅读配置绕过 Gateway 的问题
- 修复多窗口模式下状态丢失的问题

---

## [基于 Legado 上游]

本项目基于 [gedoor/legado](https://github.com/gedoor/legado) 开发，继承上游所有功能。
上游变更已通过合并策略同步，本记录仅列出分支独有变更。
