<#
.SYNOPSIS
    Builds and installs Avaruusmulkko to Google Pixel 9 Pro XL via ADB.
#>

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# 1. Locate Java
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe") -ErrorAction SilentlyContinue)) {
    $env:JAVA_HOME = $null
    $possibleJdks = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\JetBrains\*\jbr",
        "$env:LOCALAPPDATA\Programs\*\jbr",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\*\*\*\jbr",
        "C:\Program Files\Eclipse Adoptium\jdk*",
        "C:\Program Files\Microsoft\jdk*",
        "C:\Program Files\Java\jdk*",
        "$env:USERPROFILE\.jdks\*"
    )
    foreach ($pattern in $possibleJdks) {
        $candidates = Resolve-Path $pattern -ErrorAction SilentlyContinue | Sort-Object Path -Descending
        foreach ($candidate in $candidates) {
            if (Test-Path (Join-Path $candidate.Path "bin\java.exe") -ErrorAction SilentlyContinue) {
                $env:JAVA_HOME = $candidate.Path
                break
            }
        }
        if ($env:JAVA_HOME) { break }
    }

    if (-not $env:JAVA_HOME) {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd -and $javaCmd.Source) {
            $binDir = Split-Path -Parent $javaCmd.Source
            $env:JAVA_HOME = Split-Path -Parent $binDir
        }
    }
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe") -ErrorAction SilentlyContinue)) {
    Write-Error "Could not locate a valid JDK installation. Please install JDK 17+ or set JAVA_HOME."
    exit 1
}

Write-Host "Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan

# 2. Locate ADB
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath -ErrorAction SilentlyContinue)) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $adbPath = $adbCmd.Source }
}

# 3. Build Debug APK
Write-Host "`n=== Building Avaruusmulkko APK ===" -ForegroundColor Green
& ".\gradlew.bat" assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed with code $LASTEXITCODE"
    exit $LASTEXITCODE
}

$apkPath = "$scriptDir\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "APK not found at $apkPath"
    exit 1
}

Write-Host "`nBuild successful! APK created at:" -ForegroundColor Green
Write-Host "  $apkPath" -ForegroundColor Yellow

# 4. Check for connected Pixel 9 Pro XL
if ($adbPath -and (Test-Path $adbPath)) {
    Write-Host "`n=== Checking ADB Device Connection ===" -ForegroundColor Cyan
    $devices = & $adbPath devices
    $deviceList = $devices | Where-Object { $_ -match "\tdevice$" }

    if ($deviceList) {
        Write-Host "Target device detected! Installing APK..." -ForegroundColor Green
        & $adbPath install -r $apkPath
        if ($LASTEXITCODE -ne 0) {
            Write-Error "ADB install failed with code $LASTEXITCODE"
            exit $LASTEXITCODE
        }
        Write-Host "Launching Avaruusmulkko..." -ForegroundColor Green
        & $adbPath shell am start -n "com.avaruus.astro.debug/com.avaruus.astro.MainActivity"
        Write-Host "Ready for astrophotography!" -ForegroundColor Green
    } else {
        Write-Host "`nNo phone detected via ADB." -ForegroundColor Yellow
        Write-Host "To install directly to your Pixel 9 Pro XL:" -ForegroundColor Yellow
        Write-Host "  1. On your phone: Settings -> About Phone -> Tap 'Build number' 7 times to enable Developer options."
        Write-Host "  2. Go to Settings -> System -> Developer options -> Enable 'USB debugging'."
        Write-Host "  3. Connect phone via USB cable to PC and allow USB debugging when prompted."
        Write-Host "  4. Re-run this script: .\build_and_install.ps1"
        Write-Host "Or manually copy $apkPath to your phone and tap to install."
    }
} else {
    Write-Host "ADB not found. You can copy $apkPath directly to your Pixel 9 Pro XL." -ForegroundColor Yellow
}
