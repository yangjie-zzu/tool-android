# 蓝牙模块验证注意事项

> 2026-10-24 蓝牙通用化改造(v1.5.58 后)模拟器验证过程中总结, 供后续验证复用。

## 模拟器环境特性

1. **本模拟器镜像无可用的蓝牙栈**: `settings get global bluetooth_on` 可为 1, 但
   `dumpsys bluetooth_manager` 的 state 恒为 OFF, 弹系统对话框"Turning Bluetooth on…"后也不会真正开启。
   因此模拟器上扫描/连接/执行只能验证到**错误路径**(预期报"蓝牙未开启"), 真实 BLE 流程必须真机验证。
2. 系统开蓝牙对话框 "tool(debug) wants to turn on Bluetooth" 的 Allow 按钮固定在
   `[800,1101][976,1250]`, 中心点 (888,1175)。
3. 蓝牙使能是**异步**的: 点击 Allow 后 adapter.isEnabled 不会立即变 true——
   BlePermissions.ensureEnabled 已改为轮询 6s; 验证脚本在点 Allow 后至少 sleep 8s 再断言。

## Compose 页面输入框调试陷阱(编辑页/执行面板通用)

1. **键盘弹出会压缩可视区但 Compose 字段 bounds 未必按预期平移**, 且 uiautomator dump
   可能返回**布局动画中的不一致快照**(同一 dump 里有的字段是新坐标有的是旧坐标)。
   结论: 不要用"上一次 dump 的绝对坐标"连续操作多个输入框——每点一个字段都要:
   tap → 重新 dump → 验证目标字段 `focused="true"` 且内容正确 → 再输入/删除。
2. 第一次 tap 输入框经常只是"让窗口获得焦点", 字段未聚焦; **需要第二次 tap**。
   判断标准以 dump 的 focused 属性为准, 不以点击次数为准。
3. `input keyevent 111`(ESC) 收键盘; `input keyevent 4`(返回) 在键盘弹出时只收键盘、
   第二次才退出页面。
4. 删字符串用 `input keyevent 67`(DEL) 逐个发, 发完必须 dump 验证内容; 用坐标盲删
   曾把 "y180Fg2A19" 删成 "y18"(次数不足) 且删错字段。
5. `input text` 只能输 ASCII; 中文预设名没法用 adb 输入, 验证时用英文名。

## 功能验证覆盖情况(2026-10-24, 模拟器)

- 首次进入自动种子"关灯"预设 ✓(FFE0 匹配/FFF0/FFF2/两条指令/间隔100ms/关蓝牙勾选)
- 预设列表参数摘要 ✓; 执行面板参数回显与临时修改 ✓
- 执行流程事件日志(开始执行→逐步→Done) ✓, 无蓝牙时错误文案 ✓
- 新建预设(读数据模板)保存入库 ✓; 编辑回填 ✓; 删除 ✓
- 调试页扫描错误路径 ✓
- 单测: BleModelsTest 15 个全过(HEX 解析/UUID 规范化/params JSON 往返)
- **待真机**: 关灯预设真实执行、调试页扫描真实设备/服务树/读写订阅

## 单元测试环境(重要)

本机 `C:\Users\杨杰` 中文路径会让 Gradle 测试 worker 起不来
(`ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`,
根因: worker classpath @argfile 是 UTF-8, java 启动器按 GBK 代码页读, 中文路径乱码)。
**解决办法**: 已把 Gradle 用户主目录完整复制到 `D:\gradle-home`(3.0GB), 单测命令:

```bash
GRADLE_USER_HOME="D:/gradle-home" JAVA_HOME="D:/jdk-21.0.1" ./gradlew :app:testPubAppDebugUnitTest
```

master 存量失败(与本模块无关, 2026-10-24 在干净 master 上复现): EpubRichContentTest 1 个、
PdfOutlineParserTest 1 个(期望 3 条书签实得 2)。
