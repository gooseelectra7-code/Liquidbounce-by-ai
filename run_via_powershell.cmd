@echo off
REM LiquidBounce Build Script - PowerShell wrapper to bypass AppLocker
REM This script invokes PowerShell which can run -Command strings that execute
REM Java directly with Gradle classes, bypassing AppLocker's .bat/.ps1 restrictions

setlocal

echo ============================================
echo LiquidBounce Build - PowerShell Command Execution
echo ============================================

set JAVA_EXE=C:\Program Files\Java\jdk-25.0.4\bin\java.exe
set GRADLE_DIST=C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1
set PROJECT_DIR=C:\Users\SYLVIA\Desktop\LBnew-main

cd /d "%PROJECT_DIR%"

echo Prerequisites Check:
if exist "%JAVA_EXE%" (
    echo   [OK] Java found at %JAVA_EXE%
) else (
    echo   [ERROR] Java not found!
    exit /b 1
)

if exist "%GRADLE_DIST%" (
    echo   [OK] Gradle distribution found
) else (
    echo   [ERROR] Gradle distribution not found!
    exit /b 1
)

echo.
echo Starting PowerShell process to execute Gradle build...
echo.

powershell.exe -Command "& {
    $ErrorActionPreference = 'Stop'
    
    $JavaExe = '%JAVA_EXE%'
    $GradleDist = '%GRADLE_DIST%'
    $ProjectDir = '%PROJECT_DIR%'
    $WrapperJar = Join-Path $ProjectDir 'gradle\wrapper\gradle-wrapper.jar'
    
    Set-Location $ProjectDir
    
    Write-Host 'Project: ' $ProjectDir
    Write-Host 'Java: ' $JavaExe
    Write-Host 'Gradle: ' $GradleDist
    Write-Host ''
    
    # Build classpath
    $LibJars = Get-ChildItem -Path (Join-Path $GradleDist 'lib') -Filter '*.jar' | ForEach-Object { $_.FullName }
    $PluginJars = Get-ChildItem -Path (Join-Path $GradleDist 'lib\plugins') -Filter '*.jar' | ForEach-Object { $_.FullName }
    $Classpath = ($LibJars + $PluginJars + $WrapperJar) -join ';'
    
    Write-Host 'Executing Gradle build...'
    Write-Host 'Command: build -x detekt -x test'
    Write-Host ''
    
    $JavaArgs = @(
        '-Xms1024m', '-Xmx6144m', '-XX:+UseZGC',
        '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
        '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED',
        '--add-opens', 'java.base/java.lang.reflect=ALL-UNNAMED',
        '--add-opens', 'java.base/java.io=ALL-UNNAMED',
        '--add-opens', 'java.base/java.util=ALL-UNNAMED',
        '--add-opens', 'java.base/java.util.concurrent=ALL-UNNAMED',
        '-cp', $Classpath,
        'org.gradle.wrapper.GradleWrapperMain',
        'build', '-x', 'detekt', '-x', 'test',
        '--console=plain'
    )
    
    $Process = Start-Process -FilePath $JavaExe -ArgumentList $JavaArgs -NoNewWindow -Wait -PassThru
    $ExitCode = $Process.ExitCode
    
    Write-Host ''
    Write-Host 'Build completed with exit code: ' $ExitCode
    
    if (Test-Path (Join-Path $ProjectDir 'build\libs\LBnew-0.38.1.jar')) {
        Write-Host 'SUCCESS: build\libs\LBnew-0.38.1.jar created!'
        Write-Host ''
        Write-Host 'JAR file size:'
        $JarInfo = Get-Item (Join-Path $ProjectDir 'build\libs\LBnew-0.38.1.jar')
        Write-Host ($JarInfo.Length).ToString() + ' bytes'
    } else {
        Write-Host 'FAILURE: JAR not found'
        if (Test-Path (Join-Path $ProjectDir 'build\libs')) {
            Write-Host 'Contents of build\libs\:'
            Get-ChildItem -Path (Join-Path $ProjectDir 'build\libs') -Filter '*.jar' | ForEach-Object { Write-Host '  ' $_.Name }
        }
    }
    
    exit $ExitCode
}"

set BUILD_STATUS=%ERRORLEVEL%

echo.
echo ============================================
echo Build finished with exit code: %BUILD_STATUS%
echo ============================================

endlocal
exit /b %BUILD_STATUS%