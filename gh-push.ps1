# PowerShell script to push git changes using gh CLI and git
# Git configuration is already set in .git/config

$repoRoot = "C:\Users\SYLVIA\Desktop\LBnew-main"
$gitExe = "C:\Program Files\Git\bin\git.exe"
$ghExe = "C:\Program Files\GitHub CLI\gh.exe"

Set-Location $repoRoot

# Check current status
Write-Host "Checking git status..."
& $gitExe status

# Check if we're on main branch
$currentBranch = & $gitExe rev-parse --abbrev-ref HEAD
Write-Host "Current branch: $currentBranch"

# Configure user (in case not set)
Write-Host "Configuring git user..."
& $gitExe config user.email "cavestoru-jpg@example.com"
& $gitExe config user.name "cavestoru-jpg"

# Add all files
Write-Host "Adding all files..."
& $gitExe add -A

# Check what's staged
$staged = & $gitExe diff --cached --name-only
if ($staged) {
    Write-Host "Files to commit: $staged"
} else {
    Write-Host "No files to commit - repository might already be up to date"
}

# Check if we need to commit
$hasUncommitted = & $gitExe status --porcelain
if ($hasUncommitted) {
    Write-Host "Committing changes..."
    & $gitExe commit -m "feat: add full project files for CI build"
}

# Push to origin
Write-Host "Pushing to origin main..."
& $gitExe push -u origin main