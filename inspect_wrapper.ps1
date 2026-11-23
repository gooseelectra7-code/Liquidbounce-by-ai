# Inspect the gradle-wrapper.jar to understand its structure

$WrapperJar = "C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar"

Write-Host "Inspecting gradle-wrapper.jar..."
Write-Host ""

# Use jar command via PowerShell
$JavaHome = "C:\Program Files\Java\jdk-25.0.4"
$JarTool = "$JavaHome\bin\jar.exe"

if (Test-Path $JarTool) {
    Write-Host "Jar contents:"
    & $JarTool tf $WrapperJar 2>&1 | Select-Object -First 50
} else {
    Write-Host "Jar tool not found at $JarTool"
}

# Also try using PowerShell to read it as a zip
Write-Host ""
Write-Host "Using PowerShell to list jar entries:"
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($WrapperJar)
    $zip.Entries | Select-Object -First 50 FullName | ForEach-Object { Write-Host $_ }
    $zip.Dispose()
} catch {
    Write-Host "Error: $_"
}