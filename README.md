# 纸面辅助批改

手机摄像头对着桌面上的试卷，跟踪纸张位置。老师点「批改」之后，才把这一帧和评分标准发给多模态模型，并把返回的对钩、叉和短批注叠在画面上。老师对照标记自己写到纸上。应用不自动算分，也不会每一帧都请求模型。

这是一期手机验证，不包含投影仪。产品约定在 [doc/技术方案.md](doc/技术方案.md)，编译、密钥和演示模式在 [doc/开发说明.md](doc/开发说明.md)。

## 用 Android Studio 打开

用 Android Studio 打开本仓库根目录（含 `settings.gradle.kts`），等 Gradle 同步后运行 `app`。需要 JDK 17+ 和 Android SDK 36。

```bash
./gradlew assembleDebug
```

## 密钥和演示模式

接口地址、模型名和密钥写在本机的 `local.properties`（已忽略，不要提交）。示例见 `local.properties.example`。

默认是阿里云百炼的 OpenAI 兼容接口，模型 `qwen3-vl-plus`，地址 `https://dashscope.aliyuncs.com/compatible-mode/v1`。没有密钥时打开「演示模式」，用本地示例标记检查纸面是否对准，不会联网。能访问 Google API 时，可以改成 `gemini-3.1-pro-preview`，写法见开发说明。
