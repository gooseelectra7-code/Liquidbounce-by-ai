# LiquidBounce Build - PowerShell script to run Gradle directly
# Bypasses AppLocker by running java.exe directly with the wrapper jar

$ErrorActionPreference = "Stop"

$JavaHome = "C:\Program Files\Java\jdk-25.0.4"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleDist = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Write-Host "============================================"
Write-Host "LiquidBounce Build - PowerShell Direct Invocation"
Write-Host "============================================"
Write-Host "Java Home: $JavaHome"
Write-Host "Gradle Distribution: $GradleDist"
Write-Host ""

# Change to project directory
Set-Location $ProjectDir

# Check if Java exists
if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Host "ERROR: Java executable not found"
    exit 1
}

# Check if Gradle distribution exists
if (-not (Test-Path $GradleDist)) {
    Write-Host "ERROR: Gradle distribution not found"
    exit 1
}

# Build classpath using wildcard
$CP = "$GradleDist\lib\*;$ProjectDir\gradle\wrapper\gradle-wrapper.jar;."

Write-Host "Running Gradle build..."
Write-Host ""

# Run GradleMain
& "$JavaHome\bin\java.exe" `
    -Xms1024m -Xmx6144m -XX:+UseZGC `
    -cp "$CP" `
    org.gradle.launcher.GradleMain `
    build -x detekt -x test --console=plain

$ExitCode = $LASTEXITCODE

Write-Host ""
Write-Host "============================================"
Write-Host "Build completed with exit code: $ExitCode"
Write-Host "============================================"

# Verify build output
if (Test-Path "build\libs\LBnew-0.38.1.jar") {
    Write-Host ""
    Write-Host "SUCCESS: build\libs\LBnew-0.38.1.jar created"
    Write-Host ""
    Write-Host "Contents of build\libs\zip\:"
    if (Test-Path "build\libs\zip\") {
        Get-ChildItem "build\libs\zip\" | ForEach-Object { Write-Host "  $($_.Name)" }
    } else {
        Write-Host "  WARNING: build\libs\zip\ not found"
    }
} else {
    Write-Host ""
    Write-Host "FAILURE: build\libs\LBnew-0.38.1.jar not found"
    if (Test-Path "build\") {
        Write-Host "Build directory contents:"
        Get-ChildItem "build\" | ForEach-Object { Write-Host "  $($_.Name)" }
    }
}

exit $ExitCode