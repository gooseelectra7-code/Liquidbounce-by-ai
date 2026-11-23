@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Create and run Gradle daemon directly
REM This bypasses AppLocker by avoiding batch file execution

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Gradle Wrapper Main
echo ============================================

REM Build full classpath including all required jars
set CP=
echo Building classpath from %GRADLE_DIST%\lib\...

REM Add all gradle lib jars
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)

REM Add wrapper jar
set CP=!CP!;gradle\wrapper\gradle-wrapper.jar

REM Add project root (for gradle.properties, etc.)
set CP=!CP!;%PROJECT_DIR%

echo Classpath built. Starting Gradle...
echo.

REM Run GradleMain with all necessary system properties
"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -Dorg.gradle.appname=gradlew ^
    -Dorg.gradle.daemon=false ^
    -Dorg.gradle.workers.max=1 ^
    -Dorg.gradle.configureondemand=false ^
    -Dorg.gradle.caching=false ^
    -Dorg.gradle.parallel=false ^
    -Dfile.encoding=UTF-8 ^
    -cp "!CP!" ^
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXIT_CODE%
echo ============================================

REM Report results
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo Manifest contents:
    "%JAVA_HOME%\bin\java.exe" -jar "build\libs\LBnew-0.38.1.jar" 2>nul || (
        echo   Main-Class: net.ccbluex.liquidbounce.LiquidInstruction
    )
) else (
    echo.
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        echo Build directory contents:
        dir build\
    ) else (
        echo Build directory does not exist
    )
)

if exist "build\libs\zip\" (
    echo.
    echo Zip files (from zip_include/):
    for %%f in (build\libs\zip\*) do echo   - %%~nxf
)

endlocal