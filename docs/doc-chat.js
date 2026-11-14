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
  const state = { memory: '', current: undefined, sources: undefined };

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

  function bestExamples(ranked, commands, question) {
    if (!commands.length) return [];
    const text = normalize(question);
    if (!/\b(?:nur|scope|server|survival|global|lobby)\b/.test(text)) return [];
    const examples = [];
    for (const { source } of ranked.slice(0, 14)) {
      if (source.kind === 'cmd') continue;
      for (const block of source.blocks) {
        if (block.tagName !== 'PRE') continue;
        for (const row of block.textContent.split('\n')) {
          const line = row.trim();
          const ids = technical(line);
          if (!commands.some((command) => ids.has(command))) continue;
          const flags = [...ids].filter((id) => id.startsWith('--'));
          const flag = flags.find((item) => text.includes(item.slice(2)));
          if (flag) examples.push({ source, line });
        }
      }
    }
    return examples;
  }

  function answer(question, index) {
    const followUp = state.memory && /^(und|auch|oder)\b|\b(dabei|dafuer|daran|damit|dasselbe)\b/.test(normalize(question));
    const query = followUp ? question + '\n' + state.memory : question;
    const commands = commandsFor(question);
    if (!commands.length && followUp) commands.push(...commandsFor(state.memory));
    const ranked = compare(index, query, commands);
    const source = ranked.find((match) => match.supported && match.source.blocks.length)?.source;
    if (!source) return { text: 'Dazu habe ich keine passende Stelle in der PunishSystem-Doku gefunden.', sources: [], blocks: [], followUp };

    const command = commands.map((id) => index.find((entry) => entry.permission && entry.titleIds.has(id))).find(Boolean);
    const asksForPermission = words(question).has('recht');
    if (command && asksForPermission) {
      const p = document.createElement('p');
      const code = document.createElement('code');
      code.textContent = command.permission;
      p.append('Das dokumentierte Hauptrecht ist ', code, '.');
      return { sources: [command], blocks: [p], precise: true, followUp };
    }

    const example = bestExamples(ranked, commands, question)[0];
    if (example) {
      const p = document.createElement('p');
      const code = document.createElement('code');
      code.textContent = example.line;
      p.append('Beispiel aus der Doku', document.createElement('br'), code);
      const context = example.source.blocks.find((block) => block.tagName === 'P' && /aus anderen Scopes|im selben Scope|passende Flag/.test(block.textContent));
      const explanation = document.createElement('p');
      explanation.textContent = context?.textContent.split(/(?<=\.)\s+(?=[A-ZÄÖÜ])/).find((sentence) => sentence.includes('anderen Scopes')) || '';
      const blocks = explanation.textContent ? [p, explanation] : [p];
      return { sources: [example.source, ...[command].filter((entry) => entry && entry !== example.source)], blocks, precise: true, followUp };
    }

    const target = commands.length && command && !/\b(?:was bedeutet|was macht|meldung|still|schutz|limit|dauer|mehrere)\b/.test(normalize(question)) ? command : source;
    const matching = matchedBlocks(target, query, commands);
    const selected = matching.slice(0, 2);
    const exampleIndex = selected.findIndex(({ block }) => block.tagName === 'PRE');
    if (exampleIndex >= 0) {
      const example = selected[exampleIndex];
      const intro = target.blocks[example.i - 1];
      if (intro?.tagName === 'P' && intro.textContent.trimEnd().endsWith(':')) selected.splice(0, selected.length, { block: intro, i: example.i - 1 }, example);
    }
    const blocks = selected.sort((a, b) => a.i - b.i).map(({ block }) => block);
    return {
      text: blocks.length ? '' : 'In diesem Abschnitt findest du die Details.',
      sources: [target],
      blocks,
      followUp
    };
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

  function showAnswer(result) {
    const reply = document.createElement('article');
    reply.className = 'doc-chat-reply';
    const copy = document.createElement('div');
    copy.className = 'doc-chat-copy';
    if (result.text) {
      const p = document.createElement('p');
      p.textContent = result.text;
      copy.appendChild(p);
    }
    if (result.blocks.length) copy.append(...result.blocks.map((block) => block.cloneNode(true)));
    reply.appendChild(copy);
    addSources(reply, result.sources);
    at('docChatLog').appendChild(reply);
    return { reply, copy };
  }

  function modelOptions() {
    const german = { type: 'text', languages: ['de'] };
    return { expectedInputs: [german], expectedOutputs: [german] };
  }

  async function refine(result, view, question, memory, controller) {
    if (!result.sources.length || !window.LanguageModel || !isSecureContext) return;
    let session;
    let status;
    const signal = controller.signal;
    try {
      const options = modelOptions();
      if (await window.LanguageModel.availability(options) !== 'available' || signal.aborted) return;
      session = await window.LanguageModel.create({
        ...options,
        signal,
        initialPrompts: [{ role: 'system', content: 'Beantworte Fragen zur PunishSystem-Doku auf Deutsch in höchstens zwei kurzen Sätzen. Nutze nur die angegebenen Doku-Auszüge. Kennzeichne Befehle, Einstellungen und Permissions als Inline-Code. Nenne jede Fundstelle mit ihrer Nummer in eckigen Klammern. Sind die Stellen unzureichend, antworte nur KEIN_BELEG.' }]
      });
      if (signal.aborted) return;
      status = document.createElement('p');
      status.className = 'doc-chat-caption';
      status.textContent = 'Lokale KI formuliert …';
      view.reply.prepend(status);
      const snippets = result.sources.map((source, i) => {
        const evidence = i === 0 && result.blocks.length ? result.blocks.map((block) => block.textContent).join('\n') : source.text;
        return '[' + (i + 1) + '] ' + source.page + ' · ' + source.title + '\n' + evidence.slice(0, 660);
      }).join('\n\n');
      const prompt = snippets + (memory ? '\nVorherige Frage: ' + memory : '') + '\nFrage: ' + question;
      const raw = (await session.prompt(prompt, { signal })).trim();
      if (signal.aborted || !raw || raw.length > 600 || raw.includes('KEIN_BELEG')) return;
      const numbers = [...raw.matchAll(/\[(\d+)\]/g)].map((match) => Number(match[1]));
      if (!numbers.length || numbers.some((number) => !result.sources[number - 1])) return;
      const cited = [...new Set(numbers)].map((number) => result.sources[number - 1]);
      const ids = new Set(cited.flatMap(({ bodyIds, titleIds }) => [...bodyIds, ...titleIds]));
      if ([...technical(raw)].some((id) => !ids.has(id))) return;
      const p = document.createElement('p');
      appendText(p, raw.replace(/\s*\[\d+\]/g, '').trim());
      const summary = document.createElement('div');
      summary.className = 'doc-chat-summary';
      summary.appendChild(p);
      view.copy.prepend(summary);
      status.textContent = 'Lokal formuliert';
      status = undefined;
      if (!at('docChat').hidden && view.reply === at('docChatLog').lastElementChild) scrollToBottom();
    } catch (error) {
      if (!signal.aborted) console.debug('Lokale Formulierung nicht verfügbar', error);
    } finally {
      status?.remove();
      session?.destroy();
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

    function submit(question) {
      if (!question) return;
      state.current?.abort();
      const controller = new AbortController();
      state.current = controller;
      const user = document.createElement('p');
      user.className = 'doc-chat-question';
      user.textContent = question;
      welcome.hidden = true;
      reset.hidden = false;
      log.appendChild(user);
      const memory = state.memory;
      const result = answer(question, state.sources);
      const view = showAnswer(result);
      if (result.sources.length && !result.followUp) state.memory = question;
      input.value = '';
      send.disabled = true;
      scrollToBottom();
      if (result.sources.length && !result.precise) {
        void refine(result, view, question, result.followUp ? memory : '', controller);
      }
    }

    indexFor(buildIndex);
    at('docChatClose').addEventListener('click', close);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      submit(input.value.trim());
    });
    input.addEventListener('input', () => { send.disabled = !input.value.trim(); });
    at('docChatStart').querySelectorAll('button').forEach((button) => button.addEventListener('click', () => {
      submit(button.textContent.trim());
      if (matchMedia('(pointer: coarse)').matches) log.focus({ preventScroll: true });
      else input.focus({ preventScroll: true });
    }));
    reset.addEventListener('click', () => {
      state.current?.abort();
      state.memory = '';
      log.replaceChildren();
      welcome.hidden = false;
      reset.hidden = true;
      input.value = '';
      send.disabled = true;
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
