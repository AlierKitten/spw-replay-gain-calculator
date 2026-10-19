<#
.SYNOPSIS
    Inspect the Salt Player for Windows installation to locate the host library
    scan / refresh entry point, so the ReplayGain Calculator plugin can invoke
    it via reflection.

.DESCRIPTION
    Reads app/Salt Player for Windows.cfg to determine the host's classpath,
    inspects the container (typically a fat jar masquerading as ffmpeg-x64.dll),
    filters classes whose names suggest a library scan / refresh responsibility,
    then runs `javap -p` on the candidates to surface method signatures.

    This script is read-only with respect to the host install; the only writes
    are to a temporary directory under $env:TEMP, used to materialise a working
    classpath for `javap`.

.PARAMETER HostRoot
    Root directory of the SPW installation. Defaults to the path discussed during
    planning. Re-run with a different path after Salt Player updates.

.PARAMETER TempDir
    Working directory for extracted classes. Defaults to a fresh subdirectory
    under $env:TEMP.

.PARAMETER Javap
    Absolute path to the JDK's javap.exe. Defaults to the path under the current
    user's Gradle toolchain JDK. Pass `-Javap ""` to skip the default and rely on
    `javap` being on `PATH`.

.EXAMPLE
    pwsh ./tools/inspect-host.ps1
    pwsh ./tools/inspect-host.ps1 -HostRoot 'D:\Games\Salt Player for Windows'
    pwsh ./tools/inspect-host.ps1 -Javap 'C:\Program Files\Java\jdk-25\bin\javap.exe'

.NOTES
    - Requires a JDK on PATH (for `javap`); the SPW runtime is a stripped JRE
      and does not ship the development tools.
    - The script does not assume the container is a zip; if the magic number is
      not `PK\03\04` the script exits with a clear error so we know the host
      layout changed.
#>

[CmdletBinding()]
param(
    [string]$HostRoot = "E:\Steam\SteamApps\common\Salt Player for Windows",
    [string]$TempDir,
    [string]$Javap = "C:\Users\Shiin\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\javap.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Windows PowerShell 5.x ships without System.IO.Compression.FileSystem loaded.
# Explicitly pull it in so this script works on both PowerShell 5.x and 7.x.
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-Section([string]$title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

function Resolve-ClasspathContainer([string]$HostRoot) {
    $cfgPath = Join-Path $HostRoot "app\Salt Player for Windows.cfg"
    if (-not (Test-Path -LiteralPath $cfgPath)) {
        throw "未找到 cfg 文件: $cfgPath"
    }
    $classpathLine = Get-Content -LiteralPath $cfgPath |
        Where-Object { $_ -match '^app\.classpath\s*=' } |
        Select-Object -First 1
    if (-not $classpathLine) {
        throw "cfg 文件中没有 app.classpath 行"
    }
    $value = ($classpathLine -split '=', 2)[1].Trim()
    $value = $value -replace '\$APPDIR', (Join-Path $HostRoot "app")
    return $value
}

function Test-ZipMagic([string]$path) {
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $buffer = New-Object 'byte[]' 4
        $read = $stream.Read($buffer, 0, 4)
        if ($read -lt 4) { return $false }
        return ($buffer[0] -eq 0x50 -and $buffer[1] -eq 0x4B -and $buffer[2] -eq 0x03 -and $buffer[3] -eq 0x04)
    } finally {
        $stream.Dispose()
    }
}

if (-not $TempDir) {
    $TempDir = Join-Path $env:TEMP ("spw-inspect-" + [Guid]::NewGuid().ToString('N'))
}
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
Write-Host "工作目录: $TempDir"

Write-Section "宿主信息"
Write-Host "HostRoot: $HostRoot"
$container = Resolve-ClasspathContainer -HostRoot $HostRoot
Write-Host "app.classpath 指向: $container"
if (-not (Test-Path -LiteralPath $container)) {
    throw "classpath 容器不存在: $container"
}
$containerInfo = Get-Item -LiteralPath $container
Write-Host ("容器大小: {0:N2} MB" -f ($containerInfo.Length / 1MB))

if (-not (Test-ZipMagic -path $container)) {
    Write-Warning "容器不是 zip/jar 格式，可能是自定义归档格式；当前脚本无法继续。"
    Write-Warning "请重新分析宿主打包方式，或寻找其他入口探测手段。"
    exit 2
}
Write-Host "魔数: PK\\03\\04 (ZIP/jar) — 可继续枚举"

Write-Section "扫描候选类（按名称过滤）"
$zip = [System.IO.Compression.ZipFile]::OpenRead($container)
$pattern = 'MainKt|Library|Scan|Refresh|Reindex|Index|Folder|MediaStore|Catalog|MusicSource|LibraryScanner|LibraryRepository'
# Salt Player is published by Moriafly; the application packages are likely under
# `com.moriafly` / `io.github.moriafly` / `com.xuncorp`. Third-party libraries
# (androidx, kotlin, kotlinx, bytebuddy, jetbrains, junit, etc.) clutter the
# candidate set, so exclude them via a directory prefix filter first.
$rootPrefixes = @('com/moriafly', 'io/github/moriafly', 'com/xuncorp', 'saltplayer', 'Moriafly')
try {
    $candidates = @()
    foreach ($entry in $zip.Entries) {
        $name = $entry.FullName
        if (-not $name.EndsWith(".class", [System.StringComparison]::OrdinalIgnoreCase)) { continue }
        if (-not $name.Contains('/')) { continue }
        # 跳过合成类与明显无关的目录
        if ($name -match 'META-INF|module-info|build/generated') { continue }
        $inAppPackage = $false
        foreach ($prefix in $rootPrefixes) {
            if ($name.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                $inAppPackage = $true
                break
            }
        }
        if (-not $inAppPackage) { continue }
        if ($name -match $pattern) { $candidates += $name }
    }
    $candidates = $candidates | Sort-Object
    Write-Host ("候选 .class 数量: {0}" -f $candidates.Count)
    foreach ($c in $candidates) {
        Write-Host "  - $c"
    }
}
finally {
    $zip.Dispose()
}

if ($candidates.Count -eq 0) {
    Write-Warning "没有命中候选类；可能是类名被混淆。请尝试扩大关键字或人工分析。"
    exit 3
}

Write-Section "准备 javap classpath"
# 把宿主 Jar 复制一份成 .jar 扩展名，方便 javap 解析（PowerShell ZipFile API 不区分扩展名）
$workingJar = Join-Path $TempDir "host.jar"
Copy-Item -LiteralPath $container -Destination $workingJar -Force
Write-Host "复制容器 -> $workingJar"

if (-not (Test-Path -LiteralPath $Javap)) {
    Write-Warning "未找到 javap: $Javap"
    Write-Warning "请通过 -Javap 指定 JDK 25 的 javap.exe 路径后重跑。"
    exit 4
}
Write-Host "javap: $Javap"

Write-Section "javap 输出（前 30 个候选，按类名字母串首 30 个）"
$limit = 30
$shown = 0
foreach ($classPath in $candidates) {
    if ($shown -ge $limit) { break }
    $className = ($classPath -replace '\.class$', '') -replace '/', '.'
    Write-Host ""
    Write-Host "--- $className ---" -ForegroundColor Yellow
    $output = & $Javap -p -classpath $workingJar $className 2>&1
    foreach ($line in $output) {
        Write-Host "    $line"
    }
    $shown++
}

Write-Section "完成"
Write-Host "若已定位到「刷新音乐库」入口，请把对应的类名 / 方法名 / 参数类型写入"
Write-Host "  src/main/kotlin/com/alierkitten/spw/replaygain/host/HostScanEntry.kt"