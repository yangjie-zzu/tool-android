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

- 2026-10-06：v1.5.21 release（渲染器量宽重构 v11 + 重复绘制/裁切/CSS 404 修复 +
  装饰章快照体系移除 + 块渲染流水线并发），commit `3b6c04f`，tag `v1.5.21`，
  GitHub Release 含 release/debug 两个 APK，模拟器验证通过。
