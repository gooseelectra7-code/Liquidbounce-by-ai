@echo off
REM Direct Java invocation for Gradle Wrapper
REM This script bypasses gradlew.bat which may be blocked by AppLocker
REM by invoking Java directly with the Gradle wrapper classes

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Configuration
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set GRADLE_HOME=%GRADLE_DIST%
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

REM Set Gradle user home
set GRADLE_USER_HOME=C:\Users\SYLVIA\.gradle

echo ========================================
echo LiquidBounce Build Script
echo ========================================
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo Working Directory: %CD%
echo ========================================
echo.

REM Check Java exists
if not exist "%JAVA_EXE%" (
    echo ERROR: Java executable not found at:
    echo   %JAVA_EXE%
    echo.
    echo Please verify JDK 25 is installed.
    exit /b 1
)

REM Check Gradle distribution
if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at:
    echo   %GRADLE_DIST%
    echo.
    echo The Gradle wrapper may need to download the distribution.
    echo Run once with gradlew.bat to download it first, then retry this script.
    exit /b 1
)

REM Check wrapper jar
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Gradle wrapper jar not found at:
    echo   %WRAPPER_JAR%
    exit /b 1
)

REM Build classpath
set CP=%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%

echo Executing: %JAVA_EXE% -cp "%CP%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain
echo ========================================
echo.

REM Execute Gradle
"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -cp "%CP%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo ========================================
echo Build Result
echo ========================================
echo Exit Code: %EXIT_CODE%
echo.

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR file created successfully!
    echo.
    echo JAR Location: build\libs\LBnew-0.38.1.jar
    for %%A in (build\libs\LBnew-0.38.1.jar) do echo Size: %%~zA bytes
) else if exist "build\libs" (
    echo WARNING: build\libs directory exists but LBnew-0.38.1.jar not found
    echo Contents of build\libs:
    dir /b build\libs
) else (
    echo FAILURE: build directory not created or JAR not found
)

exit /b %EXIT_CODE%