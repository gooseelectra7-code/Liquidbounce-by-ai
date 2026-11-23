# LiquidBounce Gradle Build Script (PowerShell)
# Executes Gradle build via Java 25 direct invocation

$ErrorActionPreference = "Stop"

Write-Host "========================================"
Write-Host "LiquidBounce Build Script"
Write-Host "========================================"

# Configuration
$JAVA_EXE = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$GRADLE_HOME = "C:\Users\SYLVIA\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"
$WRAPPER_JAR = "C:\Users\SYLVIA\Desktop\LBnew-main\gradle\wrapper\gradle-wrapper.jar"
$PROJECT_DIR = "C:\Users\SYLVIA\Desktop\LBnew-main"

# Change to project directory
Push-Location $PROJECT_DIR

try {
    Write-Host "Project: $PROJECT_DIR"
    Write-Host "Java: $JAVA_EXE"
    Write-Host "Gradle: $GRADLE_HOME"
    Write-Host ""

    # Verify Java exists
    if (-not (Test-Path $JAVA_EXE)) {
        Write-Error "Java 25 not found at $JAVA_EXE"
        exit 1
    }
    Write-Host "Java found: OK"

    # Verify Gradle exists
    if (-not (Test-Path $GRADLE_HOME)) {
        Write-Error "Gradle distribution not found at $GRADLE_HOME"
        exit 1
    }
    Write-Host "Gradle found: OK"

    # Verify wrapper jar exists
    if (-not (Test-Path $WRAPPER_JAR)) {
        Write-Error "Wrapper JAR not found at $WRAPPER_JAR"
        exit 1
    }
    Write-Host "Gradle wrapper JAR found: OK"
    Write-Host ""

    # Check lib directories
    $libDir = Join-Path $GRADLE_HOME "lib"
    
    if (-not (Test-Path $libDir)) {
        Write-Error "Gradle lib directory not found: $libDir"
        exit 1
    }
    
    if (-not (Test-Path "$GRADLE_HOME\lib\plugins")) {
        Write-Error "Gradle plugins directory not found: $GRADLE_HOME\lib\plugins"
        exit 1
    }
    Write-Host "Gradle lib directories found: OK"
    Write-Host ""

    # Build classpath
    $libJars = Get-ChildItem -Path $libDir -Filter "*.jar" | ForEach-Object { $_.FullName }
    $pluginJars = Get-ChildItem -Path "$GRADLE_HOME\lib\plugins" -Filter "*.jar" | ForEach-Object { $_.FullName }
    
    $CLASSPATH = ($libJars + $pluginJars + $WRAPPER_JAR) -join ";"
    
    Write-Host "Running Gradle build..."
    Write-Host "Command: build -x detekt -x test"
    Write-Host ""

    # Start process to run Java
    $javaArgs = @(
        "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
        "-cp", $CLASSPATH,
        "org.gradle.wrapper.GradleWrapperMain",
        "build", "-x", "detekt", "-x", "test",
        "--console=plain"
    )

    $process = Start-Process -FilePath $JAVA_EXE -ArgumentList $javaArgs -NoNewWindow -Wait -PassThru
    
    $exitCode = $process.ExitCode

    Write-Host ""
    Write-Host "========================================"
    Write-Host "Build Complete"
    Write-Host "========================================"
    Write-Host "Exit Code: $exitCode"
    Write-Host ""

    $jarPath = Join-Path $PROJECT_DIR "build\libs\LBnew-0.38.1.jar"
    if (Test-Path $jarPath) {
        Write-Host "SUCCESS: Build output created!"
        Write-Host ""
        Write-Host "JAR File: build\libs\LBnew-0.38.1.jar"
        $jarInfo = Get-Item $jarPath
        Write-Host "Size: $($jarInfo.Length) bytes"
        Write-Host ""
        Write-Host "All JARs in build\libs:"
        Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\libs") -Filter "*.jar" | ForEach-Object {
            Write-Host "  $($_.Name) - $($_.Length) bytes"
        }
    } else {
        Write-Host "Status: JAR not found"
        Write-Host "Check the output above for errors."
        
        $buildDir = Join-Path $PROJECT_DIR "build"
        if (Test-Path $buildDir) {
            Write-Host ""
            Write-Host "Build directory contents:"
            Get-ChildItem -Path $buildDir -Recurse | Select-Object -First 20 FullName
        }
    }

    exit $exitCode

} finally {
    Pop-Location
}