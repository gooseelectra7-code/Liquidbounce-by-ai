@echo off
cd /d C:\Users\SYLVIA\Desktop\LBnew-main

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set GRADLE_LIB=%GRADLE_DIST%\lib
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

echo Executing Gradle build...
%JAVA_EXE% -Xms1024m -Xmx6144m -XX:+UseZGC -cp "%GRADLE_LIB%\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set BUILD_STATUS=%ERRORLEVEL%
echo.
echo Build completed with exit code: %BUILD_STATUS%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR file created
    dir /b build\libs\LBnew-0.38.1.jar
) else (
    echo FAILURE: JAR not found
    dir build\libs\
)