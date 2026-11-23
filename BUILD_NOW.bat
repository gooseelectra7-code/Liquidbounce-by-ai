@echo off
REM LiquidBounce Build - Execute Gradle Build with Java 25
REM This script runs the build command directly using Java

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo ========================================
echo LiquidBounce Build Script
echo ========================================
echo Project: %CD%
echo Java: C:\Program Files\Java\jdk-25.0.4\bin\java.exe
echo Gradle: 9.7.1
echo ========================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar

REM Verify Java exists
if not exist "%JAVA_EXE%" (
    echo ERROR: Java 25 not found at %JAVA_EXE%
    echo Please install JDK 25 from https://jdk.java.net/25/
    exit /b 1
)

REM Verify Gradle exists
if not exist "%GRADLE_HOME%" (
    echo ERROR: Gradle distribution not found
    echo Expected: %GRADLE_HOME%
    echo You may need to run gradle wrapper once to download Gradle
    exit /b 1
)

REM Verify wrapper jar exists
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Wrapper JAR not found
    echo Expected: %WRAPPER_JAR%
    exit /b 1
)

echo.
echo Starting Gradle build with JDK 25...
echo Command: build -x detekt -x test
echo.

REM Set up classpath for Gradle wrapper execution
set CLASSPATH=%GRADLE_HOME%\lib\*;%GRADLE_HOME%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

REM Run Gradle build with Java 25
"%JAVA_EXE%" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    -cp "%CLASSPATH%" ^
    org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo Build Complete
echo ========================================
echo Exit Code: %EXIT_CODE%
echo.

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: Build output created!
    echo.
    echo JAR File: build\libs\LBnew-0.38.1.jar
    for %%A in (build\libs\LBnew-0.38.1.jar) do echo Size: %%~zA bytes
    echo.
    dir /b build\libs\*.jar
) else (
    echo Status: JAR not found
    echo Check the output above for errors.
    echo.
    if exist "build" (
        dir build
    )
)

exit /b %EXIT_CODE%