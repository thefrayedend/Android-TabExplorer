'use strict';
// Test harness: TabExplorer's own share.js as the other side of the Android app's tests.
//   node interop.js receive <identityDir> <outDir> <tcpPort> <udpPort>
//       takes files from anyone into <outDir>; prints "READY {id,pub,port,name}" when up.
//   node interop.js send <identityDir> <tcpPort> <udpPort> <peerJson> <file or folder>...
//       sends to the device described by <peerJson> ({id,name,pub,address,port}); prints "RESULT {...}".
const path = require('path');
const fs = require('fs');
const [mode, identityDir, a3, a4, a5, ...rest] = process.argv.slice(2);
if (mode === 'receive') { process.env.TABEXPLORER_SHARE_PORT = a4 ? String(a4) : ''; process.env.TABEXPLORER_SHARE_UDP = a5 || ''; }
else { process.env.TABEXPLORER_SHARE_PORT = a3; process.env.TABEXPLORER_SHARE_UDP = a4; }
const { Share } = require('./share');
const log = (l) => console.error('[node]', l);

async function main() {
  if (mode === 'receive') {
    const out = path.resolve(a3);
    fs.mkdirSync(out, { recursive: true });
    const s = new Share({ dir: path.resolve(identityDir), log, config: () => ({ enabled: true, name: 'Node PC', folder: out, conflict: 'keepboth' }),
      ask: async (r) => { log(`approving ${r.name} (code ${r.code}): ${r.count} file(s)`); return true; } });
    await s.apply();
    console.log('READY ' + JSON.stringify({ id: s.id, pub: s.data.pub, port: s.port, name: s.name(), code: s.code }));
    return;
  }
  if (mode === 'send') {
    const peer = JSON.parse(a5);
    const files = rest;
    const s = new Share({ dir: path.resolve(identityDir), log, config: () => ({ enabled: true, name: 'Node PC', folder: path.resolve(identityDir), conflict: 'keepboth' }), ask: async () => false });
    await s.apply();
    s.peers.set(peer.id, { id: peer.id, name: peer.name, pub: peer.pub, address: peer.address, port: peer.port, lastSeen: Date.now() });
    const job = { spec: { peer: peer.id, sources: files.map((a) => path.resolve(a)) }, totalFiles: 0, totalBytes: 0, doneBytes: 0, doneFiles: 0, errors: [], canceled: false, phase: '',
      progress() {}, err(file, e) { this.errors.push({ path: file, message: e.message }); } };
    console.log('SENDER ' + JSON.stringify({ id: s.id, pub: s.data.pub, name: s.name(), code: s.code }));
    try { await s.send(job); console.log('RESULT ' + JSON.stringify({ ok: true, sentTo: job.sentTo, errors: job.errors })); }
    catch (e) { console.log('RESULT ' + JSON.stringify({ ok: false, error: e.message })); }
    await s.stop();
    process.exit(0);
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
