# 电子书阅读器 · 总体设计

> 状态：设计定稿，未开发。MVP 详细设计见 [reader-mvp-txt.md](reader-mvp-txt.md)

## 一、核心思路

"支持任意格式"不可能逐格式硬写，采用**分层架构 + 统一中间模型 + 双渲染路径**：

```
文件 → FormatDetector(扩展名 + Magic Number)
     → ParserRegistry(格式 → 解析器 的注册表)
     → Parser(第三方库 + 薄适配器, ~百行/格式)
     → 统一 Book 模型(元数据 + 章节列表 + LayoutType)
     → 按 LayoutType 分流:
        ├─ REFLOWABLE(流式) → 原生分页引擎(StaticLayout)
        └─ FIXED(固定版式)  → 直渲 View(PDF/CBZ,后续阶段)
```

EPUB 单独走 **WebView 渲染器**（epub.js + JS Bridge），与原生分页引擎并列，
构成"双渲染路径"：纯文本走原生（轻、快、无 WebView 内存底盘），EPUB 走 WebView（完整 CSS 还原）。

## 二、格式支持清单

### 首期实现

| 格式 | 解析方案 | 渲染路径 |
|---|---|---|
| TXT | 编码检测(juniversalchardet) + 章节正则 | 原生分页引擎 |
| MD | commonmark-java → AST → 自有富文本模型 | 原生分页引擎 |
| 源码(.kt/.py/.js…) | CodeParser：等宽 + 行号 + 横向滑动 | 原生分页引擎 |
| JSON / XML | CodeParser + minified 时 pretty-print(android.util.JsonReader) | 原生分页引擎 |
| EPUB | epublib 解析章节 | WebView + epub.js |

### 按需扩展（都是往 ParserRegistry 添 Parser）

| 格式 | 方案 | 难度 |
|---|---|---|
| DOCX | Apache POI(需 proguard 裁剪) | 中 |
| MOBI/AZW3 | libmobi(NDK) | 高 |
| CBZ | java.util.zip | 低 |
| PDF | PdfiumAndroid，固定版式直渲 | 低 |
| CHM | chmlib(NDK) 解出 HTML 后走流式路径 | 中 |
| FB2/HTML | Jsoup | 低 |
| RTF | 降级提取纯文本 | 低 |
| DJVU/CBR | djvulibre NDK / unrar | 高，最后考虑 |

### 不可实现（法律问题）

带 DRM 的格式：Kindle 商店书(KFX)、Adobe DRM EPUB、方正 CEB、超星 PDG。

## 三、关键设计决策

1. **双渲染路径**：TXT/MD/代码/JSON 走原生引擎；EPUB 走 WebView。
   混搭复杂度（双进度模型、双主题机制）通过抽象层消化（见下）。
2. **块页规则**：普通文本正常分页；超一屏的代码块/表格/图片独立成块页、内部滚动。
   **不做强制切分、不做缩字号重排**（代码块超页时例外：按行切，因等宽行是天然切点）。
3. **滚动模式**：全局开关，LazyColumn 按章节渲染，与分页共用"章节+偏移"进度数据。
4. **进度模型**：流式书存 `章节 index + 章内字符偏移`（换字号重分页后位置不漂移）；
   固定版式存页码；划线/书签锚点抽象为接口，原生实现为字符偏移、WebView 实现为 CFI。
5. **排版换算单一来源**：设置以 **dp** 存储（不用 sp，避免系统 fontScale 两侧不一致）。
   `Typography` 类是排版的唯一定义点，同时产出：
   - `ResolvedTypography`（px，喂给 TextPaint / StaticLayout / 分页测量）
   - CSS 变量（`--font-size` 等，注入 WebView）
   WebView 侧显式钉死 `textZoom = 100`；测量与渲染必须同源，防分页错位。
6. **设置单一定义**：`ReaderSettings` 只定义一次，两侧各一个解释器
   （原生 apply + CSS 生成器约百行）。新增设置字段必须两边同时落地。
   字体 TTF 共享：原生 `Typeface.createFromFile`，WebView `@font-face`（经 WebViewAssetLoader）。
7. **富文本模型预留** `foregroundColor` 与等宽 `fontFamily` 字段——
   代码高亮(Prism4j → 颜色 span)和等宽渲染依赖它，现在定下避免返工。
8. **语法高亮**：原生侧 Prism4j（token → 颜色 span），对 MD/EPUB 代码块和源码文件统一生效。

## 四、WebView 路线要点（EPUB 阶段实施）

- **单 WebView 复用 + 章级加载**：翻章由 JS 替换内容，DOM 始终只有当前章，内存与书大小无关
- **原生侧解压 EPUB 到 cacheDir**，WebView 按文件路径加载章节 XHTML，绕开 JSZip 全量内存解压
- **翻页用容器 `scrollLeft`**，不用宽容器 `transform: translateX()`——
  多栏分页的宽容器一旦被合成层栅格化，内存 = 页数 × 屏宽，必炸
- 大章按字数再切分控制 DOM 规模；图片降采样 + lazy load
- `onRenderProcessGone` 兜底重建；`onTrimMemory` 释放缓存；
  WebView 从视图树摘除后再 destroy，JS Bridge 不持有 Activity
- 备选整包方案：Readium Kotlin Toolkit（BSD，工业级）——若自研 epub.js 集成受阻可切换

## 五、实施阶段

1. **MVP**：TXT + 分页引擎 + 样式设置（背景色/字号/行距/边距/缩进/对齐）+ 进度记忆。
   详见 [reader-mvp-txt.md](reader-mvp-txt.md)
2. **第二阶段**：EPUB WebView 渲染器 + 字体导入 + CodeParser（等宽 → 行号 → Prism4j 高亮渐进增强）
3. **第三阶段**：滚动模式、书签/划线/笔记、目录跳转、全文搜索
4. **按需**：PDF/CBZ、DOCX、MOBI、CHM

## 六、依赖清单

| 依赖 | 用途 | 引入阶段 |
|---|---|---|
| com.github.albfernandez:juniversalchardet | 编码检测 | MVP |
| org.commonmark:commonmark(+ext-gfm-tables 等) | MD 解析 | 阶段二 |
| nl.siegmann.epublib:epublib | EPUB 解析 | 阶段二 |
| epub.js(assets 内置 JS) | WebView 分页渲染 | 阶段二 |
| io.noties:prism4j | 语法高亮 | 阶段二 |

MVP 阶段仅新增 juniversalchardet 一个依赖，纯 Java、无 NDK、无 license 风险。
