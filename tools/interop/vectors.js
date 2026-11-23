'use strict';
// Prints fixed-key test vectors computed with TabExplorer's own crypto (Node's), for the Android tests.
const crypto = require('crypto');
const hexb = (h) => Buffer.from(h, 'hex');
const pkcs8 = (raw) => Buffer.concat([hexb('302e020100300506032b656e04220420'), raw]);
const privA = hexb('a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4');
const privB = hexb('4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d');
const kp = (raw) => { const priv = crypto.createPrivateKey({ key: pkcs8(raw), format: 'der', type: 'pkcs8' }); const pub = crypto.createPublicKey(priv); return { priv, pubDer: pub.export({ type: 'spki', format: 'der' }) }; };
const A = kp(privA), B = kp(privB);
const pubA = A.pubDer.toString('base64'), pubB = B.pubDer.toString('base64');
const codeOf = (pub) => { const n = crypto.createHash('sha256').update(String(pub)).digest().readUInt32BE(0) % 100000000; const s = String(n).padStart(8, '0'); return s.slice(0, 4) + ' ' + s.slice(4); };
const secret = crypto.diffieHellman({ privateKey: A.priv, publicKey: crypto.createPublicKey({ key: B.pubDer, format: 'der', type: 'spki' }) });
const nonceC = hexb('000102030405060708090a0b0c0d0e0f'), nonceS = hexb('f0e0d0c0b0a090807060504030201000');
const okm = Buffer.from(crypto.hkdfSync('sha256', secret, Buffer.concat([nonceC, nonceS]), Buffer.from('tabexplorer-share v1'), 64));
const iv = (n) => { const b = Buffer.alloc(12); b.writeBigUInt64BE(BigInt(n), 4); return b; };
const seal = (key, n, kind, payload) => { const c = crypto.createCipheriv('aes-256-gcm', key, iv(n)); return Buffer.concat([c.update(Buffer.from([kind])), c.update(payload), c.final(), c.getAuthTag()]); };
const json = Buffer.from(JSON.stringify({ t: 'offer', count: 1 }));
console.log(JSON.stringify({
  privA: privA.toString('hex'), privB: privB.toString('hex'), pubA, pubB, codeA: codeOf(pubA), codeB: codeOf(pubB),
  secret: secret.toString('hex'), nonceC: nonceC.toString('hex'), nonceS: nonceS.toString('hex'),
  c2s: okm.subarray(0, 32).toString('hex'), s2c: okm.subarray(32).toString('hex'),
  sealedJson0: seal(okm.subarray(0, 32), 0, 1, json).toString('hex'), json: json.toString(),
  sealedData1: seal(okm.subarray(0, 32), 1, 2, Buffer.from('hello file data')).toString('hex'),
}, null, 2));
