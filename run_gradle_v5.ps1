# Direct Gradle invocation without daemon
$ErrorActionPreference = "Stop"

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleDist = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location $ProjectDir

Write-Host "=== Running Gradle Build ==="
Write-Host "Java: $JavaExe"
Write-Host "Gradle distribution: $GradleDist"
Write-Host ""

# Build classpath: all gradle lib jars
$CP = "$GradleDist\lib\*"

# Try the GradleWrapperMain approach
$Process = Start-Process -FilePath $JavaExe `
    -ArgumentList @(`
        "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC",
        "-cp", $CP,
        "org.gradle.wrapper.GradleWrapperMain",
        "build", "-x", "detekt", "-x", "test", "--console=plain"
    ) `
    -WorkingDirectory $ProjectDir `
    -NoNewWindow `
    -RedirectStandardOutput "C:\Users\SYLVIA\Desktop\LBnew-main\output_stdout.txt" `
    -RedirectStandardError "C:\Users\SYLVIA\Desktop\LBnew-main\output_stderr.txt" `
    -PassThru `
    -Wait

Write-Host "Process exit code: $($Process.ExitCode)"

Write-Host ""
Write-Host "=== Stdout (last 50 lines) ==="
Get-Content "C:\Users\SYLVIA\Desktop\LBnew-main\output_stdout.txt" -Tail 50

Write-Host ""
Write-Host "=== Stderr (last 50 lines) ==="
Get-Content "C:\Users\SYLVIA\Desktop\LBnew-main\output_stderr.txt" -Tail 50