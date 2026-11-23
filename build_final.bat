@echo off
REM LiquidBounce Build Script - Final Version
REM Runs Gradle build using direct Java invocation

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo ========================================
echo LiquidBounce Build
echo ========================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar

echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_HOME%
echo Wrapper: %WRAPPER_JAR%
echo.

REM Verify paths
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found!
    exit /b 1
)
if not exist "%GRADLE_HOME%" (
    echo ERROR: Gradle not found!
    exit /b 1
)
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Wrapper JAR not found!
    exit /b 1
)

REM Build classpath - using the exact paths that exist
set CP=%GRADLE_HOME%\lib;%GRADLE_HOME%\lib\*;%GRADLE_HOME%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

echo Running Gradle build...
echo.

"%JAVA_EXE%" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED ^
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED ^
    --add-opens java.base/sun.nio.fs=ALL-UNNAMED ^
    --add-opens java.base/sun.security.ssl=ALL-UNNAMED ^
    --add-opens java.base/sun.security.util=ALL-UNNAMED ^
    --add-opens java.base/sun.net.sponsor=ALL-UNNAMED ^
    --add-opens java.base/sun.net.protocol.http=ALL-UNNAMED ^
    -cp "%CP%" ^
    org.gradle.wrapper.GradleWrapperMain ^
    build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo Result: Exit code %EXIT_CODE%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR created at build\libs\LBnew-0.38.1.jar
    for %%F in ("build\libs\LBnew-0.38.1.jar") do echo Size: %%~zF bytes
) else (
    echo JAR not created
)

exit /b %EXIT_CODE%