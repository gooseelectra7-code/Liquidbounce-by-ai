$ErrorActionPreference = "Continue"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"

Set-Location -Path $ProjectDir

Write-Host "Step 1: Checking Java version"
& "$JavaExe" -version 2>&1

Write-Host ""
Write-Host "Step 2: Listing gradle-wrapper.jar"
Get-ChildItem "gradle\wrapper\gradle-wrapper.jar" | Format-List

Write-Host ""
Write-Host "Step 3: Checking Gradle distribution"
$GradleDist = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
if (Test-Path "$GradleDist") {
    Write-Host "Gradle distribution found"
    Get-ChildItem "$GradleDist\bin" -ErrorAction SilentlyContinue | Select-Object -First 5
} else {
    Write-Host "Gradle distribution NOT found at: $GradleDist"
}

Write-Host ""
Write-Host "Step 4: Running Gradle build via -jar"
Write-Host "Command: java -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain"
Start-Process -FilePath $JavaExe -ArgumentList "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC", "-jar", "gradle\wrapper\gradle-wrapper.jar", "build", "-x", "detekt", "-x", "test", "--console=plain" -NoNewWindow -Wait -PassThru -RedirectStandardOutput "build_output.txt" -RedirectStandardError "build_error.txt"

Write-Host ""
Write-Host "Step 5: Build output:"
Get-Content "build_output.txt" | Select-Object -Last 100

if (Test-Path "build_error.txt") {
    Write-Host ""
    Write-Host "Build errors:"
    Get-Content "build_error.txt" | Select-Object -Last 100
}

Write-Host ""
Write-Host "Step 6: Checking for JAR files"
Get-ChildItem -Path "$ProjectDir\build\libs" -File -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "Done."