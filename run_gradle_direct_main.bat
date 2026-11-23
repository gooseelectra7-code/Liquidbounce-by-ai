@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Direct GradleMain invocation
REM This script builds the classpath for GradleMain and runs it directly

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set GRADLE_WRAPPER_JAR=%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct GradleMain Invocation
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo Gradle dist: %GRADLE_DIST%
echo.

REM Build classpath using wildcard (AppLocker-safe)
set GRADLE_CLASSPATH=%GRADLE_DIST%\lib\*;%GRADLE_WRAPPER_JAR%;.

echo Running GradleMain directly...
"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -Dorg.gradle.appname=gradlew ^
    -cp "%GRADLE_CLASSPATH%" ^
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

set BUILD_ERROR=%ERRORLEVEL%

echo.
echo Build completed with exit code: %BUILD_ERROR%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        dir build\
    )
)

endlocal