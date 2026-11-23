# Debug script to understand Gradle wrapper invocation

$ErrorActionPreference = "Continue"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$GradleDist = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"

Set-Location $ProjectDir

Write-Host "============================================"
Write-Host "LiquidBounce Gradle Debug"
Write-Host "============================================"

# Test 1: Java version
Write-Host ""
Write-Host "[TEST 1] Java version:"
& $JavaExe -version 2>&1
Write-Host "Exit code: $LASTEXITCODE"

# Test 2: Check Gradle wrapper jar
Write-Host ""
Write-Host "[TEST 2] Gradle wrapper jar:"
if (Test-Path "gradle\wrapper\gradle-wrapper.jar") {
    Write-Host "gradle-wrapper.jar found"
    $wrapperSize = (Get-Item "gradle\wrapper\gradle-wrapper.jar").Length
    Write-Host "Size: $wrapperSize bytes"
} else {
    Write-Host "gradle-wrapper.jar NOT FOUND!"
}

# Test 3: Check Gradle dist lib directory
Write-Host ""
Write-Host "[TEST 3] Gradle dist lib directory:"
if (Test-Path "$GradleDist\lib") {
    Write-Host "Lib directory found"
    $jarCount = (Get-ChildItem "$GradleDist\lib\*.jar").Count
    Write-Host "Number of JARs in lib: $jarCount"
} else {
    Write-Host "Lib directory NOT FOUND!"
}

# Test 4: Check gradle-wrapper-main jar
Write-Host ""
Write-Host "[TEST 4] Gradle wrapper main jar:"
$wrapperMainJar = Get-ChildItem "$GradleDist\lib\plugins\gradle-wrapper-main*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($wrapperMainJar) {
    Write-Host "Found gradle-wrapper-main jar: $($wrapperMainJar.Name)"
} else {
    Write-Host "gradle-wrapper-main-9.7.1.jar NOT FOUND!"
    Write-Host "Searching for any wrapper-main jar..."
    Get-ChildItem "$GradleDist\lib\plugins\gradle-wrapper-main*.jar" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name)" }
}

# Test 5: Try invoking GradleWrapperMain
Write-Host ""
Write-Host "[TEST 5] Invoking GradleWrapperMain --version:"

if ($wrapperMainJar) {
    $CP = "$GradleDist\lib\*;$wrapperMainJar.FullName;."
    Write-Host "Classpath: $CP"
    
    Write-Host ""
    Write-Host "Running GradleWrapperMain --version..."
    & $JavaExe -Xms512m -Xmx2048m -cp $CP org.gradle.wrapper.GradleWrapperMain --version 2>&1
    Write-Host "Exit code: $LASTEXITCODE"
} else {
    Write-Host "Cannot test: gradle-wrapper-main jar not found"
}

Write-Host ""
Write-Host "============================================"
Write-Host "Debug completed"
Write-Host "============================================"