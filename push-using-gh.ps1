# Try using gh CLI for git operations
$repoRoot = "C:\Users\SYLVIA\Desktop\LBnew-main"
$gitExe = "C:\Program Files\Git\bin\git.exe"
$ghExe = "C:\Program Files\GitHub CLI\gh.exe"

Set-Location $repoRoot

# Use gh auth to check authentication
Write-Host "Checking gh auth..."
& $ghExe auth status

# Configure git
& $gitExe config user.email "cavestoru-jpg@example.com"
& $gitExe config user.name "cavestoru-jpg"

# Add all files
Write-Host "Adding files..."
& $gitExe add -A

# Check what needs to be committed
$staged = & $gitExe diff --cached --name-only 2>$null
if (-not $staged) {
    Write-Host "No staged changes - repository may be up to date or need initial commit"
    
    # Try to check if there are uncommitted changes
    $status = & $gitExe status --porcelain 2>$null
    if ($status) {
        Write-Host "Uncommitted changes detected, committing..."
        & $gitExe commit -m "feat: add full project files for CI build"
    } else {
        Write-Host "No changes at all"
    }
}

# Push
Write-Host "Pushing to remote..."
& $gitExe push -u origin main