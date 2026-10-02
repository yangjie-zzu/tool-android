# EPUB 原生支持 · 总体方案与剩余期数规划

> 状态基线:2026-10(v1.1.0)。一期与二期均已上线,本文档记录已完成架构与三/四期的实施方案。

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

## 三期 · 阅读功能(与格式无关,TXT 同步受益)

### 书签

- 存储:`reader_bookmark` 表(book_id, chapter_index, global_offset, excerpt, created_at);独立表,不进书表写路径。
- 入口:阅读菜单"添加书签"(当前页页首偏移+当页首行摘录);目录抽屉加"书签"列表,点击 `BookPager.locatePage` 跳转;书签可删。
- 重排不漂移:与进度同机制(全书字符偏移)。

### 全文搜索

- 按章对 `ChapterDocument` 纯文本投影线性扫描(Default 线程并发,信号量限流);大书(数百章)先出部分结果流式追加。
- 结果:章名 + 命中行上下文(关键词高亮);点击 → 定位章 + 章内偏移(`locatePage` + 章内翻页)。
- 入口:阅读菜单"搜索";大小写不敏感,支持中文。

### 章内锚点跳转

- `ChapterIndex` 扩展 `anchorId`(目录项 fragment);`Paragraph` 增加 `anchor` 字段(XHTML id)。
- 目录点击带 fragment 时:定位章后翻到 anchor 段落所在页(段落号 → 章内偏移 → `locatePage`)。
- EPUB3 nav/EPUB2 ncx 的 fragment 解析一期已预留(`TocEntry.fragment`)。

### 脚注弹层

- `FootnoteResolver`(epub/xhtml):EPUB3 `epub:type="noteref"/"footnote"` 标准配对;EPUB2 按 class 约定(note/footnote)宽容识别,识别不了的当普通文本。
- 正文:`<a noteref>` 渲染为上标角标 Run(二期 Run 模型复用)+ 可点击区域。
- 交互:点击角标 → 底部 ModalBottomSheet 显示脚注内容(纯文本+基本样式),点外部关闭回原位。
- 脚注内容默认不进正文阅读流(标准做法);`linear="no"` 的脚注文档一期已排除。

### 验收

1. TXT/EPUB 双格式书签/搜索行为一致;
2. 改字号/行距重排后,书签与搜索定位不漂移(偏移锚点机制);
3. 龙魔传说(358 章)级大书搜索响应可接受(流式出结果);
4. 含脚注样本书:角标可点、弹层内容正确、关闭回原位。

---

## 四期 · 增强(锦上添花,可按需挑选)

### CSS 子集

- 解析段落级 `text-align`(center/right)、`text-indent`、块级 margin(上下间距);诗词、署名场景受益。
- 其余 CSS(字体/颜色/背景/定位)继续忽略——全局阅读设置不被书内样式干扰是产品原则。

### SVG 栅格化

- 引入 `androidsvg`(约 500KB):SVG 图片(含 SVG 封面)首次显示时按屏幕分辨率栅格化为 Bitmap 并缓存到章目录;
- 二期图片管线的 `imageRef` 扩展一种来源类型即可,渲染层无感。

### 可选实验项(按反馈决定)

- 章节内 `<h2>` 小节二次拆章(目录两级展示);
- EPUB 出版信息页(元数据页)入口;
- 段落间距按书内 CSS 微调开关(尊重书 vs 强制全局)。

---

## 依赖与测试约定(各期通用)

- 新依赖仅四期 androidsvg;其余零新增;
- 所有 epub 解析单元保持"字符串/文件进、纯数据出",配套样本书测试集(EPUB2/EPUB3/前缀化 OPF/空 NCX/含图/含脚注/DRM 各一,见 `sample_测试书.epub` 与真书《龙魔传说》);
- 每期发布前 TXT 回归:同一 TXT 分页结果逐页比对无差异(单 Run 退化路径);
- 期与期独立可发布:二期完成即比一期多"像书",三期纯功能叠加,四期任意挑选。
