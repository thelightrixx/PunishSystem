(function () {
  'use strict';

  const stopWords = new Set(('aber alle als am an auch auf aus bei beim bin bis da dabei dafuer daher damit dann das dass dem den der des die dir doch du durch ein eine einem einen einer eines einfach er es etwas euch fuer geht gilt haben hat hier ich im in ins ist kann kannst kein keine koennen man mehr mich mir mit muss nach noch nun nur oder per schon sein sind so soll ueber um und uns unter vom von vor wann warum was weg weil welche welchem welchen welcher welches wem wen wenn wer wie wieder wir wird wo wohl wozu zu zum zur bitte punishsystem documentation doku frage hilfe heb hebe heben richt richte richten schalt schalte schalten').split(' '));
  const synonyms = new Map();
  const undoCommands = new Map(['unmute', 'unmute-ip', 'unban', 'unban-ip', 'pardon', 'untimeout', 'untimeout-ip', 'unfreeze'].map((name) => [name, '/' + name]));
  for (const group of [
    'mute muten stumm stummschalten stummschaltung schreibsperre',
    'aufheben aufhebung entfernen loesen beenden rueckgaengig ruecknahme entmut entbann entsperr wegnehmen',
    'bann bannen ban sperre sperren ausschluss verbannen runterbannen herunterbannen rausbannen wegbannen',
    'scope servername server backend teilserver gueltigkeitsbereich',
    'recht rechte berechtigung berechtigungen permission permissions hauptrecht erlaubnis',
    'mysql datenbank datenbanken sql speicher datenbankspeicher',
    'einrichten installieren einrichtung installation konfiguration konfigurieren',
    'silent still leise unauffaellig unterdruecken ausschalten -s',
    'meldung meldungen benachrichtigung benachrichtigungen ankuendigung broadcast broadcasts',
    'bridge backend bruecke paper',
    'proxy velocity',
    'net netz netzwerk netzwerkweit netzweit global ueberall',
    'konsole konsolen terminal console',
    'backup sicherung sicherungskopie sichern',
    'update aktualisieren aktualisierung',
    'neuladen reload einlesen uebernehmen',
    'report reports melden meldung',
    'verlagern uebertragen uebernehmen migrieren migration umziehen umstieg',
    'bisher bisherig vorher existierend vorhanden bestehend'
  ]) {
    const [key, ...words] = group.split(' ');
    for (const word of [key, ...words]) {
      if (!synonyms.has(word)) synonyms.set(word, key);
    }
  }

  const normalize = (text) => text.toLowerCase().replace(/[äöüß]/g, (letter) => ({ ä: 'ae', ö: 'oe', ü: 'ue', ß: 'ss' })[letter]);
  const technical = (text) => new Set(normalize(text).match(/(?<![\w/.-])(?:\/[a-z]|--?[a-z])[a-z0-9_-]*(?:=[a-z0-9_-]+)?|\b[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+\b/g) || []);
  const timePattern = /\b(?:(?:\d+(?:mo|[smhdwy]))+|\d+\s+(?:sekunden?|minuten?|stunden?|tag(?:en?)?|wochen?|monat(?:en?)?|jahr(?:en?)?))\b/i;
  const scopePattern = /\b(?:global|netzwerkweit|netzweit|ueberall|ganzen netzwerk|alle[nr]? server[n]?)\b/;
  const at = (id) => document.getElementById(id);
  const state = {};

  function words(text) {
    const normalized = normalize(text)
      .replace(/\bheb\w*\s+[^.!?\n]{0,80}\bauf\b/g, ' aufheben $&')
      .replace(/\bricht\w*\s+[^.!?\n]{0,80}\bein\b/g, ' einrichten $&')
      .replace(/\bschalt\w*\s+[^.!?\n]{0,80}\baus\b/g, ' ausschalten $&');
    const tokens = normalized.match(/[a-z][a-z0-9]{1,}/g) || [];
    const result = new Set();
    for (const token of tokens) {
      if (!stopWords.has(token)) result.add(synonyms.get(token) || token.replace(/(?:ungen|ung|ern|en|er|e|s)$/, (suffix) => token.length - suffix.length > 3 ? '' : suffix));
    }
    return result;
  }

  function commandsFor(question) {
    const text = normalize(question);
    const terms = words(text);
    const ids = [...technical(text)].filter((id) => id.startsWith('/'));
    if (ids.length) return ids;
    const bare = (text.match(/[a-z][a-z-]+/g) || []).find((word) => undoCommands.has(word));
    if (bare) return [undoCommands.get(bare)];
    const undo = terms.has('aufheben') || /\b(?:entmut|entbann|entsperr)\w*/.test(text);
    const ip = terms.has('ip');
    if (terms.has('mute') || /\bentmut\w*\b/.test(text)) return [undo ? (ip ? '/unmute-ip' : '/unmute') : (ip ? '/mute-ip' : '/mute')];
    if (terms.has('bann') || /\b(?:(?:ent|herunter|runter|raus|weg|ver)?bann(?:en|e|st|t)?|entsperr\w*)\b/.test(text)) {
      if (undo) return [ip ? '/unban-ip' : '/pardon'];
      if (/\b(?:dauerhaft|permanent|fuer immer)\b/.test(text) && !ip) return ['/permabann'];
      return [ip ? '/ban-ip' : '/ban'];
    }
    if (terms.has('timeout') || terms.has('auszeit')) return [undo ? (ip ? '/untimeout-ip' : '/untimeout') : (ip ? '/timeout-ip' : '/timeout')];
    if (terms.has('freez') || terms.has('freeze') || terms.has('einfrier') || /\b(?:auftau|entfrier|eingefror)\w*/.test(text)) return [undo || /auftau|entfrier/.test(text) ? '/unfreeze' : '/freeze'];
    return [];
  }

  function makeDocElement(node, name) {
    const el = document.createElement(name || node.tagName.toLowerCase());
    const from = node.tagName === 'PRE' ? node.querySelector('code') || node : node;
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
          Array.from(row.cells).forEach((cell, i) => {
            if (i) p.appendChild(document.createTextNode(' – '));
            p.append(...makeDocElement(cell, 'span').childNodes);
          });
          return p;
        });
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
      return {
        title,
        page,
        href: '#/' + entry.page.id + (entry.anchor ? '/' + entry.anchor : ''),
        kind: entry.kind,
        permission: entry.permission,
        blocks,
        titleWords: words(title),
        pageWords: words(page),
        bodyWords: words(entry.text),
        titleIds: technical(title),
        bodyIds: technical(entry.text),
        text: entry.text
      };
    });
  }

  function loadConcepts() {
    const entries = window.PunishSearchData || [];
    for (const [a, b, similarity] of entries) {
      if (!synonyms.has(a) && similarity >= 91 && synonyms.has(b)) synonyms.set(a, synonyms.get(b));
      if (!synonyms.has(b) && similarity >= 91 && synonyms.has(a)) synonyms.set(b, synonyms.get(a));
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

  function compare(index, question, commands) {
    const exact = technical(question);
    const terms = words(question);
    const scored = index.map((source) => {
      const hits = [...terms].filter((term) => source.titleWords.has(term) || source.bodyWords.has(term));
      const title = hits.filter((term) => source.titleWords.has(term)).length;
      let semantic = 0;
      let semanticHits = 0;
      for (const term of terms) {
        if (source.titleWords.has(term) || source.bodyWords.has(term)) continue;
        let best = 0;
        for (const [similar, weight] of (state.neighbors.get(term) || [])) {
          if (source.titleWords.has(similar)) best = Math.max(best, weight);
          else if (source.bodyWords.has(similar)) best = Math.max(best, weight * .5);
        }
        semantic += best;
        if (best >= .4) semanticHits++;
      }
      const citations = [...exact].filter((id) => source.bodyIds.has(id));
      const command = commands.some((id) => source.titleIds.has(id));
      const commandExample = source.blocks.some((block) => block.tagName === 'PRE' && commands.some((id) => technical(block.textContent).has(id)));
      const pageMatch = hits.filter((term) => source.pageWords.has(term) && !source.titleWords.has(term)).length;
      const score = hits.length * 2 + title * 2.6 + Math.min(2, pageMatch) * 0.6 + Math.min(3, semantic)
        + citations.length * 4 + (command ? 13 : 0) + (commandExample ? 6 : 0) - Math.min(3, source.text.length / 1500);
      return { source, score, supported: command || citations.length || hits.length >= 2 || (hits.length === 1 && (terms.size === 1 || semanticHits || title > 0 && terms.size === 2)) };
    });
    return scored.sort((a, b) => b.score - a.score);
  }

  function matchedBlocks(source, question, commands) {
    const query = words(question);
    const flags = technical(question);
    const databaseSetup = query.has('mysql') && query.has('einrichten');
    const yamlRequested = query.has('yaml');
    return source.blocks
      .map((block, i) => {
        const text = block.textContent;
        const ids = technical(text);
        const terms = words(text);
        const hits = [...query].filter((word) => terms.has(word)).length;
        const exact = [...flags].filter((word) => ids.has(word)).length;
        const example = block.tagName === 'PRE' && commands.some((id) => ids.has(id));
        const configuration = block.dataset.lang === 'yaml' && databaseSetup
          && (yamlRequested ? /typ:\s*["']?yaml\b/i : /typ:\s*["']?mysql\b/i).test(text);
        return { block, i, score: hits * 2 + exact * 3 + (example ? 5 : 0) + (configuration ? 6 : 0) - text.length / 900 };
      })
      .filter(({ block }) => block.textContent.length <= 1050)
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
    const tokens = /(?:\bspieler(?:name)?\s+(?:(?:ist|heißt|heisst|namens)\s+)?|\b(?:er|sie|der name)\s+(?:heißt|heisst|ist namens)\s+|\bwie\s+(?:(?:kann|soll|muss)\s+ich|(?:ent|ver)?banne\s+ich|sperre\s+ich)\s+|\bmute\s+f[üu]r\s+)["'„]?([a-z0-9_]{3,16})\b/gi;
    return [...question.matchAll(tokens)].map((match) => match[1]).find((name) => !stopWords.has(normalize(name))
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
    const escapedTarget = target.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const exampleDuration = Boolean(duration && !time);
    const place = server ? 'nur auf `' + server + '`' : 'auf allen Servern';
    let explanation = 'So sperrst du ' + player + (canonical === '/permabann' ? ' dauerhaft' : '') + ' ' + place + '.';
    if (canonical === '/pardon') explanation = 'So hebst du den globalen Bann für ' + player + ' auf.';
    if (canonical === '/unmute') explanation = 'So hebst du den Mute für ' + (examplePlayer ? 'einen Spieler' : player) + ' ' + place + ' auf.';
    const exampleNote = exampleDuration ? '\n\n`' + duration + '` ist eine Beispieldauer. Setze die gewünschte Dauer ein.'
      : examplePlayer ? '\n\n`' + player + '` ist ein Beispielname. Setze den Spielernamen ein.' : '';
    return {
      source, canonical, examplePlayer, exampleDuration,
      expected: new RegExp(escapedTarget + '(?: [^\\n]+)? ' + flag + '(?=$|[\\s.,;])', 'im'),
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
    const settings = [...technical(question)].filter((term) => term.includes('.') && !term.startsWith('/'));
    const explicit = (source) => settings.some((term) => source.bodyIds.has(term) || source.titleIds.has(term));
    const ranked = compare(index, query, commands)
      .filter(({ source, supported, score }) => supported && score >= 6 && source.blocks.length)
      .map(({ source }) => source);
    const matches = (guide ? [guide.source] : ranked.slice(0, 2))
      .map((source) => {
        const blocks = matchedBlocks(source, query, commands).slice(0, 3).sort((a, b) => a.i - b.i).map(({ block }) => block);
        if (guide?.source === source) return { source, blocks, text: source.title + '\n' + guide.text };
        const excerpt = blocks.map((block) => {
          const text = block.textContent.trim();
          if (block.dataset.lang !== 'cmd' || !commands.length) return text;
          const lines = text.split('\n').filter((line) => commands.some((command) => technical(line).has(command)));
          return lines.length ? lines.join('\n') : text;
        }).join('\n');
        const permission = source.permission ? '\nHauptrecht: ' + source.permission : '';
        return { source, blocks, text: source.title + permission + '\n' + excerpt.slice(0, 510) };
      });
    const settingMatch = matches.find(({ source }) => explicit(source));
    return { matches, settingMatch, guide, context, settings, followUp: Boolean(previous.length) };
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
    const command = /^ *\x60?(\/[a-z][\w-]*(?: +.+?)?)\x60? *$/i;
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
        result.appendChild(codeBlock(commandLine[1], interactive));
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

  function extractText(settings, match) {
    function inline(node) {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent;
      const content = Array.from(node.childNodes, inline).join('');
      if (node.tagName === 'CODE') return '\x60' + content + '\x60';
      if (node.tagName === 'STRONG') return '**' + content + '**';
      if (node.tagName === 'EM') return '*' + content + '*';
      if (node.tagName === 'BR') return '\n';
      return content;
    }
    const key = new Set(settings);
    const selected = match.source.blocks.filter((block) => [...technical(block.textContent)].some((name) => key.has(name))).slice(0, 3);
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
    return [...expanded].sort((a, b) => match.source.blocks.indexOf(a) - match.source.blocks.indexOf(b)).map((block) => {
      if (block.tagName === 'PRE') return '\x60\x60\x60' + (block.dataset.lang || 'text') + '\n' + block.textContent + '\n\x60\x60\x60';
      if (block.tagName === 'UL' || block.tagName === 'OL') {
        return Array.from(block.children, (li, i) => (block.tagName === 'OL' ? (i + 1) + '. ' : '- ') + inline(li).trim()).join('\n');
      }
      return inline(block).trim();
    }).join('\n\n');
  }

  function start(buildIndex, route, validateBanDuration) {
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
    const sources = indexFor(buildIndex);
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

    function finish(text, label, feedback) {
      beginTurn();
      const { view, question, evidence, entry } = current;
      const references = new Set([...text.matchAll(/\[(\d+)\]/g)].map((match) => evidence.matches[Number(match[1]) - 1]?.source).filter(Boolean));
      if (!label && evidence.permission) {
        references.clear();
        references.add(evidence.matches[0].source);
        label = 'Permission aus der Doku';
        text = evidence.fact;
      }
      const guide = evidence.guide;
      if (!label && guide) {
        references.clear();
        references.add(guide.source);
        const command = guide.expected.test(text.replaceAll('\x60', ''));
        const commandIds = [...technical(text)].filter((id) => id.startsWith('/'));
        const differentCommand = commandIds.some((id) => !guide.source.titleIds.has(id));
        const commandCount = (text.match(/(?<![\w/.-])\/[a-z][a-z0-9_-]*/g) || []).length;
        const refusal = /nicht beantwort|keine (?:doku|anleitung|quelle|information|m[öo]glichkeit)|keinen zugriff|doku-ausz[üu]ge verwenden|doku-aufforderungen|kann ich nicht/i.test(text);
        const missingExample = (guide.exampleDuration || guide.examplePlayer) && !/beispiel|gew[üu]nschte dauer|setze .+ ein/i.test(text);
        if (refusal || !command || differentCommand || commandCount > 1 || missingExample) {
          label = 'Doku-Beispiel';
          text = guide.text;
        }
      }
      const completeSentence = /[.!?](?:\s*\[\d+\])*\s*$/.test(text);
      const asksBack = /\?\s*(?:\[\d+\])*\s*$/.test(text) || /\bR[üu]ckfrage\b/i.test(text);
      const echoesSources = /(?:^|\n)\s*\[\d+\]\s/.test(text);
      if (!label && evidence.settingMatch && (!references.size || !completeSentence || asksBack || echoesSources)) {
        label = 'Doku-Auszug';
        text = extractText(evidence.settings, evidence.settingMatch);
        references.clear();
        references.add(evidence.settingMatch.source);
      }
      const answer = {
        label: label || (text ? 'Qwen3.5' : 'Keine Antwort'),
        text: text || feedback || 'Sende die Frage erneut.',
        sources: [...references].map((source) => source.href)
      };
      if (label === 'Abgebrochen' || label === 'Fehler' || !text) answer.incomplete = true;
      entry.answer = answer;
      renderAnswer(view, answer);
      saveChat(entry);
      if (feedback || !text) input.value = question;
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
          finish(data.text || '', data.stopped ? 'Abgebrochen' : undefined, data.stopped ? 'Antwort abgebrochen.' : undefined);
        } else if (data.phase) {
          beginTurn();
          if (current.phase !== 'stopping') updateStatus(data.phase, data);
        } else if (typeof data.text === 'string' && current.phase !== 'stopping') {
          beginTurn();
          const { evidence, view } = current;
          if (!evidence.permission && !evidence.settingMatch && !evidence.guide) renderText(view.copy, data.text);
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
          sources: evidence.matches.map((match) => match.text), permission: evidence.permission
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
        allowInput(true);
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
        start(buildIndex, route, validateBanDuration);
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
