@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Use local Gradle installation from wrapper distribution
set GRADLE_HOME=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

REM Run Gradle via direct invocation
"%JAVA_HOME%\bin\java.exe" -cp "%GRADLE_HOME%\lib\*" org.gradle.launcher.GradleMain build -x detekt -x test --console=plain

endlocal