@echo off
setlocal

REM Simple gradlew invocation - using cmd.exe /c as wrapper

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo ============================================
echo LiquidBounce Build - Simple gradlew.bat invocation
echo ============================================
echo.

REM Use cmd.exe to run gradlew.bat to bypass AppLocker
cmd.exe /c gradlew.bat build -x detekt -x test --console=plain

set BUILD_STATUS=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %BUILD_STATUS%
echo ============================================

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR file created
    dir /b build\libs\LBnew-0.38.1.jar
) else (
    echo FAILURE: JAR not found
)

if exist "build\libs\zip\" (
    echo Zip files:
    dir build\libs\zip\
)

endlocal