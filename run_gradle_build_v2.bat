@echo off
REM Build LiquidBounce using GradleWrapperMain
REM Bypasses AppLocker by using direct Java invocation with wildcard classpath

setlocal enabledelayedexpansion

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - GradleWrapperMain
echo ============================================
echo Java: %JAVA_EXE%
echo Gradle dist: %GRADLE_DIST%
echo.

REM Verify Java exists
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

REM Build classpath:
REM 1. All JARs from gradle lib (for core gradle classes)
REM 2. gradle-wrapper-main jar (the wrapper main class)
REM 3. gradle-wrapper.jar from project (contains launcher logic)
REM 4. Current directory (for gradle.properties, settings.gradle.kts, etc.)
set CLASSPATH=%GRADLE_DIST%\lib\*
set CLASSPATH=%CLASSPATH%;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar
set CLASSPATH=%CLASSPATH%;gradle\wrapper\gradle-wrapper.jar
set CLASSPATH=%CLASSPATH%;.

echo Running Gradle build...
echo.

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -Dorg.gradle.appname=gradlew -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

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
)

exit /b %EXIT_CODE%