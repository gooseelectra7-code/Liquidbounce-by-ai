#!/usr/bin/env pwsh
# Run git commands for CI trigger

$gitPath = "C:\Program Files\Git\bin\git.exe"
$workDir = "C:\Users\SYLVIA\Desktop\LBnew-main"

# Check if git exists
if (-not (Test-Path $gitPath)) {
    Write-Error "Git not found at $gitPath"
    exit 1
}

# Show current status
Write-Host "=== Current status ==="
& $gitPath -C $workDir status
if ($LASTEXITCODE -ne 0) {
    Write-Host "Git status failed or has issues"
}

# Configure user
Write-Host "=== Configuring git user ==="
& $gitPath -C $workDir config user.email "cavestoru-jpg@example.com"
& $gitPath -C $workDir config user.name "cavestoru-jpg"

# Add all files
Write-Host "=== Adding files ==="
& $gitPath -C $workDir add -A

# Check if there's anything to commit
$statusOutput = & $gitPath -C $workDir status --porcelain
if ($statusOutput) {
    Write-Host "=== Committing ==="
    & $gitPath -C $workDir commit -m "feat: push full project for CI build"
} else {
    Write-Host "No changes to commit"
}

# Push
Write-Host "=== Pushing ==="
& $gitPath -C $workDir push -u origin main