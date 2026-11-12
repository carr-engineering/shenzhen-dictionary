// The store worker (ARCH-026, ARCH-145): owns the dataset files and answers the message protocol of
// the androidx.sqlite web driver (open / prepare / step / close) with sqlite-wasm.
//
// Files are named by their dataset-relative URL ("data/mandarin.db", "data/packs/sentences.db").
// A file is fetched the first time it is opened and kept in OPFS (opfs-sahpool VFS) under the
// content tag of data/manifest.json, so later visits open it without the network and a changed
// tag downloads it again. Where OPFS cannot be used (unsupported, or held by another tab) the
// file is loaded into memory for this page instead.
//
// Besides protocol responses the worker posts events with id -1:
//   { evt: "download", file, loaded, total, done, error? }   progress of a fetch
//   { evt: "storage", persistent }                           whether OPFS is in use
import sqlite3InitModule from './sqlite3/index.mjs';

const POOL_DIRECTORY = '.szdict-sqlite';
const MANIFEST_URL = 'data/manifest.json';

let sqlite3 = null;
let pool = null;
let manifestPromise = null;
let nextId = 1;
const databases = new Map();
const statements = new Map();

const notify = (data) => self.postMessage({ id: -1, data });

const ready = (async () => {
  sqlite3 = await sqlite3InitModule({ print: () => {}, printErr: () => {} });
  try {
    pool = await sqlite3.installOpfsSAHPoolVfs({ name: 'szdict-pool', directory: POOL_DIRECTORY, initialCapacity: 16, verbosity: 0 });
  } catch (e) {
    pool = null;
  }
  notify({ evt: 'storage', persistent: pool !== null });
})();

function manifest() {
  if (!manifestPromise) {
    manifestPromise = fetch(MANIFEST_URL, { cache: 'no-cache' })
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null);
  }
  return manifestPromise;
}

/** Streams `url`, reporting progress; `sink` receives each chunk. */
async function download(url, expectedSize, sink) {
  let loaded = 0;
  let total = expectedSize || 0;
  notify({ evt: 'download', file: url, loaded, total, done: false });
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
    if (!total) total = Number(response.headers.get('Content-Length')) || 0;
    const reader = response.body.getReader();
    let lastReport = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      await sink(value);
      loaded += value.byteLength;
      const now = Date.now();
      if (now - lastReport > 100) {
        lastReport = now;
        notify({ evt: 'download', file: url, loaded, total: Math.max(total, loaded), done: false });
      }
    }
    notify({ evt: 'download', file: url, loaded, total: loaded, done: true });
    return loaded;
  } catch (e) {
    notify({ evt: 'download', file: url, loaded, total, done: true, error: String((e && e.message) || e) });
    throw e;
  }
}

// One import at a time: a chunked import claims its pool slot only when it completes.
let importChain = Promise.resolve();
const pending = new Map();

/** Makes `name` available in the pool and returns its pool file name. */
function ensurePooled(name) {
  let p = pending.get(name);
  if (!p) {
    p = importChain.then(() => ensurePooledNow(name));
    importChain = p.catch(() => {});
    pending.set(name, p);
    p.catch(() => {}).then(() => pending.delete(name));
  }
  return p;
}

async function ensurePooledNow(name) {
  const m = await manifest();
  const wanted = m && m.files ? m.files[name.replace(/^data\//, '')] : null;
  const prefix = '/' + name + '@';
  const installed = pool.getFileNames().filter((n) => n.startsWith(prefix));
  const target = prefix + (wanted ? wanted.tag : 'unversioned');
  if (installed.includes(target)) return target;
  // Offline, or the manifest does not list the file: any installed version serves.
  if (!wanted && installed.length) return installed[0];
  try {
    await pool.reserveMinimumCapacity(pool.getFileCount() + 4);
    const chunks = [];
    let finished = false;
    // importDb pulls chunks; the fetch pushes them — bridge the two with a one-slot hand-over.
    let wake = null;
    const fetching = download(name, wanted ? wanted.size : 0, async (chunk) => {
      chunks.push(chunk);
      if (wake) { wake(); wake = null; }
    }).then(() => { finished = true; if (wake) { wake(); wake = null; } });
    let failure = null;
    fetching.catch((e) => { failure = e; if (wake) { wake(); wake = null; } });
    await pool.importDb(target, async () => {
      for (;;) {
        if (chunks.length) return chunks.shift();
        if (failure) throw failure;
        if (finished) return undefined;
        await new Promise((resolve) => { wake = resolve; });
      }
    });
    for (const old of installed) if (old !== target) pool.unlink(old);
    return target;
  } catch (e) {
    // A failed update keeps the installed version in use (it is retried on the next visit).
    if (installed.length) return installed[0];
    throw e;
  }
}

async function open(name) {
  await ready;
  let db;
  if (pool) {
    const target = await ensurePooled(name);
    db = new sqlite3.oo1.DB(target, 'r', pool.vfsName);
  } else {
    const parts = [];
    const size = await download(name, 0, async (chunk) => { parts.push(chunk); });
    const bytes = new Uint8Array(size);
    let at = 0;
    for (const part of parts) { bytes.set(part, at); at += part.byteLength; }
    const p = sqlite3.wasm.allocFromTypedArray(bytes);
    db = new sqlite3.oo1.DB();
    const rc = sqlite3.capi.sqlite3_deserialize(
      db.pointer, 'main', p, size, size,
      sqlite3.capi.SQLITE_DESERIALIZE_FREEONCLOSE | sqlite3.capi.SQLITE_DESERIALIZE_READONLY,
    );
    db.checkRc(rc);
  }
  const databaseId = nextId++;
  databases.set(databaseId, db);
  return { databaseId };
}

function prepare({ databaseId, sql }) {
  const db = databases.get(databaseId);
  if (!db) throw new Error('unknown database ' + databaseId);
  const stmt = db.prepare(sql);
  const statementId = nextId++;
  statements.set(statementId, stmt);
  return {
    statementId,
    parameterCount: stmt.parameterCount,
    columnNames: stmt.columnCount ? stmt.getColumnNames([]) : [],
  };
}

function step({ statementId, bindings }) {
  const stmt = statements.get(statementId);
  if (!stmt) throw new Error('unknown statement ' + statementId);
  stmt.reset();
  stmt.clearBindings();
  for (let i = 0; i < bindings.length; i++) {
    const value = bindings[i];
    if (value !== null && value !== undefined) stmt.bind(i + 1, value);
  }
  const rows = [];
  let columnTypes = [];
  try {
    while (stmt.step()) {
      if (!rows.length) {
        for (let i = 0; i < stmt.columnCount; i++) columnTypes.push(sqlite3.capi.sqlite3_column_type(stmt.pointer, i));
      }
      rows.push(stmt.get([]));
    }
  } finally {
    stmt.reset();
  }
  return { rows, columnTypes };
}

function close({ statementId, databaseId }) {
  if (statementId !== null && statementId !== undefined) {
    const stmt = statements.get(statementId);
    statements.delete(statementId);
    if (stmt) stmt.finalize();
  }
  if (databaseId !== null && databaseId !== undefined) {
    const db = databases.get(databaseId);
    databases.delete(databaseId);
    if (db) db.close();
  }
}

// Queries run in arrival order; an open's download does not hold them up.
let chain = ready;
self.onmessage = (event) => {
  const { id, data } = event.data;
  const fail = (e) => self.postMessage({ id, error: String((e && e.message) || e) });
  if (data.cmd === 'open') {
    open(data.fileName).then((result) => self.postMessage({ id, data: result }), fail);
    return;
  }
  chain = chain.then(() => {
    try {
      switch (data.cmd) {
        case 'prepare': self.postMessage({ id, data: prepare(data) }); break;
        case 'step': self.postMessage({ id, data: step(data) }); break;
        case 'close': close(data); break;
        default: throw new Error('unknown command ' + data.cmd);
      }
    } catch (e) {
      if (data.cmd !== 'close') fail(e);
    }
  });
};
