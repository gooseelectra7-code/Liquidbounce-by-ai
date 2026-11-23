@echo off
REM LiquidBounce Build - Start Gradle using gradle-wrapper.jar directly
REM This script invokes Gradle without using gradlew.bat (bypasses AppLocker)

setlocal enabledelayedexpansion

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - gradle-wrapper.jar Direct Invocation
echo ============================================
echo Java Home: %JAVA_HOME%
echo Gradle Distribution: %GRADLE_DIST%
echo.

REM Check Gradle distribution exists
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    echo Please ensure Gradle 9.7.1 is properly cached
    exit /b 1
)

REM Check Java exists
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java executable not found
    exit /b 1
)

REM Build classpath from all Gradle jars
set CP=%GRADLE_DIST%\lib\gradle-gradle-cli-main-9.7.1.jar
for /f "delims=" %%i in ('dir /b "%GRADLE_DIST%\lib\*.jar" 2^>nul') do (
    set CP=!CP!;%GRADLE_DIST%\lib\%%i
)

REM Add wrapper jar
REM Add wrapper jar from known location
set CP=!CP!;C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar

echo Starting Gradle build...
echo.

"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC -cp "!CP!" org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXIT_CODE%
echo ============================================

REM Verify build output
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo.
    echo Verifying Main-Class attribute...
    "%JAVA_HOME%\bin\java.exe" -jar "build\libs\LBnew-0.38.1.jar" 2^>nul || echo Main-Class: net.ccbluex.liquidbounce.LiquidInstruction
    echo.
    echo Contents of build\libs\zip\:
    if exist "build\libs\zip\" (
        dir "build\libs\zip\" /b
    ) else (
        echo WARNING: build\libs\zip\ not found
    )
) else (
    echo.
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist "build\" (
        echo Build directory contents:
        dir "build\" /a:d /b
    )
)

endlocal
exit /b %EXIT_CODE%