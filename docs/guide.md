# 数据、权限与发布说明

## 数据与权限

日程、备注、图片和对话历史保存在设备上。系统云备份与设备迁移已在 [Manifest](../app/src/main/AndroidManifest.xml) 和[数据提取规则](../app/src/main/res/xml/data_extraction_rules.xml) 中关闭。目前没有完整的日程备份导出，卸载可能丢失本地记录。

手动规划不需要网络。HUB 在线登录会访问校方网站；向外部模型发送消息前可查看接收服务，跨页数据、备注和课程详情需另行选取。模型只能查询或提出更改，确认差异后才由手机写入。API Key 由 Android Keystore 中的密钥加密保存。

通知权限用于显示日程提醒。未获精确闹钟权限时，提醒可能延迟。开机广播用于重排提醒；处理提醒时，唤醒锁短暂保持 CPU 运行。

## 应用内更新

「设置 → 帮助与关于 → 版本与项目」可以立即检查更新，也可以关闭检查。默认在打开应用时每 24 小时最多检查一次本仓库的 GitHub Releases，包含预发布，不发送日程、对话或 API Key。

自动更新默认关闭。开启后，应用会核对构建号、文件摘要、包名和已安装版本的签名，再调用 Android 安装程序更新自身。系统可能要求允许从该来源安装或确认更新。

## 验证范围

[发布工作流](../.github/workflows/release.yml) 在 API 37 模拟器上离线安装签名 APK，跳过首次引导，强停并重新打开，检查是否回到「今天」。真机帧耗时按版本单独测量：0.10.17 曾出现打开卡片耗时回退；[0.10.18 在 PLR110 上的分项结果](roadmap.md#v01018-真机帧预算)列出了改善和仍超出预算的场景。

本人 HUB 在线登录、真实外部 BYOK 调用尚未验收。普通版使用 Compose 转场，不含联盟 SharedView Kit；SDK 分发授权和支持设备上的系统转场尚未核实。低于 API 33 时使用实色材质。

## 构建环境

使用 JDK 21，在 Android SDK 命令行工具中安装：

```sh
sdkmanager 'platform-tools' 'platforms;android-37.2' 'build-tools;37.0.0'
```

使用仓库的 Gradle wrapper，无需另装 Gradle。0.11.0 使用 Gradle 9.6.0、AGP 9.5.0，Java target 为 17；编译和应用版本见 [`app/build.gradle.kts`](../app/build.gradle.kts)。复现公开包时先检出对应标签，普通版不需要联盟 SDK。运行与 [Android CI](../.github/workflows/android-ci.yml) 相同的检查：

```sh
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:assemblePreview
```

开发用组件目录通过 `./gradlew :app:assembleGallery` 单独构建，APK 在 `app/build/outputs/apk/gallery/app-gallery.apk`。启动 **StackLife Gallery**，选组件、动效或材质，再点示例。顶栏调色板只改变 Gallery 的主题、字号、方向、预览宽度和降级设置；信息按钮显示来源和可操作的验收输入。示例在 `.gallery` 的内存库中，结束进程后重置；它没有联网、安装更新或安排闹钟的权限。不要用 Gallery 测正式包的帧预算。

## 签名与发布

版本标签采用 `vX.Y.Z`，须为带注释的标签，指向已推送到 `main` 的源码提交。`X.Y.Z` 对应 `versionName`；标签注释会用作 Release 说明。推送标签后，从 `main` 手动运行 [Android prerelease](https://github.com/Nakanomk/StackLife/actions/workflows/release.yml)。工作流按标签构建，在独立任务中签名和测试；已存在的同名 Release 不会被覆盖。

本地 `preview` 用本机 debug 签名，CI 的 APK 用 runner 临时签名。可覆盖更新的公开预览包从 [Releases](https://github.com/Nakanomk/StackLife/releases) 下载。`SHA256SUMS` 校验 APK 和 `BUILD-INFO.json` 的文件内容；`BUILD-INFO.json` 记录源码提交、标签和 Actions 运行链接。

用 Android build-tools 检查 APK 的签名，例如：

```sh
apksigner verify --verbose --print-certs StackLife-0.11.0-preview.apk
```

签名者应只有一个，证书 SHA-256 为 `6a51915f4e57205dfd4e5f1bee5bac6c14434e7bdaa2050c165975a512b758d9`。这是证书指纹，APK 文件的摘要随版本变化。
