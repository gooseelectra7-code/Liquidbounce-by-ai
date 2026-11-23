#!/bin/bash
cd "/c/Users/SYLVIA/Desktop/LBnew-main"
ls -la .git/
echo "---"
git status 2>&1 || echo "git status failed"
echo "---"
git config user.email "cavestoru-jpg@example.com" 2>&1
git config user.name "cavestoru-jpg" 2>&1
echo "---"
git add -A 2>&1
echo "---"
git commit -m "feat: push full project for CI build" 2>&1
echo "---"
git push -u origin main 2>&1