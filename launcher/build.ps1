# CleanMyTV Home launcher - 纯命令行构建（aapt2 + javac + d8 + zipalign + apksigner）
#
# 用法:
#   $env:CMTV_KS_PASS = "你的密钥库口令"     # 或改用 -KsPass 参数传入
#   .\build.ps1 -Manifest AndroidManifest-ts.xml -OutName CleanMyTV.apk
#
# 说明:
#   - 签名口令不再写死在本脚本里。请通过环境变量 CMTV_KS_PASS 提供。
#   - 首次构建若没有 debug.jks，会用该口令新建一个仅属于你本机的密钥库。
#   - 密钥库一旦丢失，新构建的 APK 无法覆盖安装旧版（只能卸载重装，会导致 Home 设置失效）。
param(
    [string]$Manifest = "AndroidManifest.xml",
    [string]$OutName  = "CleanMyTV.apk",
    [string]$KsPass   = $env:CMTV_KS_PASS
)
$ErrorActionPreference = "Stop"

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
$SDK         = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $SDK) { throw "未设置 ANDROID_HOME（或 ANDROID_SDK_ROOT），请指向你的 Android SDK 目录" }
$Build       = Join-Path $Root "build"
$OutApk      = Join-Path $Root $OutName
$Ks          = Join-Path $Root "debug.jks"

Write-Host "== 0. 定位工具链 =="
# 自动选取已安装的最新 build-tools 与 android.jar，避免写死版本号
$BtDir = Join-Path $SDK "build-tools"
if (-not (Test-Path $BtDir)) { throw "找不到 build-tools 目录: $BtDir" }
$BtNewest = Get-ChildItem -LiteralPath $BtDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d+(\.\d+)+$' } |
    Sort-Object { [version]$_.Name } |
    Select-Object -Last 1
if (-not $BtNewest) { throw "build-tools 目录下没有任何版本: $BtDir" }
$BT = $BtNewest.FullName
Write-Host "   build-tools: $($BtNewest.Name)"

$PlatDir     = Join-Path $SDK "platforms"
$AndroidJar  = Join-Path $PlatDir "android-34\android.jar"
if (-not (Test-Path $AndroidJar)) {
    $PlatNewest = Get-ChildItem -LiteralPath $PlatDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-\d+$' } |
        Sort-Object { [int]($_.Name -replace 'android-', '') } |
        Select-Object -Last 1
    if (-not $PlatNewest) { throw "找不到任何 platform: $PlatDir" }
    $AndroidJar = Join-Path $PlatNewest.FullName "android.jar"
    Write-Host "   警告: 未找到 android-34，改用 $($PlatNewest.Name)（targetSdkVersion 仍为 34）" -ForegroundColor Yellow
}
Write-Host "   android.jar: $AndroidJar"

# Windows 上是 aapt2.exe / d8.bat；Linux·macOS 上是 aapt2 / d8（CI 的 Linux runner 会用到）
$isWin     = ($env:OS -eq 'Windows_NT')
$exeSuffix = if ($isWin) { ".exe" } else { "" }
$aapt2     = Join-Path $BT "aapt2$exeSuffix"
$aapt      = Join-Path $BT "aapt$exeSuffix"
$d8        = Join-Path $BT $(if ($isWin) { "d8.bat" } else { "d8" })
$zipalign  = Join-Path $BT "zipalign$exeSuffix"
$apksigner = Join-Path $BT $(if ($isWin) { "apksigner.bat" } else { "apksigner" })

foreach ($t in @($aapt2, $aapt, $d8, $zipalign, $apksigner, $AndroidJar)) {
    if (-not (Test-Path $t)) { throw "缺少: $t（请确认 build-tools 与 platforms 已完整安装）" }
}
Write-Host "   OK"

Write-Host "== 1. 清理 build =="
if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Path $Build | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "gen")     | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "classes") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "dex")     | Out-Null

Write-Host "== 2. 编译资源 =="
$resDir = Join-Path $Root "res"
$resZip = Join-Path $Build "res.zip"
$hasRes = $false
if (Test-Path $resDir) {
    & $aapt2 compile --dir $resDir -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }
    $hasRes = $true
    Write-Host "   资源已编译"
} else {
    Write-Host "   无 res 目录，跳过"
}

Write-Host "== 3. aapt2 link (manifest -> base.apk) =="
$linkArgs = @(
    "link",
    "-o", (Join-Path $Build "base.apk"),
    "--manifest", (Join-Path $Root $Manifest),
    "-I", $AndroidJar,
    "--java", (Join-Path $Build "gen"),
    "--custom-package", "com.cleanmytv.home",
    "--min-sdk-version", "21",
    "--target-sdk-version", "34"
)
if ($hasRes) { $linkArgs += @("-R", $resZip) }
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "== 4. javac 编译 =="
$javaFiles = @()
$javaFiles += Get-ChildItem (Join-Path $Root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "   源文件数: $($javaFiles.Count)"
& javac --release 11 -encoding UTF-8 -classpath $AndroidJar -d (Join-Path $Build "classes") $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "== 5. d8 -> classes.dex =="
$classFiles = Get-ChildItem (Join-Path $Build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --release --min-api 21 --lib $AndroidJar --output (Join-Path $Build "dex") $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 6. 把 dex 塞进 APK =="
Copy-Item (Join-Path $Build "base.apk") (Join-Path $Build "work.apk") -Force
Push-Location (Join-Path $Build "dex")
try {
    & $aapt add (Join-Path $Build "work.apk") classes.dex
    if ($LASTEXITCODE -ne 0) { throw "aapt add 失败" }
} finally { Pop-Location }

Write-Host "== 7. zipalign =="
& $zipalign -f 4 (Join-Path $Build "work.apk") (Join-Path $Build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 8. 签名 =="
# 口令统一走环境变量传给 keytool / apksigner，不出现在命令行参数里
if ($KsPass) { $env:CMTV_KS_PASS = $KsPass }
if (-not $env:CMTV_KS_PASS) {
    throw "缺少签名口令。请先设置环境变量 CMTV_KS_PASS，或传 -KsPass 参数。"
}

if (-not (Test-Path $Ks)) {
    Write-Host "   未找到 debug.jks，正在创建新的密钥库（仅本机可用）..."
    & keytool -genkeypair -v -keystore $Ks -alias tvhome -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass:env CMTV_KS_PASS -keypass:env CMTV_KS_PASS `
        -dname "CN=CleanMyTV, OU=Dev, O=CleanMyTV, L=Local, ST=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败" }
    Write-Host "   已生成: $Ks  ← 请务必备份，丢失后无法覆盖安装旧版" -ForegroundColor Yellow
} else { Write-Host "   复用已有 keystore" }

if (Test-Path $OutApk) { Remove-Item $OutApk -Force }
& $apksigner sign --ks $Ks --ks-pass env:CMTV_KS_PASS --key-pass env:CMTV_KS_PASS `
    --out $OutApk (Join-Path $Build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "== 9. 校验 =="
& $apksigner verify --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw "签名校验失败" }

$size = [math]::Round((Get-Item $OutApk).Length / 1KB, 1)
Write-Host ""
Write-Host "构建成功: $OutApk ($size KB)" -ForegroundColor Green
