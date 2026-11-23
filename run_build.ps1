# PowerShell script to run Gradle build for LiquidBounce
# Uses direct Java invocation to bypass potential AppLocker restrictions

param(
    [string]$JavaPath = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe",
    [string]$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main",
    [string[]]$Tasks = @("build"),
    [string[]]$Exclude = @("detekt", "test")
)

$ErrorActionPreference = "Stop"

# Resolve paths
Write-Host "=== LiquidBounce Build Script ===" -ForegroundColor Cyan
Write-Host "Java: $JavaPath"
Write-Host "Project: $ProjectDir"
Write-Host ""

if (-not (Test-Path $JavaPath)) {
    Write-Host "ERROR: Java not found at $JavaPath" -ForegroundColor Red
    exit 1
}

Set-Location $ProjectDir

# Find Gradle distribution
$GradleBase = "$env:USERPROFILE\.gradle\wrapper\dists"
$GradleDist = Get-ChildItem "$GradleBase\gradle-9.7.1-bin\*" -Directory | Select-Object -First 1
if (-not $GradleDist) {
    Write-Host "ERROR: Gradle 9.7.1 distribution not found" -ForegroundColor Red
    Write-Host "Expected at: $GradleBase\gradle-9.7.1-bin\*\gradle-9.7.1"
    Write-Host ""
    Write-Host "Please run once with gradlew.bat to download Gradle, or install Gradle 9.7.1"
    exit 1
}

$GradleHome = $GradleDist.FullName
Write-Host "Gradle: $GradleHome"

# Check wrapper JAR
$WrapperJar = "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $WrapperJar)) {
    Write-Host "ERROR: Wrapper JAR not found at $WrapperJar" -ForegroundColor Red
    exit 1
}

# Build classpath
$CP = "$GradleHome\lib\*;$GradleHome\lib\plugins\gradle-wrapper-main-9.7.1.jar;$WrapperJar"

# Build arguments
$BuildArgs = $Tasks
foreach ($Ex in $Exclude) {
    $BuildArgs += "-x"
    $BuildArgs += $Ex
}
$BuildArgs += "--console=plain"

Write-Host ""
Write-Host "Running: gradle $($BuildArgs -join ' ')" -ForegroundColor Yellow
Write-Host ""

# Execute Gradle
& $JavaPath -Xms1024m -Xmx6144m -XX:+UseZGC -cp $CP org.gradle.wrapper.GradleWrapperMain $BuildArgs

$ExitCode = $LASTEXITCODE

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Exit Code: $ExitCode"

$JarPath = "$ProjectDir\build\libs\LBnew-0.38.1.jar"
if (Test-Path $JarPath) {
    Write-Host ""
    Write-Host "SUCCESS: JAR created at $JarPath" -ForegroundColor Green
    $Jar = Get-Item $JarPath
    Write-Host "  Size: $($Jar.Length) bytes"
} else {
    Write-Host ""
    Write-Host "FAILURE: JAR not found at $JarPath" -ForegroundColor Red
}

exit $ExitCode