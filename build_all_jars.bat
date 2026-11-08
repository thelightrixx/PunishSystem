@echo off
rem ================================================================
rem  PunishSystem - build_all_jars.bat
rem  Baut Velocity + Bridge (26.x) + Bridge-1.21 mit GLEICHER Version.
rem
rem  Ablauf (Standard):
rem    1. Bei Vorabversionen (alpha/beta/preview/RC/SNAPSHOT) oder einer
rem       bereits vorhandenen Buildnummer diese auf das aktuelle lokale
rem       Datum und die aktuelle Minute setzen (DDMMYY-HHMM, z. B.
rem       011126-1853; Logik aus scripts\bump-build.ps1).
rem       Finale Versionen ohne Buildnummer (z. B. 4.0 oder 4.0.0) bleiben
rem       unveraendert.
rem    2. Neue Version in alle Modul-poms + PunishVelocity.java
rem       synchronisieren (velocity-plugin.json nutzt ${project.version}).
rem    3. Alle Plattform-Module mit Maven bauen
rem       (mvn -pl velocity,bridge,bridge-1.21 -am clean package).
rem    4. Alle drei Jars als PunishSystem-v<Basis>-(<Plattform>)+<Build>.jar
rem       ins Hauptverzeichnis kopieren (gleiche Versionsnummer),
rem       z. B. PunishSystem-v4.0.0-preview-(velocity)+011126_1929.jar.
rem
rem  Aufruf:
rem    build_all_jars.bat                  Datums-Build (bei Vorabversionen oder vorhandener Buildnummer) + Build (mit Tests)
rem    build_all_jars.bat nobump           nur bauen, Version bleibt
rem    build_all_jars.bat skiptests        Datums-Build (bei Vorabversionen oder vorhandener Buildnummer) + Build ohne Tests
rem    build_all_jars.bat nobump skiptests nur bauen ohne Tests
rem ================================================================
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "SKIP_BUMP=0"
set "SKIP_TESTS=0"
set "CI_MODE=0"
for %%A in (%*) do (
    if /i "%%A"=="nobump" set "SKIP_BUMP=1"
    if /i "%%A"=="no-bump" set "SKIP_BUMP=1"
    if /i "%%A"=="skiptests" set "SKIP_TESTS=1"
    if /i "%%A"=="skip-tests" set "SKIP_TESTS=1"
    if /i "%%A"=="notests" set "SKIP_TESTS=1"
    if /i "%%A"=="ci" set "CI_MODE=1"
    if /i "%%A"=="--ci" set "CI_MODE=1"
)
if defined CI set "CI_MODE=1"
if "%GITHUB_ACTIONS%"=="true" set "CI_MODE=1"

rem --- 0. Java pruefen (JDK 25 zum Bauen erforderlich, Bytecode release 21) ---
where java >nul 2>nul
if errorlevel 1 goto :kein_java
java -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 goto :kein_java
java -version 2>&1 | findstr "25\." >nul
if not errorlevel 1 goto :java_ok
echo [FEHLER] Kein JDK 25 erkannt - Build benoetigt JDK 25 (Bytecode release 21, Major 65).
echo          Bitte JAVA_HOME auf JDK 25 setzen und erneut starten.
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
:kein_java
echo [FEHLER] java nicht gefunden. Bitte JDK 25 installieren und PATH setzen.
pause
exit /b 1
:java_ok

rem --- 1. Maven suchen oder automatisch nach .tools laden ---
set "MVN_CMD="
where mvn >nul 2>nul
if not errorlevel 1 set "MVN_CMD=mvn"
if defined MVN_CMD goto :mvn_ok
if exist ".tools\apache-maven-3.9.11\bin\mvn.cmd" set "MVN_CMD=.tools\apache-maven-3.9.11\bin\mvn.cmd"
if defined MVN_CMD goto :mvn_ok
echo [INFO] Maven nicht gefunden - lade Apache Maven 3.9.11 nach .tools ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '.tools' | Out-Null; $zip='.tools\maven.zip'; $shaDatei='.tools\maven.zip.sha512'; $urls=@('https://dlcdn.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip','https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip'); $ok=$false; $zipUrl=''; foreach ($u in $urls) { try { Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing; $ok=$true; $zipUrl=$u; break } catch { Write-Warning ('Download fehlgeschlagen: ' + $u) } }; if (-not $ok) { exit 1 }; $shaOk=$false; try { Invoke-WebRequest -Uri ($zipUrl + '.sha512') -OutFile $shaDatei -UseBasicParsing; $shaOk=$true } catch { Write-Warning ('SHA-Download fehlgeschlagen: ' + $zipUrl + '.sha512') }; if (-not $shaOk) { Write-Error 'SHA512-Datei konnte nicht geladen werden, Abbruch.'; exit 1 }; $roh=Get-Content -LiteralPath $shaDatei -Raw; $m=[regex]::Match($roh, '[0-9a-fA-F]{128}'); if (-not $m.Success) { Write-Error 'SHA512-Datei enthaelt keinen gueltigen Hash, Abbruch.'; exit 1 }; $erwartet=$m.Value.ToLower(); $aktuell=(Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash.ToLower(); if ($erwartet -ne $aktuell) { Write-Error 'SHA512-Pruefsumme stimmt nicht, Abbruch.'; exit 1 }; Write-Host 'SHA512 ok.'; Expand-Archive -Path $zip -DestinationPath '.tools' -Force; Remove-Item $zip -Force; Remove-Item $shaDatei -Force"
if errorlevel 1 goto :mvn_fehler
set "MVN_CMD=.tools\apache-maven-3.9.11\bin\mvn.cmd"
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path -LiteralPath '.gitignore') { $t=[IO.File]::ReadAllText('.gitignore'); if ($t -notlike '*.tools*') { [IO.File]::AppendAllText('.gitignore', [Environment]::NewLine + '.tools/') } }"
:mvn_ok
echo [INFO] Maven: %MVN_CMD%

rem --- 2. Version bestimmen, Buildnummer auf aktuelles Datum/Uhrzeit setzen ---
if "%SKIP_BUMP%"=="1" goto :ohne_bump
rem bump-build.ps1 entscheidet: Vorabversionen (alpha/beta/preview/RC/SNAPSHOT)
rem erhalten einen Datums-Build, finale Versionen bleiben unveraendert (Exit 3).
set "OLD_VERSION="
for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim()"`) do set "OLD_VERSION=%%V"
if not defined OLD_VERSION goto :bump_fehler
echo [INFO] Alt: %OLD_VERSION%
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\bump-build.ps1"
if errorlevel 3 goto :final_ohne_bump
if errorlevel 1 goto :bump_fehler
set "NEW_VERSION="
for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim()"`) do set "NEW_VERSION=%%V"
if not defined NEW_VERSION goto :sync_fehler
echo [INFO] Neu: %NEW_VERSION%
goto :sync_version
:ohne_bump
echo [INFO] nobump: Version bleibt unveraendert.
set "NEW_VERSION="
for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim()"`) do set "NEW_VERSION=%%V"
if not defined NEW_VERSION goto :sync_fehler
echo [INFO] Version: %NEW_VERSION%
goto :bauen

:final_ohne_bump
echo [INFO] Finale Version ohne Buildnummer erkannt - kein Datums-Build.
set "NEW_VERSION="
for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim()"`) do set "NEW_VERSION=%%V"
if not defined NEW_VERSION goto :sync_fehler
echo [INFO] Version: %NEW_VERSION%
goto :bauen

rem --- 3. Neue Version in alle Modul-poms + Velocity-Dateien spiegeln ---
:sync_version
echo [INFO] Synchronisiere Version in Module ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $n='!NEW_VERSION!'; if ([string]::IsNullOrWhiteSpace($n)) { Write-Error 'Leere Version (NEW).'; exit 1 }; $enc=New-Object Text.UTF8Encoding($false); $fehl=0; $poms=@('common\pom.xml','velocity\pom.xml','bridge\pom.xml','bridge-1.21\pom.xml'); foreach ($f in $poms) { if (-not (Test-Path -LiteralPath $f)) { Write-Error ('Datei fehlt: ' + $f); $fehl=1; continue }; $t=[IO.File]::ReadAllText($f, [Text.Encoding]::UTF8); $rx=[regex]'<version>[0-9A-Za-z._+-]+</version>'; $m=$rx.Match($t); if (-not $m.Success) { Write-Error ('Parent-Version fehlt in ' + $f); $fehl=1; continue }; if ($m.Value -eq ('<version>'+$n+'</version>')) { Write-Host ('ok: ' + $f); continue }; $t=$rx.Replace($t, '<version>'+$n+'</version>', 1); [IO.File]::WriteAllText($f, $t, $enc); Write-Host ('sync: ' + $f) }; $jf='velocity\src\main\java\de\lightrixx\punish\velocity\PunishVelocity.java'; if (-not (Test-Path -LiteralPath $jf)) { Write-Error ('Datei fehlt: ' + $jf); $fehl=1 } else { $t=[IO.File]::ReadAllText($jf, [Text.Encoding]::UTF8); $q=[char]34; $rx=[regex]('version\s*=\s*'+$q+'[A-Za-z0-9._+-]*'+$q); $m=$rx.Match($t); if (-not $m.Success) { Write-Error ('Versionsstring fehlt in ' + $jf); $fehl=1 } elseif ($m.Value -eq ('version = '+$q+$n+$q)) { Write-Host ('ok: ' + $jf) } else { $t=$rx.Replace($t, 'version = '+$q+$n+$q, 1); [IO.File]::WriteAllText($jf, $t, $enc); Write-Host ('sync: ' + $jf) } }; if ($fehl -ne 0) { exit 1 }"
if errorlevel 1 goto :sync_fehler

rem --- 4. Alle Plattform-Module in EINEM Maven-Lauf bauen (gleiche Version) ---
:bauen
set "MVN_TESTS="
if "%SKIP_TESTS%"=="1" set "MVN_TESTS=-DskipTests"
echo [INFO] Baue Velocity-, Bridge- und Bridge-1.21-Jar ...
call "%MVN_CMD%" -B -ntp -pl velocity,bridge,bridge-1.21 -am clean package %MVN_TESTS%
if errorlevel 1 goto :build_fehler

rem --- 5. Alle drei Jars mit gleicher Version ins Hauptverzeichnis legen ---
rem     Es wird nur eine Jar mit der GERADE GEBAUTEN Version akzeptiert
rem     (kein "neueste Datei", kein Fremd-Jar unter neuem Namen).
echo [INFO] Kopiere Jars ins Hauptverzeichnis ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='velocity\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Velocity-Jar mit Version ' + $v + ' in velocity\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $teile=$sauber -split '\+',2; $basis=$teile[0]; if ($teile.Count -gt 1) { $build=$teile[1] -replace '-','_'; $ziel='PunishSystem-' + $basis + '-(velocity)+' + $build + '.jar' } else { $ziel='PunishSystem-' + $basis + '-(velocity).jar' }; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
if errorlevel 1 goto :copy_fehler
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='bridge\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Bridge-Jar mit Version ' + $v + ' in bridge\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $teile=$sauber -split '\+',2; $basis=$teile[0]; if ($teile.Count -gt 1) { $build=$teile[1] -replace '-','_'; $ziel='PunishSystem-' + $basis + '-(bridge)+' + $build + '.jar' } else { $ziel='PunishSystem-' + $basis + '-(bridge).jar' }; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
if errorlevel 1 goto :copy_fehler
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='bridge-1.21\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Bridge-1.21-Jar mit Version ' + $v + ' in bridge-1.21\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $teile=$sauber -split '\+',2; $basis=$teile[0]; if ($teile.Count -gt 1) { $build=$teile[1] -replace '-','_'; $ziel='PunishSystem-' + $basis + '-(bridge-1.21)+' + $build + '.jar' } else { $ziel='PunishSystem-' + $basis + '-(bridge-1.21).jar' }; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
if errorlevel 1 goto :copy_fehler
echo [INFO] Build erfolgreich - alle Jars haben Version %NEW_VERSION%.
if "%CI_MODE%"=="1" exit /b 0
pause
exit /b 0

:mvn_fehler
echo [FEHLER] Maven-Download fehlgeschlagen. Bitte manuell installieren: https://maven.apache.org/download.cgi
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
:bump_fehler
echo [FEHLER] Buildnummer konnte nicht gesetzt werden. Siehe Meldung oben.
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
:sync_fehler
echo [FEHLER] Versions-Sync fehlgeschlagen.
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
:build_fehler
echo [FEHLER] Maven-Build fehlgeschlagen. Siehe Ausgabe oben.
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
:copy_fehler
echo [FEHLER] Jar wurde gebaut, konnte aber nicht kopiert werden. Siehe velocity\target bzw. bridge\target bzw. bridge-1.21\target.
if "%CI_MODE%"=="1" exit /b 1
pause
exit /b 1
