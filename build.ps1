param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs
)

$ErrorActionPreference = 'Stop'
$sourceDir = Join-Path $PSScriptRoot 'source'
Push-Location -LiteralPath $sourceDir
try {
    & mvn '-Dmaven.repo.local=../.m2/repository' @MavenArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
