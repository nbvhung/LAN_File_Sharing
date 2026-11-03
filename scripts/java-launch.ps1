# ASCII transport for local CLI arguments; preserves Unicode through Windows Java launchers.
function Invoke-LanJava {
    param([string] $ClassPath, [string] $MainClass, [string[]] $ApplicationArguments)
    $encoded = foreach ($argument in $ApplicationArguments) {
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($argument))
    }
    & java -cp $ClassPath $MainClass '--base64-args' @encoded
    $script:LanJavaExitCode = $LASTEXITCODE
}
