@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

echo Java home: %JAVA_HOME%
echo Java exists: 
if exist "%JAVA_HOME%\bin\java.exe" (
    echo YES - java.exe found
) else (
    echo NO - java.exe not found
    exit /b 1
)

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

echo Running Gradle wrapper...
"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC -jar gradle\wrapper\gradle-wrapper.jar build -x detekt -x test --console=plain

echo Exit code: %ERRORLEVEL%

if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: JAR created
) else (
    echo FAILURE: JAR not found
)

endlocal