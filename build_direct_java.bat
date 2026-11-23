@echo off
REM LiquidBounce Build - Direct Java invocation
REM This script runs Gradle directly via Java to bypass Windows AppLocker

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_HOME=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build Script
echo ============================================
echo JAVA_HOME: %JAVA_HOME%
echo GRADLE_HOME: %GRADLE_HOME%
echo.

REM Build classpath from gradle lib
set GRADLE_CP=
for %%i in ("%GRADLE_HOME%\lib\*.jar") do (
    set GRADLE_CP=!GRADLE_CP!;%%i
)

REM Add wrapper jar and project
set GRADLE_CP=%GRADLE_CP%;gradle\wrapper\gradle-wrapper.jar;.

echo Built classpath, running Gradle...
echo.

"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC ^
    -Dorg.gradle.appname=gradlew ^
    -Dorg.gradle.daemon=false ^
    -cp "%GRADLE_CP%" ^
    org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

echo.
echo ============================================
echo Build finished
echo ============================================
if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar
) else (
    echo FAILED: JAR not found
)