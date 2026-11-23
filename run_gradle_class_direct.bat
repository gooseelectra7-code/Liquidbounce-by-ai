@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Direct GradleMain class invocation
REM Builds classpath from Gradle distribution lib folder and wrapper jar

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct GradleMain
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo.

REM Build classpath - Gradle lib jars first, then wrapper jar, then project
set CP=
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    if "!CP!"=="" (
        set CP=%%i
    ) else (
        set CP=!CP!;%%i
    )
)

REM Add gradle-wrapper.jar
set CP=!CP!;%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar

REM Add project directory (for gradle.properties, build.gradle.kts, settings.gradle.kts)
set CP=!CP!;%PROJECT_DIR%

echo Classpaths built, running GradleMain...
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -Dorg.gradle.appname=gradlew ^
    -cp "!CP!" ^
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

set BUILD_ERROR=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %BUILD_ERROR%
echo ============================================

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    for %%f in ("build\libs\*.jar") do echo   Found: %%~nxf
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        echo Build directory contents:
        dir build\
    ) else (
        echo Build directory doesn't exist
    )
)

endlocal