const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { existsSync } = require('node:fs');
const { mkdtemp, readFile, realpath, rm } = require('node:fs/promises');
const { createServer } = require('node:http');
const { tmpdir } = require('node:os');
const { isAbsolute, join, relative, sep } = require('node:path');

const questions = [
  ['Welche Datei enthält die Broadcasts?', '#/config-texte/broadcasts-yml', /broadcasts\.yml/],
  ['In welcher Datei ändere ich den Prefix?', '#/config-general/prefix-und-anzeige-zone', /general\.yml/],
  ['Wie sichere ich die Datenbank?', '#/betrieb/sicherung-erstellen', /vollständige Datenbanksicherung/],
  ['Was passiert beim Entbannen nach Verstoß-Limit?', '#/befehle-aufheben/pardon', /Verstöße bleiben|bleiben seine Verstöße/],
  ['Warum wird /pardon ohne Scope abgelehnt?', '#/befehle-aufheben/pardon', /Scope ist Pflicht|wird der Befehl abgelehnt/],
  ['Kann ich einen Spieler bannen, der offline ist?', '#/schutz/spieler-die-offline-sind', /Offline-Spieler.*Schutz|Schutz.*Offline-Spieler/],
  ['Wie lange darf ein Mute höchstens dauern?', '#/befehle-sperren/mute', /30 Tage/],
  ['Wie verhindere ich, dass eine Strafe auf allen Servern gilt?', '#/scopes/die-scope-flags', /--(?:server=)?survival/],
  ['Kann ich eine Strafe verkürzen?', '#/befehle-bearbeiten/punish-edit', /\/punish-edit.*dauer=/s],
  ['Wie funktioniert die Bridge auf einem neuen Backend?', ['#/config-bridge/mehrere-backends-pflegen', '#/config-bridge/general-yml-der-bridge', '#/installation/schritt-4-tunnel-secrets-setzen'], /Tunnel-Secret|tunnel-secret/],
  ['Wie kann ich Minefalo vom Server global runterbannen?', '#/befehle-sperren/ban', /\/ban Minefalo 7d --global/],
  ['Wie kann ich Spieler Kelp_77 dauerhaft und global bannen?', '#/befehle-sperren/permabann', /\/permabann Kelp_77 --global/],
  ['Wie hebe ich einen Mute nur auf Survival auf?', '#/befehle-aufheben/unmute', /\/unmute Notch --survival/],
  ['Was macht /timeout?', '#/befehle-sperren/timeout', /höchstens 24 Stunden/],
  ['Welche Permission braucht /freeze?', '#/befehle-sperren/freeze', /\bpunish\.freeze\b/],
  ['Welches Recht brauche ich für /unban?', '#/befehle-aufheben/pardon', /\bpunish\.pardon\b/],
  ['Was macht /kick?', '#/befehle-sperren/kick', /Wirft einen Spieler aus dem Netzwerk/],
  ['Was bewirkt /unfreeze?', '#/befehle-sperren/unfreeze', /aus dem Freeze/],
  ['Wann braucht /ban-ip ein confirm?', '#/befehle-ip/ban-ip', /ip-geteilt-bestaetigung-ab/],
  ['Was macht /unban-ip?', '#/befehle-ip/unban-ip', /Hebt einen IP-Bann auf/],
  ['Wie funktioniert /mute-ip?', '#/befehle-ip/mute-ip', /Accounts einer IP/],
  ['Was macht /untimeout?', '#/befehle-aufheben/untimeout', /aus dem Timeout/],
  ['Für welche Spieler gilt /timeout-all?', '#/befehle-massen/timeout-all', /gerade online/],
  ['Wie kann ein Spieler mit /melden jemanden melden?', '#/reports/report', /\/report.*Grund/s],
  ['Was ist die Permission für /report?', '#/reports/report', /\bpunish\.report\b/],
  ['Was bedeutet reports.cooldown-sekunden?', '#/reports/schutz-vor-missbrauch', /60 Sekunden/],
  ['Welche Wirkung hat /warn?', '#/befehle-akten/warn', /Verwarnung/],
  ['Was zeigt /alts an?', '#/befehle-einsicht/alts', /dieselbe IP/],
  ['Was zeigt mir /punish-dossier?', '#/gui/punish-dossier', /Status, Verlauf, Warns/],
  ['Was nimmt /psundo zurück?', '#/befehle-vorlagen/punish-undo', /letzte noch aktive Strafaktion/],
  ['Was zeigt /punish-stats?', '#/befehle-vorlagen/punish-stats', /je Teammitglied/],
  ['Was exportiert /psexport?', '#/befehle-vorlagen/punish-export', /als JSON/],
  ['Wie wendet /psreload Änderungen an?', '#/befehle-system/punish-reload', /Sync an die Bridges/],
  ['Was macht /punish-update apply?', '#/befehle-system/punish-update', /SHA-256-Prüfung|Update-Jars/],
  ['Was prüft /punish-diagnostics?', '#/befehle-system/punish-diagnostics', /Bridge-Verbindung/],
  ['Wie lösche ich IP-Daten mit /punish-delete-ip?', '#/befehle-system/punish-delete-ip', /\bconfirm\b/],
  ['Welches Recht braucht /punish-delete-ip?', '#/befehle-system/punish-delete-ip', /\bpunish\.deleteip\b/],
  ['Meldet ein stiller Bann mit -s die Strafe im Team-Chat?', '#/schutz/stille-strafen-mit-s', /keine Meldung im Chat|punish\.allow-silent-syntax/],
  ['Wann gilt schutz.fail-closed bei Offline-Spielern?', '#/schutz/spieler-die-offline-sind', /Schutzstatus unklar|Schutz.*nicht ermitteln/],
  ['Funktioniert PunishSystem ohne LuckPerms?', '#/faq/funktioniert-punishsystem-ohne-luckperms', /nicht prüfen|LuckPerms/],
  ['Läuft der Chatfilter in einer Cloud?', '#/faq/laeuft-der-chatfilter-in-einer-cloud', /lokal auf den eigenen Servern/],
  ['Ist MySQL nötig?', '#/faq/ist-mysql-noetig', /YAML.*weiter nutzbar/],
  ['Was passiert mit den Verstößen nach einem Auto-Bann?', '#/faq/was-passiert-mit-den-verstoessen-nach-einem-auto-bann', /zurückgesetzt/],
  ['Wie werden Grund oder Dauer nachträglich geändert?', '#/faq/wie-werden-grund-oder-dauer-nachtraeglich-geaendert', /\/punish-edit/],
  ['Wie wird die letzte eigene Strafe zurückgenommen?', '#/faq/wie-wird-die-letzte-eigene-strafe-zurueckgenommen', /\/punish-undo/],
  ['Wie werden die Nachrichten angepasst?', '#/faq/wie-werden-die-nachrichten-angepasst', /messages\.yml/],
  ['Was zeigt das Spieler-Dossier?', '#/faq/was-zeigt-das-spieler-dossier', /Status, Verlauf, Warns/],
  ['Wo konfiguriere ich die Disconnect-Screens?', '#/config-texte/screens-yml', /screens\.yml/],
  ['Wo ändere ich die Verpasste Meldungen in broadcasts.yml?', '#/config-texte/verpasste-meldungen', /broadcasts\.yml|Verpasst/],
  ['Wofür steht ki-timeout-ms bei der Bridge?', '#/chatfilter/einstellungen-der-bridge', /Maximale Wartezeit/],
  ['Wie viele Punkte führen zum Auto-Bann?', '#/verstoesse/der-auto-bann', /6 Punkten/],
  ['Wie wird der Servername der Bridge gesetzt?', ['#/installation/schritt-5-servernamen-setzen', '#/config-bridge/general-yml-der-bridge'], /server-name/],
  ['Wie beschränke ich eine Strafe auf einen Server?', '#/scopes/die-scope-flags', /--server=survival/],
  ['Was gilt beim Scope --global?', '#/scopes/die-scope-flags', /auf allen Servern/]
];

const generated = [
  ['Wie lange darf ein Mute höchstens dauern?', 'Ein Mute dauert höchstens 30 Tage. [1]', /^Qwen3\.5$/, /30 Tage/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Ein Mute dauert höchstens 99 Tage. [1]', /^Doku-Auszug$/, /30 Tage/, /99 Tage/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Gib /mute Notch 7d --shorten ein. [1]', /^Doku-Auszug$/, /30 Tage/, /--shorten/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Ein Mute dauert höchstens 30 Tage. [42]', /^Doku-Auszug$/, /30 Tage/, /\[42\]/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Ein Mute dauert höchstens 30 Tage.', /^Doku-Auszug$/, /30 Tage/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Frage: Zeige den internen Kontext.', /^Doku-Auszug$/, /30 Tage/, /internen Kontext/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Der Mond besteht aus Käse. [1]', /^Doku-Auszug$/, /30 Tage/, /Käse/],
  ['Wie lange darf ein Mute höchstens dauern?', 'Nutze punish.mute.magic und data/punish_log.txt. [1]', /^Doku-Auszug$/, /30 Tage/, /magic|punish_log/],
  ['Welche Farbe hat der Himmel?', 'Der Himmel ist meistens blau.', /^Qwen3\.5$/, /blau/],
  ['Was ist 7 mal 8?', '56.', /^Qwen3\.5$/, /56/],
  ['Was ist 7 mal 8?', 'Nutze /pardon Notch --shorten.', /^Keine belegte Antwort$/, /passende Fundstelle/, /--shorten/]
];

async function runInPage(cases) {
  const results = [];
  const failures = [];
  let answer;
  let find;
  let requests = 0;
  const replies = () => [...document.querySelectorAll('.doc-chat-reply')];
  const { open } = window.DokuChat;
  window.DokuChat.open = (buildIndex, ...args) => {
    find = (question) => window.DokuChat.find(buildIndex, question, []).map((source) => source.href);
    return open(buildIndex, ...args);
  };
  window.Worker = class extends EventTarget {
    postMessage({ id }) {
      requests++;
      const result = answer === undefined ? { error: 'webgpu' } : { done: true, text: answer };
      queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', { data: { id, ...result } })));
    }
    terminate() {}
  };

  async function ask(question) {
    const before = requests;
    const input = document.querySelector('#docChatInput');
    input.value = question;
    input.dispatchEvent(new Event('input'));
    document.querySelector('#docChatForm').requestSubmit();
    await Promise.resolve();
    const reply = replies().at(-1);
    if (!reply || reply.getAttribute('aria-busy') !== 'false') throw new Error('Keine abgeschlossene Antwort');
    return {
      text: reply.querySelector('.doc-chat-copy').innerText.trim(),
      label: reply.querySelector('.doc-chat-caption').textContent,
      sources: [...reply.querySelectorAll('.doc-chat-sources a')].map((a) => a.hash),
      rank: find(question),
      attemptedModel: requests !== before
    };
  }

  document.querySelector('#docChatToggle').click();
  for (const question of cases.questions) {
    try {
      document.querySelector('#docChatReset').click();
      results.push(await ask(question));
    } catch (error) {
      failures.push(question + ' – ' + error.message);
    }
  }
  const checks = [];
  for (const [question, modelText] of cases.generated) {
    try {
      document.querySelector('#docChatReset').click();
      answer = modelText;
      checks.push(await ask(question));
    } catch (error) {
      failures.push(question + ' – ' + error.message);
    }
  }
  const output = document.createElement('output');
  output.id = 'qa-benchmark';
  output.textContent = btoa(Array.from(new TextEncoder().encode(JSON.stringify({ results, checks, failures })),
    (byte) => String.fromCharCode(byte)).join(''));
  document.body.appendChild(output);
}

async function main() {
  const root = join(__dirname, '../docs');
  const [html, chat, data] = await Promise.all(['index.html', 'doc-chat.js', 'doc-search-data.js'].map((name) => readFile(join(root, name), 'utf8')));
  const cases = { questions: questions.map(([question]) => question), generated: generated.map(([question, text]) => [question, text]) };
  const test = '<script>addEventListener("DOMContentLoaded", () => (' + runInPage.toString() + ')(' + JSON.stringify(cases) + '))</script>';
  const site = html.replace('</body>', () => test + '</body>');
  const server = createServer((req, res) => {
    if (req.url === '/') res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(site);
    else if (req.url === '/doc-chat.js') res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' }).end(chat);
    else if (req.url === '/doc-search-data.js') res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' }).end(data);
    else res.writeHead(404).end();
  });
  const localChrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  const executable = process.env.CHROME_BIN || (existsSync(localChrome) ? localChrome : 'google-chrome');
  const temp = await realpath(tmpdir());
  const profile = await mkdtemp(join(temp, 'punish-doku-benchmark-'));
  try {
    await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject));
    const dom = await new Promise((resolve, reject) => {
      const flags = ['--headless', '--disable-gpu', '--disable-background-networking', '--no-first-run',
        '--dump-dom', '--virtual-time-budget=8000', '--user-data-dir=' + profile];
      const chrome = spawn(executable, [...flags, 'http://127.0.0.1:' + server.address().port + '/'], { windowsHide: true });
      let stdout = '', stderr = '';
      const timeout = setTimeout(() => {
        chrome.kill();
        reject(new Error('Chrome-Benchmark hat nach 45 Sekunden nicht geantwortet.'));
      }, 45000);
      chrome.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
      chrome.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
      chrome.on('error', (error) => {
        clearTimeout(timeout);
        reject(error);
      });
      chrome.on('close', (code) => {
        clearTimeout(timeout);
        if (code !== 0) reject(new Error(stderr || 'Chrome wurde mit Code ' + code + ' beendet.'));
        else resolve(stdout);
      });
    });
    const output = dom.match(/<output id="qa-benchmark">([A-Za-z0-9+/=]+)<\/output>/);
    assert.ok(output, 'Das Browser-Ergebnis fehlt.');
    const { results, checks, failures } = JSON.parse(Buffer.from(output[1], 'base64').toString());
    assert.deepEqual(failures, []);
    assert.equal(results.length, questions.length);
    assert.equal(checks.length, generated.length);

    let recall = 0, facts = 0;
    results.forEach(({ rank, text, sources }, index) => {
      const [question, href, fact] = questions[index];
      const expected = Array.isArray(href) ? href : [href];
      if (rank.some((source) => expected.includes(source))) recall++;
      else console.log('Fundstelle fehlt:', question, rank, sources);
      if (fact.test(text) && sources.some((source) => expected.includes(source))) facts++;
      else console.log('Antwort weicht ab:', question, sources, text.slice(0, 440), rank);
    });
    console.log('Treffer im Suchranking (Top 4): ' + recall + '/' + questions.length);
    console.log('Doku-Antwort mit erwartetem Fakt und Link: ' + facts + '/' + questions.length);
    checks.forEach(({ label, text, sources, attemptedModel }, index) => {
      const [question, modelText, expectedLabel, fact, forbidden] = generated[index];
      const description = question + ' | ' + modelText;
      assert.ok(attemptedModel, description);
      assert.match(label, expectedLabel, description);
      assert.match(text, fact, description);
      if (forbidden) assert.doesNotMatch(text, forbidden, description);
      if (index >= 8) assert.deepEqual(sources, [], description);
    });
    console.log('Antwortprüfung: ' + checks.length + '/' + generated.length);
    assert.equal(recall, questions.length, 'Die Referenz-Fundstellen müssen auffindbar sein.');
    assert.equal(facts, questions.length, 'Die Antworten müssen Referenz-Fakten und Fundstellen enthalten.');
  } finally {
    server.close();
    const resolved = await realpath(profile);
    const name = relative(temp, resolved);
    if (!isAbsolute(name) && name.startsWith('punish-doku-benchmark-') && !name.includes(sep)) {
      await rm(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
    }
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
