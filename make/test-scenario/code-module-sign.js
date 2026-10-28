// Make "Code" module (JavaScript) for the ISOLATED TEST scenario only (docs/11): builds the exact masterboard.v1
// request body and its RSFA relay signature in one place, so the bytes signed are the bytes the HTTP module sends.
// No require(), no external code: SHA-256 and HMAC are implemented below (FIPS 180-4 / RFC 2104).
// Inputs (mapped in the module):
//   payload     the synthetic Masterboard test payload (object, or the webhook's JSON pass-through text)
//   raw         optional; true = payload is a complete request as JSON text, signed byte-for-byte
//   executionId {{var.scenario.executionId}}-style id for the correlation id (object payload only)
//   today       YYYY-MM-DD in Pacific/Auckland (object payload only)
//   timestamp   unix seconds as text, computed in the same run as this module (a retry runs this module again)
//   secret      the relay signing secret (test secret only)
// Outputs: body (send this exact string as the raw request body), timestamp, signature ("v1=<64 hex>"),
//          correlationId, idempotencyKey
// Security: module inputs are visible in execution history unless the scenario setting "Data is confidential" is on.

function utf8Bytes(str) {
  const out = [];
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
      const d = str.charCodeAt(i + 1);
      if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; }
    }
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return out;
}

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];

function sha256(bytes) {
  const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const msg = bytes.slice();
  const bitLen = bytes.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  const hi = Math.floor(bitLen / 0x100000000);
  for (const v of [hi, bitLen >>> 0]) msg.push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
  const w = new Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < msg.length; off += 64) {
    for (let i = 0; i < 16; i++) {
      w[i] = (msg[off + 4 * i] << 24) | (msg[off + 4 * i + 1] << 16) | (msg[off + 4 * i + 2] << 8) | msg[off + 4 * i + 3];
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const t1 = (hh + S1 + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
  }
  const out = [];
  for (const v of h) out.push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
  return out;
}

function hmacSha256Hex(keyText, messageText) {
  let key = utf8Bytes(keyText);
  if (key.length > 64) key = sha256(key);
  while (key.length < 64) key.push(0);
  const inner = sha256(key.map(b => b ^ 0x36).concat(utf8Bytes(messageText)));
  const outer = sha256(key.map(b => b ^ 0x5c).concat(inner));
  return outer.map(b => (b < 16 ? '0' : '') + b.toString(16)).join('');
}

// Synthetic payload -> masterboard.v1 request (contracts/masterboard.v1.schema.json). Refuses anything that does not
// look synthetic, so a real Masterboard item can never be sent by the test scenario.
function buildRequest(p, executionId, today) {
  const itemId = String(p.itemId || '');
  if (!/^10000000[0-9]{2}$/.test(itemId)) throw new Error('itemId is not in the synthetic range 1000000000-1000000099');
  if (String(p.boardId || '5026186805') !== '5026186805') throw new Error('unexpected boardId');
  if (!/^T[0-9]{1,2}$/.test(String(p.testCase || ''))) throw new Error('testCase must look like T1..T99');
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(String(today || ''))) throw new Error('today must be YYYY-MM-DD');
  if (!/^[A-Za-z0-9._:-]{1,64}$/.test(String(executionId || ''))) throw new Error('executionId missing');
  if (!p.data || typeof p.data !== 'object' || Array.isArray(p.data)) throw new Error('data must be an object');
  const banks = Array.isArray(p.selectedBanks) ? p.selectedBanks
    : String(p.selectedBanks || '').split(',').map(b => b.trim()).filter(Boolean);
  let selection;
  if (Array.isArray(p.templates) && p.templates.length) selection = { templates: p.templates };
  else if (banks.length) selection = { banks };
  else throw new Error('no bank or template selected');
  const options = Object.assign({ includeCandidates: true }, p.options || {});
  return {
    contractVersion: 'masterboard.v1',
    correlationId: 'mb-5026186805-' + itemId + '-' + executionId,
    idempotencyKey: itemId + '-' + p.testCase + '-' + today,
    today,
    source: { boardId: '5026186805', itemId },
    selection,
    options,
    data: p.data,
  };
}

// --- module body (the Code module runs this with `input` provided by Make) ---
// raw = true: payload is a complete request as JSON text, signed byte-for-byte (test vectors, replaying a stored body).
// Otherwise payload is a synthetic Masterboard payload (object, or the webhook's JSON pass-through text): normalised first.
let body;
let request;
const given = typeof input.payload === 'string' ? JSON.parse(input.payload) : input.payload; // malformed JSON fails here
if (input.raw === true) {
  if (typeof input.payload !== 'string') throw new Error('raw mode needs the request as JSON text');
  body = input.payload;
  request = given;
} else {
  request = buildRequest(given || {}, input.executionId, input.today);
  body = JSON.stringify(request);
}
const timestamp = String(input.timestamp);
if (!/^[0-9]{1,12}$/.test(timestamp)) throw new Error('timestamp must be unix seconds');
if (!input.secret || String(input.secret).length < 32) throw new Error('secret missing or too short');
return { body, timestamp, correlationId: request.correlationId || null, idempotencyKey: request.idempotencyKey || null,
  signature: 'v1=' + hmacSha256Hex(String(input.secret), timestamp + '.' + body) };
