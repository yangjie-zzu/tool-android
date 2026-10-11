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
