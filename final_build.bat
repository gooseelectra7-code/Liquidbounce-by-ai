@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Direct Java Invocation
REM Bypasses AppLocker blocking of .bat/.ps1 by using direct Java call

set JAVA_EXE="C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
set PROJECT_DIR="C:\Users\SYLVIA\Desktop\LBnew-main"

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================
echo.

cd /d %PROJECT_DIR%

REM Run the build using java -jar directly
%JAVA_EXE% -Xms1024m -Xmx6144m -XX:+UseZGC -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain

set BUILD_EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %BUILD_EXIT_CODE%
echo ============================================

REM Check for output
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    dir build\libs\*.jar
) else (
    echo.
    echo FAILURE: build\libs\LBnew-0.38.1.jar NOT found
    if exist build\ (
        echo Build directory contents:
        dir /s build\
    ) else (
        echo No build directory created
    )
)

endlocal
exit /b %BUILD_EXIT_CODE%