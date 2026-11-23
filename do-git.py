#!/usr/bin/env python3
"""
Script to initialize git repo and push to GitHub for CI trigger
"""

import subprocess
import os
import sys

repo_root = r"C:\Users\SYLVIA\Desktop\LBnew-main"
git_exe = r"C:\Program Files\Git\bin\git.exe"

def run_git(args, cwd=repo_root):
    """Run git command and return result"""
    cmd = [git_exe] + args
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
    print(f"stdout: {result.stdout}")
    if result.stderr:
        print(f"stderr: {result.stderr}")
    print(f"returncode: {result.returncode}")
    return result

# Step 1: Check if .git/objects exists
print("=== Checking .git directory ===")
objects_dir = os.path.join(repo_root, ".git", "objects")
print(f"Objects dir exists: {os.path.exists(objects_dir)}")

# List what's in .git directory
git_dir = os.path.join(repo_root, ".git")
if os.path.exists(git_dir):
    print("Contents of .git:")
    for item in os.listdir(git_dir):
        item_path = os.path.join(git_dir, item)
        if os.path.isdir(item_path):
            print(f"  {item}/ (dir)")
            try:
                for subitem in os.listdir(item_path):
                    print(f"    {subitem}")
            except:
                pass
        else:
            print(f"  {item}")
else:
    print(".git directory does not exist!")

# Step 2: Configure git user
print("\n=== Configuring git user ===")
run_git(["config", "user.email", "cavestoru-jpg@example.com"])
run_git(["config", "user.name", "cavestoru-jpg"])

# Step 3: Check git status
print("\n=== Checking git status ===")
result = run_git(["status", "--porcelain"])
if result.stdout.strip():
    print("Uncommitted changes found")
else:
    print("No uncommitted changes")

# Step 4: Add all files
print("\n=== Adding all files ===")
run_git(["add", "-A"])

# Step 5: Check if there's anything to commit
print("\n=== Checking staged files ===")
result = run_git(["diff", "--cached", "--name-only"])
if result.stdout.strip():
    print("Staged files:")
    for f in result.stdout.strip().split('\n'):
        if f:
            print(f"  {f}")
    
    # Step 6: Commit
    print("\n=== Committing ===")
    run_git(["commit", "-m", "feat: add full project files for CI build"])
else:
    print("No staged changes")

# Step 7: Push
print("\n=== Pushing to remote ===")
run_git(["push", "-u", "origin", "main"])

print("\n=== Done ===")