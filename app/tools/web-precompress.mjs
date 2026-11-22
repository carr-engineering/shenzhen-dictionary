#!/usr/bin/env node
// Writes Brotli (.br) and gzip (.gz) siblings for the compressible files of the Web distribution,
// for hosts that serve precompressed files (PERF-073: the initial payload is budgeted compressed).
// A host that compresses on the fly needs none of this.
//
//   node tools/web-precompress.mjs [dist-dir]     default: composeApp/build/dist/wasmJs/productionExecutable
//
// Already compressed media (audio, images) are left alone, and so is a file that does not shrink.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(process.argv[2] || path.join(here, '../composeApp/build/dist/wasmJs/productionExecutable'));
const compressible = new Set(['.html', '.js', '.mjs', '.wasm', '.json', '.webmanifest', '.txt', '.svg', '.css', '.otf', '.ttf', '.db', '.ocd2', '.hzb', '.tflite', '.onnx']);
const brotli = promisify(zlib.brotliCompress);
const gzip = promisify(zlib.gzip);

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(file);
    else if (compressible.has(path.extname(entry.name))) yield file;
  }
}

async function compress(file) {
  const bytes = fs.readFileSync(file);
  if (bytes.length < 1024) return [0, 0, 0];
  // Maximum effort for the shell; large data files trade a little size for build time.
  const quality = bytes.length > 12e6 ? 9 : 11;
  const br = await brotli(bytes, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: quality, [zlib.constants.BROTLI_PARAM_SIZE_HINT]: bytes.length, [zlib.constants.BROTLI_PARAM_LGWIN]: 24 } });
  const gz = await gzip(bytes, { level: 9 });
  const keep = (out) => out.length < bytes.length * 0.95;
  if (keep(br)) fs.writeFileSync(file + '.br', br); else fs.rmSync(file + '.br', { force: true });
  if (keep(gz)) fs.writeFileSync(file + '.gz', gz); else fs.rmSync(file + '.gz', { force: true });
  return [bytes.length, keep(br) ? br.length : bytes.length, keep(gz) ? gz.length : bytes.length];
}

const files = [...walk(root)];
const totals = [0, 0, 0];
let next = 0;
await Promise.all(Array.from({ length: 4 }, async () => {
  while (next < files.length) {
    const file = files[next++];
    const sizes = await compress(file);
    sizes.forEach((n, i) => { totals[i] += n; });
    if (sizes[0] > 200e3) console.log(path.relative(root, file).padEnd(44), ...sizes.map((n) => (n / 1e6).toFixed(2).padStart(7)));
  }
}));
console.log(`${files.length} files: ${(totals[0] / 1e6).toFixed(1)} MB → ${(totals[1] / 1e6).toFixed(1)} MB br, ${(totals[2] / 1e6).toFixed(1)} MB gzip`);
