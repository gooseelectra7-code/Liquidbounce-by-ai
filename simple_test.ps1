# Simple test - just check Java works
$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"

Write-Host "Testing Java..."
$Result = & $JavaExe -version 2>&1
$Result | ForEach-Object { Write-Host $_ }

Write-Host "Java test complete."