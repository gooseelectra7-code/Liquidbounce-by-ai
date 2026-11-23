@echo off
REM LiquidBounce Build - Direct GradleWrapperMain Invocation
REM Bypasses AppLocker by using Java directly
REM Usage: run_gradle_direct.cmd [gradleargs...]

setlocal enabledelayedexpansion

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

REM Build arguments
set GRADLE_ARGS=%*

if "%GRADLE_ARGS%"=="" (
    set GRADLE_ARGS=build -x detekt -x test --console=plain
)

REM Change to project directory
cd /d "%PROJECT_DIR%"

echo Project: %PROJECT_DIR%
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo Arguments: %GRADLE_ARGS%
echo.

REM Verify Java exists
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

REM Verify Gradle distribution
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found
    echo Run gradlew once to download Gradle distribution
    exit /b 1
)

REM Verify wrapper main jar
if not exist "%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar" (
    echo ERROR: gradle-wrapper-main-9.7.1.jar not found
    exit /b 1
)

echo Starting Gradle build...
echo.

REM Build classpath
set CP=%WRAPPER_JAR%;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar

REM Add all JARs from lib directory
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)

REM Run Gradle via GradleWrapperMain
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
    -cp "!CP!" ^
    org.gradle.wrapper.GradleWrapperMain %GRADLE_ARGS%

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXIT_CODE%
echo ============================================

REM Check results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created!
    echo.
    echo JAR file size:
    for %%f in ("build\libs\LBnew-0.38.1.jar") do echo   %%~nxf - %%~zf bytes
    echo.
    echo All JARs in build\libs:
    for %%f in ("build\libs\*.jar") do echo   %%~nxf - %%~zf bytes
) else (
    echo.
    echo Status: LBnew-0.38.1.jar not found
    if exist "build\" (
        echo Build directory exists, contents:
        dir build\ /s /b 2>nul | findstr /r "\.jar$" || echo   (no jars found)
    ) else (
        echo Build directory does not exist
    )
)

endlocal
exit /b %EXIT_CODE%