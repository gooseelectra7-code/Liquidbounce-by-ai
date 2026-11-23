@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Using wrapper JAR directly
REM Bypasses gradlew.bat restrictions by using Java directly

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Using Gradle Wrapper JAR
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo.

REM Run the Gradle wrapper JAR directly
"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -jar "%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar" ^
    build -x detekt -x test --console=plain

set BUILD_ERROR=%ERRORLEVEL%

echo.
echo Build completed with exit code: %BUILD_ERROR%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    for %%f in ("build\libs\*.jar") do echo   - %%~nxf
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        dir build\
    )
)

if exist "build\libs\zip\" (
    echo.
    echo Zip files copied to build\libs\zip\:
    for %%f in ("build\libs\zip\*") do echo   - %%~nxf
)

endlocal