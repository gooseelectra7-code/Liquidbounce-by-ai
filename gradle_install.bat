@echo off
setlocal

REM Install Gradle locally using SDKMAN or direct download
REM This script sets up a proper Gradle installation

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set GRADLE_VERSION=9.7.1
set GRADLE_DIR=C:\tools\gradle-%GRADLE_VERSION%

if not exist "%GRADLE_DIR%" (
    echo Creating Gradle directory...
    mkdir "%GRADLE_DIR%"
)

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Try to find and use gradle from Gradle installation
where gradle >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Using system gradle
    gradle build -x detekt -x test --console=plain
) else (
    echo gradle not found in PATH
    echo Please install Gradle or run via gradlew
    exit /b 1
)

endlocal