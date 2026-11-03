# 峰的工具箱

这是一个 Android 工具箱。目前包含串口调试和代码烧入。

工具箱使用 Kotlin 和 Jetpack Compose。最低支持 Android 7.0（API 24），目标版本为 API 37。安装标识为 `com.lyufeng.androidtoolkit`。

## 项目模块

| Gradle 模块 | 职责 |
| --- | --- |
| `:app` | 安装包、应用入口、页面导航和 USB 路由 |
| `:feature:home` | 工具箱首页 |
| `:feature:update` | 检查版本、下载 APK、验证签名和启动安装 |
| `:feature:serial` | 串口界面和功能编排 |
| `:feature:serial:core` | 串口数据模型 |
| `:feature:serial:connection` | USB 连接、串口会话和后台服务 |
| `:feature:serial:terminal` | 终端显示和数据编码 |
| `:feature:serial:transfer` | XMODEM 文件传输 |
| `:feature:serial:storage` | 配置存储和归档 |
| `:feature:serial:commands` | 宏命令和 VOFA+ 命令 |
| `:feature:serial:logging` | 通信日志和事件记录 |
| `:feature:flash` | 烧录界面、前台服务和功能编排 |
| `:feature:flash:model` | 烧录数据模型 |
| `:feature:flash:project-import` | 固件和源码工程导入 |
| `:feature:flash:native-runtime` | 原生进程、工具资源和本机库 |
| `:feature:flash:compiler` | 源码编译 |
| `:feature:flash:openocd` | 调试探针和固件烧录 |

## 构建

使用 Android Studio 打开项目。等待 Gradle 同步后运行 `app`。构建需要 Android SDK 37 和 JDK。

在 Windows PowerShell 中运行 `./Build.ps1`，生成 `AndroidToolKit-debug.apk`。脚本将源文件暂存到 `D:\Data\.temp`，以便 KSP 在同一磁盘生成 Room 代码。也可以使用 `-Tasks` 指定 Gradle 任务。

推送分支后，GitHub Actions 会运行 [Build Android APK](https://github.com/lyufeng000/AndroidToolKit/actions/workflows/android-build.yml)。也可以在 Actions 页面手动启动。构建成功后，在运行记录的 Artifacts 区域下载 `AndroidToolKit-debug`。

工具箱和原串口应用可以同时安装。要转移原应用中的配置，请在原应用中导出配置，再在工具箱的串口设置中导入。原应用的历史记录和文件目录授权不会自动转移。

代码烧入支持通过 USB OTG 连接 ST-Link、J-Link 或 DAPLink（CMSIS-DAP v2）。可以导入固件、源码目录或 ZIP。源码编译范围和工程格式见 [代码烧入说明](feature/flash/README.md)。

应用图标使用 `tool-box.png`。首页、串口页和烧录页跟随系统切换深浅色。

## 应用更新

应用首次进入首页时会查询 [GitHub Releases](https://github.com/lyufeng000/AndroidToolKit/releases) 的最新稳定版。也可以在首页点按「检查更新」。发现新版本后，应用会提示下载。下载验证完成后，在 Android 安装界面确认更新。

发布更新时，执行以下步骤。

1. 在 `app/build.gradle.kts` 提高 `versionCode` 和 `versionName`。
2. 使用与已分发版本相同的签名密钥签署 APK。
3. 在 `lyufeng000/AndroidToolKit` 创建公开稳定版 Release。将标签设置为 `v` 加上 `versionName`，例如 `v1.1`。版本号使用两段或三段数字。
4. 将 APK 作为 Release 附件上传，命名为 `AndroidToolKit.apk`。如果一直使用同一密钥分发 Debug APK，也可以上传 `AndroidToolKit-debug.apk`。两个附件同时存在时，应用优先选择 `AndroidToolKit.apk`。
