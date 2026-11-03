param([Parameter(Mandatory=$true)][string] $LocalFile, [Parameter(Mandatory=$true)][string] $Destination, [string] $ServerHost = '127.0.0.1', [int] $Port = 49000, [string] $CheckpointDirectory = 'runtime/client')
$ErrorActionPreference = 'Stop'
$LocalFile = (Resolve-Path -LiteralPath $LocalFile).Path
$Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
$projectDir = $PSScriptRoot
$cacheDir = Join-Path $projectDir '.m2/repository'
$jars = @((Join-Path $projectDir 'source/client/target/client-0.1.0-SNAPSHOT.jar'), (Join-Path $projectDir 'source/common/target/common-0.1.0-SNAPSHOT.jar'))
foreach ($jar in $jars) { if (!(Test-Path -LiteralPath $jar)) { throw 'Run ./build.ps1 package first.' } }
$jacksonDir = Join-Path $cacheDir 'com/fasterxml/jackson/core'
foreach ($artifact in @('jackson-databind','jackson-core','jackson-annotations')) {
    $jar = Join-Path $jacksonDir "$artifact/2.18.3/$artifact-2.18.3.jar"
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing dependency: $jar. Run ./build.ps1 package." }
    $jars += $jar
}
Push-Location -LiteralPath $projectDir
try {
    # Relative paths avoid Windows command-line encoding problems with accented folders.
    $relativeJars = @('source/client/target/client-0.1.0-SNAPSHOT.jar', 'source/common/target/common-0.1.0-SNAPSHOT.jar')
    foreach ($artifact in @('jackson-databind','jackson-core','jackson-annotations')) {
        $relativeJars += ".m2/repository/com/fasterxml/jackson/core/$artifact/2.18.3/$artifact-2.18.3.jar"
    }
    . (Join-Path $projectDir 'scripts/java-launch.ps1')
    Invoke-LanJava -ClassPath ($relativeJars -join [IO.Path]::PathSeparator) -MainClass 'vn.edu.lanfileshare.client.ResumeDemoMain' -ApplicationArguments @($LocalFile, $Destination, $ServerHost, [string] $Port, $CheckpointDirectory)
    exit $script:LanJavaExitCode
}
finally { Pop-Location }
