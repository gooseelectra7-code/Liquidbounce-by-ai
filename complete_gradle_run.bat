@echo off
cd /d "C:\Users\SYLVIA\Desktop\LBnew-main"

echo ============================================
echo Complete Gradle Build - Testing All Approaches
echo ============================================
echo.

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1

REM Check Java version
echo Java Version:
"%JAVA_EXE%" -version
echo.

REM Check Gradle distribution
echo Checking Gradle distribution...
if exist "%GRADLE_DIST%" (
    echo Gradle distribution found at %GRADLE_DIST%
) else (
    echo ERROR: Gradle distribution not found
    exit /b 1
)
echo.

REM Build the full classpath
set CP=%WRAPPER_JAR%
for %%i in ("%GRADLE_DIST%\lib\*.jar") do set CP=!CP!;%%i

echo Classpath:
echo   %WRAPPER_JAR%
echo   [all jars in %GRADLE_DIST%\lib\]
echo.

echo ============================================
echo Approach 1: GradleWrapperMain (standard)
echo ============================================
"%JAVA_EXE%" ^
    -Dorg.gradle.daemon=false ^
    -Dorg.gradle.parallel=false ^
    -Dorg.gradle.caching=false ^
    -Dorg.gradle.workers.max=1 ^
    -Dorg.gradle.jvmargs="-Xms1024m -Xmx6144m -XX:+UseZGC" ^
    -cp "%CP%" ^
    org.gradle.wrapper.GradleWrapperMain ^
    build -x detekt -x test --console=plain

set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build completed with exit code: %EXITCODE%
echo ============================================

REM Check for output
echo.
echo Checking build output...
dir build\libs\*.jar 2>nul
if errorlevel 1 (
    echo No JAR files found in build\libs\
    if exist build\ (
        echo Build directory contents:
        dir build\
    ) else (
        echo No build directory created
    )
)

exit /b %EXITCODE%