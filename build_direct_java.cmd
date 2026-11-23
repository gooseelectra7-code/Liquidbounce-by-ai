@echo off
REM Direct Java invocation for Gradle build
REM Bypasses AppLocker by using Gradle installation directly

setlocal enabledelayedexpansion

echo ============================================
echo LiquidBounce Build - Direct Java Execution
echo ============================================

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo Prerequisites:
if exist "%JAVA_EXE%" (
    echo   [OK] Java: %JAVA_EXE%
    "%JAVA_EXE%" -version
) else (
    echo   [ERROR] Java not found!
    exit /b 1
)

if exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo   [OK] Gradle wrapper script found
) else (
    echo   [ERROR] Gradle not found!
    exit /b 1
)

echo.
echo Starting Gradle build...
echo Command: build -x detekt -x test --console=plain
echo.

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    -cp "%GRADLE_HOME%\lib\*" ^
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

set BUILD_STATUS=%ERRORLEVEL%

echo.
echo Build completed with exit code: %BUILD_STATUS%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created!
    dir /b build\libs\LBnew-0.38.1.jar
) else (
    echo FAILURE: JAR not found
    if exist "build\libs\" (
        echo Contents of build\libs\:
        dir /b build\libs\*.jar 2>nul
    )
)

endlocal
exit /b %BUILD_STATUS%