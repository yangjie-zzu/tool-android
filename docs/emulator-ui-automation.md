# 模拟器 UI 自动化操作注意点

来源：v1.5.17~v1.5.21 迭代期间的模拟器验证实践（角标/聚合块/目录页渲染等验证）。
适用于本项目的模拟器 UI 自动化验证；全局约束见用户级 AGENTS.md「全局调试操作规范」。

**记录规范（2026-10-07 定，用户硬性要求）：本类验证文档只沉淀通用性知识（操作方法、
工具用法、交互机制、可复用的排查手段）；具体业务结论（某本书某页面的现象与归因、
一次性实验记录）一律不写入，留在会话/任务层。**

## 应用与包名

- **模拟器调试一律用 debug 包(`com.yukino.tool.pub.debug`),不允许用 release 包调试;
  仅当 debug 无法复现问题时才允许换 release 验证**(2026-10-07 定,用户硬性要求)。
  debug 包优势:可 `run-as` 读私有数据(章缓存 JSON/块位图缓存)、WebView 支持
  chrome://inspect 远程调试、无混淆日志可读。release 包只用于发布前的最终冒烟验证。
- 本项目模拟器上同时装有三个包：`com.yukino.tool.pub`、`com.yukino.tool.pub.debug`、
  `com.yukino.tool.pri.debug`，界面完全相同但**数据互相独立**（书架/进度/设置各自一套）。
  验证前必须确认目标包名；启动统一用
  `adb shell monkey -p <包名> -c android.intent.category.LAUNCHER 1`，
  不要用桌面图标坐标（主页图标位置会因启动对象不同产生串扰）。
- 渲染改动验证必须 `am force-stop` 后重启应用：Compose 组合状态、章文档内存缓存
  （EpubBookContent.docCache）都是进程级的，不重启会拿到旧渲染结果。

## 书架与列表

- 书架列表惯性滚动未停止时，dump/tap 的坐标会漂移——滚动后等待约 2 秒再做 dump 或点击。
- 长书架定位书目用 `uiautomator dump` 按 `text=` 匹配书名取 bounds；同名著（不同导入版本）
  用条目 y 坐标区分，点击前确认 dump 与点击之间列表未再滚动。

## 翻页与页内导航

- 翻页方向：**向左滑（900→150）= 下一页，向右滑（150→900）= 上一页**（v1.5.21 果青1
  实测；与常见阅读 App 一致。本文档旧记录写反，2026-10-06 更正——若某版本表现相反，
  以当次实测为准，翻页无效时先反向滑一页并弹菜单核对页码）。
- **单击屏幕任意位置 = 呼出/切换阅读菜单，不能翻页**（v1.5.22 实测 tap 中央/右侧均只
  弹菜单）；翻完页想核对页码才用 tap 弹菜单。关闭菜单再 tap 一下即可；**绝不能用 BACK
  关菜单——阅读页按 BACK 是直接退出阅读**（回书架/主页，v1.5.22 实测）。
- 菜单内底部进度条：**点击进度条任意位置 = 直接跳到该百分比**；点击左侧「→|」图标 =
  弹出跳页对话框（可输入精确页码）。进度条 y 坐标约 1960（全高清屏），图标中心约 (106, 1951)。
- 跳页对话框输入：先点输入框聚焦，`input keyevent 123`（MOVE_END）+ 连发 DEL 清空原值，
  再 `input text <页码>`；跳转按钮坐标**必须在软键盘弹出后重新 dump 现取**——实测
  1080x2220 屏无键盘时「跳转」在 (834,1334)，键盘弹出对话框上移后变 (834,1016)，
  沿用旧坐标会点到对话框外导致跳转静默失效（对话框关闭但页面不动）。
- 跳页确认后等待 3~5 秒再进行下一步操作：跳页后立即点中央关菜单会干扰物化，
  实测出现过跳页静默回到原页码的情况。
- 插图整页（沉浸模式，如 Illus2 整页图）上中央 tap 可能唤不出菜单（dump 只有框架
  节点）；此时改用连续滑动翻页更可靠。另 v1.5.21+PNG 实测右滑回翻两次均未生效
  （页脚页码不变），左滑前进始终可靠——回翻优先用跳页对话框。
- 首次导入大 epub（如果青1，11MB/45 章）：书架右下「导入」→「导入文件（导入后打开）」
  → SAF 选择器（双列网格，按字母序；先 push 到 /sdcard/Download/ 再选）。导入后依次
  显示"解析章节中…"/"生成装饰页 x/16"，全程约 2~3 分钟（模拟器），期间一直转圈不是卡死。
- **Git Bash 下 `adb push` 会截断 4 字符以上的文件扩展名**：`test_web.html` 落机变成
  `test_web.h`、`fake.mobi`→`fake.m`、`x.md`→`x`（2026-10-22 实测）。测试文件 push 后
  必须用 `adb shell mv` 改回完整文件名再导入，否则会命中"不支持的格式"或走错格式分流。
  文件选择器网格视图里长文件名也会视觉截断（`test_web.h`），dump 拿到的 text 就是截断
  后的显示文本，按截断名匹配 bounds。
- SAF 选择器关闭一次后重开会恢复上次滚动位置附近；每次定位目标文件仍以 dump 实时
  bounds 为准（滚动/动量会漂移）。导入菜单 FAB 中心约 (930, 2022)（全高清屏），
  菜单项按 dump 的 clickable bounds 点，勿按文字坐标。
- 「导入文本」弹窗验证：adb `input text` 不支持中文与部分标点（句点/空格会丢），
  用纯字母数字验证即可；正文为空时"确定"置灰。焦点切换后键盘顶起布局，必须重新
  dump 取输入框 bounds。
- 跳页对话框会显示"当前第 N 页 / 共 M 页"，是确认当前页码最可靠的方式（阅读页页脚
  x/M 只在无菜单时显示）。翻页/跳页后菜单进度条与页脚百分比可能仍显示旧值（取整或
  未刷新），以页码 x/M 为准。

## 定位与检测

- 阅读页正文是 Canvas 自绘，`uiautomator dump` 几乎无文本节点——页内元素定位靠
  截图 + 像素分析（如角标用红色像素判定 `r>170, g<110, b<110` 并聚类取中心）；
  菜单/对话框/设置面板是 Compose 组件，dump 有效性视实现而定，坐标最好现取现用。
- 行内角标（note.png 红圆）像素检测在多行干扰下会有漏检——缩小扫描范围或放宽阈值，
  并结合页眉章名（dump）确认所在章节。
- 跨应用/跨页面的旧坐标一律不可复用；每次点击前重新确认当前界面。

## 渲染产物检查（辅助手段）

- WEBVIEW 块位图与几何落盘在书缓存目录 `wblocks/<key>.png/.json`（json 含 w/h 与
  字符序列 cs），装饰章快照在 `deco_v2/ch_NNNN.png`——可用
  `run-as <包名> cat ... > 本地` 后用 PIL 直接查看，用于**定位渲染问题根因**。
- 2026-10-06 起位图弃用 WebP 改 PNG 无损（`BlockCache.fileOf`/`decoFileOf` 扩展名
  变更）。旧书首次打开时旧 `.webp` 按文件名自动失效并重渲染为 `.png`；书缓存目录在
  内部存储 `/data/data/<包名>/cache/reader/epub/<bookId>/`，需 `run-as` 访问。
- 拉二进制（png/字体等）必须用 `adb exec-out run-as ... cat`——`adb shell` 会做
  LF→CRLF 转换损坏二进制（PNG 头变 0d0d0a 无法解码）。
- 混合渲染渲染器版本 v11（2026-10-06）：WebView 视口直接撑到内容实际宽（桥上报
  scrollWidth，物理 px），完整截取后由显示层按位图比例缩放到版心——定宽溢出块
  （学籍表/人物介绍聚合块）零布局干预完整呈现，断行与浏览器原样；超高仍以
  `transform:scale` 缩到位图上限（绘制级，不触发重排——CSS zoom 会重排，对固定
  尺寸气泡+负 margin 叠放的原书版式是毁灭性的）。页面级背景章 (fillPage) 聚合块
  固定 16px 原书设计字号（不随阅读字号缩放）；章级 docDir 随 codec v22 落盘（聚合
  块 CSS/图片相对引用解析基准，缺失会导致整章裸样式）。
- 旧装饰章快照体系（DecorSnapshot/deco_v2）已移除（2026-10-06）：原"首段装饰盒章
  整章一页按快照呈现"改为正常分页+段级装饰盒自绘+图片段自绘——插图章呈"标题页+
  图页"形态（全书页数随插图章数量增加）；打开书不再有"生成装饰页"阶段。已知保真
  度差异：Illus4-1 类"固定尺寸气泡+负 margin 叠放"页面，App 呈现中登场人物行/
  小鸟图与条目表格有轻微交叠（原书窄视口临界版式，内容完整可读）。
- 拉取渲染问题定位时注意：WebView 块渲染是**流水线并发**（2026-10-06 起：3 个 worker
  并发加载/稳定，`view.draw` 光栅化段全局互斥——并发绘制会击穿 Chromium tile 内存
  上限产生白板，历史实测）。15 块全量渲染从串行 ~60-90s 降至 ~18s；诊断日志
  `BlkRender`（settle/DRAW）与 `BlkJS`（页内测量）可判别布局/加载问题。
- 按全局调试规范第 6 条：此类数据检查只用于定位根因，**功能验证结论必须以模拟器
  UI 实际操作与截图为准**。

## 设置面板(Compose)操作注意点(2026-10-07)

- 阅读设置面板是 Compose BottomSheet,**目测截图坐标与真实触摸坐标偏差可达 100~160px**
  (缩放显示图上量出来的坐标不可直接注入),必须 `uiautomator dump` 现取 clickable/checkable
  节点 bounds;Slider 无独立节点,只能从 thumb 当前 dump/推算位置慢速拖动(swipe ≥800ms),
  每步后截图核对数值(行距实测范围 80%~230% 步进 10%,230% 即滑块上限;段距 0%~250%)。
- 拖动滑块的 swipe 起点/终点必须都在面板内——起点落在面板外会把整个面板收起(已多次踩)。
- WEBVIEW 块渲染只吃 字号/版心宽(边距)/行距/行距跟随书内 四个设置(缓存键),段距/
  首行缩进/两端对齐不影响块位图,对齐块渲染实验时无需调它们。

## ModalBottomSheet 面板操作注意点(2026-10-09)

- `adb exec-out screencap` 截图分辨率(974 宽)与真实屏(1080x2220)不一致,**截图上量出的
  坐标必须乘 1080/974≈1.109(纵向 ≈1.111)换算后再 input tap**,否则点击静默落空(表现为
  界面毫无反应,极易误判为功能失效);换算基准以 `dumpsys window windows` 里应用窗口
  bounds 为准。
- 位图调试面板的列表条目(整幅大图 + clickable)用 `input tap` 可正常触发;但实测出现过
  一次面板实例整体失去点击(条目/TextField/遮罩点按全部无效,仅滑动滚动有效)的僵死现象,
  此时 logcat 可见该面板窗口 "Setting back callback null / Input channel disposed"——
  重开面板即恢复。遇到"面板看得见点不动"先怀疑实例僵死,关开面板重试,勿急于改代码。

## 文字选择验证注意点(2026-10-10)

- 长按用 `adb shell input motionevent DOWN x y` → sleep ≥1s → `input motionevent UP x y`
  注入;`input swipe x y x y 时长`(零位移)或 `input tap` 不能触发 Compose 长按。
  手柄拖动用 DOWN→多条 MOVE(步距 ≤30px、间隔 ≥100ms)→UP,单次大步距 MOVE 抓不到柄。
- 截图坐标→设备坐标换算: 截图 974 宽/设备 1080 宽(≈1.109),纵向 ≈1.111;换算错会
  误判"选择失效"。验证基线: 先在纯自绘文本页长按成功,再测位图块页。
- 已知修复前缺陷(≤v1.5.35): 长按落在空白行会产生空选区(无高亮无手柄)并吞掉整页
  后续 tap/长按,表象是"选择死锁";tap 一次可解。修复在 v1.5.35 之后的版本。

## 数据层调试(debug 包)

- run-as 可直接读章缓存 JSON(`cache/reader/epub/<bookId>/chapters/ch_NNNN.txt`,v22
  段级字段:`wb` 是否 WEBVIEW 段/`b` 盒样式下标/`lh` 段级行距倍率)与块位图缓存
  (`wblocks/*.png+json`)——用于**定位渲染问题根因**(哪段走了哪条渲染路径、位图是否
  存在),功能验证结论仍以 UI 实际操作与截图为准。
- WebView DevTools 开关(`WebView.setWebContentsDebuggingEnabled`)是**进程级静态**:
  进程内开过一次"web"工具即对所有 WebView 生效;但离屏块渲染的 WebView 生命周期短
  (渲染完即销毁),DevTools `/json` 轮询很难抓到,块内排查优先走位图缓存+章缓存。

## SAF 持久化授权丢失复现方法(2026-10-25)

- 复现"Permission Denial: opening provider com.android.externalstorage.
  ExternalStorageProvider"（SAF 持久化授权被系统收回）：`adb shell cmd package
  uninstall -k <pkg>` 保数据卸载——**应用数据(书架/进度/缓存)全保留,但系统侧
  持久化 URI 授权被清空**；随后 `adb install -r` 同签名重装即可。完全卸载(无 -k)
  会连书架数据一起清掉,不适合复现"书目还在、授权没了"的场景。
- 只清授权还不够：已读过的书 cache 命中时根本不读源文件(不触发授权检查),需
  `run-as <pkg> rm cache/reader/books/<bookId>.txt|.pdf|.cover.png` 删掉转存缓存,
  下次打开才会重读源 uri。cache 文件名即 bookId,`run-as ls cache/reader/books/`
  按体积/时间对应到书目。
- `dumpsys package <pkg>` 在 Android 14 上看不到 "Granted URI Permissions" 段,
  授权是否被清以实际打开行为为准。
- 真机上授权丢失的常见来源：卸载重装、Android 11+ 未使用应用权限自动重置、
  部分 ROM 清理策略、源文件/目录被移动或存储卷变化。

## adb push 的 Git Bash 路径转换陷阱(2026-10-25 实测补充)

- 除扩展名截断外,`adb push src /sdcard/...` 的目标路径会被 MSYS 转成本地盘符
  (如 `D:/Git/sdcard/Download/x.txt`),报 `remote secure_mkdirs failed`；且实测
  出现过**同一命令里第一次 push 打印 Success 但设备上并无此文件**的假成功——
  push 后必须 `adb shell ls` 复核文件真实存在。稳妥做法:命令前加
  `MSYS_NO_PATHCONV=1`,且从文件所在目录用相对路径推。

## 书架与 DocumentsUI 定位注意(2026-10-25 补充)

- 书架按 lastReadAt 排序,读过一本书后全列表位置下移——**每次回书架点书前必须
  重新 dump 定位**,沿用旧坐标会点错书(实测点成另一本)。
- 系统文件选择器(DocumentsUI)会记住上次目录、滚动位置与**网格/列表视图**;
  同一文件两次打开时坐标可能完全不同(网格 2 列 vs 列表 1 列),每次都重新 dump。
  Download 文件多时需连续滚动多次,注意滚动过头时反向小滚。
- 阅读页错误提示/按钮是 Compose 节点,可 dump 定位;正文 Canvas 区无节点,内容
  是否渲染用截图像素方差判(content 区 stdev≈50 有字,≈10 近空白,≈0 纯背景)。
- Toast 验证:uiautomator dump 抓不到 Toast,须 tap 后立即 `exec-out screencap`
  (0.5~1s 内),晚了 2s 即消失;视觉确认可用截图。

## 系统悬浮窗(TYPE_APPLICATION_OVERLAY)验证方法(2026-10-26)

- 悬浮窗权限免 UI 授予: `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`,
  免去真实设置页走查(重复测试/release 包提速用);首次功能验证仍应走真实路径:
  canDrawOverlays 检查 → `ACTION_MANAGE_OVERLAY_PERMISSION` 跳"Display over other
  apps"列表 → 点应用行 → 详情页开关。注意开关本身在 dump 里**无 checkable 节点**,
  直接点"Allow display over other apps"整行;授权生效用 `appops get <pkg>
  SYSTEM_ALERT_WINDOW` 确认(allow/default)。
- 悬浮条可见性断言: uiautomator dump 不稳定暴露 overlay 窗口,用截图像素扫描——
  底部区域逐行条带 mean<150 计深色条带数(悬浮条≈10+ 条带,移除后 0);精确文字
  读数用底部裁剪图视觉确认。顶部条同理(y 140-360 扫描)。悬浮条按钮点击坐标:
  `dumpsys window windows` 拿窗口 `mAttrs y=` 与 Requested 宽高 + 像素暗带推算,
  ✕ 在条右端内侧。
- 悬浮窗横滑(HorizontalScrollView)注入: `input swipe` 快滑(600ms 级)实测不滚动,
  需 ≥900ms 慢速拖动;滚动是否生效用前后截图条内像素 diff(>1% 即动)判定。
- WindowManager 悬浮窗的宽度别依赖 weight: LinearLayout 的 weight 子项在窗口
  UNSPECIFIED 测量下会塌成 0 宽(条只剩关闭钮一小块)——显式 measure 文本/按钮
  后按 `min(文本宽, 上限-装饰宽)` 定子项与窗口宽(SourcePathOverlay 实现即此)。
- 悬浮窗"文字画出来、background 不画"故障(2026-10-27 实测): 是**模拟器图形会话
  损坏**, 不是代码问题——同 APK 跨多个 app 进程持续复现, 重启模拟器后即恢复;
  排查顺序应为先重启模拟器做判定实验, 再怀疑代码(本次为此空跑了两轮代码实验)。
  连带坑: 模拟器重启本身可能失败/挂死——%TEMP%\AndroidEmulator crashpad 库损坏
  (反复报 attachments GetFileAttributes 错)会阻塞启动, 将该目录改名删除+清 AVD
  *.lock+taskkill 僵尸 qemu 后正常。
- 悬浮条触摸死区排查法(2026-10-27): 条看似整块深色, 但可滚动条带只占一小段
  ——逐 y 值 input swipe 探测(log 该层 onTouchEvent 是否收到事件)定位真实消费
  带; 根因常是某个子视图(如关闭钮 TextView)的竖向 padding 在高 dpi 下把整条
  撑高(440dpi 实测 15sp+2×10dp 内边距=110px), 修法: 竖向零内边距+高度
  MATCH_PARENT 垂直居中。overlay 窗口加 `setFitInsetsTypes(0)`(API30+)防系统栏
  inset 被当窗口内边距。
- 重新授权选错文件仍会对"被选的那个 uri"take 持久化授权(匹配校验在 take 之后)
  ——测试书的授权可能被误选操作顺带恢复, 复现授权丢失时注意此干扰。
- DocumentsUI 选择器的 BACK 语义: **先向上退目录,到根目录再按才关闭选择器**——
  验证"取消路径"的悬浮条移除断言必须先把选择器真正退掉,别在目录导航中途下结论。
- 模拟器多包共存时, uninstall -k/monkey/导航后必须核对 topResumedActivity 包名
  再操作——实测出现过 debug 包测试中焦点漂到 release 包阅读页导致 dump 全空的
  串扰(老坑再现),HOME 键回桌面再 monkey 指定包即可复位。

## 二进制文件抓取陷阱(2026-10-07)

- `adb shell`/`adb shell run-as ... cat` 通道会把 LF 转 CRLF——拉 APK/PNG/JPG 等
  二进制必须用 `adb exec-out "run-as <pkg> cat <path>"`,否则文件被污染出现假
  "损坏"证据(文件头 0d0d0a、体积变大)。验证过 exec-out 拉出的图片与 epub 内
  逐字节一致。
- 书目录 `cache/reader/epub/<bookId>/` 下残留大量历史调试脚本(fix*.py/patch*.py
  等),是既往会话 run-as 写入的,与应用无关;判断"解压是否干净"勿被干扰。
- 无 `wblocks` 目录 = 该书 WEBVIEW 块位图从未成功落盘(渲染失败或全书自绘分流);
  块位图缓存按书目录存放。
- 阅读页翻页方向再确认:900→150 左滑=下一页,150→900 右滑=上一页。
- OBJ 虚线框 = U+FFFC 占位符被当正文文字绘制的缺字 tofu(框内字面 "OBJ"),
  见到即说明图片段/行内图未走图片绘制管线被降级成文本行,与 Chromium 破图无关。
