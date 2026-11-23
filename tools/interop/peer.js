'use strict';
// TabExplorer's own sharing code (share.js, copied from TabExplorer's lib/) run as the other side,
// to test the Android app against the real thing. Needs Node 18 or newer; nothing to install.
//
//   node peer.js receive <folder> [name]          a PC that takes files from anyone (approves them itself) into <folder>
//   node peer.js send <name> <file or folder>...  send to the device called <name> once it's found
//   node peer.js list                             who is on the network (name, code, address)
//
// It keeps its identity in ./peer-data (delete that folder to become a "new PC").

const path = require('path');
const fs = require('fs');
const { Share } = require('./share');

const [mode, ...args] = process.argv.slice(2);
const dir = path.join(__dirname, 'peer-data');
const log = (l) => console.log(new Date().toISOString().slice(11, 19), l);

async function main() {
  if (mode === 'receive') {
    const folder = path.resolve(args[0] || 'received');
    fs.mkdirSync(folder, { recursive: true });
    const s = new Share({ dir, log, config: () => ({ enabled: true, name: args[1] || 'Test PC', folder, conflict: 'keepboth' }), ask: async (r) => { log(`approving ${r.name} (code ${r.code}): ${r.count} file(s), ${r.bytes} bytes`); return true; } });
    s.on('received', (r) => log(`received ${r.count} file(s) from ${r.from}: ${r.saved.join(', ')}`));
    await s.apply();
    log(`receiving as "${s.name()}", code ${s.code}, into ${folder} (Ctrl+C to stop)`);
    return;
  }
  const s = new Share({ dir, log, config: () => ({ enabled: true, name: 'Test PC', folder: dir, conflict: 'keepboth' }), ask: async () => false });
  await s.apply();
  log(`on the network as "${s.name()}", code ${s.code}; looking for others...`);
  await new Promise((r) => setTimeout(r, 2500));
  const peers = s.status().peers.filter((p) => p.online);
  if (mode === 'list') {
    for (const p of peers) log(`${p.name}  code ${p.code}  ${p.address}`);
    if (!peers.length) log('nobody found');
  } else if (mode === 'send') {
    const p = peers.find((x) => x.name === args[0]);
    if (!p) { log(`"${args[0]}" isn't on the network`); }
    else {
      const job = { spec: { peer: p.id, sources: args.slice(1).map((a) => path.resolve(a)) }, totalFiles: 0, totalBytes: 0, doneBytes: 0, doneFiles: 0, errors: [], canceled: false, phase: '',
        progress() {}, err(file, e) { this.errors.push({ path: file, message: e.message }); } };
      try { await s.send(job); log(`sent: ${JSON.stringify(job.sentTo)}${job.errors.length ? ' with errors ' + JSON.stringify(job.errors) : ''}`); }
      catch (e) { log('failed: ' + e.message); }
    }
  } else {
    console.log('use: node peer.js receive <folder> [name] | send <name> <files...> | list');
  }
  await s.stop();
  process.exit(0);
}
main().catch((e) => { console.error(e); process.exit(1); });
