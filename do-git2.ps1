$git = "C:\Program Files\Git\bin\git.exe"
$repo = "C:\Users\SYLVIA\Desktop\LBnew-main"

Push-Location $repo

# Configure
& $git config user.email "cavestoru-jpg@example.com" >= $null
& $git config user.name "cavestoru-jpg" >= $null

# Add all
& $git add -A >= $null

# Check if there's anything to commit
$staged = & $git diff --cached --name-only
if ($staged) {
    Write-Host "Files to commit:"
    $staged | ForEach-Object { Write-Host "  $_" }
    
    # Commit
    & $git commit -m "feat: add full project files for CI build"
    
    # Push
    & $git push -u origin main
} else {
    Write-Host "No staged changes"
    
    # Still try to push in case we need to
    & $git push -u origin main
}

Pop-Location