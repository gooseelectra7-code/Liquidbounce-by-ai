@echo off
REM LiquidBounce Build Script with Fallback
REM Tries multiple methods to build the project

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo ========================================
echo LiquidBounce Build Script
echo ========================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar
set BUILD_ARGS=build -x detekt -x test --console=plain

echo Java: %JAVA_EXE%
echo Gradle Distribution: %GRADLE_DIST%
echo Wrapper JAR: %WRAPPER_JAR%
echo.

REM Check Java
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

REM Check Gradle distribution
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found!
    echo The Gradle wrapper needs to download Gradle first.
    echo This is likely because gradlew.bat was blocked by AppLocker.
    echo.
    echo Please do the following:
    echo 1. Temporarily disable AppLocker or run from an admin PowerShell with:
    echo    Set-ExecutionPolicy Bypass -Scope Process -Force
    echo 2. Run: gradle wrapper --gradle-version 9.7.1
    echo    (This will download Gradle to the location above)
    echo 3. Run this script again
    exit /b 1
)

REM Check wrapper jar
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Gradle wrapper JAR not found at:
    echo   %WRAPPER_JAR%
    exit /b 1
)

echo Prerequisites OK. Starting build...
echo ========================================
echo.

REM Method 1: Direct Java invocation (preferred)
set CP=%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -cp "%CP%" org.gradle.wrapper.GradleWrapperMain %BUILD_ARGS%

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo Build Result
echo ========================================
echo Exit code: %EXIT_CODE%
echo.

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR file created!
    for %%A in (build\libs\LBnew-0.38.1.jar) do echo   Size: %%~zA bytes
) else if exist "build" (
    echo WARNING: build directory exists but JAR not found
    if exist "build\libs" (
        echo Contents of build\libs:
        dir /b build\libs
    )
) else (
    echo FAILURE: Build directory not created
)

exit /b %EXIT_CODE%