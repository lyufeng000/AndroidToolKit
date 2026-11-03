# 代码烧入

首页入口位于 `app`。此模块负责项目导入、源码编译、USB 授权和固件烧录。

## 使用范围

- 路线：USB OTG → ST-Link、J-Link 或 DAPLink → SWD 或 JTAG。
- DAPLink：使用 CMSIS-DAP v2 的 USB Bulk 接口。可直接烧录固件，也可先编译源码再烧录。
- 固件：BIN、HEX、ELF、AXF、S19、SREC。
- 项目：系统文件选择器中的目录或 ZIP。
- 烧录内核：OpenOCD 0.12.0，提供 129 个包含 Flash 配置的目标条目。
- 烧录手机：`arm64-v8a` 与 `armeabi-v7a`。
- 源码编译手机：`arm64-v8a`。
- 源码编译目标：Cortex-M 的 C、C++、GNU 汇编。

配置条目不代表每个芯片型号都已通过硬件验证。实际支持范围受 OpenOCD 驱动、探针固件、目标接线和芯片保护状态限制。此模块将 ST-Link 路线限制为 STM32。J-Link 路线使用 libjaylink，不使用 SEGGER SDK。

BIN 使用用户指定的地址。其他固件使用内嵌地址。烧录完成后必须校验。只有进程成功退出并报告 `Verified OK` 时，界面才显示成功。停止操作后，固件可能不完整。

源码不会自动变成可烧录固件。项目必须提供 CPU、源文件、依赖和链接脚本。模块不执行导入项目中的脚本。

## 源码工程

优先读取 `toolkit-build.json`。如果没有此文件，尝试读取 STM32CubeIDE 的 `.cproject`。用户也可以选择通用配置。通用配置会编译目录中的全部源文件，并使用目录中的头文件路径。

多目标工程应使用显式配置。Keil、CMake、Make、PlatformIO 工程可以通过此配置列出实际源文件。模块不包含这些构建系统。ARMASM、Scatter 脚本、专用编译器和外部 Flash 初始化流程不自动转换。请提供 GNU 启动文件、GNU `.ld` 脚本和工程依赖。

示例见 `examples/flash/stm32f103`。此示例面向 STM32F103C8 的 PC13 LED。请按实际目标板修改引脚。

```json
{
  "version": 1,
  "name": "工程名称",
  "cpu": "cortex-m4",
  "sources": ["Core/Src/main.c", "startup_stm32f407xx.s"],
  "includeDirs": ["Core/Inc", "Drivers/CMSIS/Include"],
  "defines": ["STM32F407xx", "USE_HAL_DRIVER"],
  "linkerScript": "STM32F407VGTX_FLASH.ld",
  "flags": ["-Os", "-std=c11", "-mfpu=fpv4-sp-d16", "-mfloat-abi=hard"],
  "libraries": [],
  "linkFlags": ["--gc-sections"]
}
```

所有路径均相对于配置文件。路径必须留在导入目录内。JSON 中的 `flags` 适用于全部源文件。混合 C/C++ 工程应省略共同的 `-std`，或使用两种语言都兼容的参数。

ZIP 和目录导入限制为 512 MB、12000 个条目和 32 层目录。导入会拒绝路径越界与大小写冲突。导入副本保存在应用私有目录内。

## 后端构建

普通 APK 构建使用已准备的内核和编译器。无需重新编译原生工具。

运行 `BuildFlashTools.ps1 -NdkPath <NDK路径> -BashPath <MSYS2的bash.exe路径>`，重新准备工具。需要 NDK r27d、Node.js，以及 MSYS2 的 autoconf、automake、libtool、make、pkgconf 和 tcl。下载缓存与构建产物写入 `D:\Data\.temp`。

对应的 OpenOCD、Jim Tcl、libjaylink 和 libusb 源码在 `third_party`。USB 桥接和子进程代码在 `native`。Clang 依赖包的版本、下载位置和 SHA256 在 `native/compiler-packages.json`。标准库来自 xPack Arm GNU Toolchain 15.2.1-1.1。`native/package-compiler.mjs` 只提取运行编译器和交叉编译所需文件，并调整 Android 动态库名称。

USB 桥接使用 Android 已授权的文件描述符。子进程只能枚举这一个探针。未授权时不会尝试打开其他 USB 设备。应用不需要 root。

## 验证

- 本地测试：归档路径检查、编译配置检查、地址处理、烧录命令与导航。
- Android 测试：OpenOCD 启动、两个探针驱动、目标配置、手机端源码编译、原生进程取消、无 USB 授权的失败路径。
- 真正的擦写、读回校验与复位需要连接探针和目标板。

2026-11-02 验证结果：196 项本地测试通过。Mi 10（Android 13）上的 10 项检查通过，包含源码编译、ZIP 与固件导入、两种探针配置的无授权失败路径、进程取消、页面导航，以及三个页面的系统深浅色切换。手机中安装的 APK 与交付 APK 的 SHA256 一致。按用户要求，本次未进行实物擦写、读回校验和复位验证。
