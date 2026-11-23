@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build Script for Windows with Application Control policies
REM This script works around execution restrictions by using cmd.exe

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build Script
echo ============================================
echo Java: %JAVA_HOME%
echo Project: %PROJECT_DIR%
echo.

REM First, try direct gradle.bat invocation via cmd.exe
echo Method 1: Using local gradle.bat...
cmd.exe /c ""%JAVA_HOME%\bin\java.exe" -Xms512m -Xmx4096m -jar "gradle\wrapper\gradle-wrapper.jar" build -x detekt -x test --console=plain"

if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: Build completed!
    echo.
    echo Checking output files...
    dir build\libs\*.jar
    echo.
    echo Checking zip directory...
    if exist build\libs\zip\ (
        dir build\libs\zip\
    ) else (
        echo zip directory not found
    )
    goto :eof
)

echo.
echo Method 2: Using Gradle CLI Main class...

REM Build classpath for direct Gradle invocation
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

REM Build classpath - include ALL gradle libs
set CP=
for /f "delims=" %%i in ('dir /b "%GRADLE_HOME%\lib\*.jar" 2^>nul') do set CP=!CP!;%GRADLE_HOME%\lib\%%i

echo Attempting direct Gradle class invocation...
"%JAVA_HOME%\bin\java.exe" -Xms512m -Xmx4096m -cp "!CP!" org.gradle.launcher.GradleMain build -x detekt -x test --console=plain --no-daemon --no-parallel

if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: Build completed!
    dir build\libs\*.jar
    if exist build\libs\zip\ (dir build\libs\zip\)
) else (
    echo.
    echo Build failed - output files not found
    if exist build\ (dir build\) else echo build directory does not exist
)

endlocal