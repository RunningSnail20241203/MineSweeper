param(
    [string]$SdkRoot,
    [string]$Output,
    [switch]$BumpVersion
)

$ErrorActionPreference = 'Stop'

$required = @('TAPTAP_CLIENT_ID', 'TAPTAP_PRIVACY_POLICY_URL', 'MINESWEEPER_KEYSTORE', 'MINESWEEPER_KEY_ALIAS')
$configKeys = $required + @('TAPTAP_REGION', 'TAPTAP_LEADERBOARD_CLIENT_TOKEN',
    'TAPTAP_LEADERBOARD_API_URL', 'MINESWEEPER_SIGNING_PASSWORD_FILE')
$passwordKeys = @('MINESWEEPER_KEYSTORE_PASSWORD', 'MINESWEEPER_KEY_PASSWORD')
$config = @{}
$configPath = Join-Path $PSScriptRoot 'build\release.env'
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        if (!$line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([A-Z_]+)\s*=(.*)$' -or $matches[1] -notin $configKeys) {
            throw 'Invalid setting in android/build/release.env.'
        }
        $config[$matches[1]] = $matches[2].Trim()
    }
}
foreach ($name in $configKeys + $passwordKeys) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ($value) { $config[$name] = $value }
}
$missing = @($required | Where-Object { !$config[$_] })
if ($missing.Count) { throw ('Missing release settings: ' + ($missing -join ', ')) }
if (!$config.TAPTAP_REGION) { $config.TAPTAP_REGION = 'CN' }
if ($config.TAPTAP_REGION -ne 'CN') { throw 'The TapTap release APK requires TAPTAP_REGION=CN.' }

$releaseKey = (Resolve-Path -LiteralPath ([Environment]::ExpandEnvironmentVariables($config.MINESWEEPER_KEYSTORE))).Path
$needsPassword = @($passwordKeys | Where-Object { !$config[$_] })
if ($needsPassword.Count -and !$config.MINESWEEPER_SIGNING_PASSWORD_FILE) {
    throw 'Set MINESWEEPER_SIGNING_PASSWORD_FILE or supply the release signing passwords in the process environment.'
}

if (!$SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot) {
    $SdkRoot = 'D:\Program Files\UnityEditors\2022.3.62f3\Editor\Data\PlaybackEngines\AndroidPlayer\SDK'
}
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path

$tools = Join-Path $SdkRoot 'build-tools\35.0.0'
$apksigner = Join-Path $tools 'apksigner.bat'
$aapt2 = Join-Path $tools 'aapt2.exe'
foreach ($tool in @($apksigner, $aapt2)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing Android SDK tool: $tool" }
}

$project = Join-Path $PSScriptRoot 'tapsdk-test'
$versionPath = Join-Path $project 'gradle.properties'
$policyVersionMatch = [regex]::Match([IO.File]::ReadAllText($versionPath),
    '(?m)^minesweeperPrivacyPolicyVersion=(?<version>[1-9][0-9]*)(?=\r?$)')
if (!$policyVersionMatch.Success) {
    throw 'Set minesweeperPrivacyPolicyVersion in gradle.properties.'
}
$privacyPolicyVersion = $policyVersionMatch.Groups['version'].Value
$policy = $null
if (![Uri]::TryCreate($config.TAPTAP_PRIVACY_POLICY_URL, [UriKind]::Absolute, [ref]$policy) -or
        $policy.Scheme -ne 'https' -or !$policy.Host) {
    throw 'TAPTAP_PRIVACY_POLICY_URL must be the published HTTPS policy URL.'
}
try {
    $policyPage = Invoke-WebRequest -UseBasicParsing -Uri $policy -TimeoutSec 15
} catch {
    throw "Could not verify the published privacy policy at $policy. $($_.Exception.Message)"
}
$policyVersionPattern = '\u653f\u7b56\u7248\u672c\s*[:\uff1a]\s*' + $privacyPolicyVersion + '(?:\D|$)'
if ($policyPage.StatusCode -ne 200 -or $policyPage.Content -notmatch $policyVersionPattern) {
    throw "Publish the version $privacyPolicyVersion privacy policy before building the release APK."
}

if ($needsPassword.Count) {
    $secretFile = [Environment]::ExpandEnvironmentVariables($config.MINESWEEPER_SIGNING_PASSWORD_FILE)
    $securePassword = Get-Content -LiteralPath $secretFile -Raw | ConvertTo-SecureString
    try {
        foreach ($name in $needsPassword) {
            $config[$name] = [Net.NetworkCredential]::new('', $securePassword).Password
        }
    } finally {
        $securePassword.Dispose()
    }
}

$config.MINESWEEPER_KEYSTORE = $releaseKey
$config.ANDROID_HOME = $SdkRoot
$config.ANDROID_SDK_ROOT = $SdkRoot
$config.Remove('MINESWEEPER_SIGNING_PASSWORD_FILE')
$originalEnvironment = @{}

$buildDirectory = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot 'build'))
$lockPath = Join-Path $buildDirectory.FullName 'android-build.lock'
try {
    $buildLock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch [IO.IOException] {
    throw 'Could not lock the Android build directory. Wait for the other build to finish and retry.'
}

try {
    $versionProperties = [IO.File]::ReadAllText($versionPath)
    $codePattern = '(?m)^minesweeperVersionCode=([1-9][0-9]*)(?=\r?$)'
    $namePattern = '(?m)^minesweeperVersionName=([0-9]+(?:\.[0-9]+)+)(?=\r?$)'
    $codeMatch = [regex]::Matches($versionProperties, $codePattern)
    $nameMatch = [regex]::Matches($versionProperties, $namePattern)
    if ($codeMatch.Count -ne 1 -or $nameMatch.Count -ne 1) {
        throw 'Expected one minesweeperVersionCode and one numeric minesweeperVersionName in gradle.properties.'
    }
    $versionCode = [int]$codeMatch[0].Groups[1].Value
    $versionName = $nameMatch[0].Groups[1].Value
    if ($BumpVersion) {
        if ($versionCode -ge 2100000000) { throw 'Android versionCode has reached its limit.' }
        $versionCode++
        $lastDot = $versionName.LastIndexOf('.')
        $nextPart = [long]$versionName.Substring($lastDot + 1) + 1
        $versionName = $versionName.Substring(0, $lastDot + 1) + $nextPart
    }
    if (!$Output) {
        $versionTag = $versionName.Replace('.', '_')
        $suffix = if ($BumpVersion) { "build$versionCode" } else { 'local' }
        $Output = Join-Path $PSScriptRoot "MineSweeper-Android-$versionTag-$suffix.apk"
    }
    $destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
    if ($BumpVersion) {
        # Reserve both values before Gradle starts so each build attempt uses a new version.
        $updatedProperties = $versionProperties -creplace $codePattern, "minesweeperVersionCode=$versionCode"
        $updatedProperties = $updatedProperties -creplace $namePattern, "minesweeperVersionName=$versionName"
        $temporaryVersionPath = "$versionPath.tmp"
        try {
            [IO.File]::WriteAllText($temporaryVersionPath, $updatedProperties)
            [IO.File]::Replace($temporaryVersionPath, $versionPath, [NullString]::Value)
        } finally {
            [IO.File]::Delete($temporaryVersionPath)
        }
    }

    Write-Host "Building Android $versionName (versionCode $versionCode)"
    Push-Location -LiteralPath $project
    try {
        foreach ($name in $config.Keys) {
            $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
            [Environment]::SetEnvironmentVariable($name, $config[$name])
        }
        & .\gradlew.bat --no-daemon --max-workers=1 "-PminesweeperVersionCode=$versionCode" `
            "-PminesweeperVersionName=$versionName" assembleRelease
        if ($LASTEXITCODE) { throw 'Gradle assembleRelease failed.' }

        $releaseApk = Join-Path $project 'build\outputs\apk\release\tapsdk-test-release.apk'
        $certificate = & $apksigner verify --print-certs $releaseApk
        if ($LASTEXITCODE) { throw 'Release APK signature verification failed.' }
        if (($certificate -join "`n") -match '(?im)^Signer #\d+ certificate DN:\s*CN=Android Debug(?:,|$)') {
            throw 'The release APK must use the dedicated release certificate.'
        }
        $badging = & $aapt2 dump badging $releaseApk
        $expectedVersion = [regex]::Escape($versionName)
        if ($LASTEXITCODE -or !($badging -cmatch "^package: name='com\.YYYAndWZ\.MineSweeper' versionCode='$versionCode' versionName='$expectedVersion'")) {
            throw 'Unexpected release APK package or version.'
        }
        Copy-Item -LiteralPath $releaseApk -Destination $destination -Force
        $certificate | Where-Object { $_ -match 'certificate MD5 digest' }
        Get-Item -LiteralPath $destination
    } finally {
        foreach ($name in $originalEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name])
        }
        Pop-Location
    }
} finally {
    $buildLock.Dispose()
}
