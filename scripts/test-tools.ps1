#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $scriptTokens = $null
    $scriptErrors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$scriptTokens, [ref]$scriptErrors)
    if ($scriptErrors.Count) {
        throw "Syntaxfehler in $($script.Name): $($scriptErrors.Message -join '; ')"
    }
}
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'build.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Das Buildskript enthält Syntaxfehler.' }
$probeBody = @'
[pscustomobject]@{ MavenPath = $MavenPath; Arguments = @($MavenArguments); Coverage = $Coverage.IsPresent }
'@
$probe = [scriptblock]::Create($ast.ParamBlock.Extent.Text + [Environment]::NewLine + $probeBody)
$legacy = & $probe 'nobump' 'ci'
if ($legacy.MavenPath -ne '' -or $legacy.Arguments.Count -ne 2 -or
    $legacy.Arguments[0] -ne 'nobump' -or $legacy.Arguments[1] -ne 'ci') {
    throw 'Die BAT-Argumente nobump und ci dürfen nicht als Maven-Pfad gebunden werden.'
}
$named = & $probe -MavenPath 'C:\tools\mvn.cmd' -Coverage 'nobump' 'ci'
if ($named.MavenPath -ne 'C:\tools\mvn.cmd' -or !$named.Coverage -or $named.Arguments.Count -ne 2) {
    throw 'MavenPath, Coverage und die übrigen Argumente werden falsch zugeordnet.'
}
$empty = & $probe
if ($empty.MavenPath -ne '' -or $empty.Arguments.Count -ne 0) {
    throw 'Ohne MavenPath muss der Build Maven selbst finden.'
}
$validation = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-CompleteBuildArguments'
}, $true)
if (!$validation) { throw 'Die Prüfung der Maven-Argumente fehlt.' }
. ([scriptblock]::Create($validation.Extent.Text))
Assert-CompleteBuildArguments @('-Dstyle.color=never', '-Pcoverage', '-U')
$blocked = @('-DskipTests', '-Dmaven.test.skip=true', '-Dtest=OneTest', '-Dit.test=OneIT',
    '-Dsurefire.excludes=**/*', '-DfailIfNoTests=false', '-plpaper', '--projects=paper',
    '-N', '--non-recursive', '-rfpaper', '--resume-from=paper', '-fother.xml', 'deploy')
foreach ($argument in $blocked) {
    $failed = $false
    try { Assert-CompleteBuildArguments @($argument) } catch { $failed = $true }
    if (!$failed) { throw "Unvollständiger Build würde zugelassen: $argument" }
}
Write-Host 'PowerShell-Prüfungen bestanden: Parameterbindung und vollständiger Build.'
