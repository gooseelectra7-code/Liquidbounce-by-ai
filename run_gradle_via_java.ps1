# PowerShell script to run Gradle build directly via Java
$ErrorActionPreference = "Stop"

$JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4"
$PROJECT_DIR = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GRADLE_DIST = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$GRADLE_WRAPPER_JAR = "$PROJECT_DIR\gradle\wrapper\gradle-wrapper.jar"

Set-Location $PROJECT_DIR

Write-Host "============================================"
Write-Host "LiquidBounce Build - Direct Java Invocation"
Write-Host "============================================"
Write-Host "JAVA_HOME: $JAVA_HOME"
Write-Host "Gradle dist: $GRADLE_DIST"
Write-Host ""

# Check Java exists
if (-not (Test-Path "$JAVA_HOME\bin\java.exe")) {
    Write-Host "ERROR: Java executable not found"
    exit 1
}

# Build classpath using wildcard
$classpath = "$GRADLE_DIST\lib\*;$GRADLE_WRAPPER_JAR;."

Write-Host "Running GradleMain..."
Write-Host ""

# Run Gradle
& "$JAVA_HOME\bin\java.exe" `
    -Xms1024m -Xmx6144m -XX:+UseZGC `
    -Dorg.gradle.appname=gradlew `
    -cp $classpath `
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

$exitCode = $LASTEXITCODE

Write-Host ""
Write-Host "============================================"
Write-Host "Build exit code: $exitCode"
Write-Host "============================================"

# Check results
if (Test-Path "build\libs\LBnew-0.38.1.jar") {
    Write-Host "SUCCESS: build\libs\LBnew-0.38.1.jar created"
    Write-Host ""
    Write-Host "Checking zip files:"
    if (Test-Path "build\libs\zip\") {
        Get-ChildItem -Path "build\libs\zip\" -Name
    }
} else {
    Write-Host "BUILD FAILED: JAR not found"
    if (Test-Path "build\") {
        Get-ChildItem -Path "build\"
    }
}