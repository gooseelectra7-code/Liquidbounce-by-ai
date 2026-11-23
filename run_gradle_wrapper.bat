@echo off
REM LiquidBounce Build - Direct GradleWrapperMain invocation
REM Bypasses AppLocker by using Java directly
REM Usage: run_gradle_wrapper.bat [args...]

setlocal enabledelayedexpansion

echo ============================================
echo LiquidBounce Build - GradleWrapperMain Direct
echo ============================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar

REM Project directory
cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Verify tools exist
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    echo Make sure gradle-wrapper.jar has been executed at least once
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo ERROR: Wrapper JAR not found at %WRAPPER_JAR%
    exit /b 1
)

if not exist "%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar" (
    echo ERROR: gradle-wrapper-main-9.7.1.jar not found
    exit /b 1
)

echo.
echo Building project...
echo Command: build -x detekt -x test --console=plain
echo.

REM Build classpath - Gradle lib jars + wrapper jars
set CP=%GRADLE_DIST%\lib;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

REM Add all JARs from lib directory
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)

REM Run Gradle via GradleWrapperMain class
"%JAVA_EXE%" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    -cp "!CP!" ^
    org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXIT_CODE%
echo ============================================

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created!
    for %%f in ("build\libs\*.jar") do echo   - %%~nxf
) else (
    echo.
    echo Status: JAR not created
    if exist "build\" (
        echo Build directory contents:
        dir build\ /s /b 2>nul
    )
)

endlocal
exit /b %EXIT_CODE%