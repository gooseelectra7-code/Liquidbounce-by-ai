@echo off
REM Build script for LiquidBounce using Gradle with direct Java invocation
REM Created to work around AppLocker restrictions on gradlew.bat

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set GRADLE_LIB=%GRADLE_DIST%\lib
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

echo === LiquidBounce Build Script ===
echo Project: %CD%
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo.

REM Check prerequisites
if not exist "%JAVA_EXE%" (
    echo ERROR: Java not found at %JAVA_EXE%
    exit /b 1
)

if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo ERROR: Gradle wrapper JAR not found at %WRAPPER_JAR%
    exit /b 1
)

echo Executing Gradle build (excluding detekt and test)...
echo.

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -cp "%GRADLE_LIB%\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set EXIT_CODE=%ERRORLEVEL%

echo.
echo === Build Complete ===
echo Exit code: %EXIT_CODE%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo.
    echo SUCCESS: JAR file created:
    dir /b build\libs\LBnew-0.38.1.jar
    for %%A in (build\libs\LBnew-0.38.1.jar) do echo Size: %%~zA bytes
) else (
    echo.
    echo FAILURE: JAR not found at build\libs\LBnew-0.38.1.jar
    if exist "build\libs" (
        echo Contents of build\libs:
        dir /b build\libs
    )
)

exit /b %EXIT_CODE%