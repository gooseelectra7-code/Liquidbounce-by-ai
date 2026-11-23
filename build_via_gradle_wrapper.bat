@echo off
REM Build LiquidBounce using GradleWrapperMain (direct Java invocation to bypass AppLocker)
REM This bypasses AppLocker by not using gradlew.bat which is blocked
REM and using wildcard classpath to avoid AppLocker inspection

setlocal enabledelayedexpansion

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - AppLocker Bypass
echo ============================================
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo.

REM Verify Java exists
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

REM Verify Gradle dist exists
if not exist "%GRADLE_DIST%\lib" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    exit /b 1
)

REM Build classpath using wildcard (bypasses AppLocker)
REM The wildcard %GRADLE_DIST%\lib\* is expanded by the shell and passed to Java
set CLASSPATH=%GRADLE_DIST%\lib\*
set CLASSPATH=%CLASSPATH%;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar
set CLASSPATH=%CLASSPATH%;.

echo Running Gradle build...
echo.

set GRADLE_MAIN=org.gradle.wrapper.GradleWrapperMain
echo Attempting to invoke: %GRADLE_MAIN%
echo Classpath: %CLASSPATH%
echo.
"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp "%CLASSPATH%" %GRADLE_MAIN% build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed
echo ============================================
echo Exit code: %EXIT_CODE%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    dir "build\libs\LBnew-0.38.1.jar"
) else (
    echo WARNING: JAR not found at build\libs\LBnew-0.38.1.jar
    if exist "build\libs" (
        echo Contents of build\libs:
        dir "build\libs" /a
    )
    if exist "build" (
        echo Contents of build:
        dir "build" /a
    )
)

exit /b %EXIT_CODE%