# 峰的工具箱

这是一个 Android 工具箱。目前包含串口调试和代码烧入。

工具箱使用 Kotlin 和 Jetpack Compose。最低支持 Android 7.0（API 24），目标版本为 API 37。安装标识为 `com.lyufeng.androidtoolkit`。

使用 Android Studio 打开项目。等待 Gradle 同步后运行 `app`。构建需要 Android SDK 37 和 JDK。

在 Windows PowerShell 中运行 `./Build.ps1`，生成 `AndroidToolKit-debug.apk`。脚本将源文件暂存到 `D:\Data\.temp`，以便 KSP 在同一磁盘生成 Room 代码。也可以使用 `-Tasks` 指定 Gradle 任务。

工具箱和原串口应用可以同时安装。要转移原应用中的配置，请在原应用中导出配置，再在工具箱的串口设置中导入。原应用的历史记录和文件目录授权不会自动转移。

代码烧入支持通过 USB OTG 连接 ST-Link、J-Link 或 DAPLink（CMSIS-DAP v2）。可以导入固件、源码目录或 ZIP。源码编译范围和工程格式见 [代码烧入说明](feature/flash/README.md)。

应用图标使用 `tool-box.png`。首页、串口页和烧录页跟随系统切换深浅色。
