$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$OutFile = "C:\Users\SYLVIA\Desktop\LBnew-main\java_check.txt"

Write-Host "Running Java version check..."
Start-Process -FilePath $JavaExe -ArgumentList "-version" -RedirectStandardOutput $OutFile -RedirectStandardError $OutFile -Wait
Write-Host "Done. Output:"
Get-Content $OutFile