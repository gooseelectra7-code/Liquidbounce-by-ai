#!/bin/bash
cd /c/Users/SYLVIA/Desktop/LBnew-main

# Check if .git exists
if [ -d .git ]; then
    echo ".git exists"
    ls -la .git/
else
    echo ".git does not exist"
fi

# Check if git is available
git --version

# Try to reinitialize
git init

# Configure
git config user.email "cavestoru-jpg@example.com"
git config user.name "cavestoru-jpg"

# Add remote
git remote add origin https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git

# Add all files
git add -A

# Check status
git status --porcelain | head -20

# Commit
git commit -m "feat: add full project files for CI build"

# Push
git push -u origin main