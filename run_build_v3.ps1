$ErrorActionPreference = "Stop"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleWrapperJar = "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"
$GradleDist = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location -Path $ProjectDir

# Verify files exist
if (-not (Test-Path $JavaExe)) {
    Write-Host "ERROR: Java not found at $JavaExe"
    exit 1
}
if (-not (Test-Path $GradleDist)) {
    Write-Host "ERROR: Gradle distribution not found at $GradleDist"
    exit 1
}

Write-Host "Running Gradle build with classpath mode..."
Write-Host ""

# Build classpath - use wildcard in classpath
$Classpath = "$GradleDist\lib\*;$GradleDist\lib\plugins\gradle-wrapper-main-9.7.1.jar;gradle\wrapper\gradle-wrapper.jar;."

# Run using GradleWrapperMain class directly
& $JavaExe -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp "$Classpath" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

$ExitCode = $LASTEXITCODE
Write-Host ""
Write-Host "Exit code: $ExitCode"

# Check for JAR files
$JarFiles = Get-ChildItem -Path "$ProjectDir\build\libs" -Filter "*.jar" -File -ErrorAction SilentlyContinue
if ($JarFiles) {
    Write-Host "SUCCESS: JARs created:"
    $JarFiles | ForEach-Object { Write-Host "  $($_.Name)" }
} else {
    Write-Host "JAR not found in build\libs"
    Write-Host "Contents of build\libs:"
    Get-ChildItem -Path "$ProjectDir\build\libs" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
}

exit $ExitCode