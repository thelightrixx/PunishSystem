(function () {
  'use strict';

  const stopWords = new Set(('aber alle als am an auch auf aus bei beim bin bis da dabei dafuer daher damit dann das dass dem den der des die dir doch du durch ein eine einem einen einer eines einfach er es etwas euch fuer geht gilt haben hat hier ich im in ins ist kann kannst kein keine koennen man mehr mich mir mit muss nach noch nun nur oder per schon sein sind so soll ueber um und uns unter vom von vor wann warum was weg weil welche welchem welchen welcher welches wem wen wenn wer wie wieder wir wird wo wohl wozu zu zum zur bitte punishsystem documentation doku frage hilfe heb hebe heben richt richte richten schalt schalte schalten antwort antworte antworten kurz kurze kurzen mal rechnung rechne ergebnis zahl zahlen mathe danke hallo hi hey servus').split(' '));
  const synonyms = new Map();
  const undoCommands = new Map(['unmute', 'unmute-ip', 'unban', 'unban-ip', 'pardon', 'untimeout', 'untimeout-ip', 'unfreeze'].map((name) => [name, '/' + name]));
  for (const group of [
    'mute muten stumm stummschalten stummschaltung schreibsperre',
    'aufheben aufhebung entfernen loesen beenden rueckgaengig ruecknahme entmut entbann entsperr wegnehmen',
    'bann bannen ban sperre sperren ausschluss verbannen runterbannen herunterbannen rausbannen wegbannen',
    'scope servername server servern teilserver gueltigkeitsbereich',
    'recht rechte berechtigung berechtigungen permission permissions hauptrecht erlaubnis',
    'mysql datenbank datenbanken sql speicher datenbankspeicher',
    'einrichten installieren einrichtung installation konfiguration konfigurieren',
    'silent still leise unauffaellig unterdruecken ausschalten -s',
    'meldung meldungen benachrichtigung benachrichtigungen ankuendigung broadcast broadcasts',
    'bridge bridges backend backends bruecke paper',
    'proxy velocity',
    'net netz netzwerk netzwerkweit netzweit global ueberall',
    'konsole konsolen terminal console',
    'sichern backup sicherung sicherungen sicherungskopie sichere gesichert datenbanksicherung',
    'update aktualisieren aktualisierung',
    'neuladen reload einlesen uebernehmen',
    'report reports melden meldung',
    'verlagern uebertragen uebernehmen migrieren migration umziehen umstieg',
    'bearbeiten aendern veraendern verkuerzen verlaengern anpassen korrigieren',
    'dauer dauern restdauer laufzeit',
    'punkt punkte punkten punktesumme',
    'offline abgemeldet offline-spieler',
    'maximal hoechstens maximum',
    'vorlage vorlagen template templates',
    'bisher bisherig vorher existierend vorhanden bestehend'
  ]) {
    const [key, ...words] = group.split(' ');
    for (const word of [key, ...words]) {
      if (!synonyms.has(word)) synonyms.set(word, key);
    }
  }

  const normalize = (text) => text.toLowerCase().replace(/[äöüß]/g, (letter) => ({ ä: 'ae', ö: 'oe', ü: 'ue', ß: 'ss' })[letter]);
  const technical = (text) => new Set(normalize(text).match(
    /(?<![\w/.-])(?:\/[a-z][a-z0-9_:-]*|--?[a-z][a-z0-9_-]*(?:=[a-z0-9_-]*)?|[a-z][a-z0-9_-]*(?:\/[a-z0-9_.-]+)+\/?)|\b[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+(?:\.\*)?|\b[a-z][a-z0-9_-]*=(?!=)/g
  ) || []);
  const fileName = /\.(?:ya?ml|json|txt|jar|db|onnx|log|properties|xml|toml|csv|sql|ini|conf|md)$/;
  const timePattern = /\b(?:(?:\d+(?:mo|[smhdwy]))+|\d+\s+(?:sekunden?|minuten?|stunden?|tag(?:en?)?|wochen?|monat(?:en?)?|jahr(?:en?)?))\b/i;
  const scopePattern = /\b(?:global|netzwerkweit|netzweit|ueberall|ganzen netzwerk|alle[nr]? servern?)\b/;
  const fileQuestion = /\b(?:welch\w* datei|wo .{0,25}(?:konfigurier|stell|aender)\w*|welch\w* konfiguration)\b/;
  const fileCoordinates = /\b(?:datei(?:en)?|enthaelt|liegt|steh\w*|find\w*|platziere\w*|aender\w*|konfigurier\w*|speicher\w*|bearbeit\w*)\b/g;
  const localScopePattern = /\b(?:nur auf|einzelnen server|nicht global|verhinder\w*|vermeid\w*|beschraenk\w*|begrenz\w*)\b/;
  const at = (id) => document.getElementById(id);
  const state = {};

  function tokenList(text) {
    const normalized = normalize(text)
      .replace(/\bheb\w*\s+[^.!?\n]{0,80}\bauf\b/g, ' aufheben $&')
      .replace(/\bricht\w*\s+[^.!?\n]{0,80}\bein\b/g, ' einrichten $&')
      .replace(/\bschalt\w*\s+[^.!?\n]{0,80}\baus\b/g, ' ausschalten $&');
    const tokens = normalized.match(/[a-z][a-z0-9]{1,}/g) || [];
    const result = [];
    for (const token of tokens) {
      if (stopWords.has(token)) continue;
      const stem = token.replace(/(?:ungen|ung|ern|en|er|e|s)$/, (suffix) => token.length - suffix.length > 3 ? '' : suffix);
      result.push(synonyms.get(token) || synonyms.get(stem) || stem);
    }
    return result;
  }

  function words(text) {
    return new Set(tokenList(text));
  }

  function termCounts(text) {
    const counts = new Map();
    for (const token of tokenList(text)) counts.set(token, (counts.get(token) || 0) + 1);
    return counts;
  }

  function commandsFor(question) {
    const text = normalize(question);
    const terms = words(text);
    const ids = [...technical(text)].filter((id) => id.startsWith('/'));
    if (ids.length) return ids;
    const bare = (text.match(/[a-z][a-z-]+/g) || []).find((word) => undoCommands.has(word));
    if (bare) return [undoCommands.get(bare)];
    if (/\bver(?:kuerz|laenger)\w*\b/.test(text) && /\b(?:strafe|dauer|bann|mute|timeout)\w*\b/.test(text)) return ['/punish-edit'];
    const undo = terms.has('aufheben') || /\b(?:entmut|entbann|entsperr)\w*/.test(text);
    const ip = terms.has('ip');
    if (terms.has('mute') || /\bentmut\w*\b/.test(text)) return [undo ? (ip ? '/unmute-ip' : '/unmute') : (ip ? '/mute-ip' : '/mute')];
    const banAction = /\b(?:bannen|gebannt|runterbannen|herunterbannen|rausbannen|wegbannen|verbannen|sperren|gesperrt|ban)\b/.test(text);
    if (terms.has('bann') || /\b(?:(?:ent|herunter|runter|raus|weg|ver)?bann(?:en|e|st|t)?|entsperr\w*)\b/.test(text)) {
      if (undo) return [ip ? '/unban-ip' : '/pardon'];
      if (!banAction) return [];
      if (/\b(?:dauerhaft|permanent|fuer immer)\b/.test(text) && !ip) return ['/permabann'];
      return [ip ? '/ban-ip' : '/ban'];
    }
    if (/(?<![\w-])timeouts?(?![\w-])/.test(text) || terms.has('auszeit')) {
      return [undo ? (ip ? '/untimeout-ip' : '/untimeout') : (ip ? '/timeout-ip' : '/timeout')];
    }
    if (terms.has('freez') || terms.has('freeze') || terms.has('einfrier') || /\b(?:auftau|entfrier|eingefror)\w*/.test(text)) return [undo || /auftau|entfrier/.test(text) ? '/unfreeze' : '/freeze'];
    return [];
  }

  function makeDocElement(node, name) {
    const el = document.createElement(name || node.tagName.toLowerCase());
    const from = node.tagName === 'PRE' ? node.querySelector('code') || node : node;
    if (node.matches('.desc')) el.dataset.role = 'description';
    if (name === 'pre') {
      const code = document.createElement('code');
      code.textContent = from.textContent.trim();
      el.appendChild(code);
      return el;
    }
    Array.from(from.childNodes).forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) el.appendChild(child.cloneNode());
      else if (child.nodeType === Node.ELEMENT_NODE && !child.matches('svg,.hash,.code-head')) {
        if (['P', 'UL', 'OL', 'LI', 'CODE', 'STRONG', 'EM', 'BR'].includes(child.tagName)) el.appendChild(makeDocElement(child));
        else el.append(...makeDocElement(child, 'span').childNodes);
      }
    });
    return el;
  }

  function docBlocks(element) {
    switch (element.tagName) {
      case 'TABLE':
        return Array.from(element.querySelectorAll('tbody tr')).map((row) => {
          const p = document.createElement('p');
          p.dataset.role = 'row';
          Array.from(row.cells).forEach((cell, i) => {
            if (i) p.append(' – ');
            p.append(...makeDocElement(cell, 'span').childNodes);
          });
          return p;
        });
      case 'H3':
        return [makeDocElement(element, 'p')];
      case 'P':
      case 'UL':
      case 'OL':
      case 'PRE': {
        const block = makeDocElement(element, element.tagName.toLowerCase());
        if (element.tagName === 'PRE' && element.dataset.lang) block.dataset.lang = element.dataset.lang;
        return [block];
      }
      case 'ARTICLE':
      case 'DETAILS':
      case 'DIV':
        if (element.matches('.wk,.mc,.hero-art,.cards,.shots,.command-tools')) return [];
        if (element.classList.contains('code')) {
          const pre = element.querySelector('pre');
          if (!pre) return [];
          const block = makeDocElement(pre, 'pre');
          block.dataset.lang = element.dataset.lang || '';
          return [block];
        }
        if (element.classList.contains('callout')) {
          const blocks = Array.from(element.children).flatMap(docBlocks);
          const title = element.querySelector('.ttl');
          if (title && blocks[0]?.tagName === 'P') {
            blocks[0].prepend(makeDocElement(title, 'strong'), ' – ');
          }
          return blocks;
        }
        return Array.from(element.children).flatMap(docBlocks);
      default:
        return [];
    }
  }

  function sourceIndex(entries) {
    return entries.map((entry) => {
      const blocks = entry.content.flatMap(docBlocks).filter((block) => block.textContent.trim());
      const title = entry.title.trim();
      const page = entry.page.title;
      const titleIds = technical(title);
      const files = [...titleIds, ...technical(entry.parentTitle || ''), ...technical(page)].filter((id) => fileName.test(id));
      const blocksWithIndex = blocks.map((block) => ({
        block, terms: words(block.textContent), ids: technical(block.textContent),
        keys: new Set(Array.from(block.querySelectorAll('code'), (code) => normalize(code.textContent.trim()))),
        text: block.textContent.trim()
      }));
      const text = [entry.permission, ...blocksWithIndex.map((block) => block.text)].filter(Boolean).join('\n');
      const bodyCounts = termCounts(text);
      const keys = new Set(blocksWithIndex.flatMap((entry) => [...entry.keys]).filter((key) => /^[a-z][a-z0-9]*(?:-[a-z0-9]+)+$/.test(key)));
      return {
        title,
        page,
        href: '#/' + entry.page.id + (entry.anchor ? '/' + entry.anchor : ''),
        kind: entry.kind,
        permission: entry.permission,
        blocks,
        blocksWithIndex,
        file: files[0],
        titleWords: words(title),
        pageWords: words(page),
        bodyWords: new Set(bodyCounts.keys()),
        bodyCounts,
        bodyLength: [...bodyCounts.values()].reduce((sum, count) => sum + count, 0),
        titleIds,
        bodyIds: new Set([...files, ...technical(entry.permission || ''), ...blocksWithIndex.flatMap(({ ids }) => [...ids])]),
        keys,
        text
      };
    });
  }

  function loadConcepts() {
    const entries = window.PunishSearchData || [];
    const seeded = new Set(synonyms.keys());
    for (const [a, b, similarity] of entries) {
      if (!synonyms.has(a) && similarity >= 91 && seeded.has(b)) synonyms.set(a, synonyms.get(b));
      if (!synonyms.has(b) && similarity >= 91 && seeded.has(a)) synonyms.set(b, synonyms.get(a));
    }
    const neighbors = new Map();
    for (const [a, b, similarity] of entries) {
      const x = words(a).values().next().value;
      const y = words(b).values().next().value;
      if (!x || !y || x === y) continue;
      if (!neighbors.has(x)) neighbors.set(x, new Map());
      if (!neighbors.has(y)) neighbors.set(y, new Map());
      const weight = Math.max(0, (similarity - 80) / 16);
      neighbors.get(x).set(y, Math.max(weight, neighbors.get(x).get(y) || 0));
      neighbors.get(y).set(x, Math.max(weight, neighbors.get(y).get(x) || 0));
    }
    return neighbors;
  }

  function indexStats(index) {
    const df = new Map();
    let total = 0;
    for (const source of index) {
      total += source.bodyLength;
      for (const term of new Set([...source.titleWords, ...source.pageWords, ...source.bodyWords])) {
        df.set(term, (df.get(term) || 0) + 1);
      }
    }
    return { df, size: index.length, averageLength: total / Math.max(1, index.length) };
  }

  function bestTermMatch(term, candidates) {
    if (term.length < 5) return 0;
    for (const candidate of candidates) {
      const shorter = term.length <= candidate.length ? term : candidate;
      const longer = term.length <= candidate.length ? candidate : term;
      if (shorter.length >= 5 && longer.length - shorter.length >= 3 && (longer.startsWith(shorter) || longer.endsWith(shorter))) return .5;
    }
    return 0;
  }

  function fileIntent(question) {
    const text = normalize(question);
    return fileQuestion.test(text) ? words(text.replace(fileCoordinates, ' ')) : undefined;
  }

  function compare(index, question, commands) {
    const exact = technical(question);
    const query = normalize(question);
    const fileTerms = fileIntent(question);
    const terms = fileTerms?.size ? fileTerms : words(question);
    const scopedAction = localScopePattern.test(query) || scopePattern.test(query);
    const bridgeSetup = terms.has('bridge') && (terms.has('einrichten') || /\b(?:neue[mnrs]?|weiter[eimnrs]+)\b/.test(query));
    const hyphenated = new Set(query.match(/(?<![\w/.-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+\b/g) || []);
    const explicitCommand = [...exact].some((id) => id.startsWith('/'));
    const stats = state.stats || indexStats(index);
    const k1 = 1.2;
    const b = 0.75;
    const idfs = new Map([...terms].map((term) => {
      const frequency = stats.df.get(term) || 0;
      return [term, Math.log(1 + (stats.size - frequency + .5) / (frequency + .5))];
    }));
    const scored = index.map((source) => {
      let lexical = 0;
      let hits = 0;
      let titleHits = 0;
      let pageHits = 0;
      let semantic = 0;
      let semanticHits = 0;
      let literalHits = 0;
      for (const term of terms) {
        const idf = idfs.get(term);
        const bodyCount = source.bodyCounts.get(term) || 0;
        const titleHit = source.titleWords.has(term);
        const pageHit = source.pageWords.has(term);
        let termScore = 0;
        if (bodyCount) {
          termScore += idf * (bodyCount * (k1 + 1)) / (bodyCount + k1 * (1 - b + b * source.bodyLength / Math.max(1, stats.averageLength)));
        }
        if (titleHit) termScore += idf * 2.6;
        if (pageHit) termScore += idf * .6;
        if (termScore) {
          lexical += termScore;
          hits++;
          if (bodyCount || titleHit) literalHits++;
          if (titleHit) titleHits++;
          if (pageHit) pageHits++;
          continue;
        }
        const compound = Math.max(bestTermMatch(term, source.titleWords) * 1.25, bestTermMatch(term, source.bodyWords) * .6);
        if (compound) {
          lexical += idf * compound;
          hits++;
          continue;
        }
        let best = 0;
        for (const [similar, weight] of (state.neighbors.get(term) || [])) {
          if (source.titleWords.has(similar)) best = Math.max(best, weight);
          else if (source.bodyWords.has(similar)) best = Math.max(best, weight * .5);
        }
        semantic += best;
        if (best >= .4) semanticHits++;
      }
      const citations = [...exact].filter((id) => source.bodyIds.has(id) || source.titleIds.has(id));
      const command = commands.some((id) => source.titleIds.has(id));
      const commandExample = source.blocksWithIndex.some(({ block, ids }) => block.dataset.lang === 'cmd' && commands.some((id) => ids.has(id)));
      const exactTitle = [...exact].filter((id) => source.titleIds.has(id)).length;
      const fileScore = fileTerms?.size && (literalHits || titleHits) && source.file ? 9 + (source.titleIds.has(source.file) ? 7 : 0) : 0;
      const backupScore = terms.has('sichern') ? (source.titleWords.has('sichern') ? 10 : 0) - (source.bodyWords.has('sichern') ? 0 : 8) : 0;
      const scopeScore = scopedAction && !explicitCommand && source.titleWords.has('scope')
        && source.blocksWithIndex.some(({ block, ids }) => block.dataset.role === 'row' && ids.has('--global')) ? 30 : 0;
      const setupScore = bridgeSetup && source.bodyWords.has('bridge') && source.blocksWithIndex.some(({ keys, text }) => keys.has('tunnel-secret') || /\btunnel-secret\s*:/.test(text))
        ? 9 + (source.titleWords.has('bridge') ? 6 : 0) + (source.titleWords.has('einrichten') ? 6 : 0) : 0;
      const offlineScore = terms.has('offline') && source.titleWords.has('offline') && source.bodyIds.has('schutz.fail-closed') ? 12 : 0;
      const numberScore = /\b(?:wie (?:viele|viel|hoch)|wie lange|wieviel\w*)\b/.test(query) && source.kind !== 'page'
        && source.blocksWithIndex.some(({ text, terms: blockTerms }) => /\b\d+\s+\p{L}/u.test(text) && !/:\s*$/.test(text)
          && [...terms].filter((term) => blockTerms.has(term)).length >= 2)
        ? 6 : 0;
      const keyScore = [...hyphenated].filter((key) => source.keys.has(key)).length * 7;
      const demoPenalty = source.title.startsWith('Ausprobieren:') && !/\b(?:ausprobieren|rechner|vorschau)\b/.test(query) ? -8 : 0;
      const commandPenalty = source.kind === 'cmd' && !explicitCommand && !commands.length ? -6 : 0;
      const coverage = terms.size ? hits / terms.size : 0;
      const score = lexical + Math.min(3, semantic) + coverage * 7 + Math.min(2, pageHits) * .6
        + titleHits * 1.8 + citations.length * 5 + exactTitle * 7 + fileScore + backupScore + scopeScore + setupScore + offlineScore
        + (command ? 13 : 0) + (commandExample ? 6 : 0) + commandPenalty + keyScore + demoPenalty + numberScore
        - Math.min(3, source.text.length / 1500);
      const supported = Boolean(command || citations.length || fileScore || scopeScore || setupScore || keyScore
        || literalHits >= 2 && (coverage >= .4 || titleHits > 0)
        || literalHits === 1 && terms.size === 1
        || literalHits >= 1 && (titleHits > 0 && coverage >= .25 || hits + semanticHits >= 2 && coverage >= .4));
      return { source, score, supported, literalHits, coverage, fileScore, scopeScore, setupScore, offlineScore };
    });
    return scored.sort((a, b) => b.score - a.score);
  }

  function matchedBlocks(source, question, commands) {
    const fileTerms = fileIntent(question);
    const query = fileTerms?.size ? fileTerms : words(question);
    const hyphenated = normalize(question).match(/(?<![\w/.-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+\b/g) || [];
    const flags = new Set([...technical(question), ...hyphenated.filter((key) => state.keys.has(key))]);
    const databaseSetup = query.has('mysql') && query.has('einrichten');
    const yamlRequested = query.has('yaml');
    return source.blocksWithIndex
      .map(({ block, text, ids, keys, terms }, i) => {
        const hits = [...query].filter((word) => terms.has(word)).length;
        const related = [...query].filter((word) => !terms.has(word) && bestTermMatch(word, terms)).length;
        const exact = [...flags].filter((word) => ids.has(word) || keys.has(word)).length;
        const example = block.dataset.lang === 'cmd' && commands.some((id) => ids.has(id));
        const configuration = block.dataset.lang === 'yaml' && databaseSetup
          && (yamlRequested ? /typ:\s*["']?yaml\b/i : /typ:\s*["']?mysql\b/i).test(text);
        const row = block.dataset.role === 'row' && hits > 0;
        const backup = row && query.has('sichern') && terms.has('mysql') ? 2 : 0;
        const setup = query.has('bridge') && (terms.has('tunnel') || /\btunnel-secret\b/.test(text)) ? 2 : 0;
        return { block, i, score: hits * 3 + related * .7 + exact * 5 + (row ? 1 : 0)
          + backup + setup + (example ? 2 : 0) + (configuration ? 6 : 0) - text.length / 900 };
      })
      .filter(({ block }) => block.textContent.length <= 1400)
      .sort((a, b) => b.score - a.score || a.i - b.i);
  }

  function refersBack(question) {
    const text = normalize(question);
    if (/\b(?:dabei|dafuer|daran|damit|dasselbe|ihn)\b|^(?:er|sie|das|der name)\b.{0,65}\b(?:i\w{1,4}t|heisst|meine)\b/.test(text)) return true;
    if (/^(?:und\s+)?(?:wie lange|nur auf|dauerhaft|permanent|fuer immer)\b/.test(text)) return true;
    return /^(?:und|auch|oder|aber|ja|nein|genau|ich meine)\b/.test(text)
      && !/^(?:und|auch|oder|aber)\s+(?:wie|was|warum|wieso|wo|wann|wer)\b/.test(text);
  }

  function previousQuestions(question, turns) {
    const previous = [];
    for (const turn of turns.slice(-3).reverse()) {
      if (!refersBack(previous.at(-1) || question)) break;
      previous.push(turn.question);
    }
    return previous;
  }

  function playerName(question) {
    const explicit = question.match(/\/(?:ban|permabann|pardon|unban|unmute)\s+["'„]?([a-z0-9_]{3,16})\b/i)?.[1];
    if (explicit) return explicit;
    const named = /\b(?:spieler(?:name)?\s+(?:(?:ist|heißt|heisst|namens)\s+)?|(?:er|sie|der name)\s+(?:heißt|heisst|ist namens)\s+)["'„]?([a-z0-9_]{3,16})\b/gi;
    const target = /\b(?:wie\s+(?:(?:kann|soll|muss)\s+ich|(?:ent|ver)?banne\s+ich|sperre\s+ich)\s+|mute\s+f[üu]r\s+)["'„]?([a-z0-9_]{3,16})\b/gi;
    return [...question.matchAll(named), ...question.matchAll(target)].map((match) => match[1])
      .find((name) => !stopWords.has(normalize(name))
        && !/^(?:spieler(?:namen?)?|jemanden|irgendwen|jemand|personen?|global|runterbannen|bannen|entbannen|ihn|euch|mehrere|alle|wirklich|dauerhaft)$/i.test(name));
  }

  function commandGuide(index, questions, commands, validateBanDuration) {
    const [command] = commands;
    if (commands.length !== 1 || !['/ban', '/permabann', '/pardon', '/unban', '/unmute'].includes(command)) return undefined;
    if (!questions.some((question) => /\bwie\b/i.test(question))) return undefined;
    if (/\b(?:warum|wieso|weshalb|kann nicht|funktioniert nicht|keine rechte|kein spieler|ip|mehrere|alle spieler)\b/i.test(questions[0])) return undefined;
    const source = index.find((entry) => entry.kind === 'cmd' && entry.titleIds.has(command));
    const syntax = source?.blocks.find((block) => block.dataset.lang === 'cmd' && block.textContent.includes('<Spieler>'));
    if (!syntax || !/--(?:Scope|global)\b/.test(syntax.textContent)) return undefined;
    const canonical = syntax.textContent.split(' ', 1)[0];
    let player = questions.map(playerName).find(Boolean);
    const caution = /\b(?:stumm|silent|leise|ohne meldung|mit grund|grund)\b|(?:^|\s)-s\b/;
    if (canonical !== '/unmute' && questions.some((question) => caution.test(normalize(question)))) return undefined;
    const localScope = /\b(?:nicht global|nur auf|einzelnen server)\b|--(?!global\b)[a-z]/;
    const scope = questions.map(normalize).find((question) => scopePattern.test(question) || localScope.test(question));
    if (!scope) return undefined;
    let flag = '--global';
    let server;
    if (localScope.test(scope)) {
      if (canonical !== '/unmute') return undefined;
      const namedScope = questions.join('\n').match(/(?:\bnur auf\s+(?:(?:dem server|server)\s+)?|(?:^|\s)--(?:server=)?)([a-z0-9_][a-z0-9_-]*)\b/i);
      server = namedScope?.[1].toLowerCase();
      if (!server || /^(?:dem|server|einem|einen|global|unbekannt|diesem|dieser)$/.test(server)) return undefined;
      flag = '--' + server;
    }
    const examplePlayer = canonical === '/unmute' && !player;
    if (examplePlayer) {
      player = index.find((entry) => entry.href === '#/schnellstart/eine-strafe-aufheben')?.blocks
        .map((block) => block.dataset.lang === 'cmd' ? block.textContent.match(/^\/unmute (\w{3,16}) --[\w-]+/m)?.[1] : undefined).find(Boolean);
    }
    if (!player) return undefined;
    const time = questions.join('\n').match(timePattern)?.[0].toLowerCase().replace(/(\d+)\s+([a-z]+)/, (_, count, unit) => {
      const abbreviations = { s: 's', m: unit.startsWith('monat') ? 'mo' : 'm', h: 'h', t: 'd', w: 'w', j: 'y' };
      return count + (abbreviations[unit[0]] || unit);
    });
    const durationExample = source.blocks.filter((block) => block !== syntax && block.dataset.lang === 'cmd')
      .map((block) => block.textContent.match(/^\S+\s+\S+\s+((?:\d+(?:mo|[smhdwy]))+)\b/i)?.[1]).find(Boolean);
    const duration = canonical === '/ban' ? time || durationExample : '';
    if (canonical === '/ban' && !duration) return undefined;
    if (time && canonical === '/ban' && !validateBanDuration(time)) return undefined;
    const target = [canonical, player, duration].filter(Boolean).join(' ');
    const exampleDuration = Boolean(duration && !time);
    const place = server ? 'nur auf `' + server + '`' : 'auf allen Servern';
    let explanation = 'So sperrst du ' + player + (canonical === '/permabann' ? ' dauerhaft' : '') + ' ' + place + '.';
    if (canonical === '/pardon') explanation = 'So hebst du den globalen Bann für ' + player + ' auf.';
    if (canonical === '/unmute') explanation = 'So hebst du den Mute für ' + (examplePlayer ? 'einen Spieler' : player) + ' ' + place + ' auf.';
    const exampleNote = exampleDuration ? '\n\n`' + duration + '` ist eine Beispieldauer. Setze die gewünschte Dauer ein.'
      : examplePlayer ? '\n\n`' + player + '` ist ein Beispielname. Setze den Spielernamen ein.' : '';
    return {
      source,
      text: explanation + '\n\n```text\n' + target + ' ' + flag + '\n```' + exampleNote
    };
  }

  function passages(question, index, turns, validateBanDuration) {
    const previous = previousQuestions(question, turns);
    const context = previous.slice().reverse().join('\n').slice(-650);
    const query = context ? question + '\n' + context : question;
    const ownCommands = commandsFor(question);
    const commands = ownCommands.length ? ownCommands : previous.map(commandsFor).find((ids) => ids.length) || [];
    if (!ownCommands.length && commands[0] === '/ban' && /\b(?:dauerhaft|permanent|fuer immer)\b/.test(normalize(question))) {
      commands[0] = '/permabann';
    }
    const permission = commands.length === 1 && words(question).has('recht')
      ? index.find((source) => source.permission && source.titleIds.has(commands[0])) : undefined;
    if (permission) {
      const command = commands[0];
      const fact = 'Das dokumentierte Hauptrecht für `' + command + '` ist `' + permission.permission + '`.';
      return { matches: [{ source: permission, text: fact }], fact, permission: permission.permission };
    }
    const guide = commandGuide(index, [question, ...previous], commands, validateBanDuration);
    const requestedCommands = [...technical(question)].filter((term) => term.startsWith('/'));
    const settings = [...technical(question)].filter((term) => term.endsWith('=') || term.includes('.') && !term.startsWith('/'));
    for (const key of normalize(question).match(/(?<![\w/-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+\b/g) || []) {
      if (state.keys.has(key) && !/(?:^|-)bann(?:-|$)/.test(key)) settings.push(key);
    }
    const exactSettings = (source) => settings.filter((term) => source.blocksWithIndex.some(({ ids, keys }) => ids.has(term) || keys.has(term)) || source.titleIds.has(term)).length;
    const fileTerms = fileIntent(question);
    const ranked = compare(index, query, commands)
      .filter(({ source, supported, score }) => supported && score >= 5 && source.blocks.length);
    const selected = guide ? [{ source: guide.source }] : ranked.slice(0, 4);
    if (!guide && settings.length && !selected.some(({ source }) => exactSettings(source))) {
      const exact = ranked.find(({ source }) => exactSettings(source));
      if (exact) selected.splice(3, 1, exact);
    }
    const matches = selected.map(({ source, literalHits, fileScore, scopeScore, setupScore, offlineScore }, i) => {
      const best = matchedBlocks(source, query, commands).slice(0, 4);
      const blocks = best.filter((item, index) => index === 0 || item.score > 0).map(({ block }) => block);
      if (guide) return { source, blocks, text: guide.text };
      const heading = [source.title, source.file, source.permission].filter(Boolean);
      const lines = best.map(({ block }) => {
        const text = block.textContent.trim();
        if (block.dataset.lang !== 'cmd' || !commands.length) return text;
        return text.split('\n').filter((line) => commands.some((command) => technical(line).has(command))).join('\n') || text;
      });
      const excerpt = lines.join('\n');
      const limit = i === 0 ? 470 : 260;
      const body = excerpt.length > limit ? excerpt.slice(0, limit).replace(/\s+\S*$/, '') : excerpt;
      const text = [...heading, body].join('\n');
      const allowed = technical(text);
      for (const key of source.keys) if (normalize(body).includes(key)) allowed.add(key);
      return { source, blocks, text, allowed, literalHits, fileScore, scopeScore, setupScore, offlineScore };
    });
    const settingMatch = settings.length && !requestedCommands.length ? matches.find(({ source }) => exactSettings(source) === settings.length) : undefined;
    const fileMatch = fileTerms?.size && !requestedCommands.length ? matches.find(({ fileScore }) => fileScore > 0) : undefined;
    return {
      matches, settingMatch, fileMatch, guide, context, settings, terms: words(question),
      commands, requestedCommands,
      query,
      localScope: localScopePattern.test(normalize(question)),
      followUp: Boolean(previous.length)
    };
  }

  function appendText(node, text) {
    const inline = /\x60([^\x60\n]+)\x60|\*\*([^*\n]+)\*\*|__([^_\n]+)__|\*([^*\n]+)\*|(?<!\w)_([^_\n]+)_(?!\w)|(?<![\w/.-])(\/[a-z][a-z0-9_-]*|--[a-z][a-z0-9_=-]*|punish(?:\.[a-z0-9_-]+)+)\b/gi;
    let offset = 0;
    for (const match of text.matchAll(inline)) {
      const [, code, starBold, underlineBold, starItalic, underlineItalic, identifier] = match;
      const element = document.createElement(code || identifier ? 'code' : starBold || underlineBold ? 'strong' : 'em');
      if (code) element.textContent = code;
      else if (identifier) element.textContent = identifier;
      else appendText(element, starBold || underlineBold || starItalic || underlineItalic);
      node.append(text.slice(offset, match.index), element);
      offset = match.index + match[0].length;
    }
    node.append(text.slice(offset));
  }

  function addSources(container, sources) {
    if (!sources.length) return;
    const list = document.createElement('ul');
    list.className = 'doc-chat-sources';
    list.setAttribute('aria-label', 'Doku-Fundstellen');
    for (const source of sources) {
      const item = document.createElement('li');
      const link = document.createElement('a');
      link.href = source.href;
      link.textContent = source.title === source.page ? source.title : source.page + ' · ' + source.title;
      item.appendChild(link);
      list.appendChild(item);
    }
    container.appendChild(list);
  }

  function showAnswer(log) {
    const reply = document.createElement('article');
    reply.className = 'doc-chat-reply';
    const copy = document.createElement('div');
    copy.className = 'doc-chat-copy';
    const caption = document.createElement('p');
    caption.className = 'doc-chat-caption';
    caption.textContent = 'Qwen3.5';
    reply.append(caption, copy);
    reply.setAttribute('aria-busy', 'true');
    log.appendChild(reply);
    return { reply, copy, caption };
  }

  function codeBlock(text, interactive) {
    const block = document.createElement('div');
    block.className = 'doc-chat-code';
    const pre = document.createElement('pre');
    const code = document.createElement('code');
    code.textContent = text;
    pre.appendChild(code);
    if (interactive && navigator.clipboard?.writeText) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = 'Kopieren';
      button.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(text);
          button.textContent = 'Kopiert';
        } catch (error) {
          console.debug('Code konnte nicht kopiert werden', error);
          button.textContent = 'Kopieren fehlgeschlagen';
        }
      });
      block.appendChild(button);
    }
    block.appendChild(pre);
    return block;
  }

  function renderText(copy, raw, interactive = false) {
    const result = document.createDocumentFragment();
    const fence = /^\s*(\x60{3,}|~{3,})[a-z0-9_-]*\s*$/i;
    const heading = /^ *(#{1,6}) +(.+)$/;
    const item = /^ *(?:([-+*])|(\d+)[.)]) +(.+)$/;
    const command = /^ *(?:\x60(\/[a-z][\w:-]*(?: +[^\x60]+)?)\x60|(\/[a-z][\w:-]*(?: +[^\x60]+)?)) *$/i;
    const lines = raw.trim().split(/\r?\n/);
    let paragraph = [];
    let list;
    let block;
    function flushParagraph() {
      if (!paragraph.length) return;
      const p = document.createElement('p');
      appendText(p, paragraph.join('\n'));
      result.appendChild(p);
      paragraph = [];
    }
    for (const line of lines) {
      const marker = line.match(fence);
      if (block) {
        if (line.trim() === block.marker) {
          if (block.lines.length) result.appendChild(codeBlock(block.lines.join('\n'), interactive));
          block = undefined;
        } else {
          block.lines.push(line);
        }
        continue;
      }
      const title = line.match(heading);
      const entry = line.match(item);
      const commandLine = line.replace(/^\s*(?:\[\d+\]\s*)+|(?:\s*\[\d+\])+\s*$/g, '').match(command);
      if (!line.trim() || marker || title || entry || commandLine) {
        flushParagraph();
        if (!entry) list = undefined;
      }
      if (marker) {
        block = { marker: marker[1], lines: [] };
      } else if (title) {
        const element = document.createElement(title[1].length <= 2 ? 'h3' : 'h4');
        appendText(element, title[2]);
        result.appendChild(element);
      } else if (entry) {
        const tag = entry[2] ? 'OL' : 'UL';
        if (list?.tagName !== tag) {
          list = document.createElement(tag);
          if (entry[2]) list.start = Number(entry[2]);
          result.appendChild(list);
        }
        const li = document.createElement('li');
        appendText(li, entry[3]);
        list.appendChild(li);
      } else if (commandLine) {
        result.appendChild(codeBlock(commandLine[1] || commandLine[2], interactive));
      } else if (line.trim()) {
        list = undefined;
        paragraph.push(line);
      }
    }
    flushParagraph();
    if (block?.lines.length) result.appendChild(codeBlock(block.lines.join('\n'), interactive));
    copy.replaceChildren(result);
  }

  function scrollToBottom() {
    const body = at('docChatBody');
    body.scrollTop = body.scrollHeight;
  }

  function close() {
    at('docChat').hidden = true;
    at('docChatToggle').setAttribute('aria-expanded', 'false');
    at('docChatToggle').focus();
  }

  const chatPrefix = 'punishsystem-doku-chat:';
  const activeChatKey = chatPrefix + 'active';

  function readChat(key) {
    const saved = JSON.parse(localStorage.getItem(key));
    if (!saved || key !== chatPrefix + saved.id || !Number.isFinite(saved.updated) || !Array.isArray(saved.turns)) return undefined;
    if (!saved.turns.length || !saved.turns.every((turn) => typeof turn?.id === 'string'
      && Number.isFinite(turn.created) && typeof turn.question === 'string' && (!turn.answer
        || typeof turn.answer.text === 'string' && typeof turn.answer.label === 'string' && Array.isArray(turn.answer.sources)))) return undefined;
    return saved;
  }

  function readChats() {
    const chats = new Map();
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(chatPrefix) || key === activeChatKey) continue;
      try {
        const chat = readChat(key);
        if (chat) chats.set(chat.id, chat);
      } catch (error) {
        console.warn('Gespeicherter Chat konnte nicht geöffnet werden', error);
      }
    }
    return chats;
  }

  function newChat() {
    return { turns: [] };
  }

  function inlineText(node) {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent;
    const content = Array.from(node.childNodes, inlineText).join('');
    if (node.tagName === 'CODE') return '\x60' + content + '\x60';
    if (node.tagName === 'STRONG') return '**' + content + '**';
    if (node.tagName === 'EM') return '*' + content + '*';
    if (node.tagName === 'BR') return '\n';
    return content;
  }

  function blockText(block) {
    if (block.tagName === 'PRE') return '\x60\x60\x60' + (block.dataset.lang || 'text') + '\n' + block.textContent.trim() + '\n\x60\x60\x60';
    if (block.tagName === 'UL' || block.tagName === 'OL') {
      return Array.from(block.children, (li, i) => (block.tagName === 'OL' ? (i + 1) + '. ' : '- ') + inlineText(li).trim()).join('\n');
    }
    return inlineText(block).trim();
  }

  function blocksText(blocks) {
    return blocks.map(blockText).filter(Boolean).join('\n\n');
  }

  function extractText(settings, match) {
    const selected = match.blocks.filter((block) => {
      const entry = match.source.blocksWithIndex.find((item) => item.block === block);
      return settings.some((key) => entry.ids.has(key) || entry.keys.has(key));
    }).slice(0, 2);
    const blocks = selected.length ? selected : match.blocks.slice(0, 2);
    const expanded = new Set(blocks);
    for (const block of blocks) {
      if (!/:\s*$/.test(block.textContent.trim())) continue;
      const index = match.source.blocks.indexOf(block);
      for (let offset = 1; offset <= 2 && expanded.size < 5; offset++) {
        const next = match.source.blocks[index + offset];
        if (next) expanded.add(next);
      }
    }
    return blocksText([...expanded].sort((a, b) => match.source.blocks.indexOf(a) - match.source.blocks.indexOf(b)));
  }

  function documentaryAnswer(evidence, { generic = false } = {}) {
    if (evidence.permission) {
      const source = evidence.matches[0]?.source;
      return { label: 'Permission aus der Doku', text: evidence.fact, sources: source ? [source.href] : [] };
    }
    if (evidence.guide) {
      return { label: 'Doku-Beispiel', text: evidence.guide.text, sources: [evidence.guide.source.href] };
    }
    if (evidence.fileMatch) {
      const { source, blocks } = evidence.fileMatch;
      const description = blocks.find((block) => block.tagName !== 'PRE' && !/:\s*$/.test(block.textContent));
      const text = '\x60' + source.file + '\x60' + (description ? '\n\n' + blockText(description) : '');
      return { label: 'Doku-Auszug', text, sources: [source.href] };
    }
    if (evidence.settingMatch) {
      const text = extractText(evidence.settings, evidence.settingMatch);
      if (text) return { label: 'Doku-Auszug', text, sources: [evidence.settingMatch.source.href] };
    }
    const terms = evidence.terms;
    const scopeMatch = evidence.matches.find(({ scopeScore }) => scopeScore);
    if (scopeMatch) {
      const rows = scopeMatch.source.blocksWithIndex.filter(({ block, ids }) => block.dataset.role === 'row'
        && (evidence.localScope ? (ids.has('--survival') || ids.has('--server=survival')) && !ids.has('--global') : ids.has('--global')));
      if (rows.length) return { label: 'Doku-Auszug', text: blocksText(rows.map(({ block }) => block)), sources: [scopeMatch.source.href] };
    }
    const first = evidence.matches[0];
    const commandMatch = evidence.requestedCommands?.length === 1 && evidence.matches.find(({ source }) => source.kind === 'cmd'
      && source.titleIds.has(evidence.requestedCommands[0]));
    if (commandMatch || first?.source.kind === 'cmd' && evidence.commands?.length === 1 && /^(?:was (?:macht|tut|ist|bewirkt)|wofuer)\b/.test(normalize(evidence.query))) {
      const match = commandMatch || first;
      const description = match.source.blocks.find((block) => block.dataset.role === 'description');
      const blocks = new Set([description, ...match.blocks.slice(0, 3)].filter(Boolean));
      const text = blocksText([...blocks].slice(0, 3));
      if (text) return { label: 'Doku-Auszug', text, sources: [match.source.href] };
    }
    const faq = evidence.matches.find(({ source }) => {
      if (source.kind !== 'faq') return false;
      if (terms.has('offline') && !source.titleWords.has('offline')) return false;
      const overlap = [...terms].filter((term) => source.titleWords.has(term)).length;
      return overlap >= 2 && overlap >= Math.ceil(terms.size * .5);
    });
    if (faq) {
      const text = blocksText(faq.blocks.slice(0, 2));
      if (text) return { label: 'Doku-Auszug', text, sources: [faq.source.href] };
    }
    const topic = evidence.matches.find(({ setupScore, offlineScore, source }) => setupScore
      || offlineScore && evidence.commands?.includes('/ban')
      || source.kind === 'cmd' && source.titleIds.has('/punish-edit') && evidence.commands?.includes('/punish-edit'));
    if (topic) {
      const blocks = topic.blocks.slice(0, 2);
      if (topic.source.kind === 'cmd' && topic.source.titleIds.has('/punish-edit')) {
        const syntax = topic.source.blocks.find((block) => block.dataset.lang === 'cmd' && block.textContent.includes('dauer='));
        if (syntax && !blocks.includes(syntax)) blocks.push(syntax);
      }
      if (blocks.length) return { label: 'Doku-Auszug', text: blocksText(blocks), sources: [topic.source.href] };
    }
    if (first && terms.has('sichern') && first.source.titleWords.has('sichern')) {
      const blocks = first.blocks.filter((block) => block.tagName !== 'PRE');
      if (blocks.length) return { label: 'Doku-Auszug', text: blocksText(blocks.slice(0, 2)), sources: [first.source.href] };
    }
    if (generic) {
      if (!first || (!first.literalHits && !evidence.commands?.some((command) => first.source.titleIds.has(command)))) return undefined;
      const text = blocksText(first.blocks.slice(0, 2));
      if (text) return { label: 'Doku-Auszug', text, sources: [first.source.href] };
    }
    return undefined;
  }

  function guardAnswer(text, evidence) {
    if (!text || /^\s*(?:\[\d+\]|(?:Der (?:Nutzer|User|Benutzer)(?:in)?|Doku-Auszüge?|Fundstellen?|Quellen?|Kontext|Bisherige Frage|Frage)\s*:)/im.test(text)
      || /\bDer (?:Nutzer|User|Benutzer)(?:in)?\s+(?:fragt|schreibt|möchte|will|ist)\b/i.test(text)) return false;
    const identifiers = technical(text);
    for (const entry of normalize(text).matchAll(/\x60([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\x60|^\s*([a-z][a-z0-9_-]*):\s*(?=\S)/gm)) {
      if (entry[1]) identifiers.add(entry[1]);
      else if (!/^https?$/.test(entry[2]) && /^\s*(?:\x60{3,}|~{3,})/m.test(text)) identifiers.add(entry[2] + ':');
    }
    if (!evidence.matches.length) return !identifiers.size;
    const citations = [...text.matchAll(/\[(\d+)\]/g)].map((match) => Number(match[1]));
    if (!citations.length || citations.some((index) => index < 1 || index > evidence.matches.length)) return false;
    const cited = [...new Set(citations)].map((index) => evidence.matches[index - 1]);
    const support = new Set(cited.flatMap(({ allowed }) => [...allowed]));
    const evidenceText = cited.map((match) => match.text).join('\n');
    for (const [, key] of normalize(evidenceText).matchAll(/\b([a-z][a-z0-9_-]*):(?=\s|$)/g)) support.add(key + ':');
    if ([...identifiers].some((id) => !support.has(id))) return false;
    const claims = text.replace(/\[(\d+)\]/g, '').replace(/^\s*(?:\d+[.)]\s|\x60{3,}.*$|~{3,}.*$)/gm, '');
    const numbers = (value) => normalize(value).match(/\b\d+(?:[.,]\d+)*(?:mo|[smhdwy]|%)?(?![\w-])/g) || [];
    const expected = new Set(numbers(evidenceText));
    if (numbers(claims).some((value) => !expected.has(value))) return false;
    const facts = words(evidenceText);
    return claims.split(/(?:[.!?](?=\s|$)|\n)+/).every((line) => {
      if (!line.trim() || /^\s*\/[a-z]/.test(line)) return true;
      const terms = words(line);
      const overlap = [...terms].filter((term) => facts.has(term) || bestTermMatch(term, facts)).length;
      return overlap >= Math.min(2, terms.size) && overlap >= terms.size * .35;
    });
  }

  function start(sources, route, validateBanDuration) {
    const panel = at('docChat');
    const form = at('docChatForm');
    const input = at('docChatInput');
    const send = form.querySelector('button');
    const log = at('docChatLog');
    const reset = at('docChatReset');
    const welcome = at('docChatStart');
    const stop = at('docChatStop');
    const history = at('docChatHistory');
    const historyToggle = at('docChatHistoryToggle');
    const historyList = at('docChatHistoryList');
    const footer = at('docChatFooter');
    const status = at('docChatStatus');
    const phaseText = at('docChatPhase');
    const progressText = at('docChatProgressText');
    const progressBar = at('docChatProgress');
    const notice = at('docChatStorageStatus');
    const download = at('docChatDownload');
    const byHref = new Map(sources.map((source) => [source.href, source]));
    const date = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium', timeStyle: 'short' });
    const formatMB = (bytes) => (bytes / 1000000).toLocaleString('de-DE', { maximumFractionDigits: 0 });
    let worker;
    let current;
    let sequence = 0;
    let chats = new Map();
    let chat = newChat();

    const errors = {
      webgpu: 'Dieser Browser stellt das benötigte WebGPU nicht bereit. Öffne die Seite in Chrome oder Edge mit aktiver Grafikbeschleunigung.',
      storage: 'Der Browser kann die Modelldateien nicht speichern. Prüfe den freien Browserspeicher oder öffne die Doku in einem normalen Browserfenster.',
      load: 'Qwen konnte nicht geladen werden. Prüfe die Internetverbindung und sende die Frage erneut.',
      answer: 'Qwen konnte die Antwort nicht abschließen. Sende die Frage erneut.',
      file: 'Öffne die Dokumentation über GitHub Pages, um Qwen im Browser zu starten.'
    };
    const phases = {
      check: 'Modellstatus wird geprüft',
      prepare: 'Modelldownload wird vorbereitet',
      transfer: 'Modellgewichte werden heruntergeladen',
      init: 'Modell wird initialisiert',
      thinking: 'Qwen denkt nach',
      writing: 'Qwen schreibt',
      stopping: 'Antwort wird gestoppt'
    };

    function accessStorage(action) {
      try {
        action();
        notice.hidden = true;
      } catch (error) {
        console.warn('Chatverlauf konnte nicht auf den Browserspeicher zugreifen', error);
        notice.hidden = false;
      }
    }

    function saveChat(entry, isNew = false) {
      chat.updated = Date.now();
      chats.set(chat.id, chat);
      accessStorage(() => {
        const key = chatPrefix + chat.id;
        const stored = readChat(key);
        if (!isNew && chat.persisted && !stored) {
          chats.delete(chat.id);
          chat = newChat();
          return;
        }
        const turns = new Map(chat.turns.map((turn) => [turn.id, turn]));
        for (const turn of stored?.turns || []) {
          if (turn.id !== entry.id) turns.set(turn.id, turn);
        }
        const merged = [...turns.values()].sort((a, b) => a.created - b.created);
        localStorage.setItem(key, JSON.stringify({ id: chat.id, updated: chat.updated, turns: merged }));
        localStorage.setItem(activeChatKey, chat.id);
        chat.turns = merged;
        chat.persisted = true;
      });
    }

    function renderQuestion(question) {
      const user = document.createElement('p');
      user.className = 'doc-chat-question';
      user.textContent = question;
      log.appendChild(user);
    }

    function renderAnswer(view, answer) {
      view.caption.textContent = answer.label;
      renderText(view.copy, answer.text, true);
      addSources(view.reply, answer.sources.map((href) => byHref.get(href)).filter(Boolean));
      view.reply.setAttribute('aria-busy', 'false');
    }

    function renderChat() {
      log.replaceChildren();
      for (const turn of chat.turns) {
        renderQuestion(turn.question);
        if (turn.answer) renderAnswer(showAnswer(log), turn.answer);
      }
      welcome.hidden = !history.hidden || Boolean(chat.turns.length);
      log.hidden = !history.hidden;
      scrollToBottom();
    }

    function selectChat(next) {
      chat = next;
      accessStorage(() => {
        if (chat.id) localStorage.setItem(activeChatKey, chat.id);
        else localStorage.removeItem(activeChatKey);
      });
      showHistory(false);
      input.value = '';
      send.disabled = true;
      renderChat();
      input.focus();
    }

    function showHistory(open) {
      history.hidden = !open;
      historyToggle.setAttribute('aria-expanded', String(open));
      footer.hidden = open;
      log.hidden = open;
      welcome.hidden = open || Boolean(chat.turns.length);
      if (!open) {
        scrollToBottom();
        return;
      }
      historyList.replaceChildren();
      const ordered = [...chats.values()].sort((a, b) => b.updated - a.updated);
      at('docChatHistoryEmpty').hidden = Boolean(ordered.length);
      for (const saved of ordered) {
        const li = document.createElement('li');
        const openChat = document.createElement('button');
        const title = document.createElement('span');
        const time = document.createElement('time');
        const label = saved.turns[0].question;
        title.textContent = label;
        openChat.type = 'button';
        if (saved.id === chat.id) openChat.setAttribute('aria-current', 'true');
        time.dateTime = new Date(saved.updated).toISOString();
        time.textContent = date.format(saved.updated);
        openChat.append(title, time);
        openChat.addEventListener('click', () => selectChat(saved));

        const remove = document.createElement('button');
        remove.className = 'doc-chat-history-delete';
        remove.type = 'button';
        remove.textContent = 'Löschen';
        remove.setAttribute('aria-label', 'Chat löschen: ' + label);
        remove.addEventListener('click', () => {
          if (!window.confirm('Diesen Chatverlauf löschen?')) return;
          accessStorage(() => {
            localStorage.removeItem(chatPrefix + saved.id);
            if (localStorage.getItem(activeChatKey) === saved.id) localStorage.removeItem(activeChatKey);
          });
          chats.delete(saved.id);
          if (saved.id === chat.id) {
            chat = newChat();
            input.value = '';
            send.disabled = true;
            renderChat();
          }
          showHistory(true);
          history.querySelector('h3').focus();
        });
        li.append(openChat, remove);
        historyList.appendChild(li);
      }
      at('docChatBody').scrollTop = 0;
    }

    function updateStatus(phase, details = {}) {
      current.phase = phase;
      status.hidden = !phase;
      if (!phase) return;
      if (phaseText.textContent !== phases[phase]) phaseText.textContent = phases[phase];
      const transfer = phase === 'transfer' && details.total > 0;
      const percent = transfer ? Math.min(100, details.loaded * 100 / details.total) : details.percent;
      progressText.textContent = transfer ? formatMB(details.loaded) + ' / ' + formatMB(details.total) + ' MB'
        : percent === undefined ? '' : percent + ' %';
      progressBar.hidden = percent === undefined;
      if (percent !== undefined) progressBar.value = percent;
      stop.textContent = ['thinking', 'writing', 'stopping'].includes(phase) ? 'Antwort stoppen' : 'Modellladen abbrechen';
    }

    function allowInput(restoreFocus = false) {
      current = undefined;
      status.hidden = true;
      input.readOnly = false;
      send.disabled = !input.value.trim();
      stop.hidden = true;
      reset.disabled = false;
      historyToggle.disabled = false;
      welcome.querySelectorAll('button').forEach((button) => { button.disabled = false; });
      if (restoreFocus && !panel.hidden) input.focus({ preventScroll: true });
      if (!panel.hidden) scrollToBottom();
    }

    function beginTurn() {
      if (current.view) return;
      const isNew = !chat.id;
      if (isNew) chat.id = crypto.randomUUID();
      const entry = { id: crypto.randomUUID(), created: Date.now(), question: current.question };
      chat.turns.push(entry);
      current.entry = entry;
      input.value = '';
      welcome.hidden = true;
      renderQuestion(entry.question);
      current.view = showAnswer(log);
      saveChat(entry, isNew);
      scrollToBottom();
    }

    function finish(text, label, feedback, sourceOverride) {
      beginTurn();
      const { view, question, evidence, entry } = current;
      if ((!label && !guardAnswer(text, evidence)) || label === 'Fehler') {
        const fallback = documentaryAnswer(evidence, { generic: true });
        if (fallback) {
          text = fallback.text;
          label = fallback.label;
          sourceOverride = fallback.sources;
        } else if (!label) {
          label = 'Keine belegte Antwort';
          text = '';
          feedback = 'Dazu habe ich keine passende Fundstelle gefunden. Formuliere die Frage etwas genauer.';
        }
      }
      const references = sourceOverride || (!label || label === 'Abgebrochen' ? [...text.matchAll(/\[(\d+)\]/g)]
        .map((match) => evidence.matches[Number(match[1]) - 1]?.source.href).filter(Boolean) : []);
      const answer = {
        label: label || (text ? 'Qwen3.5' : 'Keine Antwort'),
        text: text || feedback || 'Sende die Frage erneut.',
        sources: [...new Set(references)]
      };
      if (label === 'Abgebrochen' || label === 'Fehler' || !text) answer.incomplete = true;
      entry.answer = answer;
      renderAnswer(view, answer);
      saveChat(entry);
      if (!text || label === 'Abgebrochen') input.value = question;
      allowInput(Boolean(feedback) || document.activeElement === stop);
    }

    function modelWorker() {
      if (worker) return worker;
      worker = new Worker(new URL('./doc-chat-model.js', document.baseURI), { type: 'module' });
      worker.addEventListener('message', ({ data }) => {
        if (!current || data.id !== current.id) return;
        if (data.needsDownload) {
          if (current.approved) {
            finish('', 'Fehler', errors.load);
            return;
          }
          status.hidden = true;
          stop.hidden = true;
          if (panel.hidden) {
            allowInput();
          } else {
            download.returnValue = '';
            download.showModal();
          }
        } else if (data.error) {
          if (data.error !== 'webgpu' && data.error !== 'storage') {
            worker.terminate();
            worker = undefined;
          }
          finish('', 'Fehler', errors[data.error] || errors.load);
        } else if (data.done) {
          const text = data.text || '';
          if (data.stopped) finish(guardAnswer(text, current.evidence) ? text : '', 'Abgebrochen', 'Antwort abgebrochen.');
          else finish(text);
        } else if (data.phase) {
          beginTurn();
          if (current.phase !== 'stopping') updateStatus(data.phase, data);
        } else if (typeof data.text === 'string' && current.phase !== 'stopping') {
          beginTurn();
          const { evidence, view } = current;
          if (!evidence.matches.length && guardAnswer(data.text, evidence)) renderText(view.copy, data.text);
          if (!panel.hidden) scrollToBottom();
        }
      });
      worker.addEventListener('error', (event) => {
        console.warn('Qwen-Worker wurde beendet', event.message);
        worker.terminate();
        worker = undefined;
        if (current) finish('', 'Fehler', errors.load);
      });
      return worker;
    }

    function requestAnswer(allowDownload) {
      current.approved = allowDownload;
      const { id, question, evidence, messages } = current;
      try {
        modelWorker().postMessage({
          id, question, allowDownload, history: messages, context: evidence.context,
          sources: evidence.matches.map((match) => match.text)
        });
      } catch (error) {
        console.warn('Qwen-Worker konnte nicht geöffnet werden', error);
        finish('', 'Fehler', errors.load);
      }
    }

    function submit(question) {
      if (!question || current) return;
      const evidence = passages(question, sources, chat.turns, validateBanDuration);
      const turns = evidence.permission || evidence.guide || (evidence.matches.length && !evidence.followUp) ? [] : chat.turns.slice(-2);
      const messages = turns.filter((turn) => turn.answer && !turn.answer.incomplete).flatMap((turn) => [
        { role: 'user', content: turn.question.slice(0, 280) },
        { role: 'assistant', content: turn.answer.text.slice(0, 320) }
      ]);
      const direct = documentaryAnswer(evidence);
      current = { id: ++sequence, question, evidence, messages };
      input.value = question;
      input.readOnly = true;
      send.disabled = true;
      reset.disabled = true;
      historyToggle.disabled = true;
      welcome.querySelectorAll('button').forEach((button) => { button.disabled = true; });
      stop.disabled = false;
      stop.hidden = false;
      updateStatus('check');
      if (location.protocol === 'file:') finish('', 'Fehler', errors.file);
      else if (direct) finish(direct.text, direct.label, undefined, direct.sources);
      else requestAnswer(false);
    }

    accessStorage(() => {
      chats = readChats();
      for (const saved of chats.values()) saved.persisted = true;
      chat = chats.get(localStorage.getItem(activeChatKey)) || newChat();
    });
    renderChat();

    at('docChatDownloadConfirm').addEventListener('click', () => download.close('confirm'));
    at('docChatDownloadCancel').addEventListener('click', () => download.close('cancel'));
    download.addEventListener('close', () => {
      if (!current) return;
      if (download.returnValue === 'confirm') {
        stop.hidden = false;
        updateStatus('prepare');
        requestAnswer(true);
      } else if (current.view) {
        finish('', 'Abgebrochen', 'Modelldownload abgebrochen.');
      } else {
        const fallback = documentaryAnswer(current.evidence, { generic: true });
        if (fallback) finish(fallback.text, fallback.label, undefined, fallback.sources);
        else allowInput(true);
      }
    });

    stop.addEventListener('click', () => {
      if (!current) return;
      if (current.phase === 'thinking' || current.phase === 'writing') {
        updateStatus('stopping');
        stop.disabled = true;
        worker?.postMessage({ id: current.id, stop: true });
        return;
      }
      worker?.terminate();
      worker = undefined;
      finish('', 'Abgebrochen', 'Modellladen abgebrochen.');
    });
    at('docChatClose').addEventListener('click', close);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      submit(input.value.trim());
    });
    input.addEventListener('input', () => { send.disabled = Boolean(current) || !input.value.trim(); });
    at('docChatStart').querySelectorAll('button').forEach((button) => button.addEventListener('click', () => {
      submit(button.textContent.trim());
      if (matchMedia('(pointer: coarse)').matches) log.focus({ preventScroll: true });
      else input.focus({ preventScroll: true });
    }));
    reset.addEventListener('click', () => selectChat(newChat()));
    historyToggle.addEventListener('click', () => {
      const open = history.hidden;
      if (open) {
        accessStorage(() => {
          const stored = readChats();
          for (const [id, saved] of chats) {
            if (saved.persisted && !stored.has(id)) chats.delete(id);
          }
          for (const [id, saved] of stored) {
            saved.persisted = true;
            chats.set(id, saved);
          }
        });
      }
      showHistory(open);
      if (open) history.querySelector('h3').focus();
    });
    log.addEventListener('click', (event) => {
      const link = event.target.closest('a');
      if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      close();
      if (link.hash === location.hash) {
        event.preventDefault();
        route();
        at('main').focus({ preventScroll: true });
      }
    });
    panel.addEventListener('keydown', (event) => {
      if (!event.defaultPrevented && event.key === 'Escape' && !panel.hidden) {
        event.preventDefault();
        if (history.hidden) close();
        else {
          showHistory(false);
          historyToggle.focus();
        }
      }
    });
  }

  function indexFor(buildIndex) {
    if (!state.sources) {
      state.neighbors = loadConcepts();
      state.sources = sourceIndex(buildIndex());
      state.stats = indexStats(state.sources);
      state.keys = new Set(state.sources.flatMap((source) => [...source.keys]));
    }
    return state.sources;
  }

  window.DokuChat = {
    find(buildIndex, query, existing) {
      const terms = words(query);
      if (!technical(query).size && terms.size < 2) return [];
      return compare(indexFor(buildIndex), query, commandsFor(query))
        .filter(({ source, supported, score }) => supported && score >= 6 && !existing.includes(source.href))
        .slice(0, 4).map(({ source }) => source);
    },
    open(buildIndex, route, validateBanDuration) {
      const panel = at('docChat');
      if (!panel.hidden) {
        close();
        return;
      }
      if (!state.started) {
        start(indexFor(buildIndex), route, validateBanDuration);
        state.started = true;
      }
      panel.hidden = false;
      at('docChatToggle').setAttribute('aria-expanded', 'true');
      if (matchMedia('(pointer: coarse)').matches) at('docChatClose').focus({ preventScroll: true });
      else at('docChatInput').focus({ preventScroll: true });
      scrollToBottom();
    }
  };
})();
