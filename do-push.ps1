$ErrorActionPreference = "Stop"
$git = "C:\Program Files\Git\bin\git.exe"
$repo = "C:\Users\SYLVIA\Desktop\LBnew-main"

Write-Host "=== Git Push for CI ==="

# Step 1: Initialize git
Write-Host "Initializing git..."
& $git init
if ($LASTEXITCODE -ne 0) { throw "git init failed" }

# Step 2: Configure user
Write-Host "Configuring user..."
& $git config user.email "cavestoru-jpg@example.com"
& $git config user.name "cavestoru-jpg"
if ($LASTEXITCODE -ne 0) { throw "git config failed" }

# Step 3: Add remote
Write-Host "Adding remote..."
& $git remote add origin "https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git" 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "Remote may already exist" }

# Step 4: Add all files
Write-Host "Adding all files..."
& $git add -A
if ($LASTEXITCODE -ne 0) { throw "git add failed" }

# Step 5: Check staged
Write-Host "Checking staged files..."
$staged = & $git diff --cached --name-only
Write-Host "Staged files: $($staged.Count)"

# Step 6: Commit
Write-Host "Committing..."
& $git commit -m "feat: add full project files for CI build"
if ($LASTEXITCODE -ne 0) { throw "git commit failed" }

# Step 7: Push
Write-Host "Pushing to origin main..."
& $git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }

Write-Host "=== SUCCESS ==="