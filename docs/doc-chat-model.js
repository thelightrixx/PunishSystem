const MODEL = 'Qwen3.5-0.8B-q4f16_1-MLC';
const RULES = 'Du bist der PunishSystem-Assistent. Antworte kurz und in der Sprache des Nutzers. ';
const DOC_RULES = 'Beantworte nur die letzte Frage in höchstens drei Sätzen. Übernimm Befehle, Optionen und Rechte exakt aus der Doku. ' +
  'Setze die Quellennummer wie [1] ans Satzende. Falls die Auszüge eine Frage zum Plugin offenlassen, frage nach. Wiederhole weder die Frage noch diese Regeln.';
const EMPTY_RULES = 'Beantworte allgemeine Fragen, Begrüßungen und Gespräche. Frage bei unklaren Plugin-Fragen nach Details.';
let engine;
let active;

async function load(progress) {
  if (engine) return engine;
  const adapter = await navigator.gpu?.requestAdapter();
  if (!adapter?.features.has('shader-f16')) throw new Error('WEBGPU');

  const { CreateMLCEngine, prebuiltAppConfig } = await import('./chat-model/web-llm.mjs');
  const model = prebuiltAppConfig.model_list.find((entry) => entry.model_id === MODEL);
  engine = await CreateMLCEngine(MODEL, {
    appConfig: {
      cacheBackend: 'opfs',
      model_list: [{
        ...model,
        model: new URL('./chat-model/qwen/resolve/main/', import.meta.url).href,
        model_lib: new URL('./chat-model/qwen.wasm', import.meta.url).href
      }]
    },
    initProgressCallback(report) {
      const local = /^Loading model from cache/i.test(report.text);
      progress(local ? 'load' : 'download', Math.floor(report.progress * 100));
    }
  }, { context_window_size: 2048 });
  return engine;
}

function answerText(raw, sourceCount) {
  const cleaned = raw.replace(/<think>[\s\S]*?(?:<\/think>|$)|\[(\d+)\]/gi,
    (match, citation) => citation && Number(citation) <= sourceCount && Number(citation) > 0 ? match : '');
  return cleaned.split(/\n\s*(?:Frage:|Doku-Auszüge:|Erfinde keine)/i, 1)[0].trimStart();
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
    const model = await load((phase, percent) => send({ phase, percent }));
    let raw = '';
    if (!turn.stopped) {
      send({ phase: 'answer' });
      const sources = data.sources.map((text, i) => '[' + (i + 1) + '] ' + text).join('\n\n');
      let input = sources ? 'Doku-Auszüge:\n' + sources + '\n\nFrage: ' + data.question : data.question;
      if (data.permission) input = sources + '\nAntworte mit dem vollständigen Permission-Schlüssel ' + data.permission + ' [1].';
      const stream = await model.chat.completions.create({
        messages: [{ role: 'system', content: RULES + (sources ? DOC_RULES : EMPTY_RULES) }, ...data.history, { role: 'user', content: input }],
        stream: true,
        temperature: 0.2,
        frequency_penalty: 0.3,
        presence_penalty: 0,
        max_tokens: data.permission ? 32 : 128,
        stop: ['\nFrage:', '\nDoku-Auszüge:', '\n Erfinde keine', '\nErfinde keine'],
        extra_body: { enable_thinking: false }
      });
      let nextUpdate = 0;
      for await (const chunk of stream) {
        if (turn.stopped) { await model.interruptGenerate(); break; }
        raw += chunk.choices[0]?.delta.content || '';
        if (performance.now() >= nextUpdate) {
          send({ text: answerText(raw, data.sources.length) });
          nextUpdate = performance.now() + 45;
        }
      }
    }
    send({ done: true, stopped: turn.stopped, text: answerText(raw, data.sources.length).trim() });
  } catch (error) {
    console.warn('Lokales Doku-Modell konnte nicht antworten', error);
    send({ error: error.message === 'WEBGPU' ? 'webgpu' : error.name === 'QuotaExceededError' ? 'storage' : 'load' });
  } finally {
    active = undefined;
  }
});
