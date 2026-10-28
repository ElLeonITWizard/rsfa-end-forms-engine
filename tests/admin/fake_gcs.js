// FAKE Cloud Storage JSON API — local production-like tests ONLY (D-041, docs/14 §8). In memory.
// Media upload with ifGenerationMatch=0 (412 when the object exists; any upload without it is refused and counted),
// media download, listing by prefix with pagination. Requires "Authorization: Bearer <token>".
// GET /_fake/stats returns {objects, overwriteAttempts, preconditionFailures, names} for test assertions.
// Usage: node fake_gcs.js --port 4443 --bucket rsfa-admin-local --token local-token --seed config/monday-schema.json=<file>
'use strict';
const http = require('http');
const fs = require('fs');
const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d; };
const port = Number(arg('port', '4443')), bucket = arg('bucket', 'rsfa-admin-local'), token = arg('token', 'local-token');
const objects = new Map();
const stats = { overwriteAttempts: 0, preconditionFailures: 0 };
const seed = arg('seed');
if (seed) { const [name, file] = seed.split('='); objects.set(name, fs.readFileSync(file)); }
const json = (res, s, o) => { res.writeHead(s, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/_fake/stats') return json(res, 200, { objects: objects.size, ...stats, names: [...objects.keys()].sort() });
  if (req.headers.authorization !== 'Bearer ' + token) return json(res, 401, {});
  const base = `/storage/v1/b/${bucket}/o`;
  if (req.method === 'POST' && url.pathname === '/upload' + base) {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      if (url.searchParams.get('ifGenerationMatch') !== '0') { stats.overwriteAttempts++; return json(res, 400, {}); }
      const name = url.searchParams.get('name');
      if (objects.has(name)) { stats.preconditionFailures++; return json(res, 412, {}); }
      objects.set(name, Buffer.concat(chunks)); json(res, 200, { name });
    });
    return;
  }
  if (req.method === 'GET' && url.pathname.startsWith(base + '/') && url.searchParams.get('alt') === 'media') {
    const o = objects.get(decodeURIComponent(url.pathname.slice(base.length + 1)));
    if (!o) return json(res, 404, {});
    res.writeHead(200); res.end(o); return;
  }
  if (req.method === 'GET' && url.pathname === base) {
    const all = [...objects.keys()].filter(n => n.startsWith(url.searchParams.get('prefix') || '')).sort();
    const from = Number(url.searchParams.get('pageToken') || 0), to = Math.min(all.length, from + 50);
    return json(res, 200, { items: all.slice(from, to).map(name => ({ name })), ...(to < all.length ? { nextPageToken: String(to) } : {}) });
  }
  json(res, 404, {});
}).listen(port, '127.0.0.1', () => console.log(`fake GCS on http://127.0.0.1:${port} bucket=${bucket}`));
