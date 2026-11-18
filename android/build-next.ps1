param(
    [string]$SdkRoot,
    [string]$Output
)

& (Join-Path $PSScriptRoot 'build.ps1') @PSBoundParameters -BumpVersion
