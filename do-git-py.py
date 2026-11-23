#!/usr/bin/env python3
"""Git operations for CI trigger"""

import subprocess
import sys
import os

repo = r"C:\Users\SYLVIA\Desktop\LBnew-main"
git_exe = r"C:\Program Files\Git\bin\git.exe"
output_file = os.path.join(repo, "git-output.txt")

def run_git(args):
    cmd = [git_exe] + args
    with open(output_file, 'a') as f:
        f.write(f"\n=== {' '.join(cmd)} ===\n")
    
    result = subprocess.run(cmd, cwd=repo, capture_output=True, text=True, encoding='utf-8', errors='replace')
    
    with open(output_file, 'a') as f:
        if result.stdout:
            f.write(f"stdout: {result.stdout}\n")
        if result.stderr:
            f.write(f"stderr: {result.stderr}\n")
        f.write(f"returncode: {result.returncode}\n")
    
    return result

# Initialize output file
with open(output_file, 'w') as f:
    f.write("Git operations output\n")

print("Starting git operations...")

# Configure
run_git(['config', 'user.email', 'cavestoru-jpg@example.com'])
run_git(['config', 'user.name', 'cavestoru-jpg'])

# Add all files
run_git(['add', '-A'])

# Check staged
result = run_git(['diff', '--cached', '--name-only'])
staged = result.stdout.strip()

if staged:
    print(f"Files to commit: {len(staged.split())}")
    # Commit
    run_git(['commit', '-m', 'feat: add full project files for CI build'])
    # Push
    run_git(['push', '-u', 'origin', 'main'])
else:
    print("No staged changes")
    # Try push
    run_git(['push', '-u', 'origin', 'main'])

print("Done!")
print(f"Output written to: {output_file}")