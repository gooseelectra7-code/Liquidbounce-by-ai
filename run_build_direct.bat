@echo off
cd /d "C:\Users\SYLVIA\Desktop\LBnew-main"

echo ============================================
echo LiquidBounce Build - Direct Java Invocation
echo ============================================
echo.

REM Use direct Java invocation with -cp pointing to Gradle distribution files
set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar

echo Java: %JAVA_EXE%
echo Gradle dist: %GRADLE_DIST%
echo Wrapper jar: %WRAPPER_JAR%

REM Build classpath: wrapper jar + all gradle lib jars
set CP=%WRAPPER_JAR%;%GRADLE_DIST%\lib\*

echo.
echo Classpath includes:
echo   - %WRAPPER_JAR%
echo   - %GRADLE_DIST%\lib\*
echo.

echo Running Gradle build...
echo ============================================

"%JAVA_EXE%" ^
    -Dorg.gradle.daemon=false ^
    -Dorg.gradle.parallel=false ^
    -Dorg.gradle.caching=false ^
    -Dorg.gradle.configureondemand=false ^
    -Dorg.gradle.workers.max=1 ^
    -Dorg.gradle.vfs.watch=false ^
    -Dorg.gradle.jvmargs="-Xms1024m -Xmx6144m -XX:+UseZGC" ^
    -cp "%CP%" ^
    org.gradle.wrapper.GradleWrapperMain ^
    build -x detekt -x test --console=plain

set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================
echo Build exited with code: %EXITCODE%
echo.

REM Check for output
if exist "build\libs\LBnew-0.38.1.jar" (
    echo SUCCESS: build\libs\LBnew-0.38.1.jar created
    dir build\libs\*.jar
    if exist "build\libs\zip\" (
        echo.
        echo ZIP contents:
        dir build\libs\zip\
    )
) else (
    echo ERROR: Jar not found
    if exist "build\" (
        echo Build directory contents:
        dir /s build\
    )
)

exit /b %EXITCODE%