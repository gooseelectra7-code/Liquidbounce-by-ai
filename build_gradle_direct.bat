@echo off
cd /d "C:\Users\SYLVIA\Desktop\LBnew-main"

echo Using Java from:
"C:\Program Files\Java\jdk-25.0.4\bin\java.exe" -version
echo.

echo Running Gradle build...
"C:\Program Files\Java\jdk-25.0.4\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED --add-opens java.base/sun.security.ssl=ALL-UNNAMED --add-opens java.base/sun.security.util=ALL-UNNAMED --add-opens java.base/sun.net.sponsor=ALL-UNNAMED --add-opens java.base/sun.net.protocol.http=ALL-UNNAMED -jar "gradle\wrapper\gradle-wrapper.jar" build -x detekt -x test --console=plain

echo.
echo Exit code: %ERRORLEVEL%

echo.
echo Checking build output...
dir build\libs\*.jar 2>nul
if errorlevel 1 echo No JAR files found in build\libs\

pause