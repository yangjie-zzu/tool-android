# 个人工具集

Android 个人工具箱应用（Kotlin + Jetpack Compose）。包含以下模块：

| 模块 | 说明 |
|---|---|
| web | Web 远程控制 |
| 解压缩 | 压缩包解压（支持带密码 ZIP/7z） |
| 蓝牙应用 | 蓝牙工具 |
| IP 信息 | 本机 IP 查询，支持下拉刷新 |
| 备忘录 | 主密码 + 指纹加密的条目式备忘录，支持 2FA 动态码与扫码录入 |
| 阅读 | TXT 电子书阅读器 |

## 构建

- Android Studio / Gradle 8.9，AGP 8.7.3（需 JDK 11+，本机配置为 `D:\jdk-21.0.1`）
- 签名配置读取以下环境变量（`app/build.gradle.kts` → `signingConfigs.release`）：

  | 变量 | 说明 |
  |---|---|
  | `ANDROID_SIGNE_PATH` | keystore 文件路径 |
  | `ANDROID_SIGNE_STORE_PASSWORD` | keystore 密码 |
  | `ANDROID_SIGNE_ALIAS` | key 别名 |
  | `ANDROID_SIGNE_PASSWORD` | key 密码 |

- 变体：`pubApp`（公开版）/ `priApp`（私人版），debug 包自动追加 `.debug` 后缀，与正式包共存

  ```bash
  ./gradlew :app:assemblePubAppDebug    # 调试包
  ./gradlew :app:assemblePubAppRelease  # 发布包(R8 混淆+签名)
  ```

- 每次构建自动记录构建时间到 `BuildConfig.BUILD_TIME`，在首页标题下方展示 `v版本名 · 构建于 时间`

## 本地文件夹 `local/`（不入库）

本地专属文件统一放在 `local/` 文件夹，整个目录已被 git 忽略（规则在 `.git/info/exclude`，仅对本机生效）。当前包含：

- `local/shots/` —— UI 调试截图
- `local/samples/reader/liumang.txt` —— 真实书籍测试文件（体积大，不入库）

有不想提交的本地文件，直接丢进 `local/` 即可。

## 阅读器（TXT）

- 导入：SAF 选文件或系统"分享/打开方式"进入；自动检测编码（GBK/UTF-8/UTF-16 等），CRLF 规范化后转存 UTF-8 缓存
- 章节切分：正则识别"第X章/回/节/卷幕"、Chapter N、序章/楔子等；自动剥离精校版标题行的 `[本章字数…]` 元数据；书前目录页自动排除
- 分页：按版式测量整本生成页目录（`PageSpec`），按章**并行测量**加速；结果持久化到 `files/reader/specs/<bookId>.json`，同书同版式二次进入免重排
- 翻页：跟手覆盖动画 + 轻扫；邻页预物化缓存避免手势线程卡顿；封面/封底越界方向页面静止
- 垂直匀齐：每页底部剩余空白自动分配到页首间距与段间距（单处增量封顶 1 行高）
- 版式（字号/行距/段距/边距/主题）变更会变更版式指纹 `typoKey`，触发整本重排并刷新分页缓存

### 测试样例 `samples/reader/`

覆盖导入与切分的典型场景：GBK+CRLF 多章、带 BOM 的 UTF-8、无换行单行、无章节文本、全角缩进、段间无空行等（真实书籍样例不随库提交，放 `local/`）。

### 运行测试

```bash
./gradlew :app:testPubAppDebugUnitTest --tests "com.yukino.tool.module.reader.*"
```

> 注意：本机 Gradle 测试执行器可能因中文用户目录启动失败，可参考 `local/` 下的方式手动运行 JUnit；Android Studio 内运行不受影响。

## 文档

- 阅读器总体设计：[docs/reader-design.md](docs/reader-design.md)
- TXT 阅读器 MVP 设计：[docs/reader-mvp-txt.md](docs/reader-mvp-txt.md)
