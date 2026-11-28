#Requires -Version 5.1
[CmdletBinding()]
param(
    [Alias('Base')][string]$Version = '',
    [ValidateSet('', 'patch', 'minor')][string]$Increment = ''
)
$ErrorActionPreference = 'Stop'
$python = Get-Command python -ErrorAction SilentlyContinue
if (!$python) { $python = Get-Command python3 -ErrorAction Stop }
$arguments = @((Join-Path $PSScriptRoot 'set-version.py'))
if ($Version) { $arguments += @('--version', $Version) }
if ($Increment) { $arguments += @('--increment', $Increment) }
& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw 'Version update failed.' }
