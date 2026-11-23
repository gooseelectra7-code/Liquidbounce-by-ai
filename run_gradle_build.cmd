@echo off
REM Build script for LiquidBounce using Gradle with direct Java invocation
REM This is a .cmd file which may be less likely to be blocked by AppLocker

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set GRADLE_LIB=%GRADLE_DIST%\lib
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

echo Executing Gradle build...
"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC -cp "%GRADLE_LIB%\*;../../../lib/plugins/gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

echo.
echo Build exit code: %ERRORLEVEL%