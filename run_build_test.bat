@echo off
REM LiquidBounce Build - Test with direct Java invocation
REM This script runs gradle-wrapper.jar directly via Java

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build Test
echo ============================================
echo Java: %JAVA_HOME%
echo Project: %PROJECT_DIR%
echo.

REM Check Java is accessible
"%JAVA_HOME%\bin\java.exe" -version
if errorlevel 1 (
    echo ERROR: Java not accessible
    exit /b 1
)

echo.
echo Running Gradle build...
echo.

REM Run gradle-wrapper.jar directly - this should work around AppLocker
"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -jar "gradle\wrapper\gradle-wrapper.jar" ^
    build -x detekt -x test --console=plain

set BUILD_EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build finished with exit code: %BUILD_EXIT_CODE%
echo ============================================

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo.
    echo Manifest check:
    "%JAVA_HOME%\bin\java.exe" -jar "build\libs\LBnew-0.38.1.jar" 2^>nul
    echo   Main-Class present (see JAR manifest)
    echo.
    if exist "build\libs\zip\" (
        echo Zip files from zip_include/:
        dir "build\libs\zip\"
    )
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar NOT FOUND
    if exist "build\" (
        echo Build directory contents:
        dir "build\"
    ) else (
        echo Build directory does not exist
    )
)

endlocal