# Direct Java test
$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"

Write-Host "Java path: $JavaExe"
Write-Host "File exists: $((Test-Path $JavaExe))"

# Use Start-Process to capture output
$OutputFile = "C:\Users\SYLVIA\Desktop\LBnew-main\java_version.txt"
$Process = Start-Process -FilePath $JavaExe -ArgumentList "-version" -RedirectStandardOutput $OutputFile -RedirectStandardError $OutputFile -PassThru -Wait

Write-Host "Process exit code: $($Process.ExitCode)"
Write-Host "Output file content:"
Get-Content $OutputFile 2>&1