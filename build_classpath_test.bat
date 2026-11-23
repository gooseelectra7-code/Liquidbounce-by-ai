@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build - Debug classpath construction
REM Tests Gradle CLI Main class invocation

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Classpath Debug
echo ============================================
echo Java: %JAVA_HOME%
echo Gradle Home: %GRADLE_HOME%
echo.

REM Check gradle home exists
if not exist "%GRADLE_HOME%" (
    echo ERROR: Gradle distribution not found at %GRADLE_HOME%
    exit /b 1
)

REM Count jars in lib
for /f %%i in ('dir /b "%GRADLE_HOME%\lib\*.jar" 2^>nul ^| find /c /v ""') do set JAR_COUNT=%%i
echo Found %JAR_COUNT% jars in %GRADLE_HOME%\lib\

REM Build classpath
set CP=!GRADLE_HOME!\lib\gradle-gradle-cli-main-9.7.1.jar
set JAR_ADDED=0

for /f "delims=" %%i in ('dir /b "%GRADLE_HOME%\lib\*.jar" 2^>nul') do (
    if not "%%i"=="gradle-gradle-cli-main-9.7.1.jar" (
        set CP=!CP!;!GRADLE_HOME!\lib\%%i
        set /a JAR_ADDED+=1
    )
)

echo.
echo Classpath built with %JAR_ADDED% additional jars
echo Classpath length: !CP:length!
echo.

REM Verify java is accessible
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found
    exit /b 1
)

echo Running GradleMain...
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m ^
    -cp "!CP!" ^
    org.gradle.launcher.GradleMain ^
    build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ============================================
echo Exit code: %EXIT_CODE%
echo ============================================

endlocal
exit /b %EXIT_CODE%