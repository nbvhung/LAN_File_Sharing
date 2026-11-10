param(
    [switch] $Offline,
    [switch] $SkipTests,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs
)

$ErrorActionPreference = 'Stop'
$sourceDir = Join-Path $PSScriptRoot 'source'
$mavenCommand = Get-Command mvn -ErrorAction Stop
$options = @('-Dmaven.repo.local=../.m2/repository')
if ($Offline) { $options += '-o' }
if ($SkipTests) { $options += '-DskipTests' }
Push-Location -LiteralPath $sourceDir
try {
    # Java log messages on stderr must not turn a successful Maven process into a script failure.
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $mavenCommand.Source @options @MavenArgs
        $mavenExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    exit $mavenExitCode
}
finally {
    Pop-Location
}
