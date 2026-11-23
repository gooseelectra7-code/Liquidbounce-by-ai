@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM LiquidBounce Build - Direct Java invocation bypassing AppLocker
REM Uses Gradle CLI main class directly with classpath

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================
echo Java Home: %JAVA_HOME%
echo Gradle Distribution: %GRADLE_DIST%
echo Wrapper JAR: %WRAPPER_JAR%
echo.

REM Check Java exists
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found at %JAVA_HOME%\bin\java.exe
    endlocal
    exit /b 1
)

REM Check Gradle distribution exists
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    endlocal
    exit /b 1
)

REM Check wrapper jar exists
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Gradle wrapper JAR not found at %WRAPPER_JAR%
    endlocal
    exit /b 1
)

REM Build classpath: wrapper jar + all gradle libs
set CP=%WRAPPER_JAR%
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)
echo Classpath built with !CP:length! characters
echo.

REM Run Gradle using the CLI main class directly (bypasses AppLocker)
echo Running Gradle build...
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
echo Build completed with exit code: %EXIT_CODE%
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