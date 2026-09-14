'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (match && process.env[match[1]] === undefined) process.env[match[1]] = match[2].trim();
  }
}

loadEnv(path.join(__dirname, '.env'));
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 8787);
const CAMERA_TOKEN = process.env.CAMERA_TOKEN || '';
const VIEWER_TOKEN = process.env.VIEWER_TOKEN || '';
const MAX_FRAME_BYTES = Number(process.env.MAX_FRAME_BYTES || 500000);
const STALE_MS = Number(process.env.STALE_MS || 15000);

if (CAMERA_TOKEN.length < 24 || VIEWER_TOKEN.length < 24) {
  console.error('CAMERA_TOKEN dan VIEWER_TOKEN wajib unik dan minimal 24 karakter.');
  process.exit(1);
}

let latestFrame = null;
let latestAt = 0;
const viewers = new Set();

function safeEqual(a, b) {
  const aa = Buffer.from(String(a || ''));
  const bb = Buffer.from(String(b || ''));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}

function tokenFrom(req, url) {
  const auth = req.headers.authorization || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : url.searchParams.get('token');
}

function reply(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, {'Content-Type': type, 'Cache-Control': 'no-store'});
  res.end(body);
}

function publish(frame) {
  const header = Buffer.from(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`);
  const tail = Buffer.from('\r\n');
  for (const res of viewers) {
    if (!res.writableEnded) {
      const ok = res.write(header) && res.write(frame) && res.write(tail);
      if (!ok) viewers.delete(res);
    }
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return reply(res, 200, JSON.stringify({ok: true, viewers: viewers.size, frameAgeMs: latestAt ? Date.now() - latestAt : null}), 'application/json');
  }

  if (req.method === 'POST' && url.pathname === '/api/frame') {
    if (!safeEqual(tokenFrom(req, url), CAMERA_TOKEN)) return reply(res, 401, 'Unauthorized');
    if (!String(req.headers['content-type'] || '').startsWith('image/jpeg')) return reply(res, 415, 'JPEG only');
    let size = 0;
    const chunks = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_FRAME_BYTES) req.destroy(); else chunks.push(chunk);
    });
    req.on('end', () => {
      if (!size || size > MAX_FRAME_BYTES) return reply(res, 413, 'Invalid frame');
      latestFrame = Buffer.concat(chunks);
      latestAt = Date.now();
      publish(latestFrame);
      reply(res, 204, '');
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/stream') {
    if (!safeEqual(tokenFrom(req, url), VIEWER_TOKEN)) return reply(res, 401, 'Unauthorized');
    res.writeHead(200, {
      'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      'Connection': 'keep-alive'
    });
    viewers.add(res);
    if (latestFrame && Date.now() - latestAt < STALE_MS) publish(latestFrame);
    req.on('close', () => viewers.delete(res));
    return;
  }

  if (req.method === 'GET' && url.pathname === '/view') {
    const token = tokenFrom(req, url);
    if (!safeEqual(token, VIEWER_TOKEN)) return reply(res, 401, 'Unauthorized');
    const escapedToken = encodeURIComponent(token);
    const html = `<!doctype html><html lang="id"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>LolliCam</title><style>body{margin:0;background:#0b0f14;color:#eee;font:16px system-ui;display:grid;min-height:100vh;place-items:center}.box{width:min(96vw,900px)}h1{font-size:20px}img{display:block;width:100%;background:#111;border-radius:12px;min-height:180px;object-fit:contain}.note{color:#9aa4b2;font-size:13px}</style></head><body><main class="box"><h1>LolliCam Live</h1><img src="/stream?token=${escapedToken}" alt="Live camera"><p class="note">Jika gambar berhenti, muat ulang halaman.</p></main></body></html>`;
    return reply(res, 200, html, 'text/html; charset=utf-8');
  }

  reply(res, 404, 'Not found');
});

server.requestTimeout = 20000;
server.headersTimeout = 25000;
server.listen(PORT, HOST, () => console.log(`LolliCam listening on http://${HOST}:${PORT}`));
