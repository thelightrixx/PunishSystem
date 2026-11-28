#Requires -Version 5.1
<#
.SYNOPSIS
    Verified PunishSystem-style build; never changes a version implicitly.
#>
[CmdletBinding(PositionalBinding=$false)]
param(
    [string]$MavenPath = '',
    [switch]$Offline,
    [switch]$Coverage,
    [Parameter(Position=0, ValueFromRemainingArguments=$true)][string[]]$MavenArguments = @()
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJava = $env:JAVA_HOME
$previousUtf8 = $env:PYTHONUTF8
Push-Location -LiteralPath $projectRoot
try {
    $MavenArguments = @($MavenArguments | Where-Object { $_ -notmatch '^(nobump|no-bump|ci|--ci)$' })
    foreach ($argument in $MavenArguments) {
        if ($argument -match '(?i)skiptests|maven\.test\.skip|^(-pl|--projects|-f|--file|--settings|-s)$|^(-pl|-f|-s).+') {
            throw 'The verified build must include every module and all tests. Use Maven directly for partial development builds.'
        }
    }
    $java = Get-Command java -ErrorAction Stop
    if ($env:JAVA_HOME) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { $javaExecutable = $java.Source }
    $javaHome = Split-Path -Parent (Split-Path -Parent $javaExecutable)
    if (!(Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe'))) { throw 'A full JDK is required.' }
    $javaVersion = (& $javaExecutable --version | Out-String)
    if ($javaVersion -notmatch '(?:openjdk|java) (?<major>\d+)' -or [int]$Matches.major -lt 25) {
        throw 'JDK 25 or newer is required; production bytecode targets Java 21.'
    }
    $env:JAVA_HOME = $javaHome
    $env:PYTHONUTF8 = '1'
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (!$python) { $python = Get-Command python3 -ErrorAction Stop }
    & $python.Source -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 or newer is required.' }
    if (!$MavenPath) {
        $maven = Get-Command mvn -ErrorAction SilentlyContinue
        if ($maven) { $MavenPath = $maven.Source }
    }
    if (!$MavenPath) {
        $mavenVersion = '3.9.11'
        $tools = Join-Path $projectRoot '.tools'
        $MavenPath = Join-Path $tools "apache-maven-$mavenVersion/bin/mvn.cmd"
        if (!(Test-Path -LiteralPath $MavenPath)) {
            if ($Offline) { throw 'Maven is unavailable in offline mode.' }
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            $archive = Join-Path $tools "apache-maven-$mavenVersion-bin.zip"
            $sha512 = '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076'
            Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" -OutFile $archive -TimeoutSec 120
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ine $sha512) { throw 'Maven SHA-512 checksum mismatch.' }
            Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
        }
    }
    & $python.Source scripts/check-standard.py
    if ($LASTEXITCODE -ne 0) { throw 'Shared project standard check failed.' }
    & (Join-Path $PSScriptRoot 'test-tools.ps1')
    & $python.Source scripts/check-version.py
    if ($LASTEXITCODE -ne 0) { throw 'Version consistency check failed.' }
    & $python.Source scripts/check-docs.py
    if ($LASTEXITCODE -ne 0) { throw 'Offline handbook check failed.' }
    & $python.Source -m unittest discover -s scripts -p test_project_tools.py
    if ($LASTEXITCODE -ne 0) { throw 'Project tooling tests failed.' }
    $arguments = @('-B', '-ntp')
    if ($Offline) { $arguments += '-o' }
    if ($Coverage) { $arguments += '-Pcoverage' }
    $arguments += @('clean', 'verify')
    $arguments += $MavenArguments
    & $MavenPath @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    & $python.Source scripts/prepare-release.py
    if ($LASTEXITCODE -ne 0) { throw 'Release artifact verification failed.' }
    Write-Host 'Verified JARs, release notes and SHA256SUMS.txt are in dist/.'
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:PYTHONUTF8 = $previousUtf8
}
