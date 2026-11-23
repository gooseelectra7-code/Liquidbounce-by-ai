@echo off
REM Debug script to understand Gradle wrapper invocation

setlocal enabledelayedexpansion

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Gradle Debug
echo ============================================

REM Test 1: Java version
echo.
echo [TEST 1] Java version:
"%JAVA_EXE%" -version 2>&1
echo Exit code: %ERRORLEVEL%

REM Test 2: Check Gradle wrapper jar
echo.
echo [TEST 2] Gradle wrapper jar:
if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo gradle-wrapper.jar found
    for %%A in ("gradle\wrapper\gradle-wrapper.jar") do echo Size: %%~zA bytes
) else (
    echo gradle-wrapper.jar NOT FOUND!
)

REM Test 3: Check Gradle dist lib directory
echo.
echo [TEST 3] Gradle dist lib directory:
if exist "%GRADLE_DIST%\lib" (
    echo Lib directory found
    for %%A in ("%GRADLE_DIST%\lib\*.jar") do (
        echo JARs in lib: %%A
    )
) else (
    echo Lib directory NOT FOUND!
)

REM Test 4: Check gradle-wrapper-main jar
echo.
echo [TEST 4] Gradle wrapper main jar:
if exist "%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar" (
    echo gradle-wrapper-main-9.7.1.jar found
) else (
    echo gradle-wrapper-main-9.7.1.jar NOT FOUND!
)

REM Test 5: List all gradle-wrapper-main jars
echo.
echo [TEST 5] All gradle-wrapper-main jars in plugins:
dir /b "%GRADLE_DIST%\lib\plugins\gradle-wrapper-main*.jar" 2>&1

REM Test 6: Try invoking GradleWrapperMain
echo.
echo [TEST 6] Invoking GradleWrapperMain --version:

REM First, let's find the correct jar name
for %%A in ("%GRADLE_DIST%\lib\plugins\gradle-wrapper-main*.jar") do (
    set WRAPPER_MAIN_JAR=%%A
    echo Found wrapper main: %%A
)

if defined WRAPPER_MAIN_JAR (
    set CLASSPATH=%GRADLE_DIST%\lib\*;%WRAPPER_MAIN_JAR%;.
    echo Classpath: !CLASSPATH!
    
    echo.
    echo Running GradleWrapperMain --version...
    "%JAVA_EXE%" -Xms512m -Xmx2048m -cp "!CLASSPATH!" org.gradle.wrapper.GradleWrapperMain --version 2>&1
    echo Exit code: %ERRORLEVEL%
) else (
    echo Could not find gradle-wrapper-main jar!
)

echo.
echo ============================================
echo Debug completed
echo ============================================