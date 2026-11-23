# Fallback build script - direct Gradle invocation with explicit classpath
# This handles the case where gradle-wrapper.jar doesn't work standalone

param(
    [string]$JavaPath = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe",
    [string]$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main",
    [string]$GradleVersion = "9.7.1",
    [string]$GradleDist = ""
)

$ErrorActionPreference = "Stop"

Set-Location $ProjectDir

Write-Host "============================================"
Write-Host "LiquidBounce Build - Fallback Method"
Write-Host "============================================"

if ([string]::IsNullOrEmpty($GradleDist)) {
    $GradleDist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-$GradleVersion-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-$GradleVersion"
}

Write-Host "Java: $JavaPath"
Write-Host "Gradle dist: $GradleDist"
Write-Host ""

# Verify paths
if (-not (Test-Path $JavaPath)) {
    Write-Error "Java not found at $JavaPath"
    exit 1
}

if (-not (Test-Path $GradleDist)) {
    Write-Error "Gradle distribution not found at $GradleDist"
    Write-Host "Available Gradle distributions:"
    Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Directory | ForEach-Object { Write-Host "  $($_.Name)" }
    exit 1
}

# Find all plugin jars (including gradle-wrapper-main)
$WrapperMainJar = Get-ChildItem "$GradleDist\lib\plugins\gradle-wrapper-main*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $WrapperMainJar) {
    Write-Error "gradle-wrapper-main jar not found in $GradleDist\lib\plugins"
    exit 1
}

Write-Host "Wrapper main jar: $($WrapperMainJar.FullName)"
Write-Host "Wrapper jar: $ProjectDir\gradle\wrapper\gradle-wrapper.jar"
Write-Host ""

# Build classpath - wildcard expansion in PowerShell
$ClassPath = @(
    "$GradleDist\lib\*"
    $WrapperMainJar.FullName
    "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"
    "."
) -join ";"

Write-Host "Classpath components:"
Write-Host "  - $GradleDist\lib\*"
Write-Host "  - $($WrapperMainJar.FullName)"
Write-Host "  - $ProjectDir\gradle\wrapper\gradle-wrapper.jar"
Write-Host "  - ."
Write-Host ""

# Run Gradle
Write-Host "Running Gradle build..."
Write-Host ""

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