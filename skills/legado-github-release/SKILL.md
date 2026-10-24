---
name: legado-github-release
description: Use when asked to 发版 / publish / release a new version of legado-md3, when bumping app/version.properties or pushing a release tag, or when the Release GitHub Actions run fails. Covers the tag-triggered CI release, required secrets, and the Piper dependency asset.
---

# legado-md3 发版（tag 触发 GitHub Actions）

## Overview

发版是**纯 tag 驱动**的：本地只需改版本号、提交、推 tag，**不要**手工 `assembleAppRelease` 再上传资产。
签名、Piper 模型、产物命名、Release 创建全部由 `.github/workflows/release.yml` 完成。

**核心原则：tag 名必须等于 `app/version.properties` 的版本号；CI 不绿不推 tag。**

## 前置检查（每次发版先做）

1. `git status` 干净、`main` 与 `origin/main` 一致。
2. 5 个 Secret 存在：`gh secret list -R xyz24110189/legado-md3` 应含
   `GOOGLE_SERVICES_JSON`、`SIGNING_KEY`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
3. Piper 依赖 Release `deps-piper-v1` 仍存在（含 `piper-assets-v1.tar.gz`）；它被删则发版必挂。
4. 本地有 `app/google-services.json` 与 `app/src/main/assets/piper/`（用于本地预检，CI 另行获取）。

## 发版步骤

```powershell
# 0) 取 token（gh 未安装时也可用；仓库 manager-core 已存凭据）
#    gh 存在则直接 gh auth status 确认

# 1) 改版本号（三处同步）
#    app/version.properties  VERSION_PATCH+1（MINOR 升级时归零 PATCH）
#    CHANGELOG.md            把 [Unreleased] 内容落成 ## [x.y.z] - YYYY-MM-DD
#    .github/release-notes/x.y.z.md   （可选；存在则作为 Release 正文）

# 2) 提交并推送 main（发版请求即授权提交版本相关文件）
git add app/version.properties CHANGELOG.md .github/release-notes
git commit -m "release: x.y.z"
git push origin main

# 3) 等 main 的 CI 变绿再打 tag（ci.yml runs CI）
#    4) 打 tag 并推送（会触发 release.yml）
git tag x.y.z          # 也支持 vx.y.z；workflow 会去掉前导 v
git push origin x.y.z
```

- **先绿后 tag**：CI 红了不要推 tag，release 会直接失败或产出坏包。
- `release.yml` 只匹配 `*.*.*`（至少两个点）。别用 `x.y.z-test` 这类名字做测试，它会真的发版；测试完必须删 Release 与 tag。

## 监控与验收

```powershell
gh run list -R xyz24110189/legado-md3 -L 5
gh run watch <run-id> -R xyz24110189/legado-md3
gh run view <run-id> -R xyz24110189/legado-md3 --json status,conclusion,jobs
```

期望步骤全绿：Checkout → Free disk space → JDK21 → Gradle → Compute version →
Decode google-services.json → Decode signing keystore → Download Piper（SHA256 校验）→
Build release APKs → Collect assets → Prepare release body → Publish GitHub Release。

验收 Release 资产（必须 4 个、命名如下）：

```powershell
gh release view x.y.z -R xyz24110189/legado-md3 --json assets
# legado-md3-x.y.z-arm64-v8a.apk / -armeabi-v7a.apk / -universal.apk / mapping.txt
```

可选校验（下载 arm64 包）：

```powershell
& "D:\Users\xyz24\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs <apk>
# 期望 CN=Legado Release, O=xyz24；versionName 应为 x.y.z
```

## Secrets 与依赖资产（重建/轮换时）

```powershell
$repo = "xyz24110189/legado-md3"
$b64gs  = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path "app/google-services.json")))
$b64jks = [Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\Users\xyz24\legado_release_new.jks"))
$b64gs  | gh secret set GOOGLE_SERVICES_JSON -R $repo
$b64jks | gh secret set SIGNING_KEY         -R $repo
"legado-2026-release" | gh secret set KEYSTORE_PASSWORD -R $repo
"legado"              | gh secret set KEY_ALIAS         -R $repo
"legado-2026-release" | gh secret set KEY_PASSWORD      -R $repo
```

- Secret 值是 **base64 原文**（workflow 内 `base64 -d` 解码），不要传文件路径。
- Piper 模型：`deps-piper-v1` 是 **Pre-release**、asset `piper-assets-v1.tar.gz`（235.5MB，
  SHA256 `7531580426d0f4736f23360076eb6aeb0a7baf395c5bbef051729316be48218e`）。
  更新模型 → 发布新 asset → 同步改 `release.yml` 的 URL 与 SHA256，**否则校验失败**。
- `deps-piper-v1` 必须保持 Pre-release，避免抢占 "Latest"。

## 已知坑

| 坑 | 对策 |
|----|------|
| 直接 `gradlew assembleAppRelease` 报 KSP `different roots` | 加 `-Pksp.incremental=false`，或改用 `cmd /c ".\gradlew.bat ..."` |
| PowerShell 把 `-Pksp.incremental=false` 当任务名 | 用参数数组或 `cmd /c` 包一层 |
| `git push` 大对象 HTTP 500 | 重试；已 `http.postBuffer=524288000`、`http.version=HTTP/1.1` |
| 覆盖安装失败 | 换了签名库时先卸载旧版；versionCode=10000+run_number，正常递增 |
| `main` CI 红但强推 tag | 先修 CI；release 不会替你跑单测 |
| `gh` 未安装 | 可下载便携版到临时目录，或 `git credential fill` 取 token 走 REST API |
| 磁盘不足 | `release.yml` 已清理 runner 目录；不要再加重量级步骤 |
| 用了 `--offline` | 禁止；CI 需联网拉依赖 |

## Common Mistakes

| 借口 | 现实 |
|------|------|
| "版本号一样也能发" | tag ≠ version.properties 时产物版本混乱；发版前必须三处同步 |
| "先推 tag，CI 挂了再修" | 会生成半成品 Release；先 main CI 绿 |
| "Release 里多了个 test tag 无所谓" | 用户会看到；测试后必须删 Release + tag |
| "模型没变就不用管 SHA" | 任何 asset 重传都会变 SHA；必须重新计算并改 workflow |
