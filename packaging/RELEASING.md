# 怎么发一个发行版

面向仓库维护者。假设你已经能构建出五个文件（见 `packaging/README.md`）。

---

## 0. 先弄清三件事的关系

| 概念 | 是什么 | 注意 |
|---|---|---|
| **tag** | git 里的一个固定标记，如 `v0.2.0` | CI 的触发条件就是它（`v*`） |
| **Release** | 挂在某个 tag 上的、带说明文字和附件的东西 | 可以**先有 tag 再补 Release**，也可以建 Release 时顺手打 tag |
| **asset** | Release 里的附件（就是那五个安装包） | 单个文件上限 **2 GB**；总量无硬限但别滥用 |

**关键**：Release 不是"发布软件"的开关，它只是一个带附件的页面。
用户点 Releases 能看到什么，完全取决于你往里面放了什么。

---

## 1. 版本号规则

用语义化版本：`MAJOR.MINOR.PATCH`，tag 前面加 `v`。

```
v0.2.0       正式版
v0.2.1       修 bug
v1.0.0       功能稳定
v0.2.0-rc1   预览版（release candidate）
```

预览版写 `-rc1` 就行，**打包脚本会自动处理各格式的差异**：

```
deb / rpm  →  0.2.0~rc1     （'~' 在两者中都表示"低于正式版"，所以 0.2.0 能正确覆盖 rc）
```

⚠️ 别在版本号里用别的符号（`+` 在 deb 里有特殊含义，`_` 在某些格式里非法）。

---

## 2. 路线 A：让 CI 出包（推荐）

`.github/workflows/release.yml` 已经写好：推一个 `v*` tag，它在三种系统的
runner 上分别构建，最后自动建 Release 并把五个附件挂上去。

```bash
# 1. 确认工作区干净、在 main 上
git status
git checkout main && git pull

# 2. 打 tag 并推上去（这一步就触发了整个流程）
git tag v0.2.0
git push origin v0.2.0

# 3. 看构建进度
gh run list --limit 5
gh run watch              # 实时跟踪最新一次

# 4. 构建完，Release 已经建好了
gh release view v0.2.0
```

想先试而不真的发：在 Actions 页面点 **Run workflow**（`workflow_dispatch`）。
它只构建、只留 artifact，**不建 Release**（因为 `release` job 有
`if: startsWith(github.ref, 'refs/tags/v')`）。这是个安全的彩排。

### 如果 CI 挂了怎么办

```bash
gh run view <run-id> --log-failed      # 只看失败那步的日志
```

改完代码后**重打 tag**：

```bash
git tag -d v0.2.0                      # 删本地 tag
git push origin :refs/tags/v0.2.0      # 删远端 tag
# 修完提交，再重新 tag + push
```

---

## 3. 路线 B：手动建 Release 并上传

CI 没配好、或者你只想手搓一个包时用这条。

### 3.1 网页操作

1. 打开 `https://github.com/Only-Air/playtranslate-for-PC/releases`
2. 点 **Draft a new release**
3. **Choose a tag** → 输入 `v0.2.0` → 选 **Create new tag on publish**（或选已有 tag）
4. 填 **Release title**（习惯上就写 `v0.2.0`）
5. 写说明（见 §4）
6. 把五个文件**拖进 "Attach binaries" 区域**
7. 需要的话勾 **Set as a pre-release**（预览版）/ **Set as the latest release**
8. 点 **Publish release**

⚠️ 只点 **Save draft** 的话，普通用户看不到——草稿只有你能看。

### 3.2 gh CLI 操作（更快，可脚本化）

```bash
# 装好并登录
gh auth login

# 建 Release + 一次性上传所有附件
gh release create v0.2.0 \
    --title "v0.2.0" \
    --generate-notes \
    build/out/*.deb build/out/*.rpm build/out/*.flatpak \
    build/out/*.msi build/out/*.dmg \
    build/out/latest.json build/out/SHA256SUMS
```

常用变体：

```bash
# 草稿（不公开，先自己检查）
gh release create v0.2.0 --draft --title "v0.2.0" build/out/*

# 预览版标记
gh release create v0.2.0-rc1 --prerelease --title "v0.2.0-rc1" build/out/*

# 用文件里的说明（比 --generate-notes 可控）
gh release create v0.2.0 --notes-file notes.md build/out/*

# 往已存在的 Release 追加附件
gh release upload v0.2.0 build/out/new-file.deb

# 改说明 / 改标题
gh release edit v0.2.0 --notes-file notes.md

# 列出现有 Release
gh release list

# 删掉 Release（连带删 tag）
gh release delete v0.2.0 --cleanup-tag --yes
```

⚠️ **同名附件不能覆盖**。要替换必须先删：

```bash
gh release delete-asset v0.2.0 screengloss_0.2.0_amd64.deb --yes
gh release upload v0.2.0 build/out/screengloss_0.2.0_amd64.deb
```

---

## 4. 该上传哪七个文件

| 文件 | 作用 |
|---|---|
| `screengloss_<版本>_amd64.deb` | Debian / Ubuntu |
| `screengloss-<版本>-1.x86_64.rpm` | Fedora / openSUSE |
| `screengloss-<版本>.flatpak` | 所有发行版通用（单文件） |
| `screengloss-<版本>-x64.msi` | Windows |
| `screengloss-<版本>.dmg` | macOS |
| `latest.json` | **应用自更新读的清单**（版本 / 文件名 / 大小 / sha256） |
| `SHA256SUMS` | 人工校验用 |

`latest.json` 由 `packaging/release/make_manifest.py` 生成，格式：

```json
{
  "version": "0.2.0",
  "released": "2026-09-22",
  "notes_url": "https://github.com/.../releases/tag/v0.2.0",
  "assets": {
    "linux-deb": { "file": "screengloss_0.2.0_amd64.deb", "size": 15760, "sha256": "..." }
  }
}
```

它沿用上游 `langpack_catalog.json` 的清单模式：**把不稳定的外部结构
（GitHub Releases API 的返回格式）挡在外面**，自更新逻辑只解析这一个稳定的 JSON。

## 5. 发行说明怎么写

`--generate-notes` 只是把合并的 PR 和提交标题拼起来，对用户不友好。
手写的话，建议就三块：

```markdown
## 这一版

- 屏幕翻译在 KDE/X11 上可用：全局热键 → 截屏 → OCR → 翻译 → 叠字
- 新增 deb 与 rpm 包

## 安装

- **Windows**：下载 `.msi`，双击。首次运行按提示开屏幕录制授权。
- **macOS**：下载 `.dmg`，拖进「应用程序」。未公证版本需右键 → 打开。
- **Debian/Ubuntu**：`sudo dpkg -i screengloss_0.2.0_amd64.deb`
- **Fedora/openSUSE**：`sudo rpm -i screengloss-0.2.0-1.x86_64.rpm`
- **任意发行版**：`flatpak install --user screengloss-0.2.0.flatpak`

## 已知问题

- GNOME（Wayland）无法画悬浮叠字层，只能用侧栏模式
- Wayland 下全局快捷键只有"按下"事件，按住预览退化为开关
```

**写清平台限制**比写清功能更重要——上游 Android 版没有这些限制，
PC 版有，用户不看说明只会来开 issue。

## 6. 校验和

用户拿到包之后应该能自证没被改过：

```bash
sha256sum -c SHA256SUMS              # Linux
shasum -a 256 -c SHA256SUMS          # macOS
Get-FileHash -Algorithm SHA256 <文件> # Windows（人工比对）
```

`SHA256SUMS` 与 `latest.json` 里的哈希**必须一致**（同一个脚本生成的，
所以天然一致——别手动改其中一个）。

## 7. 发布前检查清单

- [ ] 版本号与 tag 一致（`v` 前缀只在 tag 上，附件文件名里没有 `v`）
- [ ] 五个格式齐全（缺哪个平台就先别写"支持 XXX"）
- [ ] `latest.json` 与 `SHA256SUMS` 都在
- [ ] 发行说明里写清了平台限制
- [ ] `config.sh` 里的 `APP_NAME`/`APP_SLUG`/`APP_ID` **不是占位值**
      （TRADEMARK.md 要求分发版换名换图标，见 `packaging/README.md` §许可与命名）
- [ ] 包内随附了 `LICENSE` 全文（GPL-3.0 的要求）
- [ ] 在干净的机器/虚拟机上真装一遍（CI 里的 `dpkg -i` 只覆盖了一个平台）

## 8. 关于"官方版"的措辞

`TRADEMARK.md` 允许你说明这是 PlayTranslate 的 fork，但**不允许**暗示是官方版本。
发行说明里建议固定写一句：

> 本版本是 PlayTranslate 的非官方 PC 移植分支，与上游项目无隶属或背书关系。

另外这个 fork 目前**不打算持续维护**，建议在 Release 说明里也写清楚，
避免用户期待长期更新与支持。
