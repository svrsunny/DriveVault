# Builds the signed release APK (android\app\build\outputs\apk\release\app-release.apk).
# Asks for the keystore password; it is not echoed or stored, and is cleared when the build ends.
#   powershell -ExecutionPolicy Bypass -File android\build-release.ps1 [-Keystore path] [-Alias drivevault]
param(
    [string]$Keystore = "$PSScriptRoot\signing\release.jks",
    [string]$Alias = "drivevault"
)
if (-not (Test-Path $Keystore)) { Write-Host "Keystore not found: $Keystore (see docs/RELEASING.md)" -ForegroundColor Red; exit 1 }
$secure = Read-Host "Password for $(Split-Path $Keystore -Leaf)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:DV_KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:DV_KEYSTORE = (Resolve-Path $Keystore).Path
    $env:DV_KEY_ALIAS = $Alias
    # --no-daemon: the password must not stay in a background Gradle process
    & "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot ":app:assembleRelease" --no-daemon --console=plain
    $code = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Remove-Item Env:DV_KS_PASS, Env:DV_KEYSTORE, Env:DV_KEY_ALIAS -ErrorAction SilentlyContinue
}
if ($code -eq 0) { Write-Host "Signed APK: $PSScriptRoot\app\build\outputs\apk\release\app-release.apk" -ForegroundColor Green }
exit $code
