@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build Script for Windows with JDK 21
REM Uses directly installed Gradle with JDK 21

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set GRADLE_HOME=C:\Program Files\Gradle\gradle-9.7.1

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo ============================================
echo LiquidBounce Build Script
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo GRADLE_HOME: %GRADLE_HOME%
echo.

REM Check Java
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found at %JAVA_HOME%
    exit /b 1
)

"%JAVA_HOME%\bin\java.exe" -version
echo Java found.

REM Check Gradle
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo WARNING: Gradle not found at %GRADLE_HOME%
    echo Will try using Gradle wrapper JAR...
)

REM Run Gradle build
echo.
echo Running Gradle build...
"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain

set BUILD_ERROR=%ERRORLEVEL%
echo.
echo Build completed with error level: %BUILD_ERROR%

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo ============================================
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo ============================================
    
    REM Verify manifest
    echo.
    echo JAR manifest:
    "%JAVA_HOME%\bin\java.exe" -jar "build\libs\LBnew-0.38.1.jar" 2>&1 || echo (manifest check, no main-class verification method)
    
    if exist build\libs\zip\ (
        echo.
        echo Zip files in build\libs\zip\:
        dir build\libs\zip\
    )
) else (
    echo.
    echo ============================================
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    echo ============================================
    if exist build\ (
        echo Build output:
        dir build\
    )
)

endlocal