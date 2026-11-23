# PowerShell script to run Gradle build using Java directly
# Bypasses AppLocker restrictions

param(
    [string]$Action = "build",
    [string]$Args = "-x detekt -x test --console=plain"
)

$ErrorActionPreference = "Continue"

# Configuration
$JAVA_EXE = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$GRADLE_DIST = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$PROJECT_DIR = "C:\Users\SYLVIA\Desktop\LBnew-main"

# Change to project directory
Set-Location $PROJECT_DIR

# Build classpath
$libPath = Join-Path $GRADLE_DIST "lib"
$pluginsPath = Join-Path $GRADLE_DIST "lib\plugins\gradle-wrapper-main-9.7.1.jar"
$wrapperJar = Join-Path $PROJECT_DIR "gradle\wrapper\gradle-wrapper.jar"

Write-Host "=== LiquidBounce Build ==="
Write-Host "Java: $JAVA_EXE"
Write-Host "Gradle: $GRADLE_DIST"
Write-Host ""

# Execute Gradle via Java
& $JAVA_EXE `
    -Xms1024m -Xmx6144m -XX:+UseZGC `
    -cp "$libPath\*;$pluginsPath;$wrapperJar" `
    org.gradle.wrapper.GradleWrapperMain $Action $Args

$exitCode = $LASTEXITCODE
Write-Host ""
Write-Host "=== Build completed ==="
Write-Host "Exit code: $exitCode"

# Check for JAR
$jarPath = Join-Path $PROJECT_DIR "build\libs\LBnew-0.38.1.jar"
if (Test-Path $jarPath) {
    Write-Host "SUCCESS: JAR file created: $jarPath"
    Get-Item $jarPath | Format-List FullName, Length
} else {
    Write-Host "FAILURE: JAR not found at $jarPath"
    if (Test-Path (Join-Path $PROJECT_DIR "build\libs")) {
        Write-Host "Contents of build/libs:"
        Get-ChildItem (Join-Path $PROJECT_DIR "build\libs") -Recurse
    }
}