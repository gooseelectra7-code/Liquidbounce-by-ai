@echo off
setlocal
"C:\Program Files\Java\jdk-25.0.4\bin\java.exe" -Xms1024m -Xmx6144m -XX:+UseZGC -jar "C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar" build -x detekt -x test --console=plain
endlocal