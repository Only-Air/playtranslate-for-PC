#Requires -Version 5.1
<#
.SYNOPSIS
    构建 Windows MSI 安装包（WiX v4/v5）。

.DESCRIPTION
    载荷是 Windows 侧的 app-image —— 由 jpackage 产出，含自带 JRE、原生启动器
    <slug>.exe、以及 JNI 的 .dll。本脚本把它收割进 MSI。

    一次准备（CI 里也这么做）：
        dotnet tool install --global wix
        wix extension add WixToolset.UI.wixext

.EXAMPLE
    ./build-msi.ps1 -PayloadDir C:\build\app-image -OutDir C:\build\msi
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PayloadDir,
    [string]$OutDir = "",
    [string]$IconFile = "",
    [string]$LicenseRtf = "",
    [string]$Arch = "x64"
)

$ErrorActionPreference = "Stop"

$packaging = Split-Path -Parent $PSScriptRoot          # packaging/
$root      = Split-Path -Parent $packaging             # 仓库根
if (-not $OutDir)    { $OutDir = Join-Path $root "build\msi" }
if (-not $IconFile)  { $IconFile = Join-Path $root "build\icons\app.ico" }

# ── 从 config.sh 读元数据（单一事实来源，避免两处维护） ──────────────
$cfg = @{}
Get-Content (Join-Path $packaging "config.sh") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)="?([^"#]*?)"?\s*$') {
        $k = $Matches[1]; $v = $Matches[2].Trim()
        # 跳过 ${VAR:-default} 形式的行（APP_VERSION），下面单独处理
        if ($v -notmatch '\$\{') { $cfg[$k] = $v }
    }
}
$version = if ($env:APP_VERSION) { $env:APP_VERSION } else { "0.0.0" }
$cfg["APP_VERSION"] = $version
$cfg["APP_DATE"] = (Get-Date -Format "yyyy-MM-dd")

foreach ($need in @("APP_SLUG", "APP_ID", "APP_NAME", "APP_MAINTAINER", "APP_SUMMARY")) {
    if (-not $cfg.ContainsKey($need)) { throw "config.sh 里没读到 $need" }
}

if (-not (Test-Path $PayloadDir)) { throw "载荷目录不存在：$PayloadDir" }
if (-not (Test-Path $IconFile))   {
    throw "缺少 .ico：$IconFile`n先跑：python tools/make_icons.py build/icons`n（ARPPRODUCTICON 只吃 ICO，不吃 PNG）"
}

# ── 渲染 .wxs ────────────────────────────────────────────────────────
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("wix-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work -Force | Out-Null

$wxs = Get-Content (Join-Path $PSScriptRoot "Product.wxs.in") -Raw
foreach ($k in $cfg.Keys) { $wxs = $wxs.Replace("@${k}@", $cfg[$k]) }
Copy-Item $IconFile (Join-Path $work "AppIcon.ico") -Force

# 许可页需要一个 RTF。GPL 要求分发时随附许可全文——全文以文件形式随载荷安装，
# 这里只放一页说明（这是通行做法）。给了 -LicenseRtf 就用你自己的。
if ($LicenseRtf -and (Test-Path $LicenseRtf)) {
    Copy-Item $LicenseRtf (Join-Path $work "License.rtf") -Force
} else {
    $rtf = @"
{\rtf1\ansi
\b $($cfg['APP_NAME'])\b0\par
\par
This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.\par
\par
This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.\par
\par
The full license text is installed alongside the application (LICENSE).\par
\par
This is an unofficial fork of PlayTranslate and is not endorsed by the
upstream project.\par
}
"@
    Set-Content -Path (Join-Path $work "License.rtf") -Value $rtf -Encoding ASCII
}
Set-Content -Path (Join-Path $work "Product.wxs") -Value $wxs -Encoding UTF8

# ── 构建 ─────────────────────────────────────────────────────────────
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$msi = Join-Path $OutDir "$($cfg.APP_SLUG)-$version-$Arch.msi"

Write-Host "==> wix build（WiX $((wix --version))）"
wix build `
    -arch $Arch `
    -ext WixToolset.UI.wixext `
    -bindpath "payload=$((Resolve-Path $PayloadDir).Path)" `
    -o $msi `
    (Join-Path $work "Product.wxs")

Remove-Item $work -Recurse -Force

$size = (Get-Item $msi).Length
$sha  = (Get-FileHash $msi -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "==> $msi"
Write-Host ("    {0:N0} B  sha256={1}..." -f $size, $sha.Substring(0, 16))
Write-Host ""
Write-Host "    静默安装：msiexec /i `"$msi`" /qn /norestart"
Write-Host "    卸载：    msiexec /x `"$msi`" /qn"
Write-Host "    查看：    Get-FileHash -Algorithm SHA256 `"$msi`""
Write-Host ""
Write-Host "    提醒：应用自更新必须走 msiexec /i 重装，不要覆盖自身文件（见 Product.wxs.in 注释）。"
