# Release 发布流程

> 适用范围：reader 模块（EPUB 阅读器）及整个 app 的正式版分发。
> 构建机：Windows + JDK 21（`JAVA_HOME=D:/jdk-21.0.1`）。

## 1. 前置条件

- **签名**（Windows 用户级环境变量，配置一次长期有效）：
  - `ANDROID_SIGNE_PATH=D:\Android\Key\key.jks`
  - `ANDROID_SIGNE_STORE_PASSWORD` / `ANDROID_SIGNE_ALIAS=key0` / `ANDROID_SIGNE_PASSWORD`
  - build.gradle 的 `signingConfigs.release` 从这些环境变量读取；缺失时 release 构建失败。
- **混淆**：release 默认 `minifyEnabled=true`（proguard-android-optimize）。混淆不影响
  WebView 内容渲染（跑在系统 Chromium 进程），但**发布前必须装机实测**——混淆版本与
  debug 的行为差异只能靠真机/模拟器验证发现。
- **包名**：release 为 `com.yukino.tool.pub`，debug 为 `com.yukino.tool.pub.debug`
  （applicationIdSuffix），两者可共存，书数据互相独立。

## 2. 构建

```bash
# JDK 21
export JAVA_HOME="D:/jdk-21.0.1"

# 正式版（混淆 + 签名）
./gradlew assemblePubAppRelease
# 产物: app/build/outputs/apk/pubApp/release/com.yukino.tool.pub-<版本>.apk

# 调试版（日常验证用）
./gradlew assemblePubAppDebug
# 产物: app/build/outputs/apk/pubApp/debug/com.yukino.tool.pub.debug-<版本>.apk
```

版本号在 `app/build.gradle` 的 `versionName`/`versionCode`；发版前按改动量递增。

## 3. 发布前验证（模拟器/真机）

1. `adb install -r <release.apk>` 安装（覆盖旧 release 保留书数据；换书数据需重新导入）。
2. 启动应用 → 阅读 → 打开一本书，确认：
   - 打开无崩溃；旧书若格式版本低于 codec 当前版本，会自动"解析章节中…"升级重提取；
   - 翻页数页：正文排版、注音角标、插图章正常；
   - WEBVIEW 块（目录聚合块、人物介绍聚合块、学籍表等溢出块）完整无裁切——
     块渲染依赖 `@JavascriptInterface` 桥与 kotlinx.serialization，是混淆验证的重点。
3. 有问题先比对 debug 包（同源码无混淆）定位是混淆问题还是逻辑问题。

## 4. 分发（局域网 HTTP 下载）

```bash
cd <apk 所在目录>
py -m http.server 8766 --bind 0.0.0.0   # 后台运行
```

- 查本机局域网 IP：`powershell "Get-NetIPAddress -AddressFamily IPv4"`（排除 127.*/
  169.254.* 与虚拟网卡；本机常用 `192.168.88.100`）。
- 下载地址：`http://<局域网IP>:8766/<apk 文件名>`，手机连同一 WiFi 浏览器打开下载。
- 下载不通时排查：换绑定的网卡 IP 重试；Windows 防火墙放行 Python 入站。
- 服务是前台/后台进程，电脑关机即失效，属临时分发，长期分发请用网盘/应用市场。

## 5. 发布到 GitHub Release

```bash
git push origin master
git tag -f v<版本> master && git push origin v<版本>   # tag 已在远程时先 push :refs/tags/<tag> 删除重推

# 取 git 凭证 token(https push 的 PAT/.oauth,不回显)
TOKEN=$(printf "protocol=https\nhost=github.com\n" | git credential fill | grep "^password=" | cut -d= -f2-)

# 创建 Release(tag 已存在则关联;中文说明建议用 python 构造 json 避免转义问题)
curl -s -X POST -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/yangjie-zzu/tool-android/releases \
  -d '{"tag_name":"v<版本>","name":"v<版本>","body":"说明"}'

# 上传 APK(github.com 创建 / uploads.github.com 上传;Content-Type 必须 octet-stream)
RID=$(curl -s -H "Authorization: token $TOKEN" \
  https://api.github.com/repos/yangjie-zzu/tool-android/releases/tags/v<版本> | grep -o '"id": *[0-9]*' | head -1 | grep -o '[0-9]\+')
curl -s -X POST -H "Authorization: token $TOKEN" -H "Content-Type: application/octet-stream" \
  --data-binary @<release.apk> \
  "https://uploads.github.com/repos/yangjie-zzu/tool-android/releases/$RID/assets?name=<apk 文件名>"
```

- 下载地址格式：`https://github.com/yangjie-zzu/tool-android/releases/download/v<版本>/<apk 文件名>`
- Release 管理页：`https://github.com/yangjie-zzu/tool-android/releases`
- gh CLI 未安装时用上述 API 方式；token 来自 git credential helper（https push 同源），scope 够用。

## 6. 历史记录

- 2026-10-09：v1.5.34 release（位图调试面板大图化:列表条目由 72dp 小缩略图+AlertDialog
  预览改为整幅大图直显(按屏宽 inSampleSize 降采样,条目下方一行章号/键/尺寸/大小/
  内存磁盘/几何信息),点击条目直接跳转所在章首页(onJumpToChapter→specs 章首定位,
  与 TOC 同参,原弹窗预览删除);滑动流畅化:缩略图组合期主线程同步解码改 produceState
  +IO 线程异步解码,加 48MB LruCache(path+mtime 为 key),解码前用快照宽高占位防跳动;
  渲染逻辑与 FORMAT_VERSION 零改动）。单测 215 通过(run_tests.sh 直跑;gradle test
  executor 本机报 GradleWorkerMain ClassNotFound 属环境问题);模拟器 debug+release
  混淆双包实测:大图列表/快速滑动(gfxinfo janky 7.4%)/点击跳章均正常。
- 2026-10-09：v1.5.33 release（body 高度铺满收敛:整章聚合块 fillViewport 仅 body
  自带可视背景(纯色底/图/渐变)时生效——页面级背景设计章保持背景铺满整页,body 无
  背景章(果青 Section004-0 等白底聚合章)位图高度=内容高度,不再注入撑满 CSS、落盘
  裁底部空白;信号经 ExtractResult.bodyBg → ChapterDto.bd(v21 遗留字段复用) →
  ChapterDocument.bodyBg → ReaderPage 决策;FORMAT_VERSION 32 存量书升级重提取并清
  BlockCache）。单测 215 通过;模拟器 debug 包实测 Section004-0 白区收敛到内容、
  Section005/正文无回归,release 混淆包实测通过。
- 2026-10-08：v1.5.32 release（白名单制判定重构+聚合上提通用化:①判定改为真白名单
  ——标签/属性/值域清单外一律位图,自绘是特许,清单外不再静默画错;②聚合上提从 body
  一层通用化到所有容器,div 包表格章聚合为单块(果青 Section004-0 缝隙根因:
  classifyRaw 子级循环被 S1_SCAN_SKIP 跳过全部块级子级,位图统计成死代码);③卡片
  完整性——容器带底色且混有位图子级 → 整容器单块位图,白卡不再拆成标题自绘段+
  多个带壳位图三条白片(果青 Section005);④负 margin 钳制/emitTable/信号清单等
  表格特判全部删除;FORMAT_VERSION 31 存量书自动重提取）。单测 214 通过;模拟器
  debug+release 双包验证:Section004-0 单块无缝、Section005 整卡连续、正文无回归。
  **流程教训**:v1.5.31 提交漏了 BlockBitmapDebugSheet.kt(HEAD 引用但未跟踪,仓库
  源码不完整,APK 因从工作区构建而正常),本版补交;发版前必须 `git status` 核对
  功能文件是否全部入库。
- 2026-10-08：v1.5.31 release（新增位图调试面板:阅读菜单底栏入口,只读列出当前
  渲染键命中的全部 WEBVIEW 块位图——缩略图/章号/键前缀/尺寸/大小/内存或磁盘/
  几何状态,支持章号与键前缀过滤,点开看大图;旧键过期位图不显示不清理;渲染逻辑
  与 FORMAT_VERSION 零改动）。debug 包模拟器验证:果青1 面板 34/34 块与磁盘
  wblocks 一致,过滤/大图/翻页正常;**release 混淆包未装机验证(用户明确跳过)**,
  首次真机打开建议关注块渲染(混淆重点 @JavascriptInterface 桥)。发布注意:
  工作区可能与并行会话共用,发版前必须以 `git log`/`git ls-remote --tags` 核对
  最新版本号,勿信会话开始快照(本版差点撞已发布的 v1.5.30)。
- 2026-10-08：v1.5.30 release（替换 v1.5.29 陈旧包:clean 全量重构建,FORMAT_VERSION
  29 强制被 v1.5.29 重提取过的设备再次重提取）。模拟器 release 混淆包验证:
  Section004-0 两图无缝拼合单张位图、人物表页整页位图与 Chrome 一致,单测 207 通过。
  **流程教训(两连发)**:①v1.5.29 release 包是失败构建遗留的陈旧编译产物——失败构建
  后必须 clean 再出正式包;②GitHub API 经代理偶发把 POST 响应错放成 GET 缓存(收到
  list 而非 dict),发布脚本须校验响应类型与最终资产清单,勿盲目沿用上次响应。
  v1.5.28/v1.5.29 的坏资产均已删除,说明页指向 v1.5.30。
- 2026-10-08：v1.5.29 release（表格归位图级子级参与 body 聚合——修复 v1.5.28 表格
  拆独立块后气泡头+表格分离/表格首行负 margin 裁切/灰底丢失,全位图章重新聚合为
  单张整页位图,书内背景与间距原样生效;FORMAT_VERSION 28）,tag `v1.5.29`,GitHub
  Release 含 release/debug 两个 APK。模拟器验证:人物表页整页单张位图与 Chrome
  渲染一致,单测 207 通过。**流程教训**:v1.5.28 发布的 release 包是修钳负 margin
  之前构建的旧二进制(打包→发现问题→只重打 debug→误发旧 release 包),v1.5.28 资产
  已删除并在其说明页指向 v1.5.29;发版前必须核对包内版本构建时间与最新源码一致。
- 2026-10-08：v1.5.28 release（渲染策略:EPUB 表格一律整表 WEBVIEW 位图,不再结构化
  自绘——大字号下列宽超版心压缩折行观感差,位图按书内原样等比缩放保真;表格块钳
  负 margin-top 防首行视口裁切;FORMAT_VERSION 27 存量书自动重提取;TableData
  引擎保留兼容读）,tag `v1.5.28`,GitHub Release 含 release/debug 两个 APK。模拟器
  验证:重提取后人物介绍表与 Chrome 渲染一致(一行一名、灰底保留、首行完整),
  单测 207 通过。注意:表格文字自此不随阅读字号重排(整表等比缩放)。
- 2026-10-08：v1.5.27 release（叠字修复:paraLinePitch 行框/基线按段内最大 run
  字号缩放——原实现只在"行距跟随书内"开启时生效且误取首 run,报告页 em12/em15
  标题在默认全局行距下与相邻行叠字;普通正文零影响）,tag `v1.5.27`,GitHub
  Release 含 release/debug 两个 APK。模拟器验证:debug 字号 16 + release 混淆包
  字号 21,报告页标题三行不叠、正文行距无变化,单测 207 通过。
- 2026-10-08：v1.5.26 release（定宽元素超版心修复:书内 em 定宽(报告纸 24em/
  人物表 22em)在大字号下≥版心时,paraMetrics 放弃收窄致文字按全宽断行而卡片/
  表格仍较窄 → 右缘被屏幕裁切;修:widthEm 钳到版心;盒定宽≥版心时文字断行扣
  盒 padding/边框;表格提示列总和超可用宽时全列等比压缩兜底）,tag `v1.5.26`,
  GitHub Release 含 release/debug 两个 APK。模拟器验证:debug/release 包×字号
  16/19/21,报告页与 Illus4-1 人物表文字均在卡片内;与 Chrome 打开原书 XHTML
  对比（本机 http.server + 10.0.2.2）:布局结构一致,无裁切;差异为阅读器 em 随
  阅读字号缩放(大字号时纸片钳为全宽)与两端对齐策略,属自适应重排预期行为。
- 2026-10-08：v1.5.25 release（核心修复：v13 取消章名合成后 compose 仍前置 "\n\n"
  标题块而 bodyStart=0,EPUB 全章段区间错位 2 字符——图片段行匹配失败降级文本行
  致彩页/行内注音 OBJ tofu,盒样式段落几何错乱致人物介绍截断/报告页溢出;含
  v1.5.24 的拦截器 percent-decode + documentElement 量宽 + RENDERER_VERSION 13）,
  commit `2fe9850`,tag `v1.5.25`,GitHub Release 含 release/debug 两个 APK。
  **模拟器 UI 实测通过**（debug+release 混淆包:彩页整页插图/人物介绍/目录聚合块/
  行内注音/报告页）,单元测试 207 通过。教训:v1.5.24 只跑了单测未装机验证即发布,
  修复未命中真实根因;现已立规:代码修改后必须实际运行验证。
- 2026-10-07：v1.5.24 release（块渲染修复：拦截器路径 percent-decode 修中文文件名
  图片整页 OBJ 破图；量宽并取 documentElement.scrollWidth 修正文聚合位图右缘视口
  裁切；RENDERER_VERSION 补齐至 13 使旧位图缓存失效），commit `0ae3362`，tag
  `v1.5.24`，GitHub Release 含 release/debug 两个 APK，单元测试 207 通过；真机两
  场景待复验。
- 2026-10-06：v1.5.22 release（版本收尾：移除装饰章快照残留 DecorSnapshot.kt，渲染与
  v1.5.21 一致无功能变化），commit `af9bc08`，tag `v1.5.22`，GitHub Release 含
  release/debug 两个 APK，模拟器验证通过（与 v1.5.21 基线截图逐页一致）。注意：本机
  直连 github.com 经常超时/重置，git push 与 API 调用需加本地代理
  （`git -c http.proxy=http://127.0.0.1:7890` / `curl -x http://127.0.0.1:7890`）。
- 2026-10-06：v1.5.21 release（渲染器量宽重构 v11 + 重复绘制/裁切/CSS 404 修复 +
  装饰章快照体系移除 + 块渲染流水线并发），commit `3b6c04f`，tag `v1.5.21`，
  GitHub Release 含 release/debug 两个 APK，模拟器验证通过。
