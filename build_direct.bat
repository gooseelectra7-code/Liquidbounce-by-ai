@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Build classpath from gradle installation
set CP=%GRADLE_HOME%\lib\*

REM Run Gradle with proper classpath
"%JAVA_HOME%\bin\java.exe" -cp "%CP%" org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

endlocal