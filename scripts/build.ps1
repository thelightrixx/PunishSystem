#Requires -Version 5.1
<#
.SYNOPSIS
    Baut und prüft alle Module. Die Versionsnummer bleibt unverändert.
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
function Assert-CompleteBuildArguments {
    param([string[]]$Arguments)
    foreach ($argument in $Arguments) {
        if ($argument -match '(?i)^-D.*(skip|surefire|failsafe|maven\.test|failIfNoTests)|^-D(test|it\.test)=' -or
            $argument -cnotmatch '^(-D[^=\s]+(?:=.*)?|-P[^\s]+|-U|-o|-e|-X|--update-snapshots|--offline|--errors|--debug)$') {
            throw "Dieser Build prüft alle Module und Tests. Für Teilbuilds oder eigene Maven-Ziele Maven direkt aufrufen. Unzulässiges Argument: $argument"
        }
    }
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJava = $env:JAVA_HOME
$previousUtf8 = $env:PYTHONUTF8
Push-Location -LiteralPath $projectRoot
try {
    $MavenArguments = @($MavenArguments | Where-Object { $_ -notmatch '^(nobump|no-bump|ci|--ci)$' })
    Assert-CompleteBuildArguments $MavenArguments
    $java = Get-Command java -ErrorAction Stop
    if ($env:JAVA_HOME) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { $javaExecutable = $java.Source }
    $javaHome = Split-Path -Parent (Split-Path -Parent $javaExecutable)
    if (!(Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe'))) { throw 'JAVA_HOME muss auf ein vollständiges JDK zeigen.' }
    $javaVersion = (& $javaExecutable --version | Out-String)
    if ($javaVersion -notmatch '(?:openjdk|java) (?<major>\d+)' -or [int]$Matches.major -lt 25) {
        throw 'Zum Bauen ist JDK 25 oder neuer nötig. Der erzeugte Bytecode bleibt mit Java 21 kompatibel.'
    }
    $env:JAVA_HOME = $javaHome
    $env:PYTHONUTF8 = '1'
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (!$python) { $python = Get-Command python3 -ErrorAction Stop }
    & $python.Source -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 oder neuer fehlt.' }
    if (!$MavenPath) {
        $maven = Get-Command mvn -ErrorAction SilentlyContinue
        if ($maven) { $MavenPath = $maven.Source }
    }
    if (!$MavenPath) {
        $mavenVersion = '3.9.11'
        $tools = Join-Path $projectRoot '.tools'
        $MavenPath = Join-Path $tools "apache-maven-$mavenVersion/bin/mvn.cmd"
        if (!(Test-Path -LiteralPath $MavenPath)) {
            if ($Offline) { throw 'Maven fehlt. Für den ersten Download den Build ohne -Offline starten.' }
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            $archive = Join-Path $tools "apache-maven-$mavenVersion-bin.zip"
            $sha512 = '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076'
            Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" -OutFile $archive -TimeoutSec 120
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ine $sha512) { throw 'Die SHA-512-Prüfsumme des Maven-Downloads stimmt nicht.' }
            Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
        }
    }
    & $python.Source scripts/check-standard.py
    if ($LASTEXITCODE -ne 0) { throw 'Die gemeinsamen Projektdateien weichen von der Vorlage ab.' }
    & (Join-Path $PSScriptRoot 'test-tools.ps1')
    & $python.Source scripts/check-version.py
    if ($LASTEXITCODE -ne 0) { throw 'Die Versionsangaben passen nicht zusammen.' }
    & $python.Source scripts/check-docs.py
    if ($LASTEXITCODE -ne 0) { throw 'Die Handbuchprüfung ist fehlgeschlagen.' }
    & $python.Source -m unittest discover -s scripts -p test_project_tools.py
    if ($LASTEXITCODE -ne 0) { throw 'Die Tests der Projektwerkzeuge sind fehlgeschlagen.' }
    [xml]$projectPom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw -Encoding UTF8
    $outputFolders = @((Join-Path $projectRoot 'target'))
    foreach ($module in $projectPom.project.modules.module) {
        $outputFolders += Join-Path $projectRoot "$module/target"
    }
    foreach ($folder in $outputFolders) {
        $absoluteFolder = [IO.Path]::GetFullPath($folder)
        if (!$absoluteFolder.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Buildausgabe liegt außerhalb des Projekts: $absoluteFolder"
        }
        if ((Test-Path -LiteralPath $absoluteFolder) -and ((Get-Item -LiteralPath $absoluteFolder).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Buildausgabe darf kein Verzeichnislink sein: $absoluteFolder"
        }
    }
    $arguments = @('-B', '-ntp')
    if ($Offline) { $arguments += '-o' }
    if ($Coverage) { $arguments += '-Pcoverage' }
    $arguments += @('clean', 'verify')
    $arguments += $MavenArguments
    & $MavenPath @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven konnte den Build nicht erfolgreich abschließen.' }
    & $python.Source scripts/prepare-release.py
    if ($LASTEXITCODE -ne 0) { throw 'Die Release-Dateien konnten nicht fertiggestellt werden.' }
    Write-Host 'Die geprüften JARs, Release-Hinweise und SHA256SUMS.txt liegen in dist/.'
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:PYTHONUTF8 = $previousUtf8
}
