# 打包（packaging/）

一次构建，五种发行格式。本目录只做打包，不含应用代码。

## 目录

```
packaging/
├─ config.sh                 ← 元数据的**单一来源**，改这里五种格式一起变
├─ assets/                   桌面集成模板（启动器 / .desktop / AppStream / SVG 图标）
├─ tools/
│   ├─ make_icons.py         生成 PNG + ICO + ICNS（纯 Python，无第三方依赖）
│   └─ check_icons.py        校验图标真能解码 + 图形正确（CI 冒烟测试）
├─ examples/sample-payload.sh  桩载荷，用来验证打包链路本身
├─ linux/
│   ├─ build-deb.sh          → .deb
│   ├─ build-rpm.sh          → .rpm（调 rpmbuild）
│   └─ rpm/app.spec.in
├─ flatpak/
│   ├─ app.yml.in            Flatpak 清单（finish-args 是重点）
│   └─ build-flatpak.sh      → .flatpak 单文件
├─ windows/
│   ├─ Product.wxs.in        WiX v4/v5 定义
│   └─ build-msi.ps1         → .msi
├─ macos/
│   ├─ Info.plist.in         bundle 元数据（含 TCC 说明）
│   ├─ entitlements.plist    hardened runtime 例外（JVM 必需）
│   └─ build-dmg.sh          → .dmg
└─ release/
    └─ make_manifest.py      生成 latest.json + SHA256SUMS
```

## 五种格式

| 格式 | 在哪构建 | 需要什么 | 产出 |
|---|---|---|---|
| **Windows MSI** | Windows | `dotnet tool install --global wix` + `wix extension add WixToolset.UI.wixext` | `screengloss-<版本>-x64.msi` |
| **macOS DMG** | macOS | Xcode 命令行工具（`hdiutil`）；公证另需开发者账号 | `screengloss-<版本>.dmg` |
| **Linux deb** | Linux | `dpkg-deb`（Debian/Ubuntu 自带） | `screengloss_<版本>_amd64.deb` |
| **Linux rpm** | Linux | `rpmbuild`（`rpm-build` / `rpm` 包） | `screengloss-<版本>-1.x86_64.rpm` |
| **Linux Flatpak** | Linux | `flatpak` + `flatpak-builder` + runtime/sdk | `screengloss-<版本>.flatpak` |

## 载荷约定

所有格式吃的是**同一个东西**：一个「已装好」的文件树。

```
usr/bin/<slug>                     启动器（可执行）
usr/lib/<slug>/<slug>.jar          应用 jar
usr/lib/<slug>/runtime/            随包携带的 JRE（可选，但强烈建议）
usr/lib/<slug>/*.so,*.dll,*.dylib  JNI 原生库（MNN / slimt）
usr/share/applications/<id>.desktop
usr/share/metainfo/<id>.metainfo.xml
usr/share/icons/hicolor/{48x48,128x128,256x256,scalable}/apps/<id>.{png,svg}
```

`<slug>` / `<id>` 等取自 `config.sh`。启动器**自己找家**（见
`assets/app-launcher.sh.in`），所以 `/usr/lib`（deb/rpm）、`/app/lib`（Flatpak）
两种根都能认，不需要为每个格式改脚本。

### 推荐路线：先用 jpackage 出 app-image

JVM 应用有个省事得多的走法：**JDK 自带的 `jpackage`**。

```
jpackage --type app-image --name <slug> --input <jars> \
         --main-jar <slug>.jar --main-class <Main> \
         --icon build/icons/app.ico
```

它产出的 app-image **自带 JRE**（jlink 出来的运行时）、**自带原生启动器**
（Windows 上是 `.exe`，macOS 上是 `.app`），这正是上面那棵树里
`runtime/` 与 `bin/` 的来源。之后：

- MSI / DMG：直接用本目录的脚本包 app-image；
- 或者更省事：`jpackage --type msi|dmg|deb|rpm` 直接出包（它内部调 WiX / hdiutil / dpkg / rpmbuild）。
  代价是**控制力差**（打包元数据基本改不了，没有 Flatpak）。
- Flatpak：本目录的脚本，把 app-image 映射到 `/app` 根。

所以实际推荐是：**用 jpackage 出 app-image（4 种格式都能直接吃），
Flatpak 与本目录脚本负责剩下的事**。本目录的手写脚本给你的是
完整控制权（per-user MSI、hicolor 图标、AppStream 元数据、finish-args）。

## 快速开始（本地）

```bash
# 0) 图标（五种格式共用）
python3 packaging/tools/make_icons.py build/icons
python3 packaging/tools/check_icons.py build/icons          # 校验

# 1) 载荷 —— 现在是桩载荷；换成 PC 端真实构建的产物
APP_VERSION=0.2.0 bash packaging/examples/sample-payload.sh build/payload

# 2) Linux 三种
APP_VERSION=0.2.0 bash packaging/linux/build-deb.sh     build/payload build/out
APP_VERSION=0.2.0 bash packaging/linux/build-rpm.sh     build/payload build/out
APP_VERSION=0.2.0 INSTALL_DEPS=1 bash packaging/flatpak/build-flatpak.sh build/payload build/out

# 3) Windows / macOS（必须在对应系统上）
pwsh packaging/windows/build-msi.ps1 -PayloadDir build/payload -OutDir build/out
bash packaging/macos/build-dmg.sh build/MyApp.app build/out

# 4) 清单
python3 packaging/release/make_manifest.py 0.2.0 build/out -o build/out/latest.json
```

版本号走 `APP_VERSION` 环境变量，默认 `0.0.0`。预览版用 `-`（如 `0.2.0-rc1`），
脚本会自动转成 deb/rpm 规范的 `~`（`0.2.0~rc1`），保证正式版能正确覆盖预览版。

## 实测状态（重要）

本目录的脚本**不是全都跑过**。请按这张表理解可信度：

| 组件 | 状态 | 说明 |
|---|---|---|
| `make_icons.py` / `check_icons.py` | ✅ **实测通过** | 产出 6 尺寸 PNG + 6 项 ICO + 6 chunk ICNS，逐个解码校验通过 |
| `build-deb.sh` | ✅ **实测通过** | 构建 → `dpkg -i` 安装 → `dpkg --verify` → 运行 → `dpkg -r` 卸载，全流程通过 |
| `make_manifest.py` | ✅ **实测通过** | 生成 latest.json 与 SHA256SUMS，sha256 与 `sha256sum` 一致 |
| `sample-payload.sh` | ✅ **实测通过** | 注意它只是桩载荷，**不是应用** |
| `build-rpm.sh` | ⚠️ **未实测** | 本环境无 `rpmbuild`；首次启用请在 Fedora/openSUSE 上人肉确认 |
| `build-flatpak.sh` | ⚠️ **未实测** | 本环境无 `flatpak-builder` |
| `build-msi.ps1` | ⚠️ **未实测** | 需要 Windows；MSI 无法在 Linux 上构建 |
| `build-dmg.sh` | ⚠️ **未实测** | 需要 macOS；DMG 无法在 Linux 上构建 |
| `jpackage` | ⚠️ **部分实测** | 二进制存在、类型列表确认为 `app-image/rpm/deb`；但 deb 缺 `fakeroot`、rpm 类型不可用，本环境无法完成 |

CI（`.github/workflows/release.yml`）会在各自平台的 runner 上跑通后四项。

## 已知平台陷阱（都写进了对应文件的注释）

1. **MSI 与应用自更新冲突** —— Windows Installer 按引用计数管理文件，应用不能
   覆盖自身文件，必须 `msiexec /i <new>.msi /qn` 重装。
2. **MSI 的 `UpgradeCode` 永不改** —— 改了会变成并排安装两个。
3. **macOS 的 TCC 授权绑定签名标识 + 路径** —— 换签名或换安装路径 = 用户重新授权。
4. **macOS hardened runtime 下 JVM 会崩** —— 必须给 entitlements（见 `entitlements.plist`）。
5. **Flatpak 沙箱不改变平台能力边界** —— GNOME 下没 layer-shell、portal 不给 key-up，
   沙箱内外一样；沙箱只额外把截屏/热键逼上 portal。
6. **不要在 Flatpak 里加 `--filesystem=home`** —— 用 file chooser portal 按需授权。
7. **把 SVG 改名成 PNG 是假文件** —— 软件中心解码会失败；位图必须真生成。

## 许可与命名

`config.sh` 顶部的 `APP_NAME` / `APP_SLUG` / `APP_ID` **是占位值**。

`TRADEMARK.md` 规定：源码可以自称 PlayTranslate 的 fork，但**分发出去的构建产物
必须换名字、换图标、换 application id**。所以公开发布前必须改这三个值
（以及 `packaging/assets/icon.svg` 与 `tools/make_icons.py` 里的图形）。

许可本身没有选择余地：上游是 GPL-3.0，本 fork 只能是 GPL-3.0。
分发时必须随附 `LICENSE` 全文。
