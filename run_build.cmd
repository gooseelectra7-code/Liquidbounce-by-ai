@echo off
cd /d C:\Users\SYLVIA\Desktop\LBnew-main

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%CD%\gradle\wrapper\gradle-wrapper.jar

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================
echo Project: %CD%
echo Java: %JAVA_EXE%
echo Gradle: %GRADLE_DIST%
echo.

echo Prerequisites Check:
if exist "%JAVA_EXE%" (
    echo   [OK] Java found
) else (
    echo   [ERROR] Java not found
    exit /b 1
)

if exist "%GRADLE_DIST%" (
    echo   [OK] Gradle distribution found
) else (
    echo   [ERROR] Gradle distribution not found
    exit /b 1
)

if exist "%WRAPPER_JAR%" (
    echo   [OK] Wrapper JAR found
) else (
    echo   [ERROR] Wrapper JAR not found
    exit /b 1
)

echo.
echo Starting Gradle build via GradleWrapperMain...
echo Command: build -x detekt -x test --console=plain
echo.

"%JAVA_EXE%" -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    -cp "%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;%WRAPPER_JAR%" ^
    org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

set BUILD_STATUS=%ERRORLEVEL%
echo.
echo Build completed with exit code: %BUILD_STATUS%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR file created
    dir /b build\libs\LBnew-0.38.1.jar
) else (
    echo FAILURE: JAR not found
    if exist "build\libs\" (
        echo Contents of build\libs\:
        dir /b build\libs\
    )
)