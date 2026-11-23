# Debug: Check all Gradle-related files and locations

Write-Host "=== Checking Gradle Setup ==="
Write-Host ""

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
Write-Host "Java:"
if (Test-Path $JavaExe) {
    & $JavaExe -version 2>&1
} else {
    Write-Host "  NOT FOUND at $JavaExe"
}

Write-Host ""
Write-Host "Project dir:"
Get-ChildItem "C:\Users\SYLVIA\Desktop\LBnew-main" -Directory | Select-Object -First 10 | ForEach-Object { Write-Host "  $($_.Name)" }

Write-Host ""
Write-Host "Gradle wrapper jar:"
Get-Item "C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar" | Format-List

Write-Host ""
Write-Host "Gradle distribution:"
$GradleDist = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
if (Test-Path $GradleDist) {
    $JarFiles = Get-ChildItem "$GradleDist\lib" -Filter "gradle-wrapper*.jar" -ErrorAction SilentlyContinue
    Write-Host "Wrapper JARs in distribution:"
    $JarFiles | ForEach-Object { Write-Host "  $($_.FullName)" }
} else {
    Write-Host "  NOT FOUND"
}

Write-Host ""
Write-Host "Testing gradle-wrapper.jar with -jar:"
$GradleJar = "C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar"
& $JavaExe -jar $GradleJar --version 2>&1
$ExitCode = $LASTEXITCODE
Write-Host "Exit code: $ExitCode"