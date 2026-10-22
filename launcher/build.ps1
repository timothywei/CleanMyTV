# ClearYourTV Home launcher - 纯命令行构建（aapt2 + javac + d8 + zipalign + apksigner）
# 用法: .\build.ps1 [-Manifest AndroidManifest-ts.xml] [-OutName ClearYourTV-ts.apk]
param(
    [string]$Manifest = "AndroidManifest.xml",
    [string]$OutName  = "ClearYourTV.apk"
)
$ErrorActionPreference = "Stop"

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
$SDK         = $env:ANDROID_HOME
$BT          = Join-Path $SDK "build-tools\35.0.0"
$AndroidJar  = Join-Path $SDK "platforms\android-34\android.jar"
$Build       = Join-Path $Root "build"
$OutApk      = Join-Path $Root $OutName
$Ks          = Join-Path $Root "debug.jks"

$aapt2     = Join-Path $BT "aapt2.exe"
$aapt      = Join-Path $BT "aapt.exe"
$d8        = Join-Path $BT "d8.bat"
$zipalign  = Join-Path $BT "zipalign.exe"
$apksigner = Join-Path $BT "apksigner.bat"

Write-Host "== 0. 检查工具 =="
foreach ($t in @($aapt2, $aapt, $d8, $zipalign, $apksigner, $AndroidJar)) {
    if (-not (Test-Path $t)) { throw "缺少: $t" }
}
Write-Host "   OK"

Write-Host "== 1. 清理 build =="
if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Path $Build | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "gen")   | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "classes") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Build "dex")   | Out-Null

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

Write-Host "== 3. javac 编译 =="
$javaFiles = @()
$javaFiles += Get-ChildItem (Join-Path $Root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "   源文件数: $($javaFiles.Count)"
& javac --release 11 -encoding UTF-8 -classpath $AndroidJar -d (Join-Path $Build "classes") $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "== 4. d8 -> classes.dex =="
$classFiles = Get-ChildItem (Join-Path $Build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --release --min-api 21 --lib $AndroidJar --output (Join-Path $Build "dex") $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 5. 把 dex 塞进 APK =="
Copy-Item (Join-Path $Build "base.apk") (Join-Path $Build "work.apk") -Force
Push-Location (Join-Path $Build "dex")
try {
    & $aapt add $Build\work.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw "aapt add 失败" }
} finally { Pop-Location }

Write-Host "== 6. zipalign =="
& $zipalign -f 4 (Join-Path $Build "work.apk") (Join-Path $Build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 7. keystore =="
if (-not (Test-Path $Ks)) {
    & keytool -genkeypair -v -keystore $Ks -alias tvhome -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass tvhome123 -keypass tvhome123 `
        -dname "CN=ClearYourTV, OU=Dev, O=Local, L=Local, ST=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败" }
} else { Write-Host "   复用已有 keystore" }

Write-Host "== 8. 签名 =="
if (Test-Path $OutApk) { Remove-Item $OutApk -Force }
& $apksigner sign --ks $Ks --ks-pass pass:tvhome123 --key-pass pass:tvhome123 `
    --out $OutApk (Join-Path $Build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "== 9. 校验 =="
& $apksigner verify --print-certs $OutApk
if ($LASTEXITCODE -ne 0) { throw "签名校验失败" }

$size = [math]::Round((Get-Item $OutApk).Length / 1KB, 1)
Write-Host ""
Write-Host "构建成功: $OutApk ($size KB)" -ForegroundColor Green
