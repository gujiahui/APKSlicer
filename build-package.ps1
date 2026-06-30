$projectName = "APKSlicer"
$version = "0.0.1-SNAPSHOT"
$jarFileName = "$projectName-$version.jar"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "      $projectName Package Builder" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

$baseDir = Get-Location
$targetDir = Join-Path $baseDir "target"
$exeInputDir = Join-Path $targetDir "exe-input"
$exeOutputDir = Join-Path $targetDir "exe-output"
$runtimeImageDir = Join-Path $targetDir "runtime-image"
$libDir = Join-Path $baseDir "src/main/resources/lib"

Write-Host "`n[1/5] Cleaning previous build..." -ForegroundColor Yellow
Remove-Item -Recurse -Force $exeInputDir -ErrorAction SilentlyContinue | Out-Null
Remove-Item -Recurse -Force $exeOutputDir -ErrorAction SilentlyContinue | Out-Null
Remove-Item -Recurse -Force $runtimeImageDir -ErrorAction SilentlyContinue | Out-Null

New-Item -ItemType Directory -Force -Path $exeInputDir | Out-Null
New-Item -ItemType Directory -Force -Path $exeOutputDir | Out-Null

Write-Host "[2/5] Copying JAR file..." -ForegroundColor Yellow
$jarPath = Join-Path $targetDir $jarFileName
if (-not (Test-Path $jarPath)) {
    Write-Host "ERROR: JAR file not found at $jarPath" -ForegroundColor Red
    Write-Host "Please run 'mvn package -DskipTests' first" -ForegroundColor Red
    exit 1
}
Copy-Item -Path $jarPath -Destination $exeInputDir -Force
Write-Host "      Copied: $jarFileName" -ForegroundColor Green

Write-Host "[3/5] Creating runtime image with jlink..." -ForegroundColor Yellow
$modules = @(
    "java.base",
    "java.sql",
    "java.naming",
    "java.desktop",
    "java.management",
    "java.logging",
    "java.xml",
    "java.security.jgss",
    "java.instrument",
    "java.sql.rowset",
    "java.transaction.xa",
    "java.net.http",
    "java.prefs",
    "java.security.sasl",
    "java.datatransfer",
    "jdk.attach",
    "jdk.compiler",
    "jdk.jfr",
    "jdk.management",
    "jdk.management.jfr",
    "jdk.unsupported",
    "jdk.naming.dns",
    "jdk.crypto.ec",
    "jdk.crypto.cryptoki",
    "jdk.crypto.mscapi",
    "jdk.internal.opt",
    "jdk.internal.jvmstat",
    "jdk.httpserver"
)
$moduleList = $modules -join ","

& jlink `
    --module-path "$($env:JAVA_HOME)\jmods" `
    --add-modules $moduleList `
    --output $runtimeImageDir `
    --compress=2 `
    --no-header-files `
    --no-man-pages

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: jlink failed" -ForegroundColor Red
    exit 1
}
Write-Host "      Runtime image created successfully" -ForegroundColor Green

Write-Host "[4/5] Creating application image with jpackage..." -ForegroundColor Yellow
Remove-Item -Recurse -Force "$exeOutputDir\$projectName" -ErrorAction SilentlyContinue | Out-Null
& jpackage `
    --type app-image `
    --name $projectName `
    --input $exeInputDir `
    --main-jar $jarFileName `
    --main-class org.springframework.boot.loader.launch.JarLauncher `
    --dest $exeOutputDir `
    --runtime-image $runtimeImageDir `
    --win-console `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Dsun.jnu.encoding=UTF-8"

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: jpackage failed" -ForegroundColor Red
    exit 1
}
Write-Host "      Application image created successfully" -ForegroundColor Green

$appOutputDir = Join-Path $exeOutputDir $projectName
$appLibDir = Join-Path $appOutputDir "app/lib"
$appConfigDir = Join-Path $appOutputDir "app/config"
$appLogsDir = Join-Path $appOutputDir "app/logs"
$appTempDir = Join-Path $appOutputDir "app/temp"
$appApkOutputDir = Join-Path $appOutputDir "app/output"

Write-Host "[5/5] Copying additional resources..." -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path $appLibDir | Out-Null
New-Item -ItemType Directory -Force -Path $appConfigDir | Out-Null
New-Item -ItemType Directory -Force -Path $appLogsDir | Out-Null
New-Item -ItemType Directory -Force -Path $appTempDir | Out-Null
New-Item -ItemType Directory -Force -Path $appApkOutputDir | Out-Null

Write-Host "      Copying lib jars..." -ForegroundColor Yellow
Get-ChildItem -Path $libDir -Filter "*.jar" | ForEach-Object {
    Copy-Item -Path $_.FullName -Destination $appLibDir -Force
    Write-Host "        Copied: $($_.Name)" -ForegroundColor Green
}

Write-Host "      Copying config file..." -ForegroundColor Yellow
Copy-Item -Path "src/main/resources/application-local.yml" -Destination "$appConfigDir/application.yml" -Force
Write-Host "        Copied: application.yml" -ForegroundColor Green

Write-Host "      Creating start script..." -ForegroundColor Yellow
$startScript = @"
@echo off
title $projectName
cd /d "%~dp0app"
echo ==============================================
echo          $projectName Starting...
echo ==============================================
echo Application will be available at:
echo http://localhost/
echo ==============================================
echo NOTE: Keep this window open while using the application
echo ==============================================
start http://localhost/
"%~dp0$projectName.exe"
pause
"@
$startScript | Out-File -FilePath "$appOutputDir/start.bat" -Encoding ASCII
Write-Host "        Created: start.bat" -ForegroundColor Green

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "      Package Build Completed!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "`nOutput directory: $appOutputDir" -ForegroundColor Yellow
Write-Host "`nTo run the application:" -ForegroundColor Yellow
Write-Host "  1. Double-click: start.bat" -ForegroundColor White
Write-Host "  2. Or run: $projectName.exe" -ForegroundColor White
Write-Host "`nApplication URL: http://localhost:8080/$projectName" -ForegroundColor Yellow
Write-Host "============================================" -ForegroundColor Cyan