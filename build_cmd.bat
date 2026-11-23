@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM List all JAR files in gradle lib to build classpath
set CP=
for /f "delims=" %%i in ('dir /b /s "gradle\wrapper\*" 2^>nul ^| findstr /i "\.jar$"') do (
    set CP=!CP!%%i;
)

echo Gradle wrapper classpath: !CP!

echo Running Gradle build...
"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-opens java.base/sun.security.ssl=ALL-UNNAMED --add-opens java.base/sun.security.util=ALL-UNNAMED --add-opens java.base/sun.net.sponsor=ALL-UNNAMED --add-opens java.base/sun.net.protocol.http=ALL-UNNAMED -cp "!CP!" org.gradle.launcher.GradleWrapperMain build -x detekt -x test --console=plain

endlocal