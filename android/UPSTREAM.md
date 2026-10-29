# Android 客户端来源与使用说明

本目录直接收录 ScreamFromAndroid 的完整应用源码、资源、测试和 Gradle Wrapper，可随 ScreamRouter 一起克隆，无需另外初始化 Android 子模块。

## 来源

- Android 上游：[gversluis/ScreamFromAndroid](https://github.com/gversluis/ScreamFromAndroid)
- 导入分支：`master`
- 导入提交：`1dd7df64376d9fea0d97118b626f36ab19a4a8fe`
- 导入日期：2026-10-29
- ScreamRouter 上游：[netham45/screamrouter](https://github.com/netham45/screamrouter)
- ScreamRouter 基础提交：`4d3dc1fdef257a0306b78dced516c0e84a4db3e4`
- 整合仓库：[Charron-Lin/screamrouter](https://github.com/Charron-Lin/screamrouter)

原始 Android 许可证保存在 [LICENSE](LICENSE)，ScreamRouter 的许可证见 [COPYING](../COPYING)。保留各上游的版权和许可证声明。

此次导入仅调整说明性注释、补充中文 KDoc、修复 README 截图的相对链接及增加本地文件忽略规则；应用包名、音频格式、网络默认值和运行逻辑保持上游原样。未导入上游个人 `.idea/` 配置和 `.git/` 元数据。上游原文介绍保存在 [README.md](README.md)。

## 构建

在 Android Studio 中打开本目录 `android/`，使用与 Gradle 8.13 和 Android Gradle Plugin 8.13.2 兼容的 JDK（建议 JDK 17 或 21），并安装 Android SDK Platform 36。应用最低支持 Android 10（API 29），源码的 Java/Kotlin 目标版本为 11。

也可以从仓库根目录执行：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

```bash
cd android
./gradlew :app:assembleDebug
```

需要通过 `ANDROID_HOME` 或本目录未跟踪的 `local.properties` 配置 SDK 位置。调试 APK 输出为 `app/build/outputs/apk/debug/screamfromandroid-v1.2-debug.apk`。Gradle 构建独立于服务端 Python/C++ 和 React 构建。

## 连接 ScreamRouter

1. 启动 ScreamRouter，让手机和服务器处于同一局域网。
2. 安卓客户端的目标地址填写服务器的局域网 IP，推荐使用单播，避免组播受路由器或容器网络限制。
3. 目标端口填写 `16401`（UDP），对应 ScreamRouter 默认接收端口及根 README 的 Docker 端口映射。直接运行或使用 host 网络时，当前接收管理器也监听 `4010`；安卓上游默认端口仍为 `4010`，需按部署方式调整。
4. 根据系统提示授予音频捕获和通知等权限，点击开始推流；只有允许被 Android 捕获的应用音频才能发送。
5. 在 ScreamRouter 中选择手机对应的音频源，配置输出设备和路由。

当前主机未发现 Android SDK，因此本次整合只执行源码一致性、XML 解析、Gradle Wrapper 完整性和 Git 检查，未编译 APK，也未进行真机音频联调。

## 维护

独立的上游检出位于 `D:\Code\ScreamFromAndroid`。更新时先获取 Android 上游变更，再将需要的源码同步到本目录，复核中文注释并更新上述提交号；保留本目录的来源记录、许可证和构建说明。
