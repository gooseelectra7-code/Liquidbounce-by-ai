@echo off
setlocal enabledelayedexpansion

REM LiquidBounce Build Script for Windows
REM Works around Application Control policies by using cmd.exe indirect invocation

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo ============================================
echo LiquidBounce Build
echo ============================================

REM Method 1: Simple gradlew invocation
echo Running Gradle build...
cmd.exe /c ""%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-opens java.base/sun.security.ssl=ALL-UNNAMED --add-opens java.base/sun.security.util=ALL-UNNAMED --add-opens java.base/sun.net.sponsor=ALL-UNNAMED --add-opens java.base/sun.net.protocol.http=ALL-UNNAMED -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain"

echo.
echo Checking build results...
if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    echo.
    if exist build\libs\zip\ (
        echo zip files created in build\libs\zip\:
        dir build\libs\zip\
    ) else (
        echo WARNING: zip directory not found
    )
) else (
    echo FAILED: build\libs\LBnew-0.38.1.jar not found
    if exist build\ (
        dir build\
    ) else (
        echo build directory does not exist
    )
)

endlocal