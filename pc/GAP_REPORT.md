# 差距分析：`playtranslate-for-PC` 有没有完成它 README 描述的改造

> 日期：2026年9月23日
> 被检查的提交：`main`（fork 自 `dominostars/playtranslate` @ `cfaaca6`）
> 本文回答一句话的问题，然后给出为缩小差距所做的工作、以及仍然存在的差距。

## 0. 结论

**没有完成。** 仓库与它的 README 是**一致**的——README 自己说得很清楚：

> "This is a fork of PlayTranslate, it is at the design stage, and it is not being maintained.
> **What is here is the porting design and the packaging setup for all five release formats.
> The PC application itself is not written yet**"

所以这不是"README 承诺了、实现没跟上"，而是"**README 描述的改造就是一份设计**"。
清点结果与这句话完全吻合：

| 组成部分 | 仓库里的状态（改造前） |
|---|---|
| 上游 Android 源码 | 491 个 `.kt` / 151 186 行，**原样未动** |
| `PORTING.md` | 移植设计 v0.2，855 行，**已完成** |
| `packaging/` | 五种格式脚本 + 图标生成 + 清单，**可跑**（README 自己标注了哪些没实测） |
| **PC 应用本体** | **一行都没有**。没有 `pc/`、没有模块划分、没有构建文件、没有任何可运行的产物 |

按 `PORTING.md` 的自评，这是 §9 路线图里**连 P-0 都还没进入**的状态：
§9 定义 P-1 的验收标准是"每平台明确「能做 / 不能做 / 需降级」，写入决策记录"——
而当时仓库里**没有一个可执行的东西**，探针无处可跑。

**因此本次工作 = 把 `PORTING.md` 从设计落成代码**，并在这个过程中对它做实测校验。

## 1. 做了什么

按 `PORTING.md` §7.3 的模块划分建立 `pc/` 树，优先落实它反复强调、"容易做错且错了看不出来"的接缝。

### 1.1 已落地并验证

| 模块 | 内容 | 验证方式 | 结果 |
|---|---|---|---|
| `pc/core/` | 纯 Kotlin/JVM，零平台依赖：几何(`PtRect`/`PtImage`/`ColorNormalizer`)、动作注册表 + 热键决策机器 + 路由器 + 冲突检测、翻译后端契约 + 瀑布流、区域模型、OCR 兜底注册表、平台接缝 `Support`/`CapabilityProbe` | `kotlinc` 编译 | **0 error**，199 个 class |
| `pc/bridge/` | §7.2.3 的回环 HTTP + WebSocket + bearer token 桥 | 自检套件 | **14/14 通过** |
| `pc/platform-linux/` | §9 P-1 探针：X11/Wayland、DE、portal、PipeWire、托盘、指针，全部返回 `Support` 而非异常 | 在本机运行 | 输出决策记录（见 §3 的诚实说明） |
| `pc/shell/` | 入口 + `BridgeServer.Api` 实现 + 自检 CLI（`--probe` / `--serve` / `--selftest`） | 编译 + 运行 | 可运行 |
| `pc/ui-web/` | §7.2.4 路由表的 Web 面板：`ThemeTokens` → CSS 自定义属性、13 个路由、i18n | 被 bridge 提供，自检覆盖 | 14/14（含 SPA 回退、路径穿越拒绝、i18n 返回真实译文） |
| `pc/tools/` | 可复现的分析脚本 + 真实载荷构建 | 逐个跑过 | 见 §2 |

### 1.2 bridge 的 14 项自检（`--selftest`）

§7.2.3 列了五条安全约束却没说任何一条为什么存在。每一条都被写成一个会在被破坏时**响亮失败**的测试：

```
PASS  binds loopback only                      只绑 127.0.0.1，不是 0.0.0.0
PASS  port is not a constant                  端口 0，由 OS 分配
PASS  api without a token is 401               每条路由都过授权
PASS  wrong token is 401                       常数时间比较
PASS  the HTTP port tells a websocket client where to go  426 + 正确端口
PASS  api with the token is 200
PASS  no CORS wildcard                         不回声任意 Origin
PASS  panel is served at /
PASS  unknown route falls back to the app shell (SPA)
PASS  path traversal is refused                /../../etc/passwd 拿不到
PASS  i18n route returns real translations     zh-CN.json 里真有汉字
PASS  websocket without a token is rejected
PASS  websocket handshake computes the right accept
PASS  websocket receives the hello frame
14/14 checks passed
```

其中 `websocket receives the hello frame` 抓到过一个**真实缺陷**：JDK 的
`com.sun.net.httpserver` 把 `responseLength == -1` 解释为"没有响应体"，
于是升级连接握手成功、之后每一帧都被吞掉。修法是给 WebSocket 一个自己的回环 socket
（`WebSocketEndpoint.kt`），并在 `/ws` 上回 `426 Upgrade Required` 告诉客户端去哪。
这件事值得写下来，因为它**只有跑起来才会发现**——设计文档写得再对也不会暴露它。

### 1.3 打包链路用真实载荷验证

`packaging/examples/sample-payload.sh` 的注释说：

> 真正的载荷由 PC 端构建产出（jar + 原生库），摆成同样的树之后，把本脚本换成真载荷路径即可，**其余脚本一个字不用改**。

这句话现在被验证了。新增 `pc/tools/make_payload.sh` 产出真实载荷（应用 jar + `ui-web/` + 启动器 + 桌面集成 + 图标），
`packaging/linux/build-deb.sh` **一个字没改**就吃下了它：

```
make_icons.py   → 8 个图标文件（PNG×6 + ICO + ICNS）
check_icons.py  → 通过
make_payload.sh → 25 个文件，4.6 MB（jar 3.4 MB，fat jar 含 kotlin-stdlib + coroutines）
build-deb.sh    → screengloss_0.2.0_amd64.deb（3.4 MB）
make_manifest.py→ latest.json + SHA256SUMS
```

并且**装好的树里的启动器能真的跑起来**：

```
$ payload/usr/bin/screengloss --version
ScreenGloss 0.2.0 (PlayTranslate PC port, design stage)
$ payload/usr/bin/screengloss --selftest
14/14 checks passed
$ payload/usr/bin/screengloss
panel:  http://127.0.0.1:46659/#token=…&ws=ws://127.0.0.1:34363/ws
```

## 2. 对 `PORTING.md` 的实测校正

设计文档的量化基线被引用了两次，所以它值得被复算。新增三个脚本做这件事，
`pc/PORTABILITY.md`、`pc/evidence/triage.md`、`pc/evidence/closure.md` 是它们的输出。

### 2.1 §2.1 的文件分类表：数字有偏差，且漏了一类

`pc/tools/scan_portable.py` 重算了 491 个文件：

| 桶 | §2.1 声称 | 实测 | 说明 |
|---|---:|---:|---|
| 完全不引用 Android | 125 | **123** | |
| 只用 `android.util.Log` / 注解 | 20（合并） | **18 + 7** | 这是两个不同的移植任务，不该合并 |
| 真正依赖 Android | 346 | **343** | |
| 合计 | 491 | 491 | ✅ |

### 2.2 §2.1 的测试资产表：偏差更大

| | §2.1 声称 | 实测 |
|---|---:|---:|
| 测试文件总数 | 286 | **306**（且 §2.1 自己的 150+135=285 与 286 不自洽） |
| 纯 JVM 测试 | 150 | **172** |
| Robolectric 测试 | 135 | **135** ✅ |

172 而非 150 是好消息：**能跟着 `core` 搬走的测试比设计文档说的多 22 个**。

### 2.3 最重要的发现：**那 125 个文件不是闭集，所以不是模块**

§2.1 说这 125 个文件"可直接编进桌面模块"。按字面把它抽出来编译，得到 **2101 个编译错误**，
而且错误**不是**以 Android API 为主，是以"引用了没被抽出来的同包兄弟"为主：

- `pc/tools/closure.py` 实测：**141 个被标为 portable 的文件里，57 个引用了同包的非 portable 声明**。
- Kotlin 允许同包内**不写 import** 直接引用，所以任何 import 图扫描都看不见这些边。
- 例：`TranslationPresenter`、`LivePanelRecord`、`PanelPresenter`、`ReadingArbiter` 都引用 `OverlayToolkit`（在被判为 Android 的桶里）。

结论应当改写为：**"每个文件单独看是可移植的"成立，"这个集合可以编译"不成立；
真正能搬的是闭包**，而闭包要么把 `FrameCoordinates`（9118 行位图/显示算术）一起拖过来，
要么逐个切断引用——**每一条都是一个决定**。

### 2.4 §2.1 没提的那件事：依赖会被继承

`pc/tools/triage_deps.py` 把抽出来的 148 个文件按**真正需要什么 classpath** 再分一次：

| 层 | 文件 | 含义 |
|---|---:|---|
| 只要 JDK + kotlin stdlib | 108 | 免费搬走 |
| 只要 JDK + 两个 shim | 14 | 免费搬走 |
| 需要第三方 JVM 库 | **26** | 可移植，但桌面模块因此继承依赖 |
| 碰 Android 框架 | 0 | 静态扫描的假阳性为 0 |

继承的库：`kotlinx-coroutines-core`(12 文件)、`okhttp`(8)、`gson`(5)、
`sudachi`(1)、`lucene-analyzers-common`(1)、`opencv`(1)。

**其中 `kotlinx.coroutines` 是本文件的修正对象**：第一版脚本把它当成 JDK 命名空间，
于是"纯 JVM 即可"的画像里漏掉了最大的一项依赖。另一个实测发现是
**`kotlinx-serialization` 需要的是编译器插件**，不是运行时 jar——
`@Serializable` 在只有 stdlib 的 classpath 上会退化成 `kotlin.io.Serializable` 类型别名并报 37 个错。
这类代价 §2.1 的框架完全没有体现。

### 2.5 §5.7 对 `HotkeyDecision.kt` 的描述不准确

§5.7 称它"纯逻辑、已单测、与 Android 无关"。实际它 **import 了
`android.view.InputDevice` 与 `android.view.KeyEvent`**（源掩码与修饰键常量）。
逻辑确实可移植，**常量必须替换**——这正是 `pc/core/action/` 里那份移植在做的事。
另外 `IconGestureBindings.kt` 在**包根**，不是 §7.2.4 表格里写的 `ui/`。

### 2.6 一个被两份文档同时漏掉的复用资产：**i18n 已经存在**

§7.2.4 说 Web 面板的"文案照搬"，§7.2.6 把 i18n 列为"要建"的组件。
但上游 `res/values*/strings.xml` 里已经有：

- 基础 **1006** 条（含 plurals）
- **12 种语言 × ~955 条**，合计 **11 508 条译文**

`pc/tools/gen_resources.py` 把它们机械转换成 `pc/ui-web/i18n/*.json`
（缺键回退到基础语言），同时**从同一份 XML 生成 `R.kt`**——因为抽取出来的 Kotlin
在 129 处引用 `R.string.*` / `R.attr.*`，而 `R` 是 Android 构建生成的，脱离设备必须由别的东西生成，
唯一不会漂移的生成方式就是读同一份 XML。

> 输出稳定性：`R.kt` 的 id 用 `zlib.crc32` 而非 Python 的 `hash()`。
> 第一版用 `hash()`，而它是按进程加盐的——生成的 `R.kt` 每次都不一样，
> 每次提交都会显示上千行改动。已修正并验证两次生成 md5 一致。

### 2.7 §9 的"需实测"：探针写好了，但**本机答案不算数**

§9 要求为三个"需实测"的生态事实各写一个最小验证程序。探针（`LinuxSessionProbe`）写好了，
也跑了，但**当前环境是一个容器**：没有 `DISPLAY`、没有 `WAYLAND_DISPLAY`、没有 portal。
所以三个"需实测"项的答案是 `INDETERMINATE` / `NEEDS_A_SESSION`，**不是"不支持"**。

这正是 §9 明令禁止的混淆（"不要基于假设开工"），所以探针把这件事做成了类型：
每个结论带一个 `Confidence`，`NEEDS_A_SESSION` 的语义是
"查到就算数，查不到什么都不说明"。**这一项仍然是未完成的，需要在真实会话上跑一次。**

### 2.8 §1 平台矩阵：一处结论值得复核

§1 说 GNOME-Wayland 无法叠字（Mutter 不实现 `wlr-layer-shell`），这被探针按 `CONCLUSIVE` 复核；
但同表把 **KWin 的 layer-shell 行为**标为需实测，探针只能报 `NEEDS_A_SESSION`。
另外 §1 说 Wayland 下 `GlobalShortcuts` portal 不提供 key-up——
探针把这条**降级为 `Degraded` 而不是 `No`**，因为 portal 规范只说 `Activated`，
"不提供 `Released`" 是各 DE 实现问题，不能写成平台结论。

## 3. 仍然存在的差距（不修饰）

按重要性排序。前四项是"没有它就不是应用"，后几项是"没有它不能发版"。

| # | 差距 | 状态 |
|---|---|---|
| 1 | **平台后端一个都没实现**：`CaptureBackend` / `OverlayBackend` / `HotkeyBackend` / `TrayBackend` / `AudioLoopbackBackend` / `TtsBackend` 只有接口和 `Support` 返回值，没有 Win/mac/X11/Wayland 实现 | 未开始 |
| 2 | **原生叠字层**（§7.2.1 明确划给原生）：文字排版 / ruby / 竖排 / 描边 / 点击穿透 | 未开始 |
| 3 | **WebView 宿主（JCEF/KCEF）**：`--serve` 能给出 URL，但没人把它嵌进窗口 | 未开始 |
| 4 | **词典 / 分词 / OCR 栈没有接线**：`pc/core/src/upstream/` 里抽了 148 个文件作为**暂存区**，但它们编译不过（见 §2.3），也没接进 `Waterfall` / `OcrRegistry` | 暂存状态 |
| 5 | 热键的真实捕获（Raw Input / CGEventTap / XInput2 / portal）；`HotkeyRouter` 只有状态机，没有输入源 | 未开始 |
| 6 | `SecretStore`（DPAPI / Keychain / libsecret）、AnkiConnect、自动更新通道 | 只有接口 |
| 7 | 面板 26 个路由里只有热键/外观/能力几页接了真实数据，其余渲染"未移植"占位 + 上游文件出处 | 部分 |
| 8 | rpm / flatpak / msi / dmg 四种格式仍未实测（与 `packaging/README.md` 自己标注的一致） | 继承的已知项 |
| 9 | §2.3 的闭包代价没有做：57 个文件的引用没切断，也没有把 `FrameCoordinates` 拖进来 | 未做（**这是 P-2 的真实工作量**） |

关于第 4 项，需要说清楚它的性质：`pc/core/src/upstream/` **不是已完成的移植**，
是一次**可复现的抽取 + 实测成本计量**。把它放进仓库是有意的——
`closure.md` 和 `triage.md` 就是 P-2 的工单来源，比一张"125 个文件可复用"的表有用。

## 4. 如何复现本文所有数字

```bash
# 1) 可移植性分类 + 测试资产（§2.1、§2.2）
python3 pc/tools/scan_portable.py --repo . --report pc/PORTABILITY.md

# 2) 抽取 + 依赖分层（§2.3、§2.4）
python3 pc/tools/scan_portable.py --repo . --extract pc/core/src/upstream/kotlin
python3 pc/tools/triage_deps.py --root pc/core/src/upstream/kotlin --out pc/evidence/triage.md

# 3) 闭包分析（§2.3 的关键结论）
python3 pc/tools/closure.py --src app/src/main/java --portable pc/PORTABILITY.md --out pc/evidence/closure.md

# 4) 资源生成（§2.6）
python3 pc/tools/gen_resources.py --res app/src/main/res \
    --r-kt pc/core/src/main/kotlin/com/playtranslate/R.kt --i18n pc/ui-web/i18n

# 5) 编译 + 自检
#    见 pc/README.md 的一行构建命令
java -cp <classes> io.github.only_air.screengloss.shell.Main --probe
java -cp <classes> io.github.only_air.screengloss.shell.Main --selftest

# 6) 真实载荷 + 打包
python3 packaging/tools/make_icons.py build/icons
python3 packaging/tools/check_icons.py build/icons
APP_VERSION=0.2.0 bash pc/tools/make_payload.sh build/payload
APP_VERSION=0.2.0 bash packaging/linux/build-deb.sh build/payload build/out
python3 packaging/release/make_manifest.py 0.2.0 build/out -o build/out/latest.json
```

## 5. 对 `PORTING.md` 的修改建议

不建议直接改文档（它作为设计快照有保留价值），建议**在文档顶部加一段指向本文的指针**：

1. §2.1 的数字改成实测值，并把"125 个文件可复用"改写为"125 个文件单个可移植，**闭包 57 个有外部引用**"；
2. §2.1 增加第三行"依赖会被继承"，列出 kotlinx-coroutines / okhttp / gson / sudachi / lucene / opencv；
3. §2.4 增加 `kotlinx-serialization` **需要编译器插件**这一条，它决定桌面模块的构建方式；
4. §5.7 修正 `HotkeyDecision.kt` 的"与 Android 无关"；
5. §7.2.6 记录 i18n 已有 11 508 条译文可机械复用。

## 6. 一句话总结

`README.md` 描述的东西**它自己说清楚了是设计**，所以这份仓库没有"没做到承诺"的问题；
它的问题是**停在设计**——`PORTING.md` §9 的 P-1 需要"一个可跑的最小程序"，
而当时仓库里没有可跑的东西，所以连 P-1 都进不去。

本次把 P-1 变成了可执行的东西（探针 + 桥 + 面板 + 打包链路，14/14 自检通过，
真实 `.deb` 产出），并且顺手对设计文档做了实测校验：**7 处数字/描述需要修正**，
其中"125 个文件可直接编进桌面模块"这一条最要紧——
它会把 P-2 的工期低估掉一个量级，因为真正的成本在闭包里，不在文件里。
