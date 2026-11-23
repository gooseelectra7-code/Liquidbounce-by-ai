#!/usr/bin/env python3
import sys
import os

print("Python test")
print(f"Python version: {sys.version}")
print(f"Current dir: {os.getcwd()}")

# Write test file
with open(r"C:\Users\SYLVIA\Desktop\LBnew-main\py-test.txt", 'w') as f:
    f.write("Test successful\n")

print("Test file written")