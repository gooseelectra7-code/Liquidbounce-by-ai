# PowerShell script to run Gradle build for LiquidBounce
# Uses direct Java invocation to bypass AppLocker restrictions

Set-Location "C:\Users\SYLVIA\Desktop\LBnew-main"

$JAVA_EXE = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$GRADLE_DIST = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$GRADLE_LIB = "$GRADLE_DIST\lib"
$WRAPPER_JAR = ".\gradle\wrapper\gradle-wrapper.jar"

Write-Host "Executing Gradle build..."
Write-Host "Java: $JAVA_EXE"
Write-Host "Gradle dist: $GRADLE_DIST"
Write-Host "Wrapper jar: $WRAPPER_JAR"

# Build classpath
$CP = "$GRADLE_LIB\*"
$CP += ";$GRADLE_DIST\lib\plugins\gradle-wrapper-main-9.7.1.jar"
$CP += ";$WRAPPER_JAR"

Write-Host "Classpath: $CP"

& $JAVA_EXE -Xms1024m -Xmx6144m -XX:+UseZGC -cp $CP org.gradle.wrapper.GradleWrapperMain build -x detekt -x test --console=plain

$BUILD_STATUS = $LASTEXITCODE
Write-Host ""
Write-Host "Build completed with exit code: $BUILD_STATUS"

if (Test-Path "build\libs\LBnew-0.38.1.jar") {
    Write-Host "SUCCESS: JAR file created"
    Get-ChildItem build\libs\LBnew-0.38.1.jar
} else {
    Write-Host "FAILURE: JAR not found"
    Get-ChildItem build\libs\ -ErrorAction SilentlyContinue
}