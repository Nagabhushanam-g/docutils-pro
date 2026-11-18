# Run locally in Windows PowerShell with Java keytool installed.
# Creates an upload key for Google Play. Do not upload the private key to GitHub source.
# IMPORTANT: An existing upload key must never be overwritten or regenerated
# after it has been registered with Google Play.
param(
    [string]$OutputDirectory = "."
)

$ErrorActionPreference = "Stop"
$alias = "docutils_upload"
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "Java keytool not found. Install JDK 17 and reopen PowerShell."
}

$folder = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$keystore = Join-Path $folder "DocUtils_PlayUpload.jks"
$b64File = Join-Path $folder "DocUtils_Upload_Base64_DO_NOT_SHARE.txt"
$certTemp = Join-Path $folder "DocUtils_Upload_Certificate_TEMP.der"

if (Test-Path $keystore) {
    throw "A Play upload keystore already exists at $keystore. Refusing to overwrite it. Reuse the original key for all releases."
}
if ((Test-Path $b64File) -or (Test-Path $certTemp)) {
    throw "An output file already exists. Move or remove old temporary output files after reviewing their contents."
}

Write-Host "Generating the DocUtils Pro Google Play upload signing key." -ForegroundColor Cyan
Write-Host "You will be prompted by Java keytool to enter a strong password and certificate identity."
Write-Host "Store the password securely. For PKCS12, use the same password for both the keystore and key."
Write-Host "This key is DIFFERENT from the existing permanent debug APK key."
& keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias $alias -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) {
    throw "Key generation did not succeed. Inspect and remove a partial keystore only if you are sure no registered key exists."
}

try {
    & keytool -exportcert -keystore $keystore -storetype PKCS12 -alias $alias -file $certTemp
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $certTemp)) {
        throw "Could not export the public certificate. Check the keystore password and alias."
    }
    $sha = (Get-FileHash -Path $certTemp -Algorithm SHA256).Hash.ToLowerInvariant()
} finally {
    if (Test-Path $certTemp) { Remove-Item $certTemp -Force }
}

if ($sha -eq "518360924041db59d45ca761cf4babaf484dc978756d8f5fd7446d27872af85b") {
    throw "The upload certificate matches the test/debug signing key, which must not be used for Google Play."
}

[System.IO.File]::WriteAllText($b64File, [System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystore)))

Write-Host ""
Write-Host "Upload key created. Back it up in a secure place OFF your computer." -ForegroundColor Green
Write-Host "Keystore file: $keystore"
Write-Host "Base64 file for encrypted GitHub secret: $b64File"
Write-Host "Certificate SHA-256: $sha"
Write-Host ""
Write-Host "Set these GitHub Actions settings for Nagabhushanam-g/docutils-pro:"
Write-Host "  SECRET DOCUTILS_RELEASE_KEYSTORE_BASE64  <- exact contents of the Base64 file"
Write-Host "  SECRET DOCUTILS_RELEASE_STORE_PASSWORD   <- password entered in keytool"
Write-Host "  SECRET DOCUTILS_RELEASE_KEY_ALIAS        <- docutils_upload"
Write-Host "  SECRET DOCUTILS_RELEASE_KEY_PASSWORD     <- same PKCS12 password"
Write-Host "  VARIABLE DOCUTILS_RELEASE_UPLOAD_CERT_SHA256 <- $sha"
Write-Host ""
Write-Host "Do not share the private keystore, Base64 text or passwords in a chat, screenshot, or Git repository."
Write-Host "The Base64 file is NOT ENCRYPTED. Delete it when GitHub secrets are saved. Keep the .jks file secure."
Write-Host "Then run GitHub Actions > Play Release Preflight to check this key before any release build."
