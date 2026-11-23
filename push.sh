#!/bin/bash
# Git push script for CI trigger
set -e

cd "C:/Users/SYLVIA/Desktop/LBnew-main"

# Ensure git is configured
git config user.email "cavestoru-jpg@example.com"
git config user.name "cavestoru-jpg"

# Add all files
git add -A

# Check if we have uncommitted changes or need initial commit
if ! git rev-parse HEAD >/dev/null 2>&1; then
    # No commits yet, create initial commit
    git commit -m "feat(crystalaura): add full project files for CI build"
else
    # Check for uncommitted changes
    if ! git diff-index --quiet HEAD --; then
        git commit -m "Update project files"
    fi
fi

# Push to origin main
git push -u origin main