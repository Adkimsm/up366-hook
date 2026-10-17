# up366 — 天学网 Android 自动化（LSPosed）

把桌面版 [Auto366](https://github.com/CyrilGuoCODE/Auto366)（Electron + MITM 代理）的自动化能力，用
**LSPosed 运行时 hook** 的方式搬到 Android 版天学网（`com.up366.mobile`）上。

> P0 运行时侦察已经完成；P1a/P1b 首版只读实现和本地测试已落地，真机回归仍待完成。
> 独立只读答案层（`docs/09`）已改为“目标进程内解析 + 注入式原生面板”，构建与单测通过，真机验收待执行。
> 文档先行，目的是让后续任意一次对话（不同 session / 不同人）都能凭本仓库恢复完整上下文。

---

## 1. 当前状态

| 项 | 状态 |
|---|---|
| 桌面版 Auto366 机制分析 | ✅ 已完成（见 `docs/01-desktop-auto366.md`） |
| 天学网 APK 静态侦察 | ✅ 已完成（见 `docs/02-apk-recon.md`） |
| 桥 API 清单提取 | ✅ 已完成（见 `docs/03-bridge-api.md` + `data/bridge-api.json`） |
| 移动端 HAR / 练习包侦察 | ✅ 已完成第一轮（见 `docs/02-apk-recon.md`、`docs/07-flipbook-recon.md`） |
| Android 方案设计 | ✅ 已完成（见 `docs/04-android-plan.md`） |
| P0 侦察模块代码 | ✅ 已完成首版骨架（待真机/LSPosed 验证） |
| P1a 只读运行时观察 | 🟡 代码已实现；需在 LSPosed/天学网上验证异步关联、探针和提交状态 |
| P1b 题包结构解析与状态映射 | 🟡 本地解析器和单元测试已实现；题包版本/题型覆盖及真机匹配待验证 |
| 独立只读答案层（`docs/09`） | 🟡 目标进程内解析/答案索引/注入式只读面板已实现，构建与单测通过；真机验收未执行 |
| P2-P5 | ⬜ 未开始；答案写回、自动作答、改分和自动提交不在当前实现范围 |

---

## 2. 文档索引

| 文件 | 内容 |
|---|---|
| `docs/01-desktop-auto366.md` | 桌面版 Auto366 工作原理：代理、规则类型、ZIP 动态注入、时间签名、加密格式、各规则集配置 |
| `docs/02-apk-recon.md` | 天学网 APK 静态侦察：加固、清单、WebView、网络栈、原生库、桥机制 |
| `docs/03-bridge-api.md` | JS 桥 API 全量清单 + 自动化价值标注 |
| `docs/04-android-plan.md` | Android 方案与分期计划，含 P0 侦察结果和 P1 实现边界 |
| `docs/05-risks-open-questions.md` | 风险清单与待验证问题（**每次有新发现都往这里记**） |
| `docs/06-mt-toolbox.md` | 如何用 MT MCP 工具重读 APK（跨对话复现步骤） |
| `docs/07-flipbook-recon.md` | 实际听力题包结构、题目数据模型、运行日志对应关系与 P1 开工边界 |
| `docs/08-module-development-plan.md` | 模块主线开发计划（LSPosed 运行时观察与 WebView 探针） |
| `docs/09-readonly-question-answer-plan.md` | 独立只看答案层：题包解析、本地答案索引、目标进程内注入式只读面板 |
| `data/bridge-api.json` | `assets/api.json` 原始副本（桥 API 清单，未改动） |

构建产物：`module/build/outputs/apk/debug/module-debug.apk`（纯 LSPosed 模块，无独立 App UI、无 Provider、无 `exported` 组件）。

本机验证构建命令：`./gradlew :module:testDebugUnitTest :module:assembleDebug`。

模块 APK 的唯一职责是被 LSPosed 加载进 `com.up366.mobile` 进程；LSPosed 作用域只勾选 `com.up366.mobile`。默认开启“Activity 生命周期”和“注入只读面板”；网络、WebView、桥、探针、题包解析与本地答案索引默认关闭，可在注入面板的“功能开关”里就地开启。日志输出 Logcat 标签 `UP366-P0`。

注入面板：进入天学网后自动在 DecorView 上追加一个不透明的悬浮“答”按钮，可拖动且位置持久化（存目标 App 私有 prefs，清除天学网数据会重置）；点按展开只读面板，面板会跟随按钮位置定位（按钮在上方时面板显示在其下方，靠近底部时改为显示在上方，并始终夹取在屏幕内），包含状态、功能开关、答案覆盖率与列表、维护（清除本地答案索引、隐藏悬浮按钮）、诊断（最近事件与 `traceId`/`requestId` 链路查询）。面板只读，不写 `user_answer`/`saveData`，不触发提交，不注入或修改页面 DOM/JavaScript。

超过 Logcat 单行限制的事件会输出为 `P0CHUNK id=N part=X/Y ...`。重组时按同一个 `id` 的 `part` 顺序拼接前缀后的内容，即可还原原始 JSON 事件。

桥提交状态日志只保留结构统计和匹配数量；桥通用日志对提交/上传参数省略正文。启用网络和桥观察仍可能产生敏感元数据，不要公开分享原始日志。

---

## 3. 已定约定

| 项 | 值 |
|---|---|
| 命名空间 / 模块包名 | `io.github.adkimsm.up366.hook` |
| 目标 App | `com.up366.mobile`（天学网学生 6.0.1 / vc220） |
| 构建方式 | 用户自行在 Android Studio 编译，本仓库只交付源码 |
| 日志输出 | Logcat 标签 `UP366-P0` |
| 开发环境 | 本机 shell 就是 Android 设备本体（详见 §4） |

---

## 4. 开发环境备忘

当前 shell **运行在天学网所在的同一台 Android 设备上**（Ubuntu 24.04 rootfs，Linux 6.12 android16，aarch64，uid 1001，**无 root**）。

- JDK 25（Temurin）、Android SDK `/home/adkinsm/Android/Sdk`（platforms `android-36` / `android-37.0`；build-tools `36.0.0` / `37.0.0`）
- Gradle 9.3.1（`/home/adkinsm/gradle/gradle-9.3.1`）、Node 24.15.0、bun 1.4.2
- 可直连 `dl.google.com` 与 `repo1.maven.org`
- shell **读不到** `/storage/emulated/0`（无 root）→ 读 APK 只能走 MT MCP 工具
- `platform-tools/adb` 是 x86-64 二进制，本机跑不了，忽略

---

## 5. 协作约定

1. 有新发现 → 先更新 `docs/`，再动代码。
2. 每条结论尽量标注**证据来源**（哪个工具 / 哪个文件 / 哪个类），未验证的一律写「待验证」。
3. 不把「推测」写成「事实」。`docs/05-risks-open-questions.md` 是唯一记录未解问题的地方。
4. 代码开工前必须先确认 `docs/05` 里的 **Q1（加固是否拦 LSPosed）** 有结论。
