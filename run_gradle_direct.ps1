# Direct Gradle invocation using java -jar
$ErrorActionPreference = "Stop"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"

Set-Location $ProjectDir

# Test Java
Write-Host "=== Java Version ==="
try {
    $StdErr = & $JavaExe -version 2>&1
    $StdErr | ForEach-Object { Write-Host $_ }
} catch {
    Write-Host "ERROR: Java execution failed - $_"
    exit 1
}

Write-Host ""
Write-Host "=== Running Gradle Build ==="
Write-Host "Command: java -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain"

$Process = Start-Process -FilePath $JavaExe `
    -ArgumentList "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC", "-jar", "gradle\wrapper\gradle-wrapper.jar", `
                   "build", "-x", "detekt", "-x", "test", "--console=plain" `
    -WorkingDirectory $ProjectDir `
    -NoNewWindow `
    -RedirectStandardOutput "C:\Users\SYLVIA\Desktop\LBnew-main\gradle_stdout.txt" `
    -RedirectStandardError "C:\Users\SYLVIA\Desktop\LBnew-main\gradle_stderr.txt" `
    -PassThru `
    -Wait

Write-Host "Process exit code: $($Process.ExitCode)"

Write-Host ""
Write-Host "=== Gradle Stdout (last 100 lines) ==="
Get-Content "C:\Users\SYLVIA\Desktop\LBnew-main\gradle_stdout.txt" -Tail 100

Write-Host ""
Write-Host "=== Gradle Stderr (last 100 lines) ==="
Get-Content "C:\Users\SYLVIA\Desktop\LBnew-main\gradle_stderr.txt" -Tail 100

Write-Host ""
Write-Host "=== Checking build output ==="
Get-ChildItem -Path "$ProjectDir\build\libs" -File -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name)" }

Write-Host ""
Write-Host "=== Done ==="