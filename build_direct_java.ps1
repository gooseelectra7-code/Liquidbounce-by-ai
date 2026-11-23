# LiquidBounce Build Script
# Uses PowerShell to execute Gradle build with direct Java invocation

$ErrorActionPreference = "Stop"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleDist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location $ProjectDir

Write-Host "============================================"
Write-Host "LiquidBounce Build"
Write-Host "============================================"
Write-Host "Java: $JavaExe"
Write-Host "Gradle dist: $GradleDist"
Write-Host ""

# Verify paths
if (-not (Test-Path $JavaExe)) {
    Write-Error "Java not found at $JavaExe"
    exit 1
}

if (-not (Test-Path $GradleDist)) {
    Write-Error "Gradle distribution not found at $GradleDist"
    Write-Host "Checking for Gradle distributions..."
    Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Directory -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name)" }
    exit 1
}

# Build classpath from all jar files in lib/plus wrapper jar
$ClassPath = @(
    "$GradleDist\lib\*"
    "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"
    "."
) -join ";"

Write-Host "Running Gradle build..."
Write-Host ""

& $JavaExe -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp $ClassPath org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

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