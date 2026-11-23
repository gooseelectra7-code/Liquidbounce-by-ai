@echo off
setlocal

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4

cd /d C:\Users\SYLVIA\Desktop\LBnew-main

REM Use cmd.exe to run gradle.bat which works around execution policies
cmd.exe /c "call ""C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat"" build -x detekt -x test --console=plain"

endlocal