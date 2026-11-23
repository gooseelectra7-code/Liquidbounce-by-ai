# Try running Gradle standalone (extracted from wrapper distribution)
# This bypasses the wrapper jar entirely

$JavaExe = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
$GradleHome = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1"

Set-Location $ProjectDir

# First, let's see if we can run Gradle directly from its distribution
Write-Host "=== Attempting to run Gradle standalone ==="
Write-Host "Gradle home: $GradleHome"
Write-Host ""

# Check if gradle script exists
if (Test-Path "$GradleHome\bin\gradle") {
    Write-Host "Found gradle script at $GradleHome\bin\gradle"
    
    # Try running it through bash if available
    $BashPath = Get-Command bash -ErrorAction SilentlyContinue
    if ($BashPath) {
        Write-Host "Found bash at $($BashPath.Source)"
        & bash "$GradleHome\bin\gradle" build -x detekt -x test --console=plain
    } else {
        Write-Host "Bash not found, trying direct Java invocation..."
        # The gradle script in bin/ is a shell script, we need to call Java directly
        
        # Build classpath from all jar files in lib/
        $LibDir = "$GradleHome\lib"
        $Jars = Get-ChildItem -Path $LibDir -Filter "*.jar" | ForEach-Object { $_.FullName }
        $CP = $Jars -join ";"
        
        # Main class for Gradle is org.gradle.launcher.GradleMain
        & $JavaExe -Dslogan=false -cp $CP org.gradle.launcher.GradleMain build -x detekt -x test --console=plain
    }
} else {
    Write-Host "ERROR: gradle script not found at expected location"
    Write-Host "Checking if distribution is properly unpacked..."
    Get-ChildItem -Path $GradleHome -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name)" }
}