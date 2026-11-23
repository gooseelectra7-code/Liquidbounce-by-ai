@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build Script
REM Direct Java invocation for Gradle wrapper

set "JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
set "PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main"
set "GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================
echo Java: %JAVA_EXE%
echo Project: %PROJECT_DIR%
echo.

REM Build classpath
set "CLASSPATH=%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;gradle\wrapper\gradle-wrapper.jar;."

echo Running Gradle build...
echo Gradle dist: %GRADLE_DIST%
echo.

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set BUILD_EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %BUILD_EXIT_CODE%
echo ============================================

REM Check for output
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    dir /b "build\libs\LBnew-0.38.1.jar"
    for %%A in ("build\libs\LBnew-0.38.1.jar") do echo Size: %%~zA bytes
) else (
    echo.
    echo WARNING: JAR not found at build\libs\LBnew-0.38.1.jar
    if exist "build\libs" (
        echo Contents of build\libs:
        dir /b "build\libs" 2>nul
    )
)

exit /b %BUILD_EXIT_CODE%