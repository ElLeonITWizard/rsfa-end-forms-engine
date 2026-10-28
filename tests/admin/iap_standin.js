// IAP STAND-IN — local production-like tests ONLY, never deployed (D-041, docs/14 §8).
// Plays Google sign-in + Identity-Aware Proxy in front of the admin service instances:
//   /_iap/jwk      JWK set of its own ES256 signing key (the admin containers read it through IAP_KEYS_URL)
//   /_iap/signin   a sign-in form standing in for Google's (email only; this is not authentication of anyone)
//   /_iap/signout  ends the session
//   anything else  no session -> 302 to /_iap/signin (IAP sends browsers to Google); signed-in email without IAP access
//                  -> 403 "You don't have access" (IAP's own refusal); otherwise proxied round-robin to an instance with
//                  a fresh x-goog-iap-jwt-assertion (iss https://cloud.google.com/iap, aud, email, sub, 10 minutes).
//                  Client-supplied x-goog-* headers and the stand-in's cookie are stripped, as IAP does.
// Usage: node iap_standin.js --port 8443 --upstreams http://127.0.0.1:8091,http://127.0.0.1:8092 \
//          --audience /projects/123/locations/australia-southeast1/services/rsfa-end-forms-admin --iap-users a@x,b@y
'use strict';
const http = require('http');
const crypto = require('crypto');

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d; };
const port = Number(arg('port', '8443'));
const upstreams = arg('upstreams', 'http://127.0.0.1:8091').split(',').map(u => new URL(u));
const audience = arg('audience');
const iapUsers = new Set(arg('iap-users', '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean));
if (!audience) { console.error('--audience is required'); process.exit(2); }

const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const kid = 'standin-' + crypto.randomBytes(4).toString('hex');
const jwk = { ...publicKey.export({ format: 'jwk' }), kid, alg: 'ES256', use: 'sig' };
const sessions = new Map(); // cookie value -> email
let next = 0;

const b64 = (b) => Buffer.from(b).toString('base64url');
function assertion(email) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64(JSON.stringify({ alg: 'ES256', kid, typ: 'JWT' }));
  const body = b64(JSON.stringify({ iss: 'https://cloud.google.com/iap', aud: audience, email,
    sub: 'accounts.google.com:' + crypto.createHash('sha256').update(email).digest('hex').slice(0, 20), iat: now, exp: now + 600 }));
  const sig = crypto.sign('sha256', Buffer.from(head + '.' + body), { key: privateKey, dsaEncoding: 'ieee-p1363' });
  return head + '.' + body + '.' + b64(sig);
}
function cookie(req) {
  const m = /(?:^|;\s*)__iap_standin=([0-9a-f]{32})/.exec(req.headers.cookie || '');
  return m ? m[1] : null;
}
function page(res, status, title, html) {
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(`<!doctype html><html><head><meta charset="utf-8"><title>${title}</title></head><body style="font-family:sans-serif;max-width:420px;margin:80px auto">
<p style="color:#b42318;font-size:12px">LOCAL IAP STAND-IN — not Google, not deployed</p>${html}</body></html>`);
}
const esc = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/_iap/jwk') {
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ keys: [jwk] })); return;
  }
  if (url.pathname === '/_iap/signin' && req.method === 'GET') {
    const back = url.searchParams.get('continue') || '/';
    page(res, 200, 'Sign in', `<h1>Sign in</h1><form method="post" action="/_iap/signin">
<input type="hidden" name="continue" value="${esc(back)}"><label>Google account email<br>
<input name="email" type="email" required data-testid="iap-email" style="width:100%"></label><br><br>
<button type="submit" data-testid="iap-signin">Continue</button></form>`);
    return;
  }
  if (url.pathname === '/_iap/signin' && req.method === 'POST') {
    let data = '';
    req.on('data', c => { data += c; if (data.length > 4096) req.destroy(); });
    req.on('end', () => {
      const f = new URLSearchParams(data);
      const id = crypto.randomBytes(16).toString('hex');
      sessions.set(id, String(f.get('email') || '').trim().toLowerCase());
      const back = (f.get('continue') || '/').startsWith('/') ? f.get('continue') : '/';
      res.writeHead(302, { 'Set-Cookie': `__iap_standin=${id}; HttpOnly; SameSite=Lax; Path=/`, Location: back }); res.end();
    });
    return;
  }
  if (url.pathname === '/_iap/signout') {
    sessions.delete(cookie(req));
    res.writeHead(302, { 'Set-Cookie': '__iap_standin=; Max-Age=0; Path=/', Location: '/' }); res.end(); return;
  }
  const email = sessions.get(cookie(req));
  if (!email) {
    if (req.headers['x-rsfa-admin']) { res.writeHead(302, { Location: '/_iap/signin' }); res.end(); return; } // fetch: opaque redirect
    res.writeHead(302, { Location: '/_iap/signin?continue=' + encodeURIComponent(req.url) }); res.end(); return;
  }
  if (!iapUsers.has(email)) {
    page(res, 403, 'Access denied', `<h1>You don't have access</h1><p data-testid="iap-denied">${esc(email)} is not allowed through IAP.</p>`);
    return;
  }
  const up = upstreams[next++ % upstreams.length];
  const headers = {};
  for (const [k, v] of Object.entries(req.headers)) {
    if (k.startsWith('x-goog-') || k === 'host') continue;
    headers[k] = k === 'cookie' ? v.replace(/(?:^|;\s*)__iap_standin=[^;]*/g, '').replace(/^;\s*/, '') : v;
  }
  if (!headers.cookie) delete headers.cookie;
  headers['x-goog-iap-jwt-assertion'] = assertion(email);
  headers['x-goog-authenticated-user-email'] = 'accounts.google.com:' + email;
  const p = http.request({ hostname: up.hostname, port: up.port, path: req.url, method: req.method, headers }, (r) => {
    res.writeHead(r.statusCode, { ...r.headers, 'x-standin-instance': up.port });
    r.pipe(res);
  });
  p.on('error', () => { res.writeHead(502); res.end('upstream unavailable'); });
  req.pipe(p);
}).listen(port, '127.0.0.1', () => console.log(`IAP stand-in on http://127.0.0.1:${port} -> ${upstreams.map(String).join(', ')}`));
