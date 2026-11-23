@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Use gradle.bat directly instead of gradlew
set GRADLE_EXE=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat

if exist "%GRADLE_EXE%" (
    call "%GRADLE_EXE%" build -x detekt -x test --console=plain
) else if exist "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle" (
    call C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle build -x detekt -x test --console=plain
) else (
    echo Gradle not found at %GRADLE_EXE%
    dir C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\
    exit /b 1
)

endlocal