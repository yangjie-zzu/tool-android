# PDF 阅读验证注意点

> PDF 支持验证过程中的操作陷阱与状态依赖，供后续验证复用。

## 导入路径与 uri 权限（重要）

- **"打开方式/分享"（ACTION_VIEW/SEND）导入的 uri 权限不持久**：`takePersistableUriPermission` 对这类 grant 无效，**进程被杀（force-stop、install 覆装、系统回收）后再打开该书必报 "Permission Denial … ACTION_OPEN_DOCUMENT"**，错误提示引导删除重导。这是 txt/epub/pdf 共用的既有 VIEW 导入路径行为，非 PDF 特有。
- **持久验证必须走应用内"导入 → 导入文件"**（ACTION_OPEN_DOCUMENT，权限可持久化）。但实测模拟器上从该 picker 点 PDF 文件会弹系统 resolver（VIEW 语义），选"阅读 + Just once"进入后功能正常；只要**之后不 force-stop/不覆装**，退出书架重开进度正常。
- 验证过程中任何 `adb install -r` / `am force-stop` 都会让已导入书打开失败——要么接受重导一轮，要么把重导成本算进步骤。

## 测试文件

- `make_test_pdf.py`（项目根目录）：生成最小带书签 PDF（3 页 + 两级书签树），推到 `/sdcard/Download/` 后经应用导入。**注意其顶级书签 Chapter One 未挂 /First 子书签**（Section 1.1 是孤立对象，目录里只会出现 2 条）——这是测试文件的既有缺陷，不是解析器 bug；带子书签的完整用例见 `app/src/test/.../PdfOutlineParserTest.kt`（当前 Gradle test worker 在本机环境跑不起来：GradleWorkerMain ClassNotFoundException）。

## 阅读页交互

- **跳页弹窗"跳转"后菜单仍开着**，此时滑动手势被菜单拦住不翻页——先单击关菜单再滑。
- 翻页手势依赖**邻页位图就绪**：邻页未渲染完时滑动页面静止（预取 ±2 页后重试即可）；快速连滑靠退化直切兜底。
- 进度 = 页码（`progress.globalCharOffset` 存 0 基页码，`percent` 仅展示）；重进恢复依赖 `book.ready` 后的持久化进度。
- 目录条目 = PDF outline 书签（纯 Kotlin 解析，失败/加密降级为空目录 + 仅跳页），`chapters[].startChar` 存页码，与流式书的字符偏移语义不同——**不要用流式书工具链解读 PDF 书的 chapters/progress**。
- 调试内部状态（缓存目录 `cache/reader/books/<bookId>.pdf`、`<bookId>.cover.png`）可用 `run-as com.yukino.tool.pub.debug` 查看/删除；**删 pdf 缓存文件可强制下次打开重新初始化**（重新解析目录/重建封面，进度保留）。
- 文件选择器列表长、test_outline.pdf 排在尾部，需下滑 3 屏左右；tile 坐标每次滚动后必须重新 dump。
- 系统 resolver 的"Just once"按钮在不同弹窗布局下 y 坐标不同（全列表 ~2054 / 默认高亮布局 ~1260），必须每次 dump 取 bounds。

## 双击放大态验证（e8e0392 独占视口方案）

- **双击注入**：`adb shell "input tap X Y && input tap X Y"`（设备端连发）可稳定触发；两条独立 adb 命令间隔常超 500ms 会被判为两次单击（呼菜单）。双击判定：500ms 内且两点距 < 宽/3。
- **放大态结构确认**：uiautomator dump 找 `content-desc="第 N 页"` 的 Image 节点 bounds 即页面图 layout 位置（graphicsLayer 缩放不改 layout bounds）；用它判断"条目是否撑满视口/页面是否居中"最可靠。实测（1080x2220）非放大条目高 = 页面图高 1398（pageAspect≈1.294）。
- **拖动位移观察**：大幅拖动后单张截图只能看到钳制终态；要判断"拖了 600px 动了多少"需拖动中连拍（后台 `input swipe ... &` + 循环 screencap，每张 ~0.3s）。
- **放大态架构（2026-10-14 起为列表外全屏 overlay）**：双击放大不再在列表条目内缩放，而是 `PdfZoomOverlay` 全屏层（列表上方、菜单层下方）承接缩放/平移/还原。放大内容裁剪并**铺满整个屏幕**（含系统栏区，无上下边带），平移钳制要求"放大后页面矩形覆盖整屏"——**y 轴钳制必须用完整公式（含页面图 1x 上下边缘 t/b）**，照搬 x 轴公式（页面顶=0 的假设）会让页面能拖出屏幕。放大/还原动画每帧 `pan = clampPan()`，保证初始位置自动铺满。条目只保留"双击上报焦点（onZoomRequest）+单击呼菜单"。
- **还原位置恢复的两个坑**：① 放大态列表只保留被放大条目，LazyList 的 scrollOffset 被重置——须在 onZoomRequest 时保存 (firstVisibleItemIndex, firstVisibleItemScrollOffset)；② `scrollToItem(idx, off)` 的正 offset 在首条目（idx=0）上会被钳回 0，且在 onDismiss 里立即滚会因"zoomedIndex=null 的重组未应用、列表仍只有放大条目、无可滚空间"而被整体吞掉——正确做法：`LaunchedEffect(zoomedIndex)` 等条目恢复后 `scrollToItem(idx)` + `dispatchRawDelta(off.toFloat())`。
- **放大态贴边验证方法（2026-10-17）**：判断"贴边/露背景"必须用像素分析而非目测——阅读页背景米色 RGB(246,241,231) 与页面白色 (255,255,255) 差异很小（目测极易误判），且**红线是 PDF 内容里画的页面边缘标记，不在位图边缘上**（normal_redline 红线距位图边 ~10px、long_test ~13-16px），1x 列表态页面位图本来就贴满屏幕宽——别把"红线不贴屏幕边"误判成"页面不贴边"（曾因此误诊）。放大态极限贴边的像素级判据：极限处左右/上下不应出现背景色列/行；红线极限位置 = 1x 基准页面内偏移 × scale（如 normal 页 2.5x 左极限红线应在 x≈25）。
- **混比例书放大不贴边真 bug 与修复（2026-10-17，cpp_book）**：`pageAspect` 以第 1 页（封面）为准整书统一，但 cpp_book 正文页真实比例 ≈1.484 ≠ 封面 1.423——放大层按统一比例布局时位图 `ContentScale.Fit` 在框内**左右留白**（1x 各 22px、2.5x 各 55px，透明透出背景），拖到左右极限位图永远盖不住屏幕边。此 bug 只有"书内页面比例不统一"的真书才暴露（所有单比例测试 PDF 和封面页 Fit 无留白，全部贴边）。**修复**：`PdfZoomOverlay` 按当前页位图真实比例（bmp.height/width）布局（effAspect，位图未就绪退回统一比例），框=位图重合，回到已验证贴边的数学模型；列表条目仍按统一比例（1x 留白 22px 视觉影响小）。修复后 cpp_book 第 2/3 页四向极限贴边、封面与 normal_redline/long_test 回归通过。**判读教训**：此前一度把 55px 缝误判为"扫描纸边与背景同色"——若 1x 态同位置就有同色带、且位图特征（版心）位置按"位图左缘在缝内侧"换算后 scale 对不上，应怀疑是 Fit 留白而非位图内容。
- **cpp_book 页面比例**：全书各页 PDF 比例一致（1.4231，与封面同），pageAspect 以第 1 页为准的统一假设对该书成立；位图 ContentScale.Fit 填满条目无空白带。若遇到混合比例真书（pageAspect 用第 1 页、其余页比例不同），条目/放大层会按统一比例布局、位图 Fit 出现空白带——放大钳制按统一比例矩形计算，贴边验证时要按位图实际显示区（含 Fit 空白）判读，此场景尚未用真书验证过。
- **放大不贴边根因定案（2026-10-17）**：v1.5.47 的 `TransformOrigin(origin)+translation=pan` 绘制与 `clampPan` 公式**数学自洽**（TransformOrigin pivot 是节点局部坐标，页面图布局偏移 r.topLeft 天然含在变换里）；曾尝试改成 Origin(0,0)+补偿平移，补偿式漏掉布局偏移项，误差=(s-1)×r.top/left（普通页 y 向 616.8px 大缝），已撤销恢复原写法并复测四向极限全部贴边。**教训：改写变换前先推导两边公式是否含布局偏移，别凭"疑似精度问题"猜测绕路。**
- **钳制极限与滑动停靠区分**：swipe 松手惯性可能停在距钳制极限几 px 处（顶部曾差 2px）——先多滑几次/小行程推满再判断；公式错误是几十~几百 px 级的缝，2px 级是停靠未到位。
- **调试日志浮点显示坑**：Compose `Offset.toString()` 只保留 1 位小数（日志显示 origin=(0.5, 0.5) 实际可能是 (0.5, 0.5253)），而 Float 直接拼接是全精度——同一行日志里"看似矛盾"的值先查显示精度再怀疑代码。
- **本机构建 JDK**：默认 JAVA_HOME 是 1.8，`gradlew` 直接跑报 "Dependency requires at least JVM runtime version 11"——须 `JAVA_HOME="D:\Java\jdk-17.0.7" ./gradlew assembleDebug`；debug 产物在 `app/build/outputs/apk/pubApp/debug/`（不是 apk/debug/）。
- **放大态返回键**：zoomedIndex != null 时 BackHandler 拦截返回=还原放大（直接摘层），再按一次才退书。
- **放大态钳制/惯性（2026-10-16）**：钳制与双击焦点换算必须基于页面图**实际布局矩形**（`pageLayout`：先按层宽适配、页高超层则按层高适配、双向居中）——长条页（页高/页宽 > 层高/层宽 ≈2.2）按高适配后页宽小于层宽，照搬"页宽=层宽"假设会让拖到极限时页面不贴边、左右露背景（用页面边缘画红竖线的 `long_test.pdf` 可直观验证贴边）。惯性：`VelocityTracker` 记速 + 松手 `Animatable.animateDecay` 滑行（每帧过 clampPan，撞边停住），onDragStart 取消上一段惯性。
- **PDF 进度恢复含页内偏移（2026-10-16，DB v8）**：`reader_book` 加 `scroll_offset` 列（Progress.scrollOffset，目前仅 PDF 用），PDF 打开时 `rememberLazyListState(initialFirstVisibleItemScrollOffset=...)` 精确落位。进度上报链路有三个坑：① `DisposableEffect` 的 onDispose **按注册顺序执行**——进度退出补报必须注册在 `doc` 释放（`doc?.close()`）之前，否则 renderer 已关 `pageCount=0` 上报被跳过；② onDispose 闭包**只捕获首次组合时的局部 val**（`val pageCount = doc?.pageCount ?: 0` 捕获的是 doc 未载时的 0），onDispose 里必须实时读 `doc?.pageCount`；③ 页内滚动不改页码、`LaunchedEffect(currentPage)` 不重启——页内偏移只靠退出补报落库。另外给共享表加列时 `upsertBook`/`upsertBooks` 硬编码的 VALUES 占位符数量要同步加，否则进度落盘直接崩。
- **本机与模拟器联调的 MSYS 坑**：Git Bash 会把 `/sdcard/...` 参数转换成 `D:/Git/sdcard/...`——`adb push`/`shell -d file://` 等带绝对路径的命令要加 `MSYS_NO_PATHCONV=1`，否则文件被推到设备上的错误路径（push 仍报 "1 file pushed"，极具迷惑性）。
- 首页图标坐标：截图（缩放到 975 宽）与实际（1080 宽，比例 0.903）不一致，"阅读"实际 bounds [854,1805][946,1871]，必须 dump 取实际坐标再点。
