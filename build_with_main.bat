@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Build classpath from local Gradle installation
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

REM Use java to run Gradle with all required JARs (using wildcard for lib, full path for wrapper plugins)
"%JAVA_HOME%\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC ^
  -cp "%GRADLE_DIST%\lib\*;%GRADLE_DIST%\lib\plugins\gradle-wrapper-main-9.7.1.jar;." ^
  org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

endlocal