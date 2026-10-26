@echo off
rem ================================================================
rem  PunishSystem - build_all_jars.bat
rem  Baut Velocity + Bridge (26.x) + Bridge-1.21 mit GLEICHER Version.
rem
rem  Ablauf (Standard):
rem    1. Falls eine Buildnummer vorhanden ist, diese EINMAL um +1
rem       erhoehen (nur die Ziffern, z. B. EA06c -> EA07c). Train (EA)
rem       und Stufe (c) bleiben unangetastet (Logik aus
rem       scripts\bump-build.ps1, LLNNS). Versionen ohne Buildnummer
rem       (z. B. 4.0 oder 4.0-beta) werden nicht gebumpt.
rem    2. Neue Version in alle Modul-poms + velocity-plugin.json +
rem       PunishVelocity.java synchronisieren.
rem    3. Alle Plattform-Module mit Maven bauen
rem       (mvn -pl velocity,bridge,bridge-1.21 -am clean package).
rem    4. Alle drei Jars als PunishSystem-...-velocity/-bridge/-bridge-1.21-...jar
rem       ins Hauptverzeichnis kopieren (gleiche Versionsnummer).
rem
rem  Aufruf:
rem    build_all_jars.bat                  Bump (falls Buildnummer vorhanden) + Build (mit Tests)
rem    build_all_jars.bat nobump           nur bauen, Version bleibt
rem    build_all_jars.bat skiptests        Bump (falls Buildnummer vorhanden) + Build ohne Tests
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

rem --- 2. Version bestimmen, EINMAL um +1 (fuer alle drei Jars) ---
if "%SKIP_BUMP%"=="1" goto :ohne_bump
rem Version ohne Buildnummer (final z. B. 4.0 oder Beta z. B. 4.0-beta) wird nicht gebumpt.
powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; if ($x.project.version.Trim() -notmatch '\+') { exit 3 }"
if errorlevel 3 goto :final_ohne_bump
echo [INFO] Erhoehe Buildnummer um +1 (einmalig fuer beide Jars) ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim() | Out-File -Encoding ascii ($env:TEMP + '\punish_old_version.txt')"
set /p OLD_VERSION=<"%TEMP%\punish_old_version.txt"
del "%TEMP%\punish_old_version.txt"
echo [INFO] Alt: %OLD_VERSION%
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\bump-build.ps1"
if errorlevel 1 goto :bump_fehler
powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim() | Out-File -Encoding ascii ($env:TEMP + '\punish_new_version.txt')"
set /p NEW_VERSION=<"%TEMP%\punish_new_version.txt"
del "%TEMP%\punish_new_version.txt"
echo [INFO] Neu: %NEW_VERSION%
goto :sync_version
:ohne_bump
echo [INFO] nobump: Version bleibt unveraendert.
powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim() | Out-File -Encoding ascii ($env:TEMP + '\punish_new_version.txt')"
set /p NEW_VERSION=<"%TEMP%\punish_new_version.txt"
del "%TEMP%\punish_new_version.txt"
echo [INFO] Version: %NEW_VERSION%
goto :bauen

:final_ohne_bump
echo [INFO] Version ohne Buildnummer erkannt - kein Bump.
powershell -NoProfile -ExecutionPolicy Bypass -Command "[xml]$x=Get-Content 'pom.xml'; $x.project.version.Trim() | Out-File -Encoding ascii ($env:TEMP + '\punish_new_version.txt')"
set /p NEW_VERSION=<"%TEMP%\punish_new_version.txt"
del "%TEMP%\punish_new_version.txt"
echo [INFO] Version: %NEW_VERSION%
goto :bauen

rem --- 3. Neue Version in alle Modul-poms + Velocity-Dateien spiegeln ---
:sync_version
echo [INFO] Synchronisiere Version in Module ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $o='!OLD_VERSION!'; $n='!NEW_VERSION!'; if ([string]::IsNullOrWhiteSpace($o) -or [string]::IsNullOrWhiteSpace($n)) { Write-Error 'Leere Version (OLD/NEW).'; exit 1 }; if ($o -eq $n) { exit 0 }; $files=@('common\pom.xml','velocity\pom.xml','bridge\pom.xml','bridge-1.21\pom.xml','velocity\src\main\resources\velocity-plugin.json','velocity\src\main\java\de\lightrixx\punish\velocity\PunishVelocity.java'); $enc=New-Object Text.UTF8Encoding($false); $fehl=0; foreach ($f in $files) { if (-not (Test-Path -LiteralPath $f)) { Write-Error ('Datei fehlt: ' + $f); $fehl=1; continue }; $t=[IO.File]::ReadAllText($f, [Text.Encoding]::UTF8); $q=[char]34; $vor=$t; $t=$t.Replace('<version>'+ $o +'</version>', '<version>'+ $n +'</version>').Replace($q+'version'+$q+': '+$q+$o+$q, $q+'version'+$q+': '+$q+$n+$q).Replace('version = '+$q+$o+$q, 'version = '+$q+$n+$q); if ($t -ne $vor) { [IO.File]::WriteAllText($f, $t, $enc); Write-Host ('sync: ' + $f) } else { Write-Error ('Versionsstring fehlt in ' + $f + ' - Build stoppt, bitte Version manuell angleichen.'); $fehl=1 } }; if ($fehl -ne 0) { exit 1 }"
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
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='velocity\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Velocity-Jar mit Version ' + $v + ' in velocity\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $ziel='PunishSystem-' + $sauber + '-velocity.jar'; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
if errorlevel 1 goto :copy_fehler
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='bridge\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Bridge-Jar mit Version ' + $v + ' in bridge\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $ziel='PunishSystem-' + $sauber + '-bridge.jar'; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
if errorlevel 1 goto :copy_fehler
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $d='bridge-1.21\target'; [xml]$x=Get-Content 'pom.xml'; $v=$x.project.version.Trim(); $kandidaten=@(Get-ChildItem -LiteralPath $d -Filter '*.jar' | Where-Object { ($_.Name -notlike 'original-*') -and ($_.Name -notlike '*-shaded*') -and ($_.Name -like ('*' + $v + '*')) }); if ($kandidaten.Count -eq 0) { Write-Error ('Kein Bridge-1.21-Jar mit Version ' + $v + ' in bridge-1.21\target gefunden.'); exit 1 }; $quelle=$kandidaten | Sort-Object LastWriteTime -Descending | Select-Object -First 1; $sauber='v' + ($v -replace '^[vV]',''); $ziel='PunishSystem-' + $sauber + '-bridge-1.21.jar'; Copy-Item -LiteralPath $quelle.FullName -Destination $ziel -Force; Write-Host ('Fertig: ' + $ziel)"
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
echo [FEHLER] Buildnummer konnte nicht erhoeht werden. Siehe Meldung oben.
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
