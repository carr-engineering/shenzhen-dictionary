// Recognition worker (ARCH-093, ARCH-197): runs OCR with ONNX Runtime Web and handwriting with
// TFLite Web, off the UI thread. One worker serves one model set ("ocr2" or "ink"); the page talks
// to it with { id, op, … } messages and gets { id, … } or { id, error } back. Geometry, image
// preparation and decoding stay in the shared Kotlin pipeline; this file only fetches, verifies
// and stores the model files (CAM-134) and evaluates graphs.
//
// Model files live under models/ next to this script and are described by models/manifest.json
// ({ version, sets: { ocr2: [paths], ink: [paths] }, files: { path: { size, sha256 } } }). A verified
// file is kept in Cache Storage under its content hash, with the manifest of its set beside it, so
// later loads never need the network; a changed manifest is downloaded in the background and takes
// effect the next time the set is loaded.
'use strict';

const BASE = new URL('models/', self.location.href).href;
const CACHE_NAME = 'szdict-models';
const RUNTIME_JS = 'runtime/tflite_web_api_cc_simd.js';
const RUNTIME_WASM = 'runtime/tflite_web_api_cc_simd.wasm';

// The runtime evaluates the quantised graphs with its XNNPACK kernels. Those round a few
// requantisations differently from the NEON kernels LiteRT uses on Android, so logits can differ by
// a quantisation step or two: the same text in practice, slightly different alternative scores.
let M = null;            // the runtime's Emscripten module
let ink = null;          // { graph, classes }
let ocr2 = null;         // { det, cls, rec }: ONNX Runtime Web sessions
const ORT_JS = 'ort-runtime/ort.wasm.min.js';
const ORT_MJS = 'ort-runtime/ort-wasm-simd-threaded.mjs';
const ORT_WASM = 'ort-runtime/ort-wasm-simd-threaded.wasm';

// ---------------------------------------------------------------- model files

const fileUrl = (path, sha) => BASE + path + '?sha=' + sha;
const setManifestUrl = (kind) => BASE + 'manifest.json?set=' + kind;

async function openCache() {
  try {
    return self.caches ? await caches.open(CACHE_NAME) : null;
  } catch (e) {
    return null;   // storage unavailable (private mode, insecure origin): work from the network only
  }
}

function subsetOf(manifest, kind) {
  const paths = manifest && manifest.sets && manifest.sets[kind];
  if (!Array.isArray(paths) || !manifest.files) throw new Error('Malformed model manifest');
  const files = {};
  for (const path of paths) {
    const entry = manifest.files[path];
    if (!entry || typeof entry.sha256 !== 'string' || !(entry.size > 0)) throw new Error('Malformed model manifest');
    files[path] = { size: entry.size, sha256: entry.sha256 };
  }
  return { version: String(manifest.version), files };
}

const sameSubset = (a, b) => JSON.stringify(Object.entries(a.files).sort()) === JSON.stringify(Object.entries(b.files).sort());

async function fetchManifest() {
  const response = await fetch(BASE + 'manifest.json', { cache: 'no-cache' });
  if (!response.ok) throw new Error('Model manifest: HTTP ' + response.status);
  return response.json();
}

async function storedSubset(cache, kind) {
  if (!cache) return null;
  try {
    const response = await cache.match(setManifestUrl(kind));
    return response ? await response.json() : null;
  } catch (e) {
    return null;
  }
}

/** Every file of [subset] from the cache, or null when one is missing. */
async function readStored(cache, subset) {
  const out = {};
  for (const [path, entry] of Object.entries(subset.files)) {
    const response = await cache.match(fileUrl(path, entry.sha256));
    if (!response) return null;
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength !== entry.size) return null;
    out[path] = bytes;
  }
  return out;
}

async function sha256Hex(buffer) {
  if (!(self.crypto && crypto.subtle)) return null;   // insecure origin: only the size can be checked
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', buffer));
  let hex = '';
  for (const b of digest) hex += b.toString(16).padStart(2, '0');
  return hex;
}

/** Downloads the files of [subset] that are not stored yet, verifies them and stores them. */
async function download(cache, subset, onProgress) {
  const out = {};
  const missing = [];
  for (const [path, entry] of Object.entries(subset.files)) {
    const hit = cache ? await cache.match(fileUrl(path, entry.sha256)) : null;
    const bytes = hit ? await hit.arrayBuffer() : null;
    if (bytes && bytes.byteLength === entry.size) out[path] = bytes;
    else missing.push([path, entry]);
  }
  const total = missing.reduce((sum, [, entry]) => sum + entry.size, 0);
  let loaded = 0;
  if (onProgress && total > 0) onProgress(0, total);
  await Promise.all(missing.map(async ([path, entry]) => {
    const response = await fetch(BASE + path, { cache: 'no-store' });
    if (!response.ok) throw new Error(path + ': HTTP ' + response.status);
    const bytes = new Uint8Array(entry.size);
    let at = 0;
    const reader = response.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (at + value.length > entry.size) throw new Error(path + ': larger than the manifest says');
      bytes.set(value, at);
      at += value.length;
      loaded += value.length;
      if (onProgress) onProgress(loaded, total);
    }
    if (at !== entry.size) throw new Error(path + ': incomplete download');
    const hash = await sha256Hex(bytes.buffer);
    // A file whose hash does not match is discarded and never loaded (CAM-134).
    if (hash !== null && hash !== entry.sha256) throw new Error(path + ': SHA-256 mismatch');
    if (cache) {
      try {
        await cache.put(fileUrl(path, entry.sha256), new Response(bytes.slice().buffer, { headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': String(entry.size) } }));
      } catch (e) { /* quota: usable for this session, downloaded again next time */ }
    }
    out[path] = bytes.buffer;
  }));
  return out;
}

async function storeSubset(cache, kind, subset) {
  if (!cache) return;
  try {
    await cache.put(setManifestUrl(kind), new Response(JSON.stringify(subset), { headers: { 'Content-Type': 'application/json' } }));
    // Drop files no stored set refers to any more.
    const wanted = new Set();
    for (const k of ['ocr2', 'ink']) {
      const s = k === kind ? subset : await storedSubset(cache, k);
      if (!s) continue;
      wanted.add(setManifestUrl(k));
      for (const [path, entry] of Object.entries(s.files)) wanted.add(fileUrl(path, entry.sha256));
    }
    for (const request of await cache.keys()) if (!wanted.has(request.url)) await cache.delete(request);
  } catch (e) { /* best effort */ }
}

async function forget(cache, kind, subset) {
  if (!cache) return;
  try {
    await cache.delete(setManifestUrl(kind));
    for (const [path, entry] of Object.entries(subset.files)) await cache.delete(fileUrl(path, entry.sha256));
  } catch (e) { /* best effort */ }
}

/** When online, a changed manifest is fetched in the background and swapped in for the next load. */
async function refresh(cache, kind, stored) {
  try {
    const published = subsetOf(await fetchManifest(), kind);
    if (sameSubset(published, stored)) return;
    await download(cache, published, null);
    await storeSubset(cache, kind, published);
  } catch (e) { /* the stored model stays in use; nothing is shown */ }
}

/**
 * The verified files of a set: from storage when they are there (no network needed), otherwise
 * downloaded with progress. Returns { files, stored } where `stored` tells whether they came from storage.
 */
async function obtain(kind, check, onProgress) {
  const cache = await openCache();
  const stored = await storedSubset(cache, kind);
  if (stored) {
    const files = await readStored(cache, stored).catch(() => null);
    if (files) {
      if (check) refresh(cache, kind, stored);
      return { files, subset: stored, stored: true, cache };
    }
  }
  const subset = subsetOf(await fetchManifest(), kind);
  const files = await download(cache, subset, onProgress);
  await storeSubset(cache, kind, subset);
  return { files, subset, stored: false, cache };
}

// ---------------------------------------------------------------- runtime and graphs

/** Reads what the runtime's tensor objects do not expose from the model's FlatBuffer. */
function describe(buffer) {
  const v = new DataView(buffer);
  const indirect = (p) => p + v.getUint32(p, true);
  const field = (table, index) => {
    const vtable = table - v.getInt32(table, true);
    const slot = 4 + index * 2;
    if (slot >= v.getUint16(vtable, true)) return 0;
    const offset = v.getUint16(vtable + slot, true);
    return offset ? table + offset : 0;
  };
  const model = indirect(0);
  const subgraph = indirect(indirect(field(model, 2)) + 4);
  const tensors = indirect(field(subgraph, 0));
  const tensorAt = (list, n) => indirect(tensors + 4 + v.getInt32(list + 4 + n * 4, true) * 4);
  const quant = (tensor) => {
    const q = field(tensor, 4);
    if (!q) return { scale: 0, zeroPoint: 0 };
    const table = indirect(q);
    const scales = field(table, 2);
    const zeros = field(table, 3);
    const scaleVector = scales ? indirect(scales) : 0;
    const zeroVector = zeros ? indirect(zeros) : 0;
    return {
      scale: scaleVector && v.getUint32(scaleVector, true) ? v.getFloat32(scaleVector + 4, true) : 0,
      zeroPoint: zeroVector && v.getUint32(zeroVector, true) ? Number(v.getBigInt64(zeroVector + 4, true)) : 0,
    };
  };
  const list = (index) => {
    const at = indirect(field(subgraph, index));
    const out = [];
    for (let n = 0; n < v.getUint32(at, true); n++) out.push(quant(tensorAt(at, n)));
    return out;
  };
  const shape = indirect(field(tensorAt(indirect(field(subgraph, 1)), 0), 0));
  return { inputs: list(1), outputs: list(2), shapeOffset: shape + 4, rank: v.getUint32(shape, true) };
}

/**
 * One model held in the runtime's memory. The runtime cannot resize inputs, so the first input's
 * shape is patched in the model bytes and the interpreter is built again for each new shape (cheap:
 * the weights are used in place).
 */
class Graph {
  constructor(buffer) {
    this.info = describe(buffer);
    this.size = buffer.byteLength;
    this.ptr = M._malloc(this.size);
    M.HEAPU8.set(new Uint8Array(buffer), this.ptr);
    this.runner = null;
    this.inputs = [];
    this.outputs = [];
    this.shape = '';
  }

  /** Builds the interpreter for [shape] (null: as stored); always from scratch when [fresh]. */
  open(shape, fresh) {
    const key = shape ? shape.join(',') : '';
    if (this.runner && !fresh && key === this.shape) return;
    this.close();
    if (shape) {
      if (shape.length !== this.info.rank) throw new Error('Input rank mismatch');
      const view = new DataView(M.HEAPU8.buffer, this.ptr + this.info.shapeOffset, shape.length * 4);
      shape.forEach((n, i) => view.setInt32(i * 4, n, true));
    }
    const created = M.TFLiteWebModelRunner.CreateFromBufferAndOptions(this.ptr, this.size, { numThreads: 1, enableProfiling: false, maxProfilingBufferEntries: 1024 });
    if (!created || !created.ok()) throw new Error('Could not load a recognition graph' + (created ? ': ' + created.errorMessage() : ''));
    this.runner = created.value();
    const toArray = (vector) => {
      const out = [];
      for (let i = 0; i < vector.size(); i++) out.push(vector.get(i));
      vector.delete();
      return out;
    };
    this.inputs = toArray(this.runner.GetInputs());
    this.outputs = toArray(this.runner.GetOutputs());
    this.shape = key;
  }

  infer() {
    if (!this.runner.Infer()) throw new Error('Recognition graph failed');
  }

  outputShape(index) {
    return String(this.outputs[index].shape).split(',').map(Number);
  }

  close() {
    if (this.runner) this.runner.delete();
    this.runner = null;
    this.inputs = [];
    this.outputs = [];
  }

  free() {
    this.close();
    M._free(this.ptr);
  }
}

async function startRuntime(files) {
  if (M) return;
  if (typeof self.tflite_web_api_ModuleFactory !== 'function') {
    const url = URL.createObjectURL(new Blob([files[RUNTIME_JS]], { type: 'text/javascript' }));
    try {
      importScripts(url);
    } catch (e) {
      importScripts(BASE + RUNTIME_JS);   // a content security policy without blob: scripts
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  M = await self.tflite_web_api_ModuleFactory({ wasmBinary: files[RUNTIME_WASM], print: () => {}, printErr: () => {} });
}

/** Four bidirectional LSTM layers: float [frames, 1, 10] in, [frames, 1, classes] out. */
function inferInk(features, frames) {
  // A fresh interpreter per drawing: its recurrent state must not carry over.
  ink.graph.open([frames, 1, 10], true);
  ink.graph.inputs[0].data().set(features);
  ink.graph.infer();
  const logits = ink.graph.outputs[0].data().slice();
  ink.graph.close();
  return { logits, classes: logits.length / frames };
}

const text = (buffer) => new TextDecoder('utf-8').decode(buffer);

async function buildOcr2(files) {
  const jsUrl = URL.createObjectURL(new Blob([files[ORT_JS]], { type: 'text/javascript' }));
  const mjsUrl = URL.createObjectURL(new Blob([files[ORT_MJS]], { type: 'text/javascript' }));
  const sessions = {};
  try {
    if (!self.ort) importScripts(jsUrl);
    // One thread works without cross-origin isolation; inference already has its own worker.
    ort.env.wasm.numThreads = 1;
    ort.env.wasm.proxy = false;
    ort.env.wasm.wasmPaths = { mjs: mjsUrl };
    ort.env.wasm.wasmBinary = files[ORT_WASM];
    for (const name of ['det', 'cls', 'rec']) {
      sessions[name] = await ort.InferenceSession.create(new Uint8Array(files['ocr2/' + name + '.onnx']), {
        executionProviders: ['wasm'], graphOptimizationLevel: 'all',
        enableCpuMemArena: false, enableMemPattern: false,
      });
    }
    ocr2 = sessions;
    return { labels: [text(files['ocr2/rec-labels.txt']), ''], classes: 0 };
  } catch (e) {
    await Promise.all(Object.values(sessions).map((session) => session.release()));
    throw e;
  } finally {
    URL.revokeObjectURL(jsUrl);
    URL.revokeObjectURL(mjsUrl);
  }
}

async function inferOcr2(model, data, shape) {
  if (!['det', 'cls', 'rec'].includes(model) || !ocr2) throw new Error('OCR model is not loaded');
  if (!(data instanceof Float32Array) || shape.length !== 4 || shape[1] !== 3 ||
      shape.some((n) => !Number.isInteger(n) || n <= 0) ||
      shape.reduce((a, b) => a * b, 1) !== data.length) throw new Error('Invalid OCR input tensor');
  const session = ocr2[model];
  const input = new ort.Tensor('float32', data, shape);
  let outputs;
  try {
    outputs = await session.run({ [session.inputNames[0]]: input });
    const output = outputs[session.outputNames[0]];
    if (output.type !== 'float32') throw new Error('Unexpected OCR output type: ' + output.type);
    return { data: output.data.slice(), frames: output.dims.length === 3 ? output.dims[1] : 0,
      classes: output.dims[output.dims.length - 1] };
  } finally {
    input.dispose();
    if (outputs) for (const output of Object.values(outputs)) output.dispose();
  }
}

async function load(id, kind, check) {
  let reported = -1;
  const progress = (loaded, total) => {
    // One message per percent is plenty for a progress bar.
    const step = Math.floor(loaded * 100 / Math.max(1, total));
    if (step === reported) return;
    reported = step;
    postMessage({ id, progress: true, loaded, total });
  };
  let got = await obtain(kind, check, progress);
  postMessage({ id, progress: true, loaded: -1, total: 0 });   // downloaded: now loading
  const build = async (files) => {
    if (kind === 'ocr2') return buildOcr2(files);
    await startRuntime(files);
    const graph = new Graph(files['ink/chinese_lstm_4x192.tflite']);
    graph.open([1, 1, 10], true);
    const classes = graph.outputShape(0).pop();
    graph.close();
    ink = { graph, classes };
    return { labels: [text(files['ink/labels.txt']), text(files['ink/script-classes.txt'])], classes };
  };
  try {
    return await build(got.files);
  } catch (e) {
    if (!got.stored) throw e;
    // A stored model that fails to load is deleted and downloaded again as on first use (CAM-134).
    await forget(got.cache, kind, got.subset);
    got = await obtain(kind, false, progress);
    postMessage({ id, progress: true, loaded: -1, total: 0 });
    return build(got.files);
  }
}

let queue = Promise.resolve();
self.onmessage = (event) => {
  const message = event.data;
  queue = queue.then(async () => {
    try {
      switch (message.op) {
        case 'ocr2': {
          const out = await inferOcr2(message.model, message.data, message.shape);
          postMessage({ id: message.id, ...out }, [out.data.buffer]);
          break;
        }
        case 'load': {
          const loaded = await load(message.id, message.kind, message.check);
          postMessage({ id: message.id, labels: loaded.labels, classes: loaded.classes });
          break;
        }
        case 'ink': {
          const out = inferInk(message.features, message.frames);
          postMessage({ id: message.id, ...out }, [out.logits.buffer]);
          break;
        }
        default:
          throw new Error('Unknown operation');
      }
    } catch (e) {
      postMessage({ id: message.id, error: String((e && e.message) || e) });
    }
  });
};
