# Test if the version format is causing issues
# The issue might be that the format ">=1.21.11 <26.2" isn't valid for Fabric

# Let's check what format is expected
$ProjectDir = "C:\Users\SYLVIA\Desktop\LBnew-main"
Set-Location $ProjectDir

# Check the libs.versions.toml for the minecraft version
Write-Host "Minecraft version from libs.versions.toml:"
Select-String -Path "gradle\libs.versions.toml" -Pattern "minecraft"

Write-Host ""
Write-Host "Current mod_mc_version in gradle.properties:"
Select-String -Path "gradle.properties" -Pattern "mod_mc_version"