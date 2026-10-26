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

> **2026-10-15 起首选 GitHub Actions 全自动发布**（本机直传 uploads.github.com 存在
> 大上传被链路掐断的时段性问题，见下方补充说明）：commit + 递增版本号后，
> `git tag vX.Y.Z && git -c http.proxy=http://127.0.0.1:7890 push origin vX.Y.Z`，
> Actions（.github/workflows/release.yml）自动构建 release+debug 两个 APK 并上传到
> 该 tag 的 Release。手动补发历史版本：仓库 Actions 页选 Release → Run workflow →
> 填 tag 名；或 API POST /actions/workflows/release.yml/dispatches。
> 签名依赖 4 个 repo secrets：ANDROID_KEYSTORE_BASE64（key.jks 的 base64）、
> ANDROID_SIGNE_STORE_PASSWORD / ANDROID_SIGNE_ALIAS / ANDROID_SIGNE_PASSWORD
> （2026-10-15 已配置）。以下为手动直传方式（备用）：

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
- **只上传 release 包，debug 包不再传 GitHub Release**（2026-10-14 结论）：debug 包
  51.8MB 超过本机代理对上传请求体的限制（~50MB，35MB 的 release 包可过）；症状为
  数据全部到达（资产 size 完整）但 finalize 响应被掐（curl http:000 / SSL EOF），
  资产滞留 starter 状态且 API 删除 404（GitHub 会自动 GC，不占名，可直接重传同名）。
  curl/python、走代理/直连均复现。debug 包需要分发时走局域网 HTTP（见第 4 节）。
- **上传期网络抖动的处理**：上传返回非 201 时空转重传即可（starter 不占名），间隔
  25~90s 多试几次；若整段时间持续失败（网络时段问题），等网络恢复后重传。验证资产
  是否可用：`GET /releases/{id}/assets` 看 state=uploaded，下载地址 HEAD 应 200/206。
- **本机双代理软件端口冲突（2026-10-14 发现）**：Clash for Windows 与 Bettbox 同时
  运行时，`127.0.0.1:7890` 被 clash-win64 的具体绑定占用，BettboxCore 只剩
  `0.0.0.0:7890`——显式代理 `-x 127.0.0.1:7890` 的流量实际进 Clash 而非 Bettbox；
  用本机局域网 IP（如 `192.168.88.100:7890`）访问可命中 Bettbox。若两路上传都在
  finalize 阶段被掐（数据完整到达、资产滞留 starter），为链路对 uploads.github.com
  大上传的时段性限制，换代理软件无效，等网络恢复重传即可。

## 6. 历史记录

- 2026-10-26：v1.5.70 release（悬浮路径条改进：改顶部居中（状态栏高度+8dp 避让），
  单行显示完整路径（不折行不省略，条宽按文本贴合、90% 屏宽封顶），超宽部分横滑
  （HorizontalScrollView）查看，右侧 ✕ 可随时关闭（仅移除悬浮条，选择器保持打开）；
  保留 FLAG_NOT_FOCUSABLE 不抢选择器返回键，条外触摸穿透。修复 weight 子项在
  WindowManager UNSPECIFIED 测量下塌 0 宽的问题（改显式测量定宽）。模拟器 debug
  实测：顶部位置/单行/✕关闭/选回重载/长路径横滑见尾部；release 混淆包文件夹导入
  场景全流程通过。注入滑动需 ≥900ms 慢拖属模拟器特性已记
  docs/emulator-ui-automation.md。）
- 2026-10-26：v1.5.69 release（重新授权期间系统悬浮条展示原文件绝对路径：授权失效
  点"重新授权"后，文件选择器上层悬浮深色圆角条显示原文件绝对路径（文档 id 按卷
  id 还原：primary→/storage/emulated/0、SD 卷→/storage/<卷id>，兼容单文件与文件夹
  子项 uri），指引定位同一文件；悬浮条不可获焦/不可触摸不干扰选择器，选择器关闭
  （选中/取消）即移除；无"显示在其他应用上层"权限先跳系统设置页+Toast 引导，授权
  一次长期有效；txt/epub/pdf 两阅读页一致，Manifest 新增 SYSTEM_ALERT_WINDOW。
  模拟器 debug 实测：无权限跳设置真实开关流程/TXT 与 PDF 悬浮条路径与真实路径
  一致/选回重载+悬浮条移除/取消路径移除；release 混淆包文件夹导入场景全流程
  通过（appops 授权提速）。坑与断言方法已记 docs/emulator-ui-automation.md。）
- 2026-10-26：v1.5.68 release（阅读器源文件授权失效可"重新授权"：打开书遇
  SecurityException（SAF 持久化授权被系统收回，如卸载重装/权限自动重置）时，
  阅读页由"请返回书架删除后重新导入"改为显示"重新授权"按钮——重选原文件后按
  authority+文档 id 匹配确认同一文件，恢复授权并更新 sourceUri 自动重载（nonce
  触发，同 id 进度/分组不变）；选错文件 Toast"所选文件与原书不一致"拦截不替换
  内容；txt/epub（BookContents 统一识别）与 pdf（独立链路）全覆盖；文件夹导入
  的树子项 uri 与单文件 uri 均可匹配。模拟器 debug 全流程实测（TXT 授权丢失→
  重授权进度 50% 保留/选错文件拦截/PDF 全流程/旧书回归）+release 混淆包文件夹
  导入场景全流程实测通过；授权丢失复现方法（uninstall -k 保数据清授权）与坑
  已记 docs/emulator-ui-automation.md。另：发现既有问题——跳页对话框跳至目标页
  首屏物化偶发空白页（翻一页即恢复），与本次改动无关，待查。）
- 2026-10-25：v1.5.67 release（蓝牙调试页详情卡片配色区分(真机反馈:不同类型卡片同色
  难辨):设备头部卡改 primaryContainer 主色调,日志卡改 secondaryContainer 副色调,
  服务卡保持 surfaceVariant 中灰,数据点维持嵌套 surface 浅色——四种区域一眼可辨。）
- 2026-10-25：v1.5.66 release（蓝牙调试页详情继续优化(真机反馈:日志面板过大/服务与
  数据点无区分):①操作日志默认收起,仅显示最新一条灰字,点标题展开 160dp 滚动面板并
  显示条数;②服务卡片改 surfaceVariant 底色+标题加"服务"前缀,数据点改为服务卡片内
  嵌套圆角 Surface(浅色)+标题加"数据点"前缀,层级一眼可辨。）
- 2026-10-25：v1.5.65 release（蓝牙调试页设备详情界面重排:此前为普通 Column 无滚动,
  服务多时内容超屏不可见。现三层结构——头部设备卡(名称/MAC/绑定状态/服务与数据点
  计数/断开按钮)固定,中部服务卡片区可滚动(卡片式服务,展开箭头▾/▸,数据点内嵌
  读/写/订阅操作与写输入),底部操作日志固定 140dp 面板独立滚动。模拟器走查通过,
  布局效果需真机复验。）
- 2026-10-25：v1.5.64 release（蓝牙调试页服务多开:服务展开状态由单变量改为集合,多个
  服务可同时展开互不收起;特征写输入框同理可同时打开多个。此前单变量实现导致点开一个
  服务就收起上一个(真机验证反馈)。模拟器走查+编译通过,展开交互需真机复验。）
- 2026-10-25：v1.5.63 release（蓝牙调试页层级返回:返回键逐层退——设备详情(断开并清理)
  →设备扫描列表(停扫)→预设列表→退出,替换此前"详情按返回直接跳回预设列表且连接不清理"
  的行为;"断开返回"按钮与返回键走同一 debugBack() 逻辑。发版前全页面导航走查(编辑页
  返回按钮/调试页返回键/列表页退出)模拟器实测通过;设备详情层需真机验证。）
- 2026-10-25：v1.5.62 release（蓝牙调试页可连性标注+配对处理:①扫描列表解析广播可连性
  (API 26+ 用 ScanResult.isConnectable, 低版本用 Flags 位),不可连接的纯广播设备
  (beacon 等)标注"不可连";②新增配对逻辑——读/写/订阅收到权限不足错误(status 5/15/0x0F)
  时自动 createBond 拉起系统配对框,监听绑定完成(30s 超时)后自动重连并重试一次原操作,
  预设执行与调试页共用;执行器日志提示"设备要求配对,已拉起系统配对";调试页设备详情
  显示绑定状态。模拟器验证编译/单测/页面正常,配对流程需真机验证。）
- 2026-10-25：v1.5.61 release（蓝牙连接三修复(真机验证反馈):①核心 bug——BleClient
  连接成功回调(STATE_CONNECTED)未通知挂起方,任何设备连接都必然干等超时报"连接超时",
  现连接成功即放行;②调试页点击设备即时反馈:条目显示"连接中…"并锁定列表,连接前自动
  停扫(扫描进行中发起连接易失败);③连接超时 8s→12s,文案改"连接超时:设备可能不接受
  连接(如手机/电视等非外设),或已被其他设备占用";④BluetoothActivity 补套 ToolTheme,
  修复漏主题导致的 Material 默认紫配色,现与其他模块一致(12+ 跟随壁纸动态取色)。）。
- 2026-10-25：v1.5.60 release（蓝牙调试页返回修复:①去掉调试页顶部"返回"按钮;②新增
  BackHandler——调试页/编辑页按系统返回键回预设列表,此前无返回处理直接退出整个蓝牙
  Activity(真机验证反馈);预设列表页按返回仍正常退出。模拟器实测:调试页无返回按钮、
  返回键回列表、列表页返回退出 ✓）。
- 2026-10-24：v1.5.59 release（蓝牙模块通用化改造:①三种功能模板(发指令/读数据/监听)+
  一键预设,执行面板参数可临时改(仅本次/存回);②全量调试页——扫描周围所有 BLE 设备、
  展示全部服务/数据点树,可读/写(HEX/ASCII)/订阅并滚动日志;③参数输入支持手填或从调试
  结果选择(设备扫描选择器/特征树选择器);④AppDb v9 新增 ble_preset 表,首次自动生成
  内置"关灯"预设等价替代旧硬编码逻辑(FFE0 匹配/FFF0/FFF2/两条指令/间隔100ms/执行完
  关蓝牙);⑤权限收拢 BlePermissions+蓝牙使能异步轮询 6s(修复允许后立即误报"蓝牙未开启"),
  删除全局 lateinit。单测新增 BleModelsTest 15 个全过(HEX/UUID/JSON 往返);模拟器验证
  种子/列表/执行面板/保存/编辑回填/删除/错误路径,截图在 local/shots/ble_01~11;真实 BLE
  关灯与调试待真机手动验证。另:单测环境修复——中文用户名路径致 Gradle 测试 worker 起
  不来,复制 GRADLE_USER_HOME 到 D:\gradle-home 绕开,详见 docs/bluetooth-verify-notes.md;
  master 存量失败 EpubRichContentTest/PdfOutlineParserTest 各 1 个与本版无关）。
- 2026-10-22：v1.5.58 release（删除确认弹窗的"删除"按钮改红色(colorScheme.error),
  与"取消"形成破坏性操作对比。模拟器 debug 实测截图确认）。
- 2026-10-22：v1.5.57 release（WebView 阅读页返回键修复+删除二次确认:①WebBookReader
  补 BackHandler——此前未注册返回处理, 返回键走系统默认行为直接退出整个阅读 Activity
  (表现为"导入文本打开后按返回回不到最近阅读"), 现与顶栏返回一致退出阅读态回书架;
  ②最近阅读与书架的删除按钮统一加二次确认弹窗(标题含书名, 提示连缓存进度不可恢复,
  取消不动确认才删)。模拟器 debug 实测: 返回键回书架页签、取消保留条目、确认删除
  生效）。
- 2026-10-22：v1.5.56 release（导入文本零包装+最近阅读可删除:①导入文本正文原样落盘,
  不拆段/不包 p/不转 br/不加样式, 直接交 WebView 渲染——此前包装逻辑在 SVG 内部插
  `<br>` 致 HTML 解析器终止 SVG 解析只剩文字泄漏(实测复现定位);②WebBookReader 加
  defaultTextEncodingName=UTF-8——file:// 无响应头声明编码, 统一按 UTF-8 解码中文
  不乱码;③最近阅读条目加删除按钮, 语义与书架删除一致(整本书连缓存进度)。模拟器
  debug 实测: 裸 SVG 片段 html 渲染正常且中文不乱码, 最近阅读删除后条目消失、缓存
  文件同步清理(run-as 核对)）。
- 2026-10-22：v1.5.55 release（阅读格式分流+导入文本+全局去 Screen 后缀:①BookFormat
  新增 WEB——html/htm/xhtml/mht/mhtml/svg/图片类扩展名走新组件 WebBookReader(WebView
  加载登记时转存的本地副本,记最后阅读时间不做阅读进度),txt/md/markdown/log/text/out
  归 TXT 阅读器,其余扩展名(mobi/docx 等)拒绝导入并 Toast 提示,单文件与文件夹导入
  统一走 formatOf() 分流;②导入菜单新增"导入文本"——弹窗输入书名+正文,包装成阅读
  主题配色 HTML 按 WEB 登记并直接打开,正文空时确定置灰;③全局重命名 ReaderScreen→
  ReaderPage/PdfScreen→PdfPage/MigrationScreen→MigrationPage。模拟器 debug 实测:
  html 经 SAF 导入 WebView 渲染(CSS 生效)、导入文本弹窗全流程、md 走 TXT、.m/.h
  拒绝提示、PDF/TXT 回归通过。坑:copyToInbox 返回 file:// uri 字符串不能直接当
  cachePath 存库(需 Uri.parse().path);adb push 会截断扩展名已记
  docs/emulator-ui-automation.md）。
- 2026-10-17：v1.5.48 release（PDF 放大层按页面位图真实比例布局:PdfZoomOverlay 的
  框 aspectRatio 与钳制/焦点换算/绘制 pivot 共用的 pageLayout 改用当前页位图真实
  比例(bmp.height/width,未就绪退回整书统一 pageAspect)——修复混比例书放大拖到
  极限左右露背景:pageAspect 以第 1 页为准统一,cpp_book 正文页比例≈1.484≠封面
  1.423,位图 Fit 在框内左右各留 22px 透明区(2.5x 即 55px)透出背景,钳制保证的
  "框盖住屏幕"盖不住更窄的位图;单比例书真实比例=pageAspect 行为不变。列表条目
  侧仍按统一比例定高。模拟器实测: cpp_book 第 2/3 页四向极限贴边(修复前左右
  恒 55px 缝),封面/normal_redline/long_test 回归通过,惯性撞边停住+双击还原正常;
  过程坑已记 docs/pdf-verify-notes: 扫描书纸边与背景同色的"假缝"误判、混比例
  场景判读按位图实际显示区）。
- 2026-10-16：v1.5.47 release（PDF 放大态钳制修正+拖动惯性:①钳制与双击焦点
  换算改按页面图实际布局矩形(pageLayout: 先按层宽适配, 页高超层按层高适配,
  双向居中)——修复长条页放大后拖到左右极限页面不贴边、露背景;②拖动接入
  VelocityTracker+Animatable.animateDecay 惯性滑行, 撞边停住, 再拖接管。
  模拟器实测: 长页测试 PDF(360x1080 页缘红竖线, 资产 long_test.pdf)放大后
  左右拖到极限红边贴屏幕边; 快速滑动松手连拍 1.25s 内容滑行减速;
  普通竖版页无回归）。
- 2026-10-16：v1.5.46 release（PDF 进度恢复精确到页内偏移:reader_book 加
  scroll_offset 列(DB v8 迁移), Progress 加 scrollOffset 字段(仅 PDF 用),
  打开时 initialFirstVisibleItemScrollOffset 精确落位, 页码变化上报+退出
  组合补报——修复"滑动后重开位置不一样"。三个坑已记 docs/pdf-verify-notes:
  upsert VALUES 占位符随列同步加/进度补报 DisposableEffect 注册在 doc 释放前/
  onDispose 闭包须实时读 doc?.pageCount）。模拟器实测: v7 库覆装迁移无感,
  滑动(offset=708)退出重开前后截图逐像素一致, 落库 (0,708), txt 流式书
  进度不受影响; 本版起 APK 由 GitHub Actions 自动构建上传(push tag 触发)。
- 2026-10-14：v1.5.45 release（PDF 放大态四项修复:①放大内容裁剪层改整个屏幕,
  铺满全屏无上下边带,钳制语义改"页面矩形覆盖整屏"并修正 y 轴完整公式,动画
  每帧钳制保证初始铺满;②双击还原跳回首页修复——保存 (index,offset) 并在
  LaunchedEffect(zoomedIndex) 条目恢复后 scrollToItem+dispatchRawDelta 精确
  回位(scrollToItem 正 offset 首条目被钳/onDismiss 立即滚被吞两个坑);③放大
  态返回键先还原放大再退书;④备忘录 pdf 解析失败系文件实为 Markdown 伪装
  .pdf,属正常不改码）。模拟器 debug 实测:放大铺满+四向钳制+还原逐像素回位
  (日志 save 0/200 → scrolled 0/200)+返回键两级行为+菜单/翻页/进度回归全通过。
- 2026-10-14：v1.5.44 release（PDF 双击放大改列表外全屏 overlay:双击放大的页由
  列表上方 PdfZoomOverlay 全屏层承接缩放/平移/还原,页面图在避让系统栏的区域内
  居中占满视口;条目瘦身为纯展示+双击上报归一化焦点+单击呼菜单;列表保留禁滚与
  相邻页跳过,还原摘层后原地恢复——消除与相邻页"重叠"观感的同时,修复上一方案
  的两个缺陷:条目 fillMaxSize 在 LazyColumn 无限高约束下撑不满视口(页面无法
  居中)、放大态垂直拖动钳制塌缩接近锁死）。模拟器 debug 实测:双击放大(页面
  居中,dump 页面图 bounds [0,411][1080,1809])+垂直拖动 900px 跟手+水平拖动
  +双击还原回原页+放大态呼菜单(顶底栏浮于放大层上)+翻页/进度回归全通过。
- 2026-10-13：v1.5.43 release（PDF 放大态拖动平移:双击放大后单指拖动可上下左右
  看页面各处——放大态页面条目启用拖动检测(消费拖动,列表不滚动),平移范围按
  transformOrigin 中心缩放公式钳制在条目框内;未放大态不安装拖动检测,列表滚动
  不受影响;双击还原平移归零）。模拟器 debug 实测:双击放大(焦点缩放)+拖动平移
  (水平/垂直)+双击还原全通过;release 混淆版 EPUB 阅读无回归。
- 2026-10-13：v1.5.42 release（PDF 改连续滚动阅读:整本一个竖向滚动列表(LazyColumn),
  页面位图按可视窗口惰性渲染(当前页±1/后2,复用串行渲染+96MB LRU),页间 2dp 缝,
  去掉横滑翻页与翻页动画(删 PdfPageView);页码/进度按首个可视页实时落账,打开滚回
  持久化进度页,目录/跳页/进度条改滚动定位;页面双击放大/还原(2.5x 焦点缩放),
  单击呼菜单(延迟一拍避免双击干扰);含 v1.5.41 未发之左缩进段行尾截断修复)。
  模拟器 debug 实测:滚动翻页/页码实时更新/单击呼菜单/双击放大还原全通过;
  release 混淆版 EPUB 阅读无回归(PDF 专项验证因模拟器 DocumentsUI 索引卡死
  无法导入新文件而跳过;PDF 渲染链路未改动,debug 已全链路覆盖)。
- 2026-10-12：v1.5.41 release（修复纯左缩进段行尾截断:段级 margin-left(无定宽)
  此前不触发独立断行,主排版按版心全宽断行+绘制右移 ml,行尾超出版心被屏幕裁切
  ——果青3 BONUS TRACK margin-left:5.5em 段每行尾字被裁;needsOverride 补左缩进
  判定,左缩进段走独立断行收进版心）。模拟器 debug 实测:果青3 同页行尾完整、
  缩进保留,前后页无回归;release 混淆版 EPUB 打开/翻页/块位图正常。
  发版过程注:Gradle daemon OOM 崩,`--no-daemon -Dorg.gradle.jvmargs=-Xmx1536m` 通过。
- 2026-10-11：v1.5.40 release（最近阅读过滤:只收录打开过的书——登记态 lastReadAt
  预填导入时间,纯导入未读的书(ready=false)不再混入最近阅读）。模拟器 debug+release
  双包实测:未打开的 testimg 从列表消失,打开过的书与排序不变。
- 2026-10-11：v1.5.39 release（书架顶栏修正:顶层去掉"书架"大标题,双页签即导航,
  消除与"书架"页签的重名;组内顶栏照旧显示组名）。模拟器 debug+release 双包实测:
  顶层直达页签列表,组内标题正常。
- 2026-10-11：v1.5.38 release（书架最近阅读 + 块位图大图）：①书架顶层改双页横滑
  ——最近阅读(默认页,按 lastReadAt 倒序,封面+格式·进度+时间标签)↔ 书架原列表,
  页签点击/横滑切换,组内普通列表不变;②WEBVIEW 块位图可点击看大图——块行矩形
  命中(与绘制同源坐标)复用全屏大图层(共位过渡);③大图手势重做——不注册
  onDoubleTap(单击立即回调,零延迟):点图片 300ms 内二击=双击缩放切换(2.5x 以点击
  点为焦点带动画),单击无操作,点蒙层立即关闭,可视矩形按 graphicsLayer 中心缩放
  公式计算(修复缩放态命中错位);捏合/平移/长按面板保留。模拟器 debug+release
  双包实测:双列表切换/排序/时间标签,块位图点击大图,双击放大还原(日志确认
  scale 2.5→1),点图不关/点蒙层即关,混淆版 EPUB 排版与块位图正常。
- 2026-10-11：v1.5.37 release（阅读器支持 PDF + 两项修复）：①新增 PDF 固定版式阅读
  ——系统 PdfRenderer 直渲（平行渲染路径 PdfScreen/PdfPageView，±2 邻页预渲染+96MB
  位图 LRU，拖拽滑动翻页/进度条/跳页/设置复用现有交互），纯 Kotlin 书签树解析
  （xref 表/交叉引用流/对象流//Prev 链/损坏兜底扫描，加密与解析失败降级空目录），
  页码进度持久化，封面自动生成，无 DB 迁移零新依赖；②修复新导入 EPUB 首开空书
  ——ensureReady 丢弃 doInit 回填结果，阅读页拿到 0 章内容源只剩"最后一页了"，
  且进度落盘覆盖 doInit 落库致永久空书（存量坏书重开自动重新提取自愈）；③修复
  "打开方式/分享"导入 uri 授权不持久——授权不可持久化时登记即转存源文件到应用
  缓存，进程被杀后不再 Permission Denial。模拟器 debug+release 双包实测:PDF 导入
  /阅读/翻页/目录跳页/跳页/进度恢复全链路通过;果青3 空书修复后正常(540 页,混合
  渲染块/插图彩页正常);release 混淆版 EPUB 打开/翻页/WEBVIEW 块位图正常。
- 2026-10-10：v1.5.36 release（块几何采集根本性重做+位图选择修复:①采集就绪改确定性
  静止屏障——资源终态(load/字体/图片 decode,以 error 事件为终态)+MutationObserver
  布局静止(3 双帧窗口零变更),单次采集上报,替代尺寸稳定投票+双遍全等投票+6s 超时的
  概率式方案,保险丝 15s;②几何 rs 改扁平整数数组(体积-40%),上限 5000→20000 字符,
  解析新旧格式兼容;③纯图块上报 ok:2(无文字,非失败),调试面板区分 无文字/无几何;
  ④RENDERER_VERSION 14,存量块自动重渲获得新采集;⑤修复长按空白行产生空选区
  (无高亮无手柄)吞掉整页后续 tap/长按的"选择死锁"）。单测 216 通过;模拟器
  debug+release 双包实测:果青1 重渲后 25 块全部到达静止屏障零超时,此前无几何的
  章13/15/41 等全部几何可用,位图块选词/跨位图与自绘混选手柄拖动/复制入口均正常。
  发版过程注:构建机内存紧张时 Gradle daemon 反复 OOM 崩,降堆
  `-Dorg.gradle.jvmargs=-Xmx1536m` 后通过。
- 2026-10-09：v1.5.35 release（位图调试列表解码期占位:未解码条目按快照宽高显示
  surfaceVariant 底+居中转圈,解码完成后原位替换,消除滚动空白与高度跳动;渲染逻辑
  与 FORMAT_VERSION 零改动）。单测 215 通过;模拟器 debug+release 双包实测:大图
  列表/滑动/点击跳章正常,占位高度稳定无跳动（转圈画面因单张解码约百毫秒短于
  screencap 粒度未能截到,占位布局本身已 UI 确认）。发版注意:模拟器多包共存
  （pub/pub.debug/pri.debug),冒烟前必须核对 topResumedActivity 包名,曾误在
  debug 遗留界面上验证 release 导致误判"面板僵死"。
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
