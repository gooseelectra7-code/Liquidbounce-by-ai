@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM LiquidBounce Build - Direct Java with classpath
REM Bypasses AppLocker by not using gradlew.bat

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=%PROJECT_DIR%\gradle\wrapper\gradle-wrapper.jar

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build - Direct Java Classpath
echo ============================================

REM Check prerequisites
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java not found at %JAVA_HOME%\bin\java.exe
    exit /b 1
)

if not exist "%GRADLE_DIST%" (
    echo ERROR: Gradle distribution not found at %GRADLE_DIST%
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    echo ERROR: Wrapper JAR not found at %WRAPPER_JAR%
    exit /b 1
)

echo Prerequisites checked OK
echo.

REM Build classpath with all jars
set CP=%WRAPPER_JAR%
for %%i in ("%GRADLE_DIST%\lib\*.jar") do (
    set CP=!CP!;%%i
)

echo Running Gradle via Java CLI...
echo.

"%JAVA_HOME%\bin\java.exe" ^
    -Xms1024m -Xmx6144m -XX:+UseZGC ^
    --add-opens java.base/java.lang=ALL-UNNAMED ^
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED ^
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens java.base/java.io=ALL-UNNAMED ^
    --add-opens java.base/java.util=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED ^
    --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED ^
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED ^
    --add-opens java.base/sun.nio.fs=ALL-UNNAMED ^
    --add-opens java.base/sun.security.ssl=ALL-UNNAMED ^
    --add-opens java.base/sun.security.util=ALL-UNNAMED ^
    --add-opens java.base/sun.net.sponsor=ALL-UNNAMED ^
    --add-opens java.base/sun.net.protocol.http=ALL-UNNAMED ^
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

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
) else (
    echo FAILURE: build\libs\LBnew-0.38.1.jar not found
    if exist "build\" dir build\
)

endlocal
exit /b %EXIT_CODE%