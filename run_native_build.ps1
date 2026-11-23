# Native PowerShell script that mimics gradlew behavior
# Uses JAVA_HOME if set, otherwise finds Java in common locations

$ErrorActionPreference = "Continue"

# Find Java
$JavaExe = $env:JAVA_HOME
if ($JavaExe -and $JavaExe -ne "") {
    $JavaExe = "$JavaExe\bin\java.exe"
} else {
    # Try common locations
    $PossibleJavas = @(
        "C:\Program Files\Java\jdk-25.0.4\bin\java.exe",
        "C:\Program Files\Java\jdk-21\bin\java.exe",
        "C:\Program Files\Microsoft JDK-21.0.0\bin\java.exe",
        "C:\Program Files\Amazon Coridon\jdk-21\bin\java.exe",
        "C:\Program Files\Amazon Corretto\jdk-21\bin\java.exe"
    )
    foreach ($path in $PossibleJavas) {
        if (Test-Path $path) {
            $JavaExe = $path
            break
        }
    }
}

if (-not $JavaExe -or -not (Test-Path $JavaExe)) {
    Write-Host "ERROR: Java not found. Please set JAVA_HOME or install Java 21+."
    exit 1
}

Write-Host "Using Java: $JavaExe"
Write-Host "Java version:"
& $JavaExe -version 2>&1

# Set up paths
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
Set-Location $ProjectDir

# Default JVM options
$DEFAULT_JVM_OPTS = "-Xms1024m", "-Xmx6144m", "-XX:+UseZGC"

# App name
$APP_BASE_NAME = "gradlew"

# Gradle wrapper JAR path
$GRADLE_WRAPPER_JAR = "$ProjectDir\gradle\wrapper\gradle-wrapper.jar"

# Check gradle wrapper jar
if (-not (Test-Path $GRADLE_WRAPPER_JAR)) {
    Write-Host "ERROR: Gradle wrapper JAR not found at $GRADLE_WRAPPER_JAR"
    exit 1
}

Write-Host "`nRunning Gradle build..."
Write-Host "========================================"

# Build the full command like gradlew does
$JavaArgs = @(
    $DEFAULT_JVM_OPTS
    "-Dorg.gradle.appname=$APP_BASE_NAME"
    "-jar"
    "`"$GRADLE_WRAPPER_JAR`""
) + $args

Write-Host "Java args: $($JavaArgs -join ' ')"

# Execute Java
& $JavaExe @JavaArgs

$ExitCode = $LASTEXITCODE
Write-Host "`n========================================"
Write-Host "Gradle exit code: $ExitCode"

# Check for JAR files
$JarFiles = Get-ChildItem -Path "$ProjectDir\build\libs" -Filter "*.jar" -File -ErrorAction SilentlyContinue
if ($JarFiles) {
    Write-Host "SUCCESS: JARs created:"
    $JarFiles | ForEach-Object { Write-Host "  $($_.FullName)" }
} else {
    Write-Host "No JARs found in build\libs"
}

exit $ExitCode