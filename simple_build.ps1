# Simple PowerShell script to run Gradle build for LiquidBounce
param(
    [switch]$NoVerbose = $false
)

$ErrorActionPreference = "Stop"

# Configuration
$JAVA_EXE = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$PROJECT_DIR = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GRADLE_DIST = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location $PROJECT_DIR

Write-Host "=== LiquidBounce Build ===" -ForegroundColor Cyan
Write-Host "Project: $PROJECT_DIR"
Write-Host "Java: $JAVA_EXE"
Write-Host "Gradle: $GRADLE_DIST"
Write-Host ""

# Check Java exists
if (-not (Test-Path $JAVA_EXE)) {
    Write-Host "ERROR: Java not found at $JAVA_EXE" -ForegroundColor Red
    exit 1
}

# Check gradle distribution
if (-not (Test-Path $GRADLE_DIST)) {
    Write-Host "ERROR: Gradle distribution not found at $GRADLE_DIST" -ForegroundColor Red
    exit 1
}

# Check gradle wrapper jar
$WRAPPER_JAR = "$PROJECT_DIR\gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $WRAPPER_JAR)) {
    Write-Host "ERROR: Gradle wrapper jar not found at $WRAPPER_JAR" -ForegroundColor Red
    exit 1
}

# Run Gradle via Java
$CP = "$GRADLE_DIST\lib\*;$GRADLE_DIST\lib\plugins\gradle-wrapper-main-9.7.1.jar;$WRAPPER_JAR"

Write-Host "Running Gradle build (excluding detekt and test)..." -ForegroundColor Yellow
Write-Host ""

& $JAVA_EXE `
    -Xms1024m -Xmx6144m -XX:+UseZGC `
    -cp $CP `
    org.gradle.wrapper.GradleWrapperMain `
    build -x detekt -x test --console=plain

$exitCode = $LASTEXITCODE

Write-Host ""
Write-Host "=== Build completed ===" -ForegroundColor Cyan
Write-Host "Exit code: $exitCode"

# Check for JAR
$jarPath = "$PROJECT_DIR\build\libs\LBnew-0.38.1.jar"
if (Test-Path $jarPath) {
    Write-Host "SUCCESS: JAR file created: $jarPath" -ForegroundColor Green
    $jarInfo = Get-Item $jarPath
    Write-Host "  Size: $($jarInfo.Length) bytes"
} else {
    Write-Host "FAILURE: JAR not found at $jarPath" -ForegroundColor Red
    if (Test-Path "$PROJECT_DIR\build\libs") {
        Write-Host "Contents of build\libs:"
        Get-ChildItem "$PROJECT_DIR\build\libs" -Recurse | Write-Host
    }
}

exit $exitCode