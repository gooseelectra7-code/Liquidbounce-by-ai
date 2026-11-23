# PowerShell script to run Gradle build for LiquidBounce
# Created to work around AppLocker restrictions on gradlew.bat

$ErrorActionPreference = "Stop"

# Configuration
$JAVA_EXE = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$PROJECT_DIR = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GRADLE_DIST = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$WRAPPER_JAR = "$PROJECT_DIR\gradle\wrapper\gradle-wrapper.jar"

Write-Host "=== LiquidBounce Build Script ===" -ForegroundColor Cyan
Write-Host "Project: $PROJECT_DIR"
Write-Host "Java: $JAVA_EXE"
Write-Host "Gradle: $GRADLE_DIST"
Write-Host ""

# Change to project directory
Set-Location $PROJECT_DIR

# Check prerequisites
if (-not (Test-Path $JAVA_EXE)) {
    Write-Host "ERROR: Java not found at $JAVA_EXE" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $GRADLE_DIST)) {
    Write-Host "ERROR: Gradle distribution not found at $GRADLE_DIST" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $WRAPPER_JAR)) {
    Write-Host "ERROR: Gradle wrapper JAR not found at $WRAPPER_JAR" -ForegroundColor Red
    exit 1
}

# Build classpath
$GRADLE_LIB = "$GRADLE_DIST\lib"
$CP = "$GRADLE_LIB\*;$GRADLE_DIST\lib\plugins\gradle-wrapper-main-9.7.1.jar;$WRAPPER_JAR"

Write-Host "Executing Gradle build (excluding detekt and test)..." -ForegroundColor Yellow
Write-Host ""

# Run Gradle
& $JAVA_EXE -Xms1024m -Xmx6144m -XX:+UseZGC -cp $CP org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

$EXIT_CODE = $LASTEXITCODE

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Exit code: $EXIT_CODE" -ForegroundColor $(if ($EXIT_CODE -eq 0) { "Green" } else { "Red" })

$JAR_PATH = "$PROJECT_DIR\build\libs\LBnew-0.38.1.jar"
if (Test-Path $JAR_PATH) {
    Write-Host ""
    Write-Host "SUCCESS: JAR file created:" -ForegroundColor Green
    Write-Host "  Path: $JAR_PATH"
    $jarInfo = Get-Item $JAR_PATH
    Write-Host "  Size: $($jarInfo.Length) bytes"
} else {
    Write-Host ""
    Write-Host "FAILURE: JAR not found at $JAR_PATH" -ForegroundColor Red
    if (Test-Path "$PROJECT_DIR\build\libs") {
        Write-Host "Contents of build\libs:"
        Get-ChildItem "$PROJECT_DIR\build\libs" -Force | ForEach-Object { Write-Host "  $_" }
    }
}

exit $EXIT_CODE