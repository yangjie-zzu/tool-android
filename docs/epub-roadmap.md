# EPUB 原生支持 · 总体方案与剩余期数规划

> 状态基线:2026-10(v1.2.2)。一/二/三/四/五期与六期(外部 CSS+float 降级+负 margin)均已完成;
> 书签与全文搜索未做(方案保留在三期章节)。本文档记录已完成架构与剩余项实施方案。

## 六期 · 设计页排版支持(已完成,随 v1.2.2)

真书扉页(title.xhtml)暴露:□true/or/√false 选择框松散左对齐,与原书"右浮紧凑小框"设计不符。
三项能力(方案分析见对话记录,组合约 90 行,不触碰分页核心):

- **A1 外部 CSS**:`extract(file)` 收集 `<link rel="stylesheet">` 指向的 CSS 文件(相对文档文件解析,
  单层引用,2MB 上限,缺失宽容跳过)→ 喂给既有 parseStyleBlock。真实 EPUB 样式主要在外部 CSS,
  此项让全书 class 排版(text-align/indent/margin)整体生效。
- **A2 负 margin**:段前额外距允许负值(margin 简写按 CSS 语义取上/下),下限 clamp 为
  抵消全局段前距(不侵蚀行高本体,防文字重叠)。
- **A3 float 降级**:`float:right` 的块标记为右对齐独立块(子段未显式对齐时 align=2;
  显式 text-align 优先),无文字环绕(完整 float 需区域约束分页,500+ 行不划算)。

章文件版本 v6(解析期能力升级——v5 书的 align 缺 float 识别结果,旧书打开自动升级重提取);
`BREAK_STRATEGY_VERSION 13`。
验收:真书 title 页"□true(右)/or(中,外部 CSS)/√false(右)"贴近原书设计;v6 样本书
(link CSS+float+负 margin)三项生效;负 margin 提取/float 识别/外部 CSS 读取单测覆盖;零崩溃。

## 五期 · 行内图片(已完成,随 v1.2.1)

真书测试(《果青 1》,Calibre EPUB2)暴露:23 组脚注全部为**图片型角标**
(`<a epub:type="noteref"><sup><img src="note.png"/></sup></a>`),三期实现只认文本角标 → 角标丢失。
本期为通用**行内小图**能力,详尽可行性分析见 `inline-image-feasibility.md`。

- **解析**: noteref 角标内容为空时扫子节点 `<img>` → 投影落 U+FFFC 占位,段落记
  `inlineImages: [(段内偏移, 图片相对路径)]`;章文件 v5(`ii` 字段,`FORMAT_VERSION=5`),
  旧书打开自动升级。
- **断行**: compose 时占位符挂 `ReplacementSpan`(getSize 返回图片等比尺寸,行高跟随字号
  不撑行;draw 空实现,绘制走自绘管线);尺寸 = 高 1.2 倍字号等比,上限 1.6 倍字号/版心宽,
  坏图按一个字宽方框占位。
- **物化**: `ChapterLines.inlineSizes` 预取(断行时算好);drawLines 折算行区间内的
  `DrawInline(行内下标, 路径, w, h)` 进 `DrawLine`。
- **绘制**: `drawInlineLine` 按占位符切段绘制(文本段样式/基线偏移按段折算,图片底边贴基线
  下沉 0.18 倍字号);与两端对齐拉伸正交(U+FFFC 被 tokenize 当宽字符独立成词元)。
- **点击命中**: 占位符的点击度量不含 ReplacementSpan 宽,字符吸附可偏多个字符——
  命中行内先做"角标偏移差匹配"(容差 3 字符),未中退常规 ±1 邻域;行内点击不落角标容差时不弹菜单。
- **验收**(2026-10-03):v5 样本书(文本/图片角标混合)与真书(自动升级 v4→v5 后 6 组角标)
  角标行内显示正确(justify 下位置准确)、点击弹层内容正确;单测 131 个;零崩溃。

## 一、已完成(一期,随 v1.0.8~v1.0.10 发布)

### 架构

```
reader/
├── common/                 公共层(格式无关)
│   ├── BookModels.kt       ReaderBook/ReaderGroup/ChapterIndex/ReaderSettings
│   ├── BookContent.kt      ChapterDocument/Paragraph/BookContent 接口/TxtBookContent/ImportResult
│   ├── BookPage.kt         排版三层: TextLine(断行)→PageSpec(分页)→DrawLine(绘制) + BookPager
│   ├── PaginationEngine.kt 网格分页引擎
│   ├── Typography.kt       dp→px 唯一换算点
│   └── ReaderPageView.kt   自绘渲染 View
├── TxtImporter.kt          TXT 懒初始化(编码检测→转存→章节切分)
├── epub/                   EPUB 独立主流程
│   ├── EpubImporter.kt     ensureReady: 解压/DRM 拒绝/逐 spine 提取/章文件落盘
│   ├── EpubOpf.kt          container.xml/OPF/NCX/Nav 解析(前缀化标签按本地名匹配)
│   ├── HtmlTextExtractor.kt Jsoup XHTML→纯文本段落
│   └── EpubBookContent.kt  章文件懒加载(BookContent 实现)
└── BookRegistrar.kt        登记式导入(单本/文件夹成组)
```

### 关键设计决策

1. **两条主流程完全分离**:TXT 与 EPUB 各自完整的导入/初始化链路,只在公共层(`common/`)相遇;排版/渲染层只认识 `BookContent` 接口,不认识格式。
2. **内容模型**:`ChapterDocument(title, paragraphs: List<Paragraph>)`,`bodyText` 是段落纯文本投影;全书字符偏移(进度/选区/分页缓存锚点)全部定义在投影上。二期在此扩展 Run(内联样式)与图片。
3. **登记式导入 + 懒初始化**:导入只写一行元数据(文件名/格式/大小/组),内容转码/解压/章节解析推迟到首次打开(`ensureReady`),失败收敛为 `BookInitException` 文案。
4. **存储单行化**:SQLite(手写 `AppDb`,v1.0.10 时为 v5)全部单行 upsert/delete,无全量重写;分组表 `reader_group`(可嵌套,`source_uri` 记录来源文件夹供重复导入去重)。
5. **章文件布局**:EPUB 每章一个 UTF-8 文本文件 `cache/reader/epub/<bookId>/chapters/ch_NNNN.txt`,章区间在全书偏移轴上连续拼接(章间一个虚拟换行偏移)。
6. **兼容性经验**:真实世界 EPUB 常见前缀化 OPF(`opf:package`)、空标题 NCX、spine 混图片条目——解析层已做宽容处理,后续新功能须延续该原则。

### 修复史(避免回退)

- OPF 前缀标签解析(v1.0.9)
- 空标题 NCX 条目丢弃,章名兜底文档内 h1~h6(v1.0.9)
- 首页版本号改 resValue(BuildConfig 常量被 Kotlin 内联致增量编译残留旧值,v1.0.9)
- 懒初始化回填被进度落盘旧对象覆盖(load 返回刷新后的书同步回内存态,v1.0.10)
- 文件夹遍历路径分隔符丢失、根目录未成组(v1.0.10)

---

## 二期 · 富文本与图片(已完成,随 v1.1.0 发布)

**目标**:书"看起来像书"——粗斜体、插图、封面。排版/渲染层唯一一次实质扩展。

### 实施结果(与规划的差异点)

- **章文件格式**:一期纯文本 `ch_NNNN.txt` 装不下样式/图片,二期改为 JSON(`ChapterFileCodec`,
  段落 = {t 投影文本, r 扁平 [s,e,style...] 三元组, img 相对路径})。读取先试 JSON、失败按纯文本
  split('\n') 回退——一期老书无缝可读(无样式),不做强制重初始化(列表前缀会改投影导致进度漂移)。
- **IMAGE 段投影占位 U+FFFC**(单字符),全书偏移轴保持连续,进度/选区/分页锚机制零改动;
  imageRef 在章文件里存解压目录相对路径,EpubBookContent 加载时转绝对路径,排版/渲染零路径解析。
- **解析侧** `HtmlTextExtractor`:规整(空白折叠/CJK 粘连清除)与 Run 边界记录同一次遍历完成
  (事后映射必错位);内联标签不是段落边界;Run 全区间覆盖(样式段之间的普通文本显式 style=0,
  绘制层按区间无缝覆盖);style 属性宽容匹配;img 独立成段;ol/ul 前缀 "N. "/"• ";
  简单表逐行 "a | b",跨行跨列/嵌套表出占位段。
- **排版侧**:段落级剥标题 `stripLeadingTitleParas`(与字符级逐字符等价,测试覆盖);段落区间表
  `paraRanges` + 行内样式折算 `lineStyles`(二分定位段落);图片行按 decodeBounds 等比占位
  (超高图 cap 到一页高,坏图占位 3 行高),行高替换不走 fitPitch(满页排版只对文本行生效)。
- **渲染侧**:样式段衍生 paint 缓存(SparseArray,版式变化重建);下划线/删除线画线、上下标
  字号 0.65 + 基线偏移;样式段与 justify 词元分段正交(按字符游标裁剪);图片 Bitmap LRU
  (32MB,单线程后台解码,未就绪画占位框),换书 clearImages;COVER 页画封面图(版心内等比居中)。
- **封面**:OPF 四级探测(properties cover-image → meta name=cover → id 含 cover → spine 首图)
  解析期一次完成;封面文件本就在解压目录,直接记路径不复制;书架 48dp 缩略图(后台解码 ~96px)。
- `BREAK_STRATEGY_VERSION` 9→10(旧分页缓存全失效重算一次);AppDb 未升版(cover_path 一期已建)。

### 验收结果(2026-10-02 模拟器)

1. 自建测试书 `sample_测试书_v2.epub`(封面/粗斜体/下划线/删除线/上下标/style 属性/横图/超高竖图/
   有序无序嵌套列表/简单表/复杂表)全部渲染正确,截图存 `shots/`;
2. TXT 回归:book3(1.6MB)打开定位进度位置,排版与翻页正常;
3. 单测 100 个全部通过(新增 `EpubRichContentTest` 22 个:投影不变性/Run 边界/编解码回退/
   剥标题等价/样式折算);logcat 无崩溃无异常。

### 遗留小项(不影响使用,后续顺手可修)

- 章内 `<h1>` 与 NCX 章名仅差空格时(如 "第一章样式" vs "第一章 样式")不剥重——一期同行为,
  可在 `dedupeLeadingTitle` 比较前规整空格;
- 超高图缩到一页内后宽度小于版心,当前靠左不居中(图片行 DrawLine.x 恒 0)。

### 老书自动升级(随 v1.1.0)

一期导入的 EPUB(纯文本章文件、无封面)在**打开时自动升级**为二期格式,无需删书重导:

- **触发**: `ensureReady` 快速路径检测首章文件非 `[` 开头(`ChapterFileCodec.isLegacyFormat`)
  → 升级分支,阶段文案"升级书籍内容中…"。升级后章文件为 JSON,检测不命中,天然幂等。
- **进度迁移** `migrateProgress`: 旧偏移二分定位旧章 → 章号经 `mapChapters` 映射到新章
  → 章内偏移按"旧章长∶新章长"等比缩放。章号映射用**顺序保持的标题匹配**(双指针+锚点间
  线性插值)——升级只会增章(一期跳过的图片页登记为章,如 longmo 358→359 章),旧章序列是
  新章序列的子序列;实测 longmo 50% 进度迁移后偏移仅差 5 字符。
- **失败安全**: 升级在 `dir/upgrade_tmp` 完成解压提取(persist=false 不落库),成功后才
  rename 替换 `chapters/` 并把封面搬运到书目录根 `cover.*`;失败删临时目录返回原书
  (老格式照常可读,下次再试);中途崩溃由既有 `filesOk` 完整重建路径自愈。
- **图片页章名**: 纯图片章的目录标题常是文件名(如 "0.jpg"),落章名时规整为"插图"。
- **验证**(2026-10-02,真实"一期导入→覆盖安装→打开"场景): longmo(12.4MB/358→359 章/50% 进度)
  迁移精确保留(12/71 50%)、章文件 JSON 带样式;rich_v2 封面回填、封面页正常;logcat 零崩溃。

### 内容模型扩展(`common/BookContent.kt`)

```kotlin
// 现状
class Paragraph(val text: String)
// 扩展为
class Paragraph(
    val text: String,               // 纯文本投影(偏移锚定,永不变)
    val runs: List<Run> = emptyList(),   // 空 = 整段单一样式(TXT 恒为空)
    val kind: Kind = Kind.TEXT,          // TEXT / IMAGE
    val imageRef: String? = null,        // kind=IMAGE: 解压后绝对路径
)
data class Run(val start: Int, val end: Int, val style: Int)  // style 位标记: bold/italic/underline/strike/sup/sub
```

原则:**纯文本投影(`text`)与富文本信息(`runs`)分离**——进度、选区、搜索、TTS 等一切偏移机制仍只依赖投影,Run 只影响绘制。

### 解析侧(epub/xhtml)

- 新增 `InlineStyleResolver`:HTML 标签(`<b>/<i>/<em>/<strong>/<u>/<s>/<sup>/<sub>`)与内联 `style` 合并为 Run 边界;嵌套标签区间合并。
- `XhtmlBlockBuilder` 产出带 Run 的段落;`<img>`(块级独立成行)产出 IMAGE 段落,imageRef 指向解压目录内文件;`<image>`(SVG 内)暂降级忽略(四期 SVG 栅格化时启用)。
- `ImageCollector`:章内图片路径清单(懒初始化时随章文件一起记录,渲染时按需解码)。

### 排版侧(common/layout)

- 断行:`StaticLayout` 带 CharacterStyle span 度量(断行结果与投影逐字对齐,行区间模型不变)。
- `TextLine`/`DrawLine` 携带 Run 边界(行内第 i 字符所属 Run,由段落 runs 折算)。
- IMAGE 段落:排版产出 `ImageLine`(按版心宽等比缩放,高度计入分页;超高图缩到一页内)。
- 满页排版(fitPitch)只对文本行生效,图片行按实际高度占位。

### 渲染侧(common/render)

- `ReaderPageView` 按 Run 切换 paint:粗体(Typeface.BOLD)、斜体(skew -0.25f)、下划线/删除线(画线)、上下标(字号缩放+基线偏移)。
- 图片行 `drawBitmap`,按需解码(Bitmap 内存 LRU,按章缓存,翻页/换书释放);解码在 Default 线程,首帧未就绪先画占位框。

### 封面

- 探测顺序:OPF `cover-image` property → manifest id 含 cover → meta name=cover → 第一个 spine 图片文档。
- 懒初始化时解出封面到 `cache/reader/epub/<bookId>/cover.*`,`ReaderBook.coverPath` 回填。
- 书架:书目行首显示封面缩略图(48dp,无封面用现有图标);阅读页 COVER 页从纯文字升级为封面图(无封面回退文字)。

### 列表/表格降级

- `<ol>/<ul>`:序号/圆点前缀转文本段落。
- 简单表格:逐行"单元格 | 单元格";含 rowspan/colspan 的复杂表格显示"[表格内容,建议使用原版式查看]"占位段。

### 验收

1. 含粗斜体/插图/封面的样本书渲染正确,样式与图片位置合理;
2. TXT 回归:同一 TXT 新旧分页逐页比对无差异(单 Run 退化路径);
3. 选择/进度/分页缓存机制照旧(投影偏移未变);
4. 大图书内存稳定(图片 LRU 生效,无 OOM)。

### 风险

- 断行带样式度量的性能:章 LRU 已有;必要时按 Run 缓存 TextPaint;
- 图片解码内存:LRU 上限(如 32MB)+ 翻页释放;
- 兼容性:内联样式写法千奇百怪,`InlineStyleResolver` 需配套样本书测试集。

---

## 三期 · 阅读功能(部分完成:锚点跳转+脚注弹层随 v1.1.1;书签/搜索未做)

### 章内锚点跳转(已完成)

- 导入时 XHTML `id` 记为段落 anchor(`Paragraph.anchor`;容器 id 指向其首个产出段落,
  `openAnchors` 栈最早优先消费);目录条目 fragment 经 `tocMap` 存入 `ChapterIndex.anchorId`
  (同文档多条目录仍取首条,一期同约定)。
- 目录点击: `EpubBookContent.anchorOffset` 扫段落定位章内偏移 → `chapterStart + off` →
  `locatePage` 落**锚点所在页**(页粒度;异常落点防御退章首);TXT 无锚点退章首。
- 章文件 JSON 段落字段 `a`。

### 脚注弹层(已完成)

- 识别(保守): noteref = `epub:type`/`class` 含 noteref 的**同文档** a(跨文档降级普通文本,
  不误伤普通内链);脚注容器 = `epub:type=footnote` / class 含 footnote|note / id 被 noteref
  引用——三者任一即从正文流剔除,文本提入章级 `footnotes` 表(不参与排版与偏移)。
- 角标: noteref 文本保留进投影(偏移轴含角标),套二期 SUP 样式自动小字上标;
  区间记 `NoteAnchor`(段内坐标)随章文件 JSON(`n` 字段)持久化;章文件顶层升级为
  `{p:[...], notes:{id:text}}` 对象(读兼容二期数组/一期纯文本)。
- 交互: tap → 复用 `SelectionGeometry.hit/globalAt` 得全书偏移 → `footnoteAt` 查章脚注表
  → ModalBottomSheet 显示,关闭即回原位(不改页面状态);菜单打开时不检测角标。
- 样本书 `sample_测试书_v3.epub`(目录带 fragment/EPUB3 aside 脚注/class 风格脚注/普通内链)
  已入库;验证:锚点落页正确、两类脚注弹层内容正确、内链不误判、TXT 回归无差异、零崩溃。

### 书签 / 全文搜索(未做,按用户指示跳过;方案保留如下)

- 书签: `reader_bookmark` 表(book_id, chapter_index, global_offset, excerpt, created_at);
  阅读菜单"添加书签"+目录抽屉书签列表;偏移锚定不漂移。
- 搜索: 按章扫描投影(并发限流,流式出结果),章名+命中行上下文,点击定位。

### 验收(三期已做部分)

1. 含脚注+锚点样本书:角标可点、弹层内容正确、关闭回原位 ✓;
2. 目录带 fragment 点击落锚点所在页 ✓;
3. TXT 回归无差异 ✓。

---

## 四期 · 增强(已完成:CSS 子集+SVG 栅格化+三个实验项,随 v1.2.0)

### CSS 子集(已完成)

- **来源**:`<style>` 块单类名选择器(`parseStyleBlock`,从整个文档收集——style 通常在 head)+
  元素 style 属性(style 优先);复杂选择器/继承链/px·百分比单位忽略。
- **text-align(center/right)**:段落级 `align` → compose 用 `AlignmentSpan` 参与断行度量
  (右对齐 = ALIGN_OPPOSITE,LTR 即右)→ 物化按行自然宽算起点 x,跳过两端对齐拉伸;
  图片行同样随段对齐。
- **text-indent**:段级 `indentEm` 覆盖全局缩进(0 = 显式顶格;对齐段不做缩进);
  段首自带空格的检查保留。
- **块级 margin**:段级 `spaceAboveEm/belowEm`,排版时叠加进段首行段前距(前段 below+自身
  above,不折叠;网格化)——受"段距跟随书内"设置开关取舍(默认开;关=纯全局段距,
  ReaderSettings.bookSpacing,AppDb v6)。

### SVG 栅格化(已完成)

- 新依赖 `com.caverock:androidsvg:1.4`(roadmap 预告的唯一新依赖;注意 1.4 无 getFromFile,
  用 getFromInputStream)。
- `SvgDecoder`(common):bounds(文档尺寸,排版占位)/decode(按目标尺寸栅格化,渲染 LRU/
  书架缩略图共用);EpubBookContent.imageBounds 与 ReaderPageView.imageFor 按扩展名分派,
  渲染层无感。覆盖:img 引用的 .svg 插图、SVG 封面(封面页+书架缩略图)。
- 不做:HTML 内嵌 `<svg>` 元素、独立 SVG spine 文档。

### h2 小节二次拆章(实验项,已完成)

- 解析侧 h1..h6 段落记 `heading`;`EpubImporter.splitSections` 按 heading==2(非首段)切分,
  首小节用目录名(level 0),后续小节用 h2 文本(level 1,anchor = h2 id,目录锚点跳转衔接);
  脚注表按文档全量随每小节落盘。章文件 v4 版本字段(`ChapterDto.v`)驱动旧书自动升级。
- 目录两级展示:`ChapterIndex.level`,TocSheet 小节缩进小字;书籍信息弹层统计"N 章 · N 小节"。

### 出版信息页(实验项,已完成)

- 阅读菜单顶栏"信息"入口 → ModalBottomSheet:书名/作者/格式/大小/章·小节统计/全书字数/
  当前进度(章名)/添加/最近阅读时间。

### 段距跟随书内开关(实验项,已完成)

- 设置面板"段距跟随书内"开关(默认开);关闭后书内 margin 全部忽略,纯全局段距;
  对齐/缩进不受该开关影响(书内意图明确)。typoKey 含 bookSpacing,切换即整本重排。

### 验收(四期,2026-10-03 模拟器)

1. v4 样本书 `sample_测试书_v4.epub`(入库):居中诗整体居中无缩进/indent:0 顶格覆盖全局/
   margin 3em 间距加大且开关可关/右对齐署名 ✓;
2. SVG 插图与 SVG 封面(封面页+书架 48dp 缩略图)栅格化清晰 ✓;
3. h2 拆章:一章拆 3 目录条目(两级缩进),书籍信息"2 章 · 2 小节" ✓;
4. TXT 回归:book3 排版/缩进与既有一致 ✓;单测 128 个全通过;logcat 零崩溃。

---

## 依赖与测试约定(各期通用)

- 新依赖仅四期 androidsvg;其余零新增;
- 所有 epub 解析单元保持"字符串/文件进、纯数据出",配套样本书测试集(EPUB2/EPUB3/前缀化 OPF/空 NCX/含图/含脚注/DRM 各一,见 `sample_测试书.epub` 与真书《龙魔传说》);
- 每期发布前 TXT 回归:同一 TXT 分页结果逐页比对无差异(单 Run 退化路径);
- 期与期独立可发布:二期完成即比一期多"像书",三期纯功能叠加,四期任意挑选。
