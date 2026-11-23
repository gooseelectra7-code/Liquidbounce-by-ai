@echo off
REM Debug script to check classpath setup

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

echo JAVA_HOME: %JAVA_HOME%
echo GRADLE_DIST: %GRADLE_DIST%
echo.

echo Checking Java exists:
if exist "%JAVA_HOME%\bin\java.exe" (
    echo Java found at %JAVA_HOME%\bin\java.exe
    "%JAVA_HOME%\bin\java.exe" -version
) else (
    echo Java NOT found!
    exit /b 1
)

echo.
echo Checking Gradle lib directory exists:
if exist "%GRADLE_DIST%\lib" (
    echo Gradle lib directory found
    dir /b "%GRADLE_DIST%\lib\*.jar" | find /c ".jar"
    echo jars in lib
) else (
    echo Gradle lib directory NOT found!
)

echo.
echo Checking gradle-wrapper.jar:
if exist "%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar" (
    echo gradle-wrapper.jar found
) else (
    echo gradle-wrapper.jar NOT found!
)

echo.
echo Checking gradle-wrapper-main jar:
if exist "%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar" (
    echo gradle-wrapper-main-9.7.1.jar found
) else (
    echo gradle-wrapper-main-9.7.1.jar NOT found!
)

echo.
echo Testing GradleWrapperMain:
set CLASSPATH=%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;.
"%JAVA_HOME%\bin\java.exe" -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain --version 2>&1 || echo GradleWrapperMain test failed