(function () {
  'use strict';

  const stopWords = new Set(('aber alle als am an auch auf aus bei beim bin bis da dabei dafuer daher damit dann das dass dem den der des die dir doch du durch ein eine einem einen einer eines einfach er es etwas euch fuer geht gilt haben hat hier ich im in ins ist kann kannst kein keine koennen man mehr mich mir mit muss nach noch nun nur oder per schon sein sind so soll ueber um und uns unter vom von vor wann warum was weg weil welche welchem welchen welcher welches wem wen wenn wer wie wieder wir wird wo wohl wozu zu zum zur bitte punishsystem documentation doku frage hilfe heb hebe heben richt richte richten schalt schalte schalten').split(' '));
  const synonyms = new Map();
  const undoCommands = new Map(['unmute', 'unmute-ip', 'unban-ip', 'pardon', 'untimeout', 'untimeout-ip', 'unfreeze'].map((name) => [name, '/' + name]));
  for (const group of [
    'mute muten stumm stummschalten stummschaltung schreibsperre',
    'aufheben aufhebung entfernen loesen beenden rueckgaengig ruecknahme entmut entbann entsperr wegnehmen',
    'bann bannen ban sperre sperren ausschluss verbannen',
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
  const at = (id) => document.getElementById(id);
  const state = { history: [], sources: undefined };

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
    if (terms.has('bann') || /\bentbann\w*|\bentsperr\w*/.test(text)) return [undo ? (ip ? '/unban-ip' : '/pardon') : (ip ? '/ban-ip' : '/ban')];
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

  function passages(question, index) {
    const previous = state.history.slice(-2).find((message) => message.role === 'user');
    const followUp = previous && /^(und|auch|oder)\b|\b(dabei|dafuer|daran|damit|dasselbe)\b/.test(normalize(question));
    const query = followUp ? question + '\n' + previous.content : question;
    const commands = commandsFor(question);
    if (!commands.length && followUp) commands.push(...commandsFor(previous.content));
    const permission = commands.length === 1 && words(question).has('recht')
      ? index.find((source) => source.permission && source.titleIds.has(commands[0])) : undefined;
    const matches = compare(index, query, commands)
      .filter(({ source, supported, score }) => supported && score >= 6 && source.blocks.length)
      .slice(0, 2)
      .map(({ source }) => {
        const blocks = matchedBlocks(source, query, commands).slice(0, 3).sort((a, b) => a.i - b.i);
        const excerpt = blocks.map(({ block }) => {
          const text = block.textContent.trim();
          if (block.dataset.lang !== 'cmd' || !commands.length) return text;
          const lines = text.split('\n').filter((line) => commands.some((command) => technical(line).has(command)));
          return lines.length ? lines.join('\n') : text;
        }).join('\n');
        const permission = source.permission ? '\nHauptrecht: ' + source.permission : '';
        return { source, text: source.title + permission + '\n' + excerpt.slice(0, 510) };
      });
    if (!permission) return { matches };
    const command = commands[0];
    const fact = 'Das dokumentierte Hauptrecht für ' + command + ' ist ' + permission.permission;
    return { matches: [{ source: permission, text: fact }], fact, permission: permission.permission };
  }

  function appendText(node, text) {
    const parts = text.split('\x60');
    parts.forEach((part, i) => {
      if (i % 2 && i < parts.length - 1) {
        const code = document.createElement('code');
        code.textContent = part;
        node.appendChild(code);
      } else {
        node.appendChild(document.createTextNode((i % 2 ? '\x60' : '') + part));
      }
    });
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

  function showAnswer() {
    const reply = document.createElement('article');
    reply.className = 'doc-chat-reply';
    const copy = document.createElement('div');
    copy.className = 'doc-chat-copy';
    const caption = document.createElement('p');
    caption.className = 'doc-chat-caption';
    caption.setAttribute('role', 'status');
    caption.textContent = 'Qwen wird vorbereitet …';
    reply.append(caption, copy);
    reply.setAttribute('aria-busy', 'true');
    at('docChatLog').appendChild(reply);
    return { reply, copy, caption };
  }

  function renderText(copy, text) {
    copy.replaceChildren();
    for (const paragraph of text.trim().split(/\n\n+/)) {
      if (!paragraph) continue;
      const p = document.createElement('p');
      appendText(p, paragraph);
      copy.appendChild(p);
    }
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

  function start(buildIndex, route) {
    const panel = at('docChat');
    const form = at('docChatForm');
    const input = at('docChatInput');
    const send = form.querySelector('button');
    const log = at('docChatLog');
    const reset = at('docChatReset');
    const welcome = at('docChatStart');
    const stop = at('docChatStop');
    const initial = at('docChatModelInfo');
    let worker;
    let current;
    let sequence = 0;
    let modelReady = false;

    const errors = {
      webgpu: 'Dieser Browser stellt das benötigte WebGPU nicht bereit. Öffne die Seite in Chrome oder Edge mit aktiver Grafikbeschleunigung.',
      storage: 'Der Browser hat zu wenig freien Speicher für Qwen. Öffne die Doku in einem normalen Browserfenster und versuche es erneut.',
      load: 'Qwen konnte nicht geladen werden. Prüfe beim ersten Start die Internetverbindung und sende die Frage erneut.'
    };

    function finish(text, message) {
      const { view, question, evidence } = current;
      view.reply.setAttribute('aria-busy', 'false');
      if (message) {
        view.caption.textContent = message;
        view.copy.replaceChildren();
      } else {
        view.caption.textContent = text ? 'Qwen3.5 · Lokal erzeugt' : 'Qwen hat diesmal keine Antwort erzeugt.';
        renderText(view.copy, text);
        if (text || evidence.permission) {
          const references = new Set([...text.matchAll(/\[(\d+)\]/g)].map((match) => evidence.matches[Number(match[1]) - 1]?.source).filter(Boolean));
          if (evidence.permission) {
            references.add(evidence.matches[0].source);
            if (!technical(text).has(evidence.permission.toLowerCase())) {
              renderText(view.copy, evidence.fact + '.');
              view.caption.textContent = 'Permission aus der Doku';
            }
          }
          addSources(view.reply, [...references]);
          const content = view.copy.textContent.slice(0, 650);
          state.history.push({ role: 'user', content: question }, { role: 'assistant', content });
          state.history = state.history.slice(-4);
        }
      }
      if (message) input.value = question;
      current = undefined;
      input.readOnly = false;
      send.disabled = !input.value.trim();
      stop.hidden = true;
      if (panel.contains(document.activeElement) && document.activeElement === stop) input.focus({ preventScroll: true });
      if (!panel.hidden) scrollToBottom();
    }

    function startupMessage(phase, percent) {
      if (phase === 'answer') return 'Qwen schreibt …';
      const action = phase === 'load' ? 'Qwen wird geladen' : 'Qwen wird einmalig geladen';
      const value = Number.isFinite(percent) && percent > 0 ? ' · ' + Math.min(100, percent) + ' %' : '';
      return action + value + ' …';
    }

    function modelWorker() {
      if (worker) return worker;
      worker = new Worker(new URL('./doc-chat-model.js', document.baseURI), { type: 'module' });
      worker.addEventListener('message', ({ data }) => {
        if (!current || data.id !== current.id) return;
        if (data.error) {
          finish('', errors[data.error] || errors.load);
        } else if (data.done) {
          finish(data.text || '', data.stopped ? 'Antwort abgebrochen.' : '');
        } else if (data.phase) {
          if (data.phase === 'answer') modelReady = true;
          const message = startupMessage(data.phase, data.percent);
          if (current.view.caption.textContent !== message) current.view.caption.textContent = message;
        } else if (typeof data.text === 'string' && !current.evidence.permission) {
          renderText(current.view.copy, data.text);
          if (!panel.hidden) scrollToBottom();
        }
      });
      worker.addEventListener('error', (event) => {
        console.warn('Qwen-Worker wurde beendet', event.message);
        worker.terminate();
        worker = undefined;
        modelReady = false;
        if (current) finish('', errors.load);
      });
      return worker;
    }

    function submit(question) {
      if (!question || current) return;
      const user = document.createElement('p');
      user.className = 'doc-chat-question';
      user.textContent = question;
      welcome.hidden = true;
      reset.hidden = false;
      log.appendChild(user);
      const evidence = passages(question, state.sources);
      const id = ++sequence;
      current = { id, question, view: showAnswer(), evidence };
      if (modelReady) current.view.caption.textContent = 'Qwen schreibt …';
      input.value = '';
      input.readOnly = true;
      send.disabled = true;
      stop.disabled = false;
      stop.hidden = false;
      scrollToBottom();
      if (location.protocol === 'file:') {
        finish('', 'Öffne die Dokumentation über GitHub Pages, um Qwen im Browser zu starten.');
        return;
      }
      try {
        modelWorker().postMessage({
          id, question, history: evidence.permission ? [] : state.history.slice(-2),
          sources: evidence.matches.map((source) => source.text), permission: evidence.permission
        });
      } catch (error) {
        console.warn('Qwen-Worker konnte nicht geöffnet werden', error);
        finish('', errors.load);
      }
    }

    indexFor(buildIndex);
    if (location.protocol === 'file:') initial.textContent = 'Den lokalen KI-Chat kannst du über GitHub Pages öffnen.';
    stop.addEventListener('click', () => {
      if (!current) return;
      if (modelReady) {
        current.view.caption.textContent = 'Antwort wird gestoppt …';
        stop.disabled = true;
        worker?.postMessage({ id: current.id, stop: true });
        return;
      }
      worker?.terminate();
      worker = undefined;
      finish('', 'Modellladen abgebrochen.');
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
    reset.addEventListener('click', () => {
      if (current) {
        worker?.terminate();
        worker = undefined;
        current = undefined;
        modelReady = false;
      }
      state.history = [];
      log.replaceChildren();
      welcome.hidden = false;
      reset.hidden = true;
      input.value = '';
      input.readOnly = false;
      send.disabled = true;
      stop.hidden = true;
      stop.disabled = false;
      input.focus();
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
        close();
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
    open(buildIndex, route) {
      const panel = at('docChat');
      if (!panel.hidden) {
        close();
        return;
      }
      if (!state.started) {
        start(buildIndex, route);
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
