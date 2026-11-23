@echo off
setlocal enabledelayedexpansion
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
cd /d "%PROJECT_DIR%"

REM Verify files exist first
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    exit /b 1
)
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ERROR: gradle-wrapper.jar not found
    exit /b 1
)

echo Running Gradle build via -jar wrapper mode...
echo.

REM Standard usage: gradle-wrapper.jar as bootstrap, handles all wrapper logic internally
"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -jar "gradle\wrapper\gradle-wrapper.jar" build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%
echo.
echo Exit code: %EXIT_CODE%
if exist "build\libs\LBnew-0.38.1.jar" (echo SUCCESS: JAR created) else (echo JAR not found)
exit /b %EXIT_CODE%