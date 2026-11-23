#!/bin/sh
cd /c/Users/SYLVIA/Desktop/LBnew-main

# Initialize git repo
git init

# Configure
git config user.email "cavestoru-jpg@example.com"
git config user.name "cavestoru-jpg"

# Add remote
git remote add origin https://github.com/gooseelectra7-code/Liquidbounce-by-ai.git

# Add all files
git add -A

# Check what's staged
echo "Staged files:"
git diff --cached --name-only | head -20

# Commit
git commit -m "feat: add full project files for CI build"

# Push
git push -u origin main