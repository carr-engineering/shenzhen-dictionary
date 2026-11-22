// The store worker (ARCH-026, ARCH-145): owns the dataset files and answers the message protocol of
// the androidx.sqlite web driver (open / prepare / step / close) with sqlite-wasm.
//
// Files are named by their dataset-relative URL ("data/mandarin.db", "data/packs/sentences.db").
// A file is fetched the first time it is opened, verified against the SHA-256 of data/manifest.json
// and kept in OPFS (opfs-sahpool VFS). Which stored file is the installed one is recorded in a
// pointer file, written only after verification, so an interrupted or corrupt download is never
// opened. An installed file opens without the network; the manifest is checked in the background
// afterwards, a newer version is downloaded and verified beside the one in use, and one write of
// the pointer file makes it the version the next visit opens (the old file is deleted then).
// Where OPFS cannot be used (unsupported, or held by another tab) the file is loaded into memory
// for this page instead: copied from the tab that holds the store when there is one, downloaded
// otherwise.
//
// Keeping what is stored (a visit must not download the dataset again):
// - The pool's files can be open in one worker only. The worker that holds the pool holds the Web
//   Lock "szdict-store" for its whole life; a new worker waits for it, so a reload, a navigation or
//   the reload of a service-worker update does not race the previous page's worker. The start page
//   also ends its worker when the page is hidden (index.html), which frees both at once.
// - Opening the pool is retried while the previous holder lets go, and a failed opening never
//   deletes the stored files (sqlite-wasm's default is to remove the pool directory when its
//   initialisation fails: `preserveOnInitFailure`).
// - A download that is interrupted (page closed or reloaded, network lost) continues where it
//   stopped on the next attempt: it is written to a partial file and the rest is asked for with an
//   HTTP Range request (see "resumable downloads" below).
// - Files the pointer file does not name are deleted only when the pointer file was read; when it
//   cannot be read, a stored file whose name carries the manifest's SHA-256 is verified and adopted
//   instead of being downloaded again.
//
// Besides protocol responses the worker posts events with id -1:
//   { evt: "download", file, loaded, total, done, error?, background }   progress of a fetch
//   { evt: "storage", persistent }                                      whether OPFS is in use
//   { evt: "updated", version }                                         a newer dataset is installed for the next visit
// and tells the other tabs about an installed update on the BroadcastChannel "szdict-datasets".
import sqlite3InitModule from './sqlite3/index.mjs';

const POOL_DIRECTORY = '.szdict-sqlite';
const MANIFEST_URL = 'data/manifest.json';
const POINTER_FILE = 'szdict-datasets.json';
const FIRST_CHECK_MS = 4000;
const RECHECK_MS = 6 * 60 * 60 * 1000;
const STORE_LOCK = 'szdict-store';
/** How long a new worker waits for the previous holder of the store (a page being unloaded) to end. */
const LOCK_WAIT_MS = 4000;
/** Delays between attempts to open the pool while its files are still held elsewhere. */
const POOL_RETRY_MS = [100, 250, 500, 1000, 2000];
const SHARE_CHANNEL = 'szdict-store-share';
const SHARE_WAIT_MS = 3000;

let sqlite3 = null;
let pool = null;
/** name → { file, sha256, version }: the verified, installed files. */
let pointers = {};
let nextId = 1;
const databases = new Map();
const statements = new Map();

const notify = (data) => self.postMessage({ id: -1, data });

// ---- SHA-256 over a stream of chunks (WebCrypto digests only whole buffers) ----

const K = new Int32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be,
  0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
  0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85,
  0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
  0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
  0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

class Sha256 {
  constructor() {
    this.h = new Int32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
    this.w = new Int32Array(64);
    this.tail = new Uint8Array(64);
    this.fill = 0;
    this.length = 0;
  }

  update(data) {
    let i = 0;
    this.length += data.length;
    if (this.fill) {
      while (i < data.length && this.fill < 64) this.tail[this.fill++] = data[i++];
      if (this.fill < 64) return;
      this.block(this.tail, 0);
      this.fill = 0;
    }
    for (; i + 64 <= data.length; i += 64) this.block(data, i);
    while (i < data.length) this.tail[this.fill++] = data[i++];
  }

  block(p, o) {
    const w = this.w;
    const h = this.h;
    for (let t = 0; t < 16; t++, o += 4) w[t] = (p[o] << 24) | (p[o + 1] << 16) | (p[o + 2] << 8) | p[o + 3];
    for (let t = 16; t < 64; t++) {
      const x = w[t - 15];
      const y = w[t - 2];
      const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    let a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
    for (let t = 0; t < 64; t++) {
      const t1 = (k + (((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7))) + ((e & f) ^ (~e & g)) + K[t] + w[t]) | 0;
      const t2 = ((((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10))) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      k = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + k) | 0;
  }

  hex() {
    const bytes = this.length;
    const pad = new Uint8Array((this.fill < 56 ? 56 : 120) - this.fill + 8);
    pad[0] = 0x80;
    const view = new DataView(pad.buffer);
    view.setUint32(pad.length - 8, Math.floor(bytes / 0x20000000));
    view.setUint32(pad.length - 4, (bytes << 3) >>> 0);
    this.update(pad);
    let out = '';
    for (const word of this.h) out += (word >>> 0).toString(16).padStart(8, '0');
    return out;
  }
}

// ---- the pointer file ----

/**
 * The installed files and whether the pointer file was read. A pointer file that is not there yet
 * is a read of nothing; one that cannot be read or parsed is not trusted to say what to delete.
 */
async function readPointers() {
  let file;
  try {
    const root = await navigator.storage.getDirectory();
    file = await (await root.getFileHandle(POINTER_FILE)).getFile();
  } catch (e) {
    return { pointers: {}, trusted: !!e && e.name === 'NotFoundError' };
  }
  try {
    const parsed = JSON.parse(await file.text());
    return parsed && typeof parsed === 'object' ? { pointers: parsed, trusted: true } : { pointers: {}, trusted: false };
  } catch (e) {
    return { pointers: {}, trusted: false };
  }
}

/** One write swaps every changed file at once (dataset, companion and packs stay one version). */
async function writePointers() {
  const root = await navigator.storage.getDirectory();
  const handle = await root.getFileHandle(POINTER_FILE, { create: true });
  const bytes = new TextEncoder().encode(JSON.stringify(pointers));
  if (handle.createWritable) {
    // Written to a temporary file that replaces the pointer file when it is closed.
    const writable = await handle.createWritable();
    await writable.write(bytes);
    await writable.close();
  } else {
    const access = await handle.createSyncAccessHandle();
    try {
      // Written over the old content and then cut to length: the file is never empty in between.
      access.write(bytes, { at: 0 });
      access.truncate(bytes.byteLength);
      access.flush();
    } finally {
      access.close();
    }
  }
}

// ---- holding the store ----

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function opfsAvailable() {
  return !!(self.navigator && navigator.storage && navigator.storage.getDirectory &&
    self.FileSystemFileHandle && FileSystemFileHandle.prototype.createSyncAccessHandle);
}

let releaseStoreLock = () => {};

/**
 * Takes the Web Lock that says which worker holds the pool, and keeps it until this worker ends.
 * True when it is held (or Web Locks are not supported); false when another worker kept it longer
 * than a page takes to unload — another open tab.
 */
function takeStoreLock() {
  if (!navigator.locks || !navigator.locks.request) return Promise.resolve(true);
  return new Promise((resolve) => {
    let settled = false;
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      if (controller) controller.abort();
      resolve(false);
    }, LOCK_WAIT_MS);
    navigator.locks.request(STORE_LOCK, controller ? { signal: controller.signal } : {}, () => {
      if (settled) return undefined;   // granted after giving up: let it go at once
      settled = true;
      clearTimeout(timer);
      resolve(true);
      return new Promise((release) => { releaseStoreLock = release; });
    }).catch(() => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(false);
    });
  });
}

/** Opens the pool, retrying while the previous holder's handles are being released. */
async function installPool() {
  for (let attempt = 0; ; attempt++) {
    try {
      return await sqlite3.installOpfsSAHPoolVfs({
        name: 'szdict-pool',
        directory: POOL_DIRECTORY,
        initialCapacity: 16,
        verbosity: 0,
        // A failed opening must leave the stored datasets alone (the default deletes the pool) …
        preserveOnInitFailure: true,
        // … and be tried again rather than answered from the cached failure.
        forceReinitIfPreviouslyFailed: true,
      });
    } catch (e) {
      if (attempt >= POOL_RETRY_MS.length) throw e;
      await sleep(POOL_RETRY_MS[attempt]);
    }
  }
}

/** The holder of the pool hands an installed file to a tab that could not open the pool. */
function serveOtherTabs() {
  let channel;
  try { channel = new BroadcastChannel(SHARE_CHANNEL); } catch (e) { return; }
  channel.onmessage = (event) => {
    const request = event.data;
    if (!request || request.type !== 'request' || !pool) return;
    const installed = pointers[request.name];
    if (!installed || (request.sha256 && installed.sha256 !== request.sha256)) return;
    try {
      const bytes = pool.exportFile(installed.file);
      channel.postMessage({ type: 'file', id: request.id, name: request.name, sha256: installed.sha256, bytes });
    } catch (e) { /* the asking tab downloads it */ }
  };
}

const ready = (async () => {
  sqlite3 = await sqlite3InitModule({ print: () => {}, printErr: () => {} });
  if (opfsAvailable() && await takeStoreLock()) {
    try {
      pool = await installPool();
      const read = await readPointers();
      pointers = read.pointers;
      const stored = pool.getFileNames();
      if (read.trusted) {
        // Whatever the pointer file does not name was never verified, or was replaced by an update.
        const installed = new Set(Object.values(pointers).map((p) => p && p.file));
        for (const file of stored) if (!installed.has(file)) pool.unlink(file);
      }
      for (const name of Object.keys(pointers)) {
        if (!pointers[name] || !stored.includes(pointers[name].file)) delete pointers[name];
      }
    } catch (e) {
      pool = null;
      releaseStoreLock();
    }
  }
  if (pool) serveOtherTabs();
  notify({ evt: 'storage', persistent: pool !== null });
})();

// ---- manifest and downloads ----

let manifestPromise = null;

/** data/manifest.json — dataset_version plus size and SHA-256 per file — or null when it cannot be fetched. */
function manifest(refresh) {
  if (!manifestPromise || refresh) {
    const request = fetch(MANIFEST_URL, { cache: 'no-cache' })
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null);
    manifestPromise = request;
    // A failed fetch is not remembered: the next question asks again.
    request.then((m) => { if (!m && manifestPromise === request) manifestPromise = null; });
  }
  return manifestPromise;
}

const listed = (m, name) => (m && m.files ? m.files[name.replace(/^data\//, '')] : null) || null;

/** Streams `url`, reporting progress; `sink` receives each chunk. */
async function download(url, expectedSize, background, sink) {
  let loaded = 0;
  let total = expectedSize || 0;
  notify({ evt: 'download', file: url, loaded, total, done: false, background });
  try {
    const response = await fetch(url, { cache: 'no-store' });
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
        notify({ evt: 'download', file: url, loaded, total: Math.max(total, loaded), done: false, background });
      }
    }
    return loaded;
  } catch (e) {
    notify({ evt: 'download', file: url, loaded, total, done: true, error: String((e && e.message) || e), background });
    throw e;
  }
}

function verify(name, wanted, size, digest) {
  if (!wanted || !wanted.sha256) return;
  if (size !== wanted.size || digest !== wanted.sha256) throw new Error('checksum mismatch for ' + name);
}

/**
 * A stored file of the wanted version that no pointer names (the pointer file could not be read):
 * its pointer entry when its content verifies, so it is used instead of downloaded again.
 */
function adoptStored(name, target, wanted) {
  if (!wanted || !wanted.sha256 || !pool.getFileNames().includes(target)) return null;
  try {
    const bytes = pool.exportFile(target);
    const hash = new Sha256();
    hash.update(bytes);
    verify(name, wanted, bytes.byteLength, hash.hex());
    return { file: target, sha256: wanted.sha256, version: wanted.dataset_version || null };
  } catch (e) {
    return null;
  }
}

// ---- resumable downloads ----
//
// A file is downloaded into a plain OPFS file of its own version (`.szdict-partial/`), written as it
// arrives. When the page is closed, reloaded or loses the network before the end, the next attempt
// asks the server for the rest only (HTTP Range) and appends it. The finished file is copied into the
// pool and verified against the manifest on the way; only then is the partial file removed. A
// partial file of another version of the same dataset file is deleted when a download starts.

const PARTIAL_DIRECTORY = '.szdict-partial';
const PART_CHUNK = 1 << 20;
const FLUSH_EVERY = 4 << 20;

const partialName = (target) => target.replace(/^\//, '').replace(/[^A-Za-z0-9._@-]/g, '_');

async function partialDirectory() {
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle(PARTIAL_DIRECTORY, { create: true });
}

/** Deletes partial downloads of `name` other than `keep` (an older or newer version of it). */
async function dropOtherPartials(directory, name, keep) {
  const prefix = partialName('/' + name + '@');
  const stale = [];
  for await (const [entry] of directory.entries()) if (entry.startsWith(prefix) && entry !== keep) stale.push(entry);
  for (const entry of stale) await directory.removeEntry(entry).catch(() => {});
}

/**
 * Brings the partial file of `target` up to the full size: from the start, or from where an earlier
 * attempt stopped. Returns its size. Progress counts the bytes already there.
 */
async function downloadToPartial(name, handle, wanted, background) {
  const resumable = !!(wanted && wanted.sha256 && wanted.size);
  const total = resumable ? wanted.size : 0;
  const access = await handle.createSyncAccessHandle();
  let offset = 0;
  try {
    offset = resumable ? access.getSize() : 0;
    if (!resumable || offset > total) { access.truncate(0); offset = 0; }
    if (resumable && offset === total) return offset;
    notify({ evt: 'download', file: name, loaded: offset, total, done: false, background });
    const response = await fetch(name, offset > 0 ? { cache: 'no-store', headers: { Range: 'bytes=' + offset + '-' } } : { cache: 'no-store' });
    if (response.status === 206) {
      const range = /^bytes (\d+)-/.exec(response.headers.get('Content-Range') || '');
      if (!range || Number(range[1]) !== offset) throw new Error('unexpected range for ' + name);
    } else if (response.ok) {
      if (offset > 0) { access.truncate(0); offset = 0; }   // the server sent the whole file
    } else {
      throw new Error('HTTP ' + response.status + ' for ' + name);
    }
    const expected = total || Number(response.headers.get('Content-Length')) || 0;
    const reader = response.body.getReader();
    let lastReport = 0;
    let unflushed = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      let written = 0;
      while (written < value.byteLength) written += access.write(value.subarray(written), { at: offset + written });
      offset += value.byteLength;
      unflushed += value.byteLength;
      if (unflushed >= FLUSH_EVERY) { access.flush(); unflushed = 0; }
      const now = Date.now();
      if (now - lastReport > 100) {
        lastReport = now;
        notify({ evt: 'download', file: name, loaded: offset, total: Math.max(expected, offset), done: false, background });
      }
    }
    access.flush();
    if (total && offset !== total) throw new Error('incomplete download of ' + name + ': ' + offset + ' of ' + total + ' bytes');
    return offset;
  } catch (e) {
    try { access.flush(); } catch (ignored) { /* closed below */ }
    notify({ evt: 'download', file: name, loaded: offset, total, done: true, error: String((e && e.message) || e), background });
    throw e;
  } finally {
    access.close();
  }
}

/**
 * Downloads `name` beside whatever is installed (resuming an interrupted download), copies it into
 * the pool and verifies it. Returns the pointer entry of the new file; nothing refers to that file
 * until the entry is written.
 */
async function fetchIntoPool(name, wanted, background) {
  const target = '/' + name + '@' + (wanted && wanted.sha256 ? wanted.sha256.slice(0, 16) : 'unversioned');
  const adopted = adoptStored(name, target, wanted);
  if (adopted) return adopted;
  if (pool.getFileNames().includes(target)) pool.unlink(target);
  const directory = await partialDirectory();
  const partial = partialName(target);
  await dropOtherPartials(directory, name, partial);
  const handle = await directory.getFileHandle(partial, { create: true });
  const size = await downloadToPartial(name, handle, wanted, background);

  await pool.reserveMinimumCapacity(pool.getFileCount() + 4);
  const access = await handle.createSyncAccessHandle();
  const hash = new Sha256();
  let at = 0;
  try {
    const imported = await pool.importDb(target, async () => {
      if (at >= size) return undefined;
      const chunk = new Uint8Array(Math.min(PART_CHUNK, size - at));
      const read = access.read(chunk, { at });
      if (!read) throw new Error('short read of the download of ' + name);
      const bytes = read === chunk.byteLength ? chunk : chunk.subarray(0, read);
      hash.update(bytes);
      at += read;
      return bytes;
    });
    verify(name, wanted, imported, hash.hex());
  } catch (e) {
    access.close();
    if (pool.getFileNames().includes(target)) pool.unlink(target);
    // What was downloaded does not verify: the next attempt starts again from the beginning.
    if (/checksum mismatch/.test(String(e && e.message))) await directory.removeEntry(partial).catch(() => {});
    notify({ evt: 'download', file: name, loaded: 0, total: 0, done: true, error: String((e && e.message) || e), background });
    throw e;
  }
  access.close();
  await directory.removeEntry(partial).catch(() => {});
  notify({ evt: 'download', file: name, loaded: size, total: size, done: true, background });
  return { file: target, sha256: wanted ? wanted.sha256 || null : null, version: wanted ? wanted.dataset_version || null : null };
}

// One import at a time: a chunked import claims its pool slot only when it completes.
let importChain = Promise.resolve();
const pending = new Map();

function queued(task) {
  const p = importChain.then(task);
  importChain = p.catch(() => {});
  return p;
}

/** Makes `name` available in the pool and returns its pool file name. */
function ensurePooled(name) {
  const installed = pointers[name];
  if (installed) {
    scheduleUpdateCheck();
    return Promise.resolve(installed.file);
  }
  let p = pending.get(name);
  if (!p) {
    p = queued(async () => {
      if (pointers[name]) return pointers[name].file;
      const entry = await fetchIntoPool(name, listed(await manifest(false), name), false);
      pointers[name] = entry;
      await writePointers();
      return entry.file;
    });
    pending.set(name, p);
    p.catch(() => {}).then(() => pending.delete(name));
  }
  return p;
}

// ---- background update (ARCH-145; full downloads — changesets are not implemented) ----

let checkTimer = null;
let updateRunning = false;

function scheduleUpdateCheck() {
  if (checkTimer !== null) return;
  checkTimer = setTimeout(async function run() {
    await checkForUpdate().catch(() => {});
    checkTimer = setTimeout(run, RECHECK_MS);
  }, FIRST_CHECK_MS);
}

/**
 * Compares every installed file with the manifest. Changed files are downloaded and verified
 * beside the ones in use; when all of them are there, one pointer write installs them together.
 * Any failure leaves the installed files untouched and in use; the next check tries again.
 */
async function checkForUpdate() {
  if (!pool || updateRunning) return;
  const m = await manifest(true);
  if (!m || !m.files) return;
  const stale = Object.keys(pointers).filter((name) => {
    const wanted = listed(m, name);
    return wanted && wanted.sha256 && wanted.sha256 !== pointers[name].sha256;
  });
  if (!stale.length) return;
  updateRunning = true;
  const fetched = [];
  try {
    await queued(async () => {
      for (const name of stale) fetched.push([name, await fetchIntoPool(name, listed(m, name), true)]);
      const next = Object.assign({}, pointers);
      for (const [name, entry] of fetched) next[name] = entry;
      const previous = pointers;
      pointers = next;
      try {
        await writePointers();
      } catch (e) {
        pointers = previous;
        throw e;
      }
    });
    const version = m.dataset_version || null;
    notify({ evt: 'updated', version });
    try {
      const channel = new BroadcastChannel('szdict-datasets');
      channel.postMessage({ type: 'updated', version });
      channel.close();
    } catch (e) { /* no BroadcastChannel: the other tabs find out on their next check */ }
  } catch (e) {
    for (const [, entry] of fetched) if (pool.getFileNames().includes(entry.file)) pool.unlink(entry.file);
  } finally {
    updateRunning = false;
  }
}

// ---- without the pool: the file in memory for this page ----

/**
 * The installed file from the tab that holds the pool, verified like a download; null when no tab
 * answers in time (none holds it, or it does not have this file).
 */
function fromHolder(name, wanted) {
  let channel;
  try { channel = new BroadcastChannel(SHARE_CHANNEL); } catch (e) { return Promise.resolve(null); }
  const id = Math.random().toString(36).slice(2) + Date.now().toString(36);
  return new Promise((resolve) => {
    const finish = (bytes) => { clearTimeout(timer); channel.close(); resolve(bytes); };
    const timer = setTimeout(() => finish(null), SHARE_WAIT_MS);
    channel.onmessage = (event) => {
      const reply = event.data;
      if (!reply || reply.type !== 'file' || reply.id !== id || !(reply.bytes instanceof Uint8Array)) return;
      const expected = wanted && wanted.sha256 ? wanted : { size: reply.bytes.byteLength, sha256: reply.sha256 };
      try {
        const hash = new Sha256();
        hash.update(reply.bytes);
        if (expected.sha256) verify(name, expected, reply.bytes.byteLength, hash.hex());
        finish(reply.bytes);
      } catch (e) {
        finish(null);
      }
    };
    channel.postMessage({ type: 'request', id, name, sha256: wanted && wanted.sha256 ? wanted.sha256 : null });
  });
}

async function downloadToMemory(name, wanted) {
  const parts = [];
  const size = await download(name, wanted ? wanted.size : 0, false, async (chunk) => { parts.push(chunk); });
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const part of parts) { bytes.set(part, at); at += part.byteLength; }
  try {
    const hash = new Sha256();
    hash.update(bytes);
    verify(name, wanted, size, hash.hex());
  } catch (e) {
    notify({ evt: 'download', file: name, loaded: size, total: size, done: true, error: String(e.message), background: false });
    throw e;
  }
  notify({ evt: 'download', file: name, loaded: size, total: size, done: true, background: false });
  return bytes;
}

// ---- the driver protocol ----

/** Files this page has open, by name: an update installed meanwhile does not change them. */
const opened = new Map();

async function open(name) {
  await ready;
  let db;
  if (pool) {
    let target = opened.get(name);
    if (!target) {
      target = await ensurePooled(name);
      opened.set(name, target);
    }
    db = new sqlite3.oo1.DB(target, 'r', pool.vfsName);
  } else {
    const wanted = listed(await manifest(false), name);
    const bytes = (await fromHolder(name, wanted)) || (await downloadToMemory(name, wanted));
    const size = bytes.byteLength;
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
  if (event.data && event.data.warm) {
    // The start page names the dataset the app is about to open: fetched or found ahead of the request.
    const name = event.data.warm;
    ready.then(() => { if (pool && !opened.has(name)) return ensurePooled(name); }).catch(() => {});
    return;
  }
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
