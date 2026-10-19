# up366-hook

面向天学网 Android 客户端（`com.up366.mobile`）的 **LSPosed 运行时模块**：在目标 App 进程内做只读观察
（桥调用、WebView 页面、网络、题包解析、答案索引），并通过注入到目标进程的只读面板查看结果。
在只读能力之上，模块还提供默认关闭的可选自动化（一键填答、听力快进并交卷、速度档位）。

> 仅供学习与研究使用，严禁商用。本项目不修改、不重分发天学网 APK。

---

## 1. 功能

| 能力 | 说明 | 默认 |
|---|---|---|
| Activity / 进程观察 | Hook `Instrumentation#callActivityOnCreate`、`Application#onCreate` | 开 |
| 注入式只读面板 | 悬浮按钮 + 面板（DecorView 兄弟 View），状态 / 开关 / 答案 / 维护 / 诊断 / 日志 / 开发者工具 | 开 |
| WebView 与 JS 桥观察 | 页面加载、`addJavascriptInterface`、桥调用与同步/异步结果关联、原生→页面音频回调 | 关 |
| 只读 WebView 探针 | `file://` 页面 DOM 结构、真实组件选择器、渲染完成重试、iframe | 关 |
| 网络观察 | OkHttp 动态代理拦截，记录请求/响应元数据与截断正文 | 关 |
| 题包解析（只读） | `questionData.js`、组合大题递归展开、音频资源映射 | 关 |
| 本地答案索引（只读） | 解析 `answer_text` 建 SQLite 索引（目标 App 私有目录），面板显示当前套题答案 | 关 |
| 开发者工具（只读） | 对当前页面执行 DOM 概要 / 计算样式 / 门控概览；原生音频回调列表 | 面板内按钮 |
| 一键填答 | 用本地答案索引在答题层点击选项（程序化点击绕过页面 CSS 禁用）；快进运行中的填答会并入快进并先于自动交卷 | 关 |
| 听力快进并交卷 | 加速播放 + 压缩页面等待 + 重试「听完」回调，全部解锁后自动提交；运行中可重复点击重启，可随时停止 | 关 |
| 速度档位 | 关闭 / 标准 / 快速 / 极限，统一控制播放倍速、页面时钟倍速、等待压缩与重试间隔 | 快速 |

详细的设计、边界与真机证据见仓库本地文档（不随仓库分发）。

---

## 2. 构建

```bash
./gradlew :module:testDebugUnitTest :module:assembleDebug
```

产物：`module/build/outputs/apk/debug/module-debug.apk`（纯 LSPosed 模块，无独立 App UI、无 Provider、无 `exported` 组件）。

发布构建需要本地 `keystore.properties`（指向签名 keystore，不入库；缺省时 release 包不签名）：

```bash
./gradlew :module:assembleRelease
```

产物：`module/build/outputs/apk/release/module-release.apk`，发布到 LSPosed 模块仓库的是这个包。

| 项 | 值 |
|---|---|
| 模块包名 | `io.github.adkimsm.up366.hook` |
| 目标 App | `com.up366.mobile` |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| 目标 ABI | `arm64-v8a` |
| Xposed API | `de.robv.android.xposed:api:82`（`compileOnly`） |

安装后在 LSPosed 中启用模块，**作用域只勾选 `com.up366.mobile`**。

---

## 3. 测试用的题包样本

部分单元测试（`QuestionDataParserTest`、`AnswerIndexerTest`）依赖一份**运行时题包样本**，它属于第三方内容，
**不随本仓库分发**。默认情况下这两个测试会因为找不到样本而失败，其余测试正常。

如需完整跑通测试：从设备的题包目录把一份题包复制到仓库根目录的 `flipbook/` 下，保持结构：

```text
flipbook/
└── <bookId>/
    ├── <chapterId>/<pageId>/1.html
    ├── <chapterId>/<pageId>/questions/<questionId>/questionData.js
    └── resources/...                       # 页面脚本与样式（供离线分析）
```

测试通过向上查找 `flipbook/` 来定位样本，因此放在仓库根目录即可，不需要额外配置。
`flipbook/`、`data/`、`docs/` 与日志均已加入 `.gitignore`，不会被提交。

---

## 4. 使用

1. 安装 `module-debug.apk`，LSPosed 中启用并只勾选 `com.up366.mobile`。
2. 打开天学网，DecorView 上会出现悬浮「答」按钮（可拖动、位置持久化）；点按展开只读面板。
3. 在「功能开关」里按需开启：网络、WebView、桥、探针、题包解析、本地答案索引。
4. 「开发者工具」对当前页面执行 DOM 概要 / 计算样式 / 门控概览（只读，不点击、不改 DOM）。
5. 自动化（需先在「功能开关」打开对应开关）：
   - 「一键填答」：按本地答案索引在答题层点击选项；快进运行中的填答请求会并入快进、先于自动交卷执行；
   - 「快进听音并交卷」：加速听音、压缩页面等待并自动交卷；运行中可再次点击重启，也可用「停止快进」提前结束；
   - 「速度档：…」：切换速度档位（关闭 / 标准 / 快速 / 极限）。

日志默认输出 Logcat，标签 `UP366-P0`。可在面板开启「结构化日志写入私有目录」把同样已脱敏的 NDJSON 事件写入
目标 App 私有目录 `files/up366/logs/`；`log_raw_bridge`（默认关，仅调试）会额外写入无法解析的原始回调/状态载荷，
**含业务数据，用完请删除**。超过 Logcat 单行限制的事件会分片输出为 `P0CHUNK id=N part=X/Y ...`。

---

## 5. 隐私边界

- 模块代码只运行在 `com.up366.mobile` 进程内，不声明 `exported` 组件，不监听外部广播，不提供 IPC。
- 答案、题干与凭据不写入默认 Logcat；`uId`/`clientId` 等标识使用不可逆短哈希，Cookie/Token 等字段默认脱敏。
- 面板与开发者工具中的页面结构、题干与选项只存在于目标进程内，不落盘、不写 Logcat。
- 可选的文件日志与原始载荷仅写目标 App 私有目录，清除天学网数据会一并清除。

---

## 6. 发版（维护者）

打 tag 推送后，GitHub Actions（`.github/workflows/release.yml`）会跑测试、构建签名 APK，并同时发布两个 release：

- **主仓库**：tag 为推送的 `v*` tag；
- **LSPosed 镜像仓库** `Xposed-Modules-Repo/io.github.adkimsm.up366.hook`：tag 规范为 `{versionCode}-{versionName}`（如 `1-0.1.0`）。

```bash
git tag -a v0.2.0 -m "changelog（作为 release 正文）"
git push origin v0.2.0
```

- 发版前先改 `module/build.gradle.kts` 里的 `versionCode` / `versionName`；
- release 正文优先取 tag 附注，未写时自动用提交标题生成；
- 顺序上先发主仓库、再发镜像仓库；`MIRROR_TOKEN` 只影响镜像仓库这一步；
- 工作流也支持在 Actions → Release → Run workflow 手动触发，`publish` 不勾选则只构建不发布。

仓库 Secrets（Settings → Secrets and variables → Actions）：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 发布 keystore 的 base64（`base64 -w0 up366-release.jks`） |
| `KEYSTORE_STORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `up366` |
| `KEY_PASSWORD` | 密钥口令 |
| `MIRROR_TOKEN` | 对镜像仓库有 `Contents: Read and write` 权限的 fine-grained PAT |

---

## 7. 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整条款见 [`LICENSE`](LICENSE)。

```
up366-hook — LSPosed module for the Tianxuewang Android client
Copyright (C) 2026  up366-hook contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
