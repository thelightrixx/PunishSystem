#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'build.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Build script has syntax errors.' }
$probeBody = @'
[pscustomobject]@{ MavenPath = $MavenPath; Arguments = @($MavenArguments); Coverage = $Coverage.IsPresent }
'@
$probe = [scriptblock]::Create($ast.ParamBlock.Extent.Text + [Environment]::NewLine + $probeBody)
$legacy = & $probe 'nobump' 'ci'
if ($legacy.MavenPath -ne '' -or $legacy.Arguments.Count -ne 2 -or
    $legacy.Arguments[0] -ne 'nobump' -or $legacy.Arguments[1] -ne 'ci') {
    throw 'Legacy BAT arguments must not bind to MavenPath.'
}
$named = & $probe -MavenPath 'C:\tools\mvn.cmd' -Coverage 'nobump' 'ci'
if ($named.MavenPath -ne 'C:\tools\mvn.cmd' -or !$named.Coverage -or $named.Arguments.Count -ne 2) {
    throw 'Named MavenPath/Coverage and remaining arguments must stay independent.'
}
$empty = & $probe
if ($empty.MavenPath -ne '' -or $empty.Arguments.Count -ne 0) {
    throw 'The default build must select Maven automatically.'
}
Write-Host 'PowerShell build argument regression checks passed (3 cases).'
