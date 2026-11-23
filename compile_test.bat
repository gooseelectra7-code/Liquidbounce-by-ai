@echo off
setlocal enabledelayedexpansion

REM Quick compile test - should be faster than full build

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo Quick Kotlin Compile Test
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo.

REM Try using gradle wrapper jar directly
echo Attempting direct jar execution via cmd.exe /c...

(
echo setlocal enabledelayedexpansion
echo cd /d "%PROJECT_DIR%"
echo "%JAVA_HOME%\bin\java.exe" -Xms512m -Xmx2048m -XX:+UseZGC -jar gradle\wrapper\gradle-wrapper.jar compileKotlin --console=plain
echo endlocal
) > "%TEMP%\compile_run.bat"

cmd.exe /c call "%TEMP%\compile_run.bat"

set RESULT=%ERRORLEVEL%

echo.
echo Compile test completed with exit code: %RESULT%

if exist "build\classes\kotlin\main" (
    echo SUCCESS: Kotlin classes compiled
) else (
    echo Checking for other compile output...
    if exist "build\" (
        dir build\
    )
)

del "%TEMP%\compile_run.bat" 2>nul

endlocal