# Simple Gradle run - just invoke java with -jar
$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"

Set-Location $ProjectDir

Write-Host "Running Gradle build..."
Write-Host ""

# This is exactly what gradlew does on line 213-214
& $JavaExe -Dorg.gradle.appname=gradlew -jar "gradle\wrapper\gradle-wrapper.jar" build -x detekt -x test --console=plain

Write-Host ""
Write-Host "Exit code: $LASTEXITCODE"

Write-Host ""
Write-Host "Checking build output..."
if (Test-Path "build\libs") {
    Get-ChildItem "build\libs" -Recurse | ForEach-Object { Write-Host "  $($_.FullName)" }
} else {
    Write-Host "No build\libs directory"
}