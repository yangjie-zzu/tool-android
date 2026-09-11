# 阅读器 MVP · TXT 详细设计

> 隶属 [reader-design.md](reader-design.md) 的第一阶段。范围：仅 TXT，核心目标是把
> **分页引擎 + 样式设置 + 进度记忆**这条主干跑通，形成后续所有流式格式复用的核心资产。

## 一、范围

### 做

- SAF 导入 TXT（编码自动检测，GBK/UTF-8/UTF-16 等）
- 书架：书籍列表、进度百分比、删除、继续阅读；按 lastReadAt 倒序（最近在读置顶），
  TXT 无封面统一用默认图标
- 阅读页：滑动翻页（右滑上一页 / 左滑下一页）、单击呼出菜单、可点击进度条跳转、章节目录跳转
- **样式设置**：主题预设（纸白/羊皮纸/护眼绿/夜间）+ 自定义背景/前景色、
  字号、行距、页边距、首行缩进、两端对齐、屏幕常亮
- 进度记忆：章节 + 章内字符偏移，换设置重分页后位置不漂移
- 沉浸模式：阅读页独立于 App 主题，状态栏色随阅读主题联动

### 不做（留给后续阶段）

MD/EPUB/代码格式、书签划线、全文搜索、滚动模式、字体文件导入、
音量键翻页、横屏分栏。

## 二、模块结构（遵循现有工程约定）

每类工具 = `module/<name>/` + 独立 Activity + Compose 页面，持久化走
`filesDir` 下的 JSON（kotlinx-serialization），与备忘录模块（note.json）同风格。
不引入 Room/DataStore。

```
app/src/main/java/com/yukino/tool/module/reader/
├── ReaderActivity.kt        // ComponentActivity，内部导航：书架 ↔ 阅读页
├── ReaderStore.kt           // books.json / settings.json 读写 + 内存态
├── TxtImporter.kt           // SAF uri → 编码检测 → UTF-8 缓存 → 章节索引
├── ChapterSplitter.kt       // 章节识别正则
├── Typography.kt            // ReaderSettings → ResolvedTypography(px)，排版唯一定义点
├── PaginationEngine.kt      // 章节文本 + 版式 → 页切片列表（测量核心）
├── ReaderBookshelfPage.kt   // 书架
├── ReaderPage.kt            // 阅读页：AndroidView(ReaderPageView) + 点击区 + 菜单浮层
├── ReaderPageView.kt        // 自定义 View：draw StaticLayout（只画，不处理手势）
├── ReaderSettingsSheet.kt   // 底部设置面板
└── ReaderTocSheet.kt        // 目录底部弹层
```

接入点：
- `AndroidManifest.xml` 注册 `ReaderActivity`（同 WebActivity 方式），
  `screenOrientation="portrait"` 锁竖屏——避免旋转重建 Activity 丢会话态
  （分页结果/页指针）；分屏改窗口大小时由 viewport 变化自动重分页，不受影响
- `Home.kt` 宫格加一张 `MenuCard("阅读")`
- `app/build.gradle.kts` 新增依赖：`com.github.albfernandez:juniversalchardet:2.5.0`

## 三、数据模型（kotlinx-serialization）

```kotlin
// filesDir/reader/books.json
@Serializable
data class ReaderBook(
    val id: String,                      // UUID
    val title: String,                   // 文件名去扩展名
    val sourceUri: String,               // SAF uri（已 takePersistableUriPermission）
    val cachePath: String,               // cacheDir/reader/books/{id}.txt，导入时转存的 UTF-8
    val encoding: String,                // 检测出的原始编码，仅展示用
    val totalChars: Long,                // 全书字符数（算百分比用）
    val chapters: List<ChapterIndex>,
    val addedAt: Long,
    val lastReadAt: Long,
    val progress: Progress = Progress()
)

@Serializable
data class ChapterIndex(val title: String, val startChar: Long)   // 章标题 + 全书起始偏移

@Serializable
data class Progress(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,             // 章内字符偏移（相对章起点）
    val percent: Double = 0.0            // (章起点+偏移)/totalChars，仅展示
)

// filesDir/reader/settings.json
@Serializable
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val customBg: Long? = null,          // theme == CUSTOM 时生效
    val customFg: Long? = null,
    val fontSizeDp: Float = 19f,         // 范围 12..32，步进 1
    val lineSpacingPercent: Int = 180,   // 范围 120..240，步进 10
    val marginDp: Int = 16,              // 范围 8..32，步进 4
    val indent: Boolean = true,          // 首行缩进 2 字符
    val justify: Boolean = true,         // 两端对齐
    val keepScreenOn: Boolean = true
)

enum class ReaderTheme { PAPER, SEPIA, GREEN, NIGHT, CUSTOM }

// 主题预设色值
val ReaderTheme.colors: Pair<Int, Int>   // (背景, 前景)
//  PAPER  #F6F1E7 / #1A1A1A
//  SEPIA  #E8DCC0 / #4A3B28
//  GREEN  #C7EDCC / #2C3A2E
//  NIGHT  #15171A / #9DA5AE
```

写入策略：进度在翻页时防抖 500ms 落盘 + `onPause` 强制落盘；设置变更即写。
JSON 文件小（书几十本也只几 KB），整读整写即可。

## 四、导入流程（TxtImporter）

1. 复用 `util/base.kt` 的 FilePicker（OpenDocument，type `text/*` + `application/octet-stream`）
2. `takePersistableUriPermission` 保住重访权
3. 流式读入字节，juniversalchardet 检测编码；检测结果置信度低时兜底 UTF-8
4. 解码并**规范化**后转存 UTF-8 缓存文件 `cacheDir/reader/books/{id}.txt`：
   - CRLF/CR → LF 统一换行（GBK 小说几乎全是 Windows 换行，不规范化会污染
     段落切分与行首缩进逻辑）
   - 剥离 UTF-8 BOM（否则第一个章节标题匹配失败、首个字符是隐形 \uFEFF）
   ——之后所有读取只碰缓存文件，原始 uri 仅留作溯源；cacheDir 被系统清理时
   提示重新导入（或下次打开时检测 cachePath 不存在则自动重新转存）
5. ChapterSplitter 扫全文生成章节索引（偏移基于规范化后的文本，全书一致）
6. **去重**：sourceUri 已存在视为同一本书——刷新缓存与章节索引，进度保留
7. 写入 books.json，书架刷新
8. **删除书籍**时连带删除 cachePath 缓存文件，防 cacheDir 无限膨胀

**大文件**：解码 50MB GBK 约占 100MB 短暂内存，现代设备可承受；MVP 设置上限
100MB（超出提示不支持），流式解码优化留给后续。

**章节识别**（ChapterSplitter，逐行匹配以下任一）：

```
第[零一二三四五六七八九十百千万0-9]{1,12}[章回节卷幕]
Chapter\s+\d+
序章|序言|楔子|尾声|后记|番外
```

- 命中行长度 ≤ 50 字符才认定为标题（防正文误伤）
- 全书命中数 < 3 或 > 2000 时，降级为单章全书（标题取书名）

## 五、排版换算（Typography）

排版的**唯一定义点**，测量与绘制都从这里拿值，禁止散落乘 density：

```kotlin
data class ResolvedTypography(
    val fontPx: Float,
    val lineExtraPx: Float,     // StaticLayout 的 spacingAdd
    val indentPx: Float,        // 首行缩进
    val marginPx: Int,
    val textWidth: Int,         // viewportWidth - 2*marginPx
    val textHeight: Int,        // viewportHeight - 顶部页眉区 - 底部页脚区
    val fgColor: Int,
    val bgColor: Int,
    val justify: Boolean
)

object Typography {
    fun resolve(context: Context, s: ReaderSettings, viewport: Size): ResolvedTypography {
        val d = context.resources.displayMetrics.density
        val fontPx = s.fontSizeDp * d
        // 期望行高 = 字号 × 行距%，StaticLayout 行高 = (fm.descent - fm.ascent) + spacingAdd，
        // spacingAdd 用 字号px × (行距%/100 - 1) 近似（误差 <2%，视觉无感）
        ...
    }
}
```

换算表（后续 WebView 渲染器直接照此生成 CSS，本 MVP 只用原生侧）：

| 设置项 | 原生侧（px） | 未来 CSS 侧 |
|---|---|---|
| 字号 19dp | `19 × density` → paint.textSize | `--font-size: 19px` |
| 行距 180% | `字号px × 0.8` → spacingAdd | `--line-height: 1.8` |
| 边距 16dp | `16 × density`，StaticLayout 宽度内缩 | `padding: 16px` |
| 首行缩进 2 字 | `2 × 字号px` | `text-indent: 2em` |
| 两端对齐 | Layout.Alignment.ALIGN_JUSTIFY | `text-align: justify` |

viewport 来源：阅读页 `onSizeChanged`（真实可用区域，已扣除 insets）。
分页缓存键含 viewport，分栏/旋转导致尺寸变化自动触发重分页。

## 六、分页引擎（PaginationEngine）

**职责分工**：StaticLayout 负责断行/缩进/对齐/行距（一行怎么排），
引擎负责行窗口切页（哪些行属于哪一页）。整章只建一个布局。

**输入**：章内文本 + ResolvedTypography
**输出**：`PaginatedChapter`（整章 layout + `List<PageSlice>`，每片 = 行区间）

### 1. 构建整章布局（测量即排版）

```kotlin
val layout = StaticLayout.Builder
    .obtain(spanned, 0, spanned.length, paint, typo.textWidth)
    .setAlignment(if (typo.justify) ALIGN_JUSTIFY else ALIGN_NORMAL)
    .setSpacingAdd(typo.lineExtraPx)
    .setIncludePad(false)                     // 去字体内置留白，行高可控
    .setBreakStrategy(BREAK_STRATEGY_HIGH_QUALITY)  // CJK 避头尾
    .build()
```

- 首行缩进用 `LeadingMarginSpan.Standard(indentPx, 0)` 按 \n 段落套 span，
  **不改文本**——字符偏移与源文件保持一致，进度记忆依赖这一点
- paint.textSize / 颜色均取自 ResolvedTypography（Typography 唯一供值）

### 2. 行窗口切页

```kotlin
var line = 0
while (line < layout.lineCount) {
    val top = layout.getLineTop(line)
    var end = line
    while (end < layout.lineCount &&
           layout.getLineBottom(end) - top <= typo.textHeight) end++
    if (end == line) end = line + 1     // 防御：单行超一页高时强制翻
    pages += PageSlice(line, end)
    line = end
}
```

- 逐行累高而非"行高×行数"——对将来 MD 的标题/代码行（行高不一）零改动兼容
- 段落跨页是自然行为（小说标准排版）；整本无章节的超长"单段"同样天然支持

### 3. 平移绘制（渲染即测量）

ReaderPageView 画某页不新建布局，clip + translate 同一个 layout：

```kotlin
canvas.clipRect(0, headerH, width, height - footerH)
canvas.translate(typo.marginPx, headerH - layout.getLineTop(page.startLine))
layout.draw(canvas)
```

**测量与绘制共用同一 layout 对象**——"同源"的落地点，分页永远精确。

### 4. 进度换算与页定位（行号是偏移与页码的桥）

原则：**页码是派生值，字符偏移是本征值**。持久化只存 `chapterIndex + charOffset`；
页码只在当前分页结果内使用，翻页时随手维护偏移，重分页后由偏移重新派生页码。

```kotlin
fun pageForOffset(chapter: PaginatedChapter, charOffset: Int): Int {
    val line = chapter.layout.getLineForOffset(charOffset)   // 偏移→行,O(log)
    val pages = chapter.pages                                // 行→页,行区间二分
    var lo = 0; var hi = pages.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (pages[mid].startLine <= line) lo = mid else hi = mid - 1
    }
    return lo
}
```

- 页 → 偏移（翻页时维护）：`layout.getLineStart(page.startLine)`
- 偏移 → 页（重定位）：上式两步二分
- 百分比：`(chapter.startChar + page.startChar) / book.totalChars`
- 进度条反查：percent × totalChars → chapters.startChar 二分定位章 → 章内差值

场景对照：

| 场景 | 当前页怎么得 |
|---|---|
| 打开书/进程恢复 | 加载章 → 分页 → pageForOffset(保存的 charOffset) |
| 翻页 | pageIndex ± 1（同时更新待落盘偏移） |
| 跨章 | 前进=新章第 0 页；后退=上一章末页 |
| 设置变更 | 重分页 → pageForOffset(未变的 charOffset) |
| 拖进度条 | 定位章 → pageForOffset(章内差值) |

精度：锚点为原页首字符，重分页后包含该字符的页即当前页，漂移严格小于一页。
防御：charOffset 越界 clamp 到末页；保存的偏移恒为行首，不会落在行中间。

### 5. 缓存与线程

- 分页结果内存 LRU（容量 4 章），键 = `(bookId, chapterIndex, typography 关键字段哈希, viewport)`；
  设置或屏幕变化 → 键变 → 自动失效重分。不落盘，冷启动重分当前章 <100ms
- 惰性分页 + 预取：只保证当前章 ± 1 章，翻到边界再补
- StaticLayout 构建在 `Dispatchers.Default`（5k 字几 ms，50k 字几十 ms），
  UI 持 `StateFlow<PaginatedChapter?>`；翻页只是换 page 指针 + 重绘（<1ms），永远同步

## 七、阅读页（ReaderPage + ReaderPageView）

结构：

```
Box(背景色 = typo.bgColor)
├── AndroidView(ReaderPageView)                   // 只负责 draw StaticLayout
├── 顶部页眉 Row(章节名, 页脚：x/y 页 · percent)     // Compose overlay，小字、次级色
├── pointerInput(detectTapGestures)               // 任意位置单击 → 呼出/隐藏菜单
├── pointerInput(detectHorizontalDragGestures)    // 水平滑动翻页
└── AnimatedVisibility(菜单浮层)                   // 顶栏(返回/书名/目录) + 底栏(进度滑杆/设置)
```

- **返回行为**：系统返回键/顶栏返回 → 先落盘进度再回书架；书架再返回 → 结束 ReaderActivity

- `ReaderPageView.setPage(layout, page, typo)`：持有整章 layout，clip + translate 绘制当前页
  的行区间（见第六节第 3 步），不新建布局
- **滑动翻页（跟手拖拽，覆盖式·书页层序）**：页面层序=书页序——上一页在上层、
  下一页在下层。左滑：当前页(上层)向左跟手滑出，露出静止的下一页；
  右滑：上一页(上层)从左跟手滑入盖回当前页。落影统一贴滑动页右缘。
  **跨章拖拽同样跟手**（邻章分页结果预取，含虚拟章节，当前章 ± 1 LRU）；
  **虚拟章节**：书首/书末各有一个虚拟章节（封面=居中书名 / 末页=居中"最后一页了"，
  16sp 渐显，无页眉页脚），跟手翻入/翻出与普通跨章翻页完全一致，
  松手过阈即停驻在虚拟章节（合法阅读位置，进度可持久化），未过阈弹回；
  虚拟章节**不出现在目录**；
  菜单浮层打开时禁用滑动，防止误触；
  松手判定：**过半屏或滑动速度超过阈值(900dp/s,峰值保持估算)** → 状态先行提交
  （快速连滑不丢页），动画随后纯视觉收尾滑到终点，否则弹回；
  邻章预取未就绪时回退为松手判定后跨章直切；
  菜单浮层打开时禁用滑动，防止误触
- **系统返回手势冲突**：Android 10+ 左右屏幕边缘滑动属于系统返回，
  边缘 24dp 死区**只拦与返回同向的手势**（左缘向右滑/右缘向左滑）；
  离开屏幕边缘方向（左缘向左滑/右缘向右滑）不冲突，可正常翻页；
  不给 `systemGestureExclusion`，尊重系统手势
- 章边界：翻到章末再向后 → 确保下一章分页完成（惰性触发）→ 跳到其第 0 页；反向同理
- 进度更新：每次换页记录 `(chapterIndex, 当前页 startChar, percent)`，防抖落盘
- 设置变更（字号/行距/边距/主题…）：`ResolvedTypography` 重算 → 当前章重分页 →
  **按保存的 charOffset 重新定位到所在页**（二分页切片找包含该偏移的页）——
  这就是"换设置进度不漂移"的实现
- 屏幕常亮：`keepScreenOn` 时给根 Box 加 `Modifier.keepScreenOn()`

菜单浮层：
- 顶栏：返回书架、书名、目录按钮（开 ReaderTocSheet）
- 底栏：进度滑杆（**拖动或点击轨道**跨章跳转，按 percent 反查章节；
  Material3 Slider 自带 track 点击寻位，零额外实现）、设置按钮（开 ReaderSettingsSheet）
- 点击浮层外的正文区域 → 隐藏浮层并恢复滑动翻页

## 八、设置面板（ReaderSettingsSheet）

底部弹层，所有改动**即时生效**（settings 是 Compose state，改完立刻重排重绘）：

| 分组 | 控件 | 范围 |
|---|---|---|
| 主题 | 5 个色卡横排（4 预设 + 自定义），选中态描边 | PAPER/SEPIA/GREEN/NIGHT/CUSTOM |
| 自定义色 | 选 CUSTOM 时展开：背景/前景两行色板（12 预设色）+ hex 输入框 | ARGB |
| 字号 | `A-` `A+` 步进器，中间显示当前值 | 12..32 dp |
| 行距 | Slider + 百分比文本 | 120..240% |
| 边距 | Slider + dp 文本 | 8..32 dp |
| 开关 | 首行缩进 / 两端对齐 / 屏幕常亮 | Boolean |

前景色对比度不做自动校验（自定义色由用户负责），仅当 CUSTOM 且未选色时禁用应用按钮。

## 九、边界与异常

| 场景 | 处理 |
|---|---|
| cachePath 缓存被系统清理 | 打开时检测，缺失则用 sourceUri 重新转存（重新编码检测） |
| sourceUri 授权丢失(极端) | 提示重新导入，进度保留 |
| 空文件/解码失败 | 导入时报错，不入库 |
| 单章超长(整本无章节) | 分页引擎按行切，天然支持 |
| 设置极端值 | 字号 12 也能排下超长 URL 行 → 按行切兜底，不崩不卡 |
| 编码检测错误(显示乱码) | MVP：长按书籍可删除后重导；"重新导入并手动指定编码"留后续版本 |
| 分屏改变窗口大小 | viewport 变化 → 分页缓存键失效自动重分页，当前偏移重新定位 |

## 十、验收标准

1. 导入 10MB GBK 编码小说：编码识别正确，导入 < 3s，再次打开秒开
2. 连续快速滑动翻页无可感卡顿（单页重绘 < 16ms）；点击进度条轨道可跳转到对应位置
3. 改字号/行距/边距/主题，当前阅读位置不漂移（保持在原句附近）
4. 杀进程重开，恢复到上次进度（正确章 + 页）
5. 5 种主题 + 自定义色正确应用到正文、页眉页脚、状态栏
6. 深色系统主题下进入阅读页，阅读主题不受系统影响
7. Windows 换行(CRLF)和带 BOM 的文件导入后段落缩进正常、章节识别正常
8. 同一文件重复导入不出现重复书目；删除书籍后 cacheDir 对应缓存文件被清理

## 十一、开发计划与各环节验证

合计约 6 个工作日 + 1 天缓冲。按"风险最高先做、最细通路尽早打通"排序，
分页引擎（纯函数 + 单测驱动）排在所有 UI 之前。

| 阶段 | 工作量 | 内容 | 验证方式 |
|---|---|---|---|
| D0 骨架 | 0.5 天 | module/reader、ReaderActivity(锁竖屏)、manifest、Home 入口、依赖、4 个样本 TXT(GBK+CRLF / UTF-8 BOM / 无章节 / 超长单行) | 手动：Home 卡片打开空 Activity，样本就位 |
| D1 数据层+导入 | 1 天 | 模型、ReaderStore、TxtImporter(检测/规范化/转存/去重/删缓存) | 仪器化：真样本导入端到端；手动：books.json 记录、重复导入去重；打点：导入 <3s |
| D1.5 章节切分 | 0.5 天 | ChapterSplitter：正则、长度约束、越界降级 | 单测：标题变体/误判/降级；手动：样本长篇切分合理 |
| D2–D3 分页引擎+绘制 ⭐ | 2 天 | Typography、切页算法(与 StaticLayout 解耦)、ReaderPageView、pageForOffset | 单测(假行数据)：拼接还原/无空页/定位/clamp；仪器化(真布局)：拼接还原、5k 字 <100ms；手动 go/no-go：真机读书、改字号重排不漂移、GPU 渲染模式快翻不破 16ms |
| D4 阅读页交互 | 1 天 | 滑动翻页(24dp 边缘死区)、单击菜单、页眉页脚、跨章、防抖落盘、返回存进度 | 手动：验收 1/2/4；边缘死区不误触 |
| D5 设置+目录+进度条 | 1 天 | SettingsSheet 即时生效、重定位、TocSheet、进度条点击/拖动跳转 | 单测：Typography 换算边界；手动：验收 3/5/6 |
| D5.5 书架+联调 | 0.5 天 | 书架(排序/删除清理/继续阅读)、边界场景 | 手动：验收 7/8 + 全量 8 条回归 |
| D+1 缓冲 | 1 天 | 密度/字体兼容、行距视觉校准、OEM 差异 | 手动：多真机矩阵过一遍 |

里程碑：D3 末 go/no-go（核心风险解除，不过关停 UI 层返工引擎）；D5 末功能完整；
D5.5 验收全绿。

提交策略：沿用中文提交风格，按里程碑 4 次提交（骨架+导入 / 引擎+阅读页 /
设置+目录 / 书架+联调），每次提交前对应层验证必须通过。

分页引擎是重点投入对象，它的质量决定后续 MD/EPUB 的成本。

## 十二、验证方式

三层结构，按成本从低到高：

### 1. 本地单测（JUnit，`./gradlew test`，秒级，每次提交必跑）

覆盖纯逻辑。注意 StaticLayout 是 Android 框架代码，本地 JVM 跑不了——
因此切页算法与布局解耦：行窗口切页接收"行信息"接口（行高/行首偏移），
生产实现包 StaticLayout，测试用确定性假实现（如固定每 N 字符一行）：

- **ChapterSplitter**：常规"第X章"、「Chapter N」、序章/楔子、标题超长不误判、
  命中数 <3 或 >2000 降级单章、规范化后文本
- **切页算法**（假行数据）：空文本、单行、超长单段、行高不均（预留 MD 场景）、
  **页切片拼接 == 原文**（最强校验）、无空页、每页高 ≤ textHeight
- **pageForOffset**：随机文本 + 随机偏移，定位页行区间必含目标行；越界 clamp 到末页
- **Typography 换算**：dp→px 数值、行距公式边界值

### 2. 仪器化测试（androidTest，真机/模拟器，`./gradlew connectedAndroidTest`）

验证真 StaticLayout 下的引擎行为，不做 UI 自动化，直接当函数调：

- 真实文本分页后：拼接还原、每页高度合法、5k 字章节构建耗时 < 100ms
- 导入链路端到端：真 GBK/BOM 样本文件走完导入，缓存与章节索引正确

### 3. 手动验收（第十节 8 条清单 + 4 个样本文件）

| 验证项 | 手段 |
|---|---|
| 导入 < 3s | debug 版 Logcat 打点（Importer 首尾计时） |
| 翻页无卡顿 | 开发者选项 → GPU 渲染模式分析，连续快翻条形不破 16ms 基准线 |
| paginate 耗时 | 引擎首尾打点，5k 字 / 50k 字两档 |
| 内存平稳 | Profiler 或 `adb shell dumpsys meminfo`，翻 100 页前后对比无增长 |

节奏：D3 末 go/no-go 走引擎项，D5 末走设置/主题项，D5.5 全量过一遍。

调试设备约定（仅本方案生效）：日常调试一律用模拟器（emulator-5554），
即使 adb 同时检测到真机——所有命令显式 `-s emulator-5554`；
仅最终验收（D5.5 真机项）明确要求时才用真机 serial。

纪律：修 bug 先在 1/2 层写复现用例再修复，回归由测试守护。
