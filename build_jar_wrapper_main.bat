@echo off
setlocal enabledelayedexpansion

echo ============================================
echo LiquidBounce Build - Direct Classpath Invocation
echo ============================================

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo Java: %JAVA_HOME%
echo Gradle Distribution: %GRADLE_DIST%
echo.

REM Verify Java exists
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java executable not found
    exit /b 1
)

REM Build classpath from gradle lib directory
set CP=%GRADLE_DIST%\lib\*

echo Classpath: %CP%
echo.

echo Running GradleMain...
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -cp "%CP%" ^
    org.gradle.launcher.GradleMain ^
    build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build exit code: %EXIT_CODE%
echo ============================================

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo.
    echo Checking zip files:
    if exist "build\libs\zip\" (
        dir "build\libs\zip\" /b
    )
) else (
    echo BUILD FAILED: JAR not found
    if exist "build\" (
        dir build\
    )
)

endlocal
exit /b %EXIT_CODE%