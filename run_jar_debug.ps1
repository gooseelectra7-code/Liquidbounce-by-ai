# Debug script to test different Gradle invocation methods

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleJar = "gradle\wrapper\gradle-wrapper.jar"
$BuildOutputDir = "$ProjectDir\output_build"
$BuildErrorDir = "$ProjectDir\error_build"

# Create output directories
New-Item -ItemType Directory -Force -Path $BuildOutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $BuildErrorDir | Out-Null

Write-Host "========================================"
Write-Host "Testing: java -jar gradle-wrapper.jar build"
Write-Host "========================================"

Set-Location -Path $ProjectDir

try {
    # Run with -jar flag
    $Process = Start-Process -FilePath $JavaExe `
        -ArgumentList "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC", "-jar", $GradleJar, "build", "-x", "detekt", "-x", "test", "--console=plain" `
        -NoNewWindow `
        -RedirectStandardOutput "$BuildOutputDir\stdout.txt" `
        -RedirectStandardError "$BuildErrorDir\stderr.txt" `
        -PassThru `
        -Wait
    
    $ExitCode = $Process.ExitCode
    Write-Host "Exit code: $ExitCode"
    
    Write-Host "`n--- STDOUT (last 50 lines) ---"
    Get-Content "$BuildOutputDir\stdout.txt" | Select-Object -Last 50
    
    if (Test-Path "$BuildErrorDir\stderr.txt") {
        Write-Host "`n--- STDERR (last 50 lines) ---"
        Get-Content "$BuildErrorDir\stderr.txt" | Select-Object -Last 50
    }
    
    # Check for JAR
    $JarFiles = Get-ChildItem -Path "$ProjectDir\build\libs" -Filter "*.jar" -File -ErrorAction SilentlyContinue
    if ($JarFiles) {
        Write-Host "`nSUCCESS: JARs created:"
        $JarFiles | ForEach-Object { Write-Host "  $($_.FullName)" }
    } else {
        Write-Host "`nNo JARs found in build\libs"
    }
    
} catch {
    Write-Host "ERROR: $_"
}

Write-Host "`nDone."