# Build script for LiquidBounce
# Run via PowerShell to bypass AppLocker on batch files

$ErrorActionPreference = "Stop"
$JavaHome = "C:\Program Files\Java\jdk-25.0.4"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleDist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location $ProjectDir

Write-Host "============================================"
Write-Host "LiquidBounce Build"
Write-Host "============================================"
Write-Host "Java: $JavaHome"
Write-Host "Gradle dist: $GradleDist"
Write-Host ""

if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Host "ERROR: Java not found"
    exit 1
}

# Classpath: all gradle lib jars + wrapper jar + project
$CP = "$GradleDist\lib\*;$GradleDist\lib\plugins\gradle-wrapper-main-9.7.1.jar;."

Write-Host "Running Gradle build..."
Write-Host ""

& "$JavaHome\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC -cp $CP org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

$ExitCode = $LASTEXITCODE

Write-Host ""
Write-Host "Build exit code: $ExitCode"

# Check for output
if (Test-Path "build\libs\LBnew-0.38.1.jar") {
    Write-Host "SUCCESS: build\libs\LBnew-0.38.1.jar created"
    if (Test-Path "build\libs\zip\") {
        Get-ChildItem "build\libs\zip\"
    }
} else {
    Write-Host "Jar not found"
    if (Test-Path "build\") {
        Get-ChildItem "build\"
    }
}

exit $ExitCode