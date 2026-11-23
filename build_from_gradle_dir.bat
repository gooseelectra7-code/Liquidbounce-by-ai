@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - using extracted Gradle from wrapper dist directory

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo Gradle: %GRADLE_DIST%

REM Run gradle from the dist directory
echo.
echo Running Gradle build...
"%GRADLE_DIST%\bin\gradle.bat" build -x detekt -x test --console=plain

set BUILD_ERROR=%ERRORLEVEL%
echo.
echo Build completed with error level: %BUILD_ERROR%

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    if exist build\libs\zip\ (
        echo Zip files:
        dir build\libs\zip\
    )
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        dir build\
    )
)

endlocal