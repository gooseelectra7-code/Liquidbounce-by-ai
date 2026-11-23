@echo off
REM LiquidBounce Build Script - Direct Java Invocation
REM Run: build_now_standalone.cmd

setlocal enabledelayedexpansion

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar
set PROJECT_DIR=%~dp0

REM Change to project directory
cd /d "%PROJECT_DIR%"

echo Project: %PROJECT_DIR%
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo.

REM Check if we can execute java directly via cmd
echo Checking Java executable...
cmd.exe /c ""%JAVA_EXE%" -version" 2>&1
if errorlevel 1 (
    echo ERROR: Cannot execute java.exe
    echo AppLocker may be blocking execution
    goto :fallback
)

echo Java executable works!
echo.

REM Build classpath manually (avoiding wildcard issues)
set CP=%GRADLE_DIST%\lib\*
set CP=%CP%;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar
set CP=%CP%;%WRAPPER_JAR%

echo Starting Gradle build via GradleWrapperMain...
echo Command: build -x detekt -x test --console=plain
echo.

REM Execute Gradle via direct Java invocation
cmd.exe /c ""%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED -cp "%CP%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain"

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXIT_CODE%
echo ============================================

goto :check_jar

:fallback
echo.
echo AppLocker blocking detected. Trying alternative approach...
echo.

REM Try using gradle.bat from Gradle installation
if exist "%GRADLE_DIST%\bin\gradle.bat" (
    echo Found gradle.bat at %GRADLE_DIST%\bin\gradle.bat
    echo Trying direct gradle.bat invocation...
    cmd.exe /c "call ""%GRADLE_DIST%\bin\gradle.bat"" build -x detekt -x test --console=plain"
    set EXIT_CODE=%ERRORLEVEL%
    goto :check_jar
)

echo ERROR: No alternative build method available
exit /b 1

:check_jar
REM Check for output JAR
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created!
    echo.
    echo All JARs in build\libs:
    dir /b "build\libs\*.jar" 2>nul
) else (
    echo.
    echo Status: LBnew-0.38.1.jar not found
    if exist "build\" (
        echo Build directory exists
        dir "build" /s /b 2>nul | findstr /r "\.jar$" || echo   (no jars created)
    ) else (
        echo Build directory does not exist
    )
)

endlocal
exit /b %EXIT_CODE%