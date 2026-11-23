#!/bin/sh
# Fix broken git repo by reinitializing

cd /c/Users/SYLVIA/Desktop/LBnew-main

# Backup existing config
if [ -f .git/config ]; then
    cat .git/config > /tmp/git-config-backup.txt
    echo "Backed up config"
fi

# Remove broken .git
rm -rf .git
echo "Removed broken .git"

# Reinitialize
git init
echo "Initialized new git repo"

# Restore config
if [ -f /tmp/git-config-backup.txt ]; then
    cat /tmp/git-config-backup.txt > .git/config
    echo "Restored config"
fi

# Configure user
git config user.email "cavestoru-jpg@example.com"
git config user.name "cavestoru-jpg"

# Update remote URL (in case it changed)
git remote set-url origin https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git 2>/dev/null || \
git remote add origin https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git

# Add all files
git add -A

# Show staged file count
STAGED_COUNT=$(git diff --cached --name-only | wc -l)
echo "Staged files: $STAGED_COUNT"

# Commit
git commit -m "feat: add full project files for CI build"
echo "Committed"

# Push
git push -u origin main
echo "Pushed"