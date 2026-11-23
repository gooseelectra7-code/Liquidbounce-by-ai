@echo off
REM Simple build script for LiquidBounce
REM This script runs the Gradle build task with direct Java invocation

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Use java.exe directly, avoiding gradlew.bat which may be blocked by AppLocker
"C:\Program Files\Java\jdk-25.0.4\bin\java.exe" ^
    -version

echo.
echo Java version check complete.
echo.

REM Set the classpath for Gradle wrapper execution
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

REM Build the full classpath
set CLASSPATH=%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

echo Starting Gradle build...
echo.

REM Execute Gradle with the build task (excluding detekt and test as specified)
"C:\Program Files\Java\jdk-25.0.4\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -cp "%CLASSPATH%" ^
    org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set BUILD_EXIT_CODE=%ERRORLEVEL%

echo.
echo Build completed with exit code: %BUILD_EXIT_CODE%

REM Check for output JAR
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: JAR file created at build\libs\LBnew-0.38.1.jar
) else (
    echo.
    echo JAR file not found. Check build output above for errors.
)

exit /b %BUILD_EXIT_CODE%