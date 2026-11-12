// usage: node tools/serve-web.mjs <root> <port>   — static files, SPA fallback, precompressed siblings (.br/.gz)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { Transform } from 'node:stream';
// RATE (bit/s, shared by all responses) and LATENCY (ms per request) emulate a connection for every client, workers included.
const RATE = +(process.env.RATE || 0) / 8, LATENCY = +(process.env.LATENCY || 0);
let free = 0, sent = 0;
function throttle() {
  return new Transform({ highWaterMark: 16384, transform(chunk, _e, done) {
    sent += chunk.length;
    if (!RATE) return done(null, chunk);
    const now = Date.now(); free = Math.max(free, now) + (chunk.length / RATE) * 1000;
    setTimeout(() => done(null, chunk), Math.max(0, free - now));
  } });
}
process.on('SIGUSR2', () => { console.log('SENT', sent); sent = 0; });
const [root, port] = [path.resolve(process.argv[2]), +process.argv[3]];
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml', '.css': 'text/css', '.txt': 'text/plain; charset=utf-8', '.otf': 'font/otf', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.opus': 'audio/ogg', '.ogg': 'audio/ogg', '.m4a': 'audio/mp4', '.db': 'application/octet-stream', '.map': 'application/json' };
http.createServer((req, res) => { if (LATENCY) return setTimeout(() => handle(req, res), LATENCY); handle(req, res); }).listen(port, () => console.log('serving', root, 'on', port));
function handle(req, res) {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  let file = path.join(root, p);
  if (!file.startsWith(root)) { res.writeHead(403); return res.end(); }
  if (p.endsWith('/') && fs.existsSync(path.join(file, 'index.html'))) file = path.join(file, 'index.html');
  if (p === '/privacy' && fs.existsSync(path.join(root, 'privacy.html')) && !process.env.NOPRIVACY) file = path.join(root, 'privacy.html');   // GLB-112: the static page outside the running app
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    if (path.extname(p)) { res.writeHead(404); return res.end('not found'); }
    file = path.join(root, 'index.html');
  }
  const headers = { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' };
  const accept = req.headers['accept-encoding'] || '';
  let send = file;
  if (!process.env.NOCOMPRESS && !req.headers.range) {
    if (/\bbr\b/.test(accept) && fs.existsSync(file + '.br')) { send = file + '.br'; headers['Content-Encoding'] = 'br'; }
    else if (/\bgzip\b/.test(accept) && fs.existsSync(file + '.gz')) { send = file + '.gz'; headers['Content-Encoding'] = 'gzip'; }
    headers['Vary'] = 'Accept-Encoding';
  }
  const stat = fs.statSync(send);
  const size = stat.size;
  const etag = `"${stat.size}-${Math.floor(stat.mtimeMs)}${headers['Content-Encoding'] || ''}"`;
  headers.ETag = etag;
  if (req.headers['if-none-match'] === etag) { res.writeHead(304, headers); return res.end(); }
  if (process.env.LOG) console.log(req.method, p, headers['Content-Encoding'] || '-', size);
  const range = req.headers.range && /bytes=(\d+)-(\d*)/.exec(req.headers.range);
  if (range) {
    const start = +range[1], end = range[2] ? +range[2] : size - 1;
    res.writeHead(206, { ...headers, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes' });
    return fs.createReadStream(send, { start, end, highWaterMark: 16384 }).pipe(throttle()).pipe(res);
  }
  res.writeHead(200, { ...headers, 'Content-Length': size, 'Accept-Ranges': 'bytes' });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(send, { highWaterMark: 16384 }).pipe(throttle()).pipe(res);
}
