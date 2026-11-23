# LiquidBounce Build Script - Direct Java Invocation
# Bypasses gradle-wrapper.jar issues on Windows with AppLocker
# Uses Gradle 9.7.1 directly with java.exe

param(
    [string]$JavaPath = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe",
    [string]$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
)

$ErrorActionPreference = "Stop"

Set-Location $ProjectDir

Write-Host "============================================"
Write-Host "LiquidBounce Build - Direct Java Invocation"
Write-Host "============================================"
Write-Host "Java: $JavaPath"
Write-Host "Project: $ProjectDir"
Write-Host ""

# Verify Java exists
if (-not (Test-Path $JavaPath)) {
    Write-Error "Java not found at $JavaPath"
    exit 1
}

# Gradle distribution location
$GradleDist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

# Build classpath from Gradle lib jars + wrapper main jar + project wrapper jar + current dir
$ClassPath = @(
    "$GradleDist\lib\*"
    "$GradleDist\lib\plugins\gradle-wrapper-main-9.7.1.jar"
    "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"
    "."
) -join ";"

Write-Host "Running Gradle build..."
Write-Host "Gradle dist: $GradleDist"
Write-Host ""

# Execute Gradle via direct Java invocation
& $JavaPath -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp $ClassPath org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

$ExitCode = $LASTEXITCODE

Write-Host ""
Write-Host "============================================"
Write-Host "Build completed with exit code: $ExitCode"
Write-Host "============================================"

# Check for output
if (Test-Path "build\libs\LBnew-0.38.1.jar") {
    Write-Host "SUCCESS: build\libs\LBnew-0.38.1.jar created"
    Get-Item "build\libs\LBnew-0.38.1.jar" | Format-List FullName, Length
} else {
    Write-Host "WARNING: JAR not found at build\libs\LBnew-0.38.1.jar"
    if (Test-Path "build\libs") {
        Write-Host "Contents of build\libs:"
        Get-ChildItem "build\libs" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
    }
}

exit $ExitCode