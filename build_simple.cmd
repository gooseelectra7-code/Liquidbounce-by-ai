@echo off
REM LiquidBounce Build Script - Simple Command Line
REM Executes Gradle build via direct Java invocation
REM Usage: run_gradle.bat [gradle-args...]
REM Example: run_gradle.bat build -x detekt -x test

setlocal enabledelayedexpansion

echo ========================================
echo LiquidBounce Build Script
echo ========================================
echo Project: %~dp0
echo Java: C:\Program Files\Java\jdk-25.0.4\bin\java.exe
echo Gradle: 9.7.1
echo ========================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar
set PROJECT_DIR=%~dp0

REM Change to project directory
cd /d "%PROJECT_DIR%"

REM Verify tools exist
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

if not exist "%GRADLE_HOME%" (
    echo ERROR: Gradle not found at %GRADLE_HOME%
    echo Run gradle wrapper once to download Gradle distribution first
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo ERROR: Wrapper JAR not found at %WRAPPER_JAR%
    exit /b 1
)

if not exist "%GRADLE_HOME%\lib\gradle-wrapper-main-9.7.1.jar" (
    echo ERROR: gradle-wrapper-main-9.7.1.jar not found
    exit /b 1
)

echo.
echo Starting Gradle build...
echo Command: build -x detekt -x test
echo.

REM Build classpath - using the specific JARs we know exist
set CP=%GRADLE_HOME%\lib;%GRADLE_HOME%\lib\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

REM Add all jars from lib directory to classpath (not using wildcard due to Windows path issues)
for /f "delims=" %%i in ('dir /b /s "%GRADLE_HOME%\lib\*.jar" 2^>nul') do (
    set CP=!CP!;%%i
)

REM Run Gradle
"%JAVA_EXE%" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    -cp "%CP%" ^
    org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo Build Complete
echo Exit Code: %EXIT_CODE%
echo ========================================

REM Check for output JAR
set JAR_PATH=%PROJECT_DIR%build\libs\LBnew-0.38.1.jar
if exist "%JAR_PATH%" (
    echo.
    echo SUCCESS: Build output created!
    echo JAR File: build\libs\LBnew-0.38.1.jar
    for %%F in ("%JAR_PATH%") do echo Size: %%~zF bytes
    echo.
    echo All JARs in build\libs:
    dir /b "%PROJECT_DIR%build\libs\*.jar" 2>nul
) else (
    echo.
    echo Status: JAR not created
    if exist "%PROJECT_DIR%build" (
        echo Build directory exists, checking contents...
        dir "%PROJECT_DIR%build" /s /b 2>nul
    )
)

endlocal
exit /b %EXIT_CODE%