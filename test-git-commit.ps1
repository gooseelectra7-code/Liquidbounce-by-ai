# PowerShell script to test git operations

$git = "C:\Program Files\Git\bin\git.exe"
$repo = "C:\Users\SYLVIA\Desktop\LBnew-main"

# Test if git works
Write-Host "Testing git..."
Push-Location $repo

$status = & $git status 2>&1
Write-Host "Status: $status"

$version = & $git --version 2>&1
Write-Host "Version: $version"

Pop-Location