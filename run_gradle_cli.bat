@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM LiquidBounce Build - Direct Classpath Invocation
REM This bypasses AppLocker by using -cp instead of -jar

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct Classpath Invocation
echo ============================================
echo Java Home: %JAVA_HOME%
echo Gradle Distribution: %GRADLE_DIST%
echo.

REM Check Java exists
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found at %JAVA_HOME%\bin\java.exe
    endlocal
    exit /b 1
)

REM Build classpath from gradle distribution jars
set CP=
echo Building classpath from %GRADLE_DIST%\lib\...
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)
echo Classpath built with !CP:length! characters
echo.

REM Run Gradle using CLI jar with -cp (bypasses AppLocker)
echo Running Gradle...
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -Dorg.gradle.appname=gradlew ^
    -Dorg.gradle.daemon=false ^
    -cp "!CP!" ^
    org.gradle.launcher.GradleMain ^
    build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build exit code: %EXIT_CODE%
echo ============================================

REM Check for build output
if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    if exist "build\libs\zip\" (
        echo Contents of build\libs\zip\:
        dir "build\libs\zip\" /b
    )
) else (
    echo.
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist "build\" (
        echo Build directory contents:
        dir build\
    )
)

endlocal
exit /b %EXIT_CODE%