@echo off
cd /d "C:\Users\SYLVIA\Desktop\LBnew-main"
"C:\Program Files\Git\bin\git.exe" status
echo.
echo --- Git Config ---
"C:\Program Files\Git\bin\git.exe" config user.email
"C:\Program Files\Git\bin\git.exe" config user.name
echo.
echo --- Remote ---
"C:\Program Files\Git\bin\git.exe" remote -v