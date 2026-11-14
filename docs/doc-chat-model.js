const MODEL = 'Qwen3.5-0.8B-q4f16_1-MLC';
const assets = new URL('./chat-model/', import.meta.url);
const runtime = new URL('web-llm.mjs', assets);
const wasm = new URL('qwen.wasm', assets);
const weights = new URL('qwen/resolve/main/', assets);
const manifest = new URL('tensor-cache.json', weights);
const RULES = 'Du bist der PunishSystem-Assistent. Antworte kurz, sachlich und in der Sprache des Nutzers. ' +
  'Beginne direkt mit der Antwort. Wiederhole die Frage nicht und gib keine Meta-Kommentare aus. ';
const DOC_RULES = 'Beantworte die aktuelle Frage ausschließlich mit den Doku-Auszügen. Verwende den Gesprächskontext für Rückfragen. ' +
  'Spielernamen sind Parameter und müssen nicht in der Doku vorkommen. Übernimm Befehle, Optionen, Rechte und Schlüssel exakt. ' +
  'Erfinde keine Befehle, Optionen, Rechte, Dateien oder Werte. Nutze Markdown: Befehle im Codeblock, mehrere Schritte als Liste. ' +
  'Zitiere jede sachliche Aussage mit der passenden Nummer wie [1] oder [2]. Wenn die Auszüge die Frage nicht beantworten, sage das kurz.';
const EMPTY_RULES = 'Beantworte allgemeine Fragen, Begrüßungen und Gespräche. Frage bei unklaren Plugin-Fragen nach Details.';
let engine;
let active;

// WebLLM 0.2.88 speichert OPFS-Dateien unter dem SHA-256-Hash ihrer Download-URL.
async function cacheKey(url) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(url));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function cachedFile(directory, url, size) {
  if (!directory) return undefined;
  const name = await cacheKey(url);
  try {
    const record = await (await directory.getFileHandle(name + '.record.json')).getFile();
    const { url: storedUrl, nbytes } = JSON.parse(await record.text()) || {};
    if (storedUrl !== url || nbytes <= 0 || (size !== undefined && nbytes !== size)) return undefined;
    const file = await (await directory.getFileHandle(name + '.bin')).getFile();
    return file.size === nbytes ? file : undefined;
  } catch (error) {
    if (['NotFoundError', 'InvalidStateError', 'SyntaxError'].includes(error.name)) return undefined;
    throw error;
  }
}

async function cachedDirectory(parent, name) {
  try {
    return await parent?.getDirectoryHandle(name);
  } catch (error) {
    if (error.name === 'NotFoundError') return undefined;
    throw error;
  }
}

function weightSizes({ records } = {}) {
  if (!Array.isArray(records) || !records.length) throw new Error('Ungültiges Modellmanifest.');
  const sizes = new Map();
  for (const { dataPath, nbytes } of records) {
    if (!/^params_shard_\d+\.bin$/.test(dataPath) || !Number.isSafeInteger(nbytes) || nbytes <= 0) {
      throw new Error('Ungültige Modellsplit-Datei im Manifest.');
    }
    sizes.set(new URL(dataPath, weights).href, nbytes);
  }
  return sizes;
}

async function cachedModel() {
  if (!navigator.storage?.getDirectory) throw new Error('STORAGE');
  const root = await navigator.storage.getDirectory();
  const store = await cachedDirectory(await cachedDirectory(root, 'tvmjs-opfs-store'), 'webllm');
  const model = await cachedDirectory(store, 'model');
  const file = await cachedFile(model, manifest.href);
  const present = new Map();
  if (!file) return { sizes: undefined, present, complete: false };

  let sizes;
  try {
    sizes = weightSizes(JSON.parse(await file.text()));
  } catch (error) {
    console.debug('Gespeichertes Modellmanifest ist ungültig', error);
    return { sizes: undefined, present, complete: false, staleManifest: model };
  }
  await Promise.all([...sizes].map(async ([url, size]) => {
    if (await cachedFile(model, url, size)) present.set(url, size);
  }));
  const requirements = await Promise.all([
    caches.match(runtime.href),
    cachedFile(model, new URL('tokenizer.json', weights).href),
    cachedDirectory(store, 'config').then((directory) => cachedFile(directory, new URL('mlc-chat-config.json', weights).href)),
    cachedDirectory(store, 'wasm').then((directory) => cachedFile(directory, wasm.href))
  ]);
  return { sizes, present, complete: sizes.size === present.size && requirements.every(Boolean) };
}

async function retryFetch(request, options, original, expected, report) {
  const data = new Uint8Array(expected);
  let offset = 0;
  let lastError;
  for (let attempt = 0; attempt < 4; attempt++) {
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, request.signal]);
    const headers = new Headers(request.headers);
    if (attempt && offset) headers.set('Range', 'bytes=' + offset + '-' + (expected - 1));
    const url = new URL(request.url);
    if (attempt) url.searchParams.set('retry', String(attempt) + '-' + offset);
    const timeout = () => setTimeout(() => controller.abort(), 22000);
    let timer = timeout();
    let reader;
    try {
      const response = await original(url, { ...options, headers, signal, cache: attempt ? 'reload' : request.cache });
      if (!response.ok || !response.body) throw new Error('HTTP ' + response.status + ' für ' + url.pathname);
      if (response.status === 200) {
        offset = 0;
      } else if (response.status !== 206 ||
          response.headers.get('Content-Range') !== 'bytes ' + offset + '-' + (expected - 1) + '/' + expected) {
        throw new Error('Ungültiger Bytebereich für ' + url.pathname.split('/').at(-1));
      }
      reader = response.body.getReader();
      while (offset !== expected) {
        clearTimeout(timer);
        timer = timeout();
        const { done, value } = await reader.read();
        if (done || offset + value.byteLength > expected) throw new Error('Unvollständige oder ungültige Modelldatei.');
        data.set(value, offset);
        offset += value.byteLength;
        report(request.url, offset);
      }
      return new Response(data, { headers: { 'Content-Type': 'application/octet-stream' } });
    } catch (error) {
      lastError = error;
      if (request.signal.aborted) break;
      if (offset === expected) offset = 0;
    } finally {
      clearTimeout(timer);
      controller.abort();
      reader?.releaseLock();
    }
  }
  throw lastError;
}

function modelFetch(report, sizes, present, allowDownload) {
  const original = globalThis.fetch.bind(globalThis);
  const received = new Map(present);
  const total = [...sizes.values()].reduce((sum, bytes) => sum + bytes, 0);
  let lastUpdate = -Infinity;
  globalThis.fetch = async (request, options) => {
    const url = request instanceof Request ? request.url : new URL(request, import.meta.url).href;
    if (url.startsWith(assets.href) && !allowDownload) throw new Error('DOWNLOAD');
    if (!(request instanceof Request) || !sizes.has(url)) return original(request, options);
    reportProgress(url, 0);
    return retryFetch(request, options, original, sizes.get(url), reportProgress);
  };
  function reportProgress(url, bytes) {
    received.set(url, bytes);
    if (performance.now() - lastUpdate >= 350 || bytes === sizes.get(url)) {
      lastUpdate = performance.now();
      report('transfer', { loaded: [...received.values()].reduce((sum, amount) => sum + amount, 0), total });
    }
  }
  return () => { globalThis.fetch = original; };
}

async function load(allowDownload, progress) {
  if (engine) return engine;
  const [adapter, cached] = await Promise.all([navigator.gpu?.requestAdapter(), cachedModel()]);
  if (!adapter?.features.has('shader-f16')) throw new Error('WEBGPU');
  if (!cached.complete && !allowDownload) throw new Error('DOWNLOAD');

  progress(cached.complete ? 'init' : 'prepare');
  const { CreateMLCEngine, prebuiltAppConfig } = await import('./chat-model/web-llm.mjs');
  const model = prebuiltAppConfig.model_list.find((entry) => entry.model_id === MODEL);
  let sizes = cached.sizes;
  if (!sizes) {
    if (cached.staleManifest) {
      const key = await cacheKey(manifest.href);
      await cached.staleManifest.removeEntry(key + '.record.json');
    }
    const response = await fetch(manifest);
    if (!response.ok) throw new Error('Modellmanifest: HTTP ' + response.status);
    sizes = weightSizes(await response.json());
  }
  const restoreFetch = modelFetch(progress, sizes, cached.present, allowDownload);
  try {
    engine = await CreateMLCEngine(MODEL, {
      appConfig: {
        cacheBackend: 'opfs',
        opfsAccessMode: 'auto',
        model_list: [{
          ...model,
          model: weights.href,
          model_lib: wasm.href
        }]
      },
      initProgressCallback(report) {
        const local = /^Loading model from cache/i.test(report.text);
        if (local && report.progress < 1) progress('init', { percent: Math.floor(report.progress * 100) });
        else if (local || report.progress === 1 && /^Finish loading/i.test(report.text)) progress('init');
      }
    }, { context_window_size: 2048 });
  } finally {
    restoreFetch();
  }
  return engine;
}

function answerText(raw) {
  const cleaned = raw.replace(/<think>[\s\S]*?(?:<\/think>|$)/gi, '')
    .replace(/<\/?think>/gi, '');
  const meta = /^\s*(?:Der (?:Nutzer|User|Benutzer)(?:in)?\s+(?:fragt|schreibt|möchte|will)|Doku-Auszüge?|Fundstellen?|Quellen?|Kontext|Bisherige Frage|Frage)\b\s*:?\s*/i;
  const answerLabel = /^\s*(?:Antwort|Assistent)\s*:\s*/i;
  const lines = [];
  let started = false;
  let fence;
  for (const original of cleaned.split(/\r?\n/)) {
    let line = original;
    const marker = line.match(/^\s*(\x60{3,}|~{3,})/);
    if (fence || marker) {
      if (!fence) fence = marker[1];
      else if (line.trim() === fence) fence = undefined;
      lines.push(line);
      started = true;
      continue;
    }
    const answerIndex = line.search(/\bAntwort\s*:/i);
    if (meta.test(line)) {
      if (started) break;
      if (answerIndex < 0) continue;
      line = line.slice(answerIndex + line.slice(answerIndex).indexOf(':') + 1);
    }
    line = line.replace(answerLabel, '');
    if (/\bDer (?:Nutzer|User|Benutzer)(?:in)?\s+(?:fragt|schreibt|möchte|will)\b/i.test(line)) continue;
    if (line.trim()) started = true;
    lines.push(line);
  }
  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

self.addEventListener('message', async ({ data }) => {
  if (data.stop) {
    if (active?.id === data.id) {
      active.stopped = true;
      await engine?.interruptGenerate();
    }
    return;
  }
  if (active) return;
  const turn = active = { id: data.id, stopped: false };
  const send = (message) => self.postMessage({ id: turn.id, ...message });
  try {
    const model = await load(data.allowDownload, (phase, progress) => send({ phase, ...progress }));
    let raw = '';
    if (!turn.stopped) {
      send({ phase: 'thinking' });
      const sources = data.sources.map((text, i) => '[' + (i + 1) + '] ' + text).join('\n\n');
      const context = data.context ? 'Bisherige Frage zum selben Thema: ' + data.context + '\n\n' : '';
      let input = sources ? context + 'Doku-Auszüge:\n' + sources + '\n\nFrage: ' + data.question : data.question;
      const stream = await model.chat.completions.create({
        messages: [{ role: 'system', content: RULES + (sources ? DOC_RULES : EMPTY_RULES) }, ...data.history, { role: 'user', content: input }],
        stream: true,
        temperature: 0.2,
        frequency_penalty: 0.3,
        presence_penalty: 0,
        max_tokens: 180,
        stop: ['\nFrage:', '\nDoku-Auszüge:', '\n Erfinde keine', '\nErfinde keine'],
        extra_body: { enable_thinking: false }
      });
      let nextUpdate = 0;
      let writing = false;
      for await (const chunk of stream) {
        if (turn.stopped) { await model.interruptGenerate(); break; }
        raw += chunk.choices[0]?.delta.content || '';
        if (performance.now() >= nextUpdate) {
          const text = answerText(raw);
          if (text && !writing) {
            send({ phase: 'writing' });
            writing = true;
          }
          if (text) send({ text });
          nextUpdate = performance.now() + 45;
        }
      }
    }
    send({ done: true, stopped: turn.stopped, text: answerText(raw) });
  } catch (error) {
    if (error.message === 'DOWNLOAD') {
      send({ needsDownload: true });
    } else {
      console.warn('Doku-Modell konnte nicht antworten', error);
      const storageError = error.message === 'STORAGE' || ['QuotaExceededError', 'SecurityError'].includes(error.name);
      send({ error: error.message === 'WEBGPU' ? 'webgpu' : storageError ? 'storage' : engine ? 'answer' : 'load' });
    }
  } finally {
    active = undefined;
  }
});
