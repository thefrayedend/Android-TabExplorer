'use strict';
// Sharing between PCs on the local network: TabExplorer on one PC sends files straight to
// TabExplorer on another ("Send to > Workshop"). One implementation for Windows, macOS and Linux.
//
// Finding each other: every PC with sharing on says who it is on UDP port 47591 (multicast
// 239.255.47.59, and broadcast for networks that drop multicast) every 20 seconds, and answers
// when another PC asks: its name, its id, its TCP port and its public key.
//
// Approving: a PC takes files only from PCs it approved once - asked on the first send, or ticked
// in its Sharing settings. The approval belongs to the sender's key, so another PC can't pass
// itself off as it by taking its name.
//
// Sending: one TCP connection per send. Each side has an X25519 key pair; its private key and the
// other side's public key make a secret that only those two PCs can make, so getting through the
// greeting proves who both are. Everything after the greeting is encrypted (AES-256-GCM).
//
// Receiving: into the folder chosen in Sharing settings, folders kept as they were. A file with
// the same name is replaced or kept beside it, as chosen there. Each file is written under a
// temporary name first, so a send cut off part-way never leaves half a file under the real name.

const crypto = require('crypto');
const dgram = require('dgram');
const net = require('net');
const fs = require('fs');
const fsp = fs.promises;
const os = require('os');
const path = require('path');
const { EventEmitter } = require('events');

const PORT = parseInt(process.env.TABEXPLORER_SHARE_PORT || '', 10) || 47591;           // TCP: files
const UDP_PORT = parseInt(process.env.TABEXPLORER_SHARE_UDP || '', 10) || 47591;        // UDP: finding each other
const GROUP = '239.255.47.59';
const TAG = 'tabexplorer-share';
const ANNOUNCE_MS = 20000;
const ONLINE_MS = 65000;              // not heard from for longer than this: not on
const ASK_MS = 3 * 60 * 1000;         // how long a first send waits for the other PC to say yes
const CHUNK = 256 * 1024;
const MAX_FRAME = 32 * 1024 * 1024;
const MAX_ITEMS = 200000;
const PART = '.txpart';
const isWin = process.platform === 'win32';

// ---------------------------------------------------------------- small helpers
const b64 = (b) => Buffer.from(b).toString('base64');
const pubKey = (s) => crypto.createPublicKey({ key: Buffer.from(s, 'base64'), format: 'der', type: 'spki' });
const privKey = (s) => crypto.createPrivateKey({ key: Buffer.from(s, 'base64'), format: 'der', type: 'pkcs8' });
// the 8 digits shown next to a PC's name, to tell two PCs apart (and to check one is really the one)
function codeOf(pub) {
  const h = crypto.createHash('sha256').update(String(pub || '')).digest();
  const n = h.readUInt32BE(0) % 100000000;
  const s = String(n).padStart(8, '0');
  return s.slice(0, 4) + ' ' + s.slice(4);
}
const cleanName = (s) => String(s || '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 40);
function defaultName() {
  const h = cleanName(os.hostname().replace(/\.local$/i, ''));
  return h || 'This PC';
}
function friendly(e) {
  const c = e && e.code;
  if (c === 'ECONNREFUSED') return 'it isn\'t taking files right now (is TabExplorer open there, with sharing on?)';
  if (c === 'ETIMEDOUT' || c === 'EHOSTUNREACH' || c === 'ENETUNREACH' || c === 'TIMEOUT') return 'it didn\'t answer (is it on, with TabExplorer running?)';
  if (c === 'ECONNRESET' || c === 'EPIPE') return 'the connection was cut off';
  if (c === 'ENOSPC') return 'its disk is full';
  return (e && e.message) || String(e);
}

// One name part of a path as it arrives from another PC: never a way out of the folder, and
// valid on this system (Windows refuses more characters and a few names).
const WIN_RESERVED = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i;
function safePart(s) {
  let p = String(s || '').replace(/[\u0000-\u001f]/g, '');
  if (isWin) p = p.replace(/[<>:"|?*\\]/g, '_');
  p = p.replace(/\//g, '_');
  if (isWin) p = p.replace(/[. ]+$/, '');
  if (!p || p === '.' || p === '..') return '';
  if (isWin && WIN_RESERVED.test(p)) p = '_' + p;
  return p.slice(0, 240);
}
function safeRel(rel) {
  const parts = String(rel || '').split('/').map(safePart);
  if (!parts.length || parts.length > 64 || parts.some((x) => !x)) return null;
  return parts;
}
async function exists(p) { try { return await fsp.lstat(p); } catch (_) { return null; } }
async function uniqueName(dir, name) {
  const ext = path.extname(name), stem = ext && ext !== name ? name.slice(0, -ext.length) : name;
  for (let i = 2; i < 10000; i++) {
    const cand = `${stem} (${i})${ext === name ? '' : ext}`;
    if (!(await exists(path.join(dir, cand)))) return cand;
  }
  return `${stem} (${Date.now()})${ext}`;
}

// ---------------------------------------------------------------- framing + encryption
// A frame: 4 bytes of length, then the body. The greeting is plain JSON; after it every body is
// AES-256-GCM (12-byte counter nonce, 16-byte tag at the end) of: 1 + JSON, or 2 + file data.
class Channel {
  constructor(sock) {
    this.sock = sock;
    this.buf = Buffer.alloc(0);
    this.frames = [];
    this.waiters = [];
    this.ended = null;
    this.sendKey = null; this.recvKey = null;
    this.sendN = 0n; this.recvN = 0n;
    sock.on('data', (d) => this.onData(d));
    sock.on('error', (e) => this.finish(e));
    sock.on('close', () => this.finish(Object.assign(new Error('The connection was cut off.'), { code: 'ECONNRESET' })));
  }
  onData(d) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, d]) : d;
    while (this.buf.length >= 4) {
      const n = this.buf.readUInt32BE(0);
      if (n > MAX_FRAME) { this.finish(new Error('The other PC sent something that isn\'t TabExplorer sharing.')); this.sock.destroy(); return; }
      if (this.buf.length < 4 + n) break;
      const body = this.buf.subarray(4, 4 + n);
      this.buf = this.buf.subarray(4 + n);
      this.frames.push(Buffer.from(body));
    }
    if (this.frames.length > 64) this.sock.pause(); // the reader is busy writing to disk: let it catch up
    this.wake();
  }
  wake() { while (this.waiters.length && (this.frames.length || this.ended)) { const w = this.waiters.shift(); w(); } }
  finish(e) { if (!this.ended) { this.ended = e || new Error('closed'); this.wake(); } }
  nonce(n) { const iv = Buffer.alloc(12); iv.writeBigUInt64BE(n, 4); return iv; }
  async rawNext(ms) {
    if (!this.frames.length && !this.ended) {
      await new Promise((resolve) => {
        let t = null;
        const w = () => { clearTimeout(t); resolve(); };
        this.waiters.push(w);
        if (ms) t = setTimeout(() => { const i = this.waiters.indexOf(w); if (i >= 0) this.waiters.splice(i, 1); resolve(); }, ms);
      });
    }
    if (this.frames.length) {
      const f = this.frames.shift();
      if (this.frames.length < 16 && this.sock.isPaused()) this.sock.resume();
      return f;
    }
    if (this.ended) throw this.ended;
    throw Object.assign(new Error('timed out'), { code: 'TIMEOUT' });
  }
  // the next message: { json } or { data }
  async next(ms) {
    const body = await this.rawNext(ms);
    if (!this.recvKey) return { json: JSON.parse(body.toString('utf8')) };
    if (body.length < 17) throw new Error('A damaged message arrived.');
    const de = crypto.createDecipheriv('aes-256-gcm', this.recvKey, this.nonce(this.recvN++));
    de.setAuthTag(body.subarray(body.length - 16));
    let plain;
    try { plain = Buffer.concat([de.update(body.subarray(0, body.length - 16)), de.final()]); } catch (_) {
      throw Object.assign(new Error('The other PC couldn\'t prove who it is.'), { code: 'EAUTH' });
    }
    if (plain[0] === 1) return { json: JSON.parse(plain.subarray(1).toString('utf8')) };
    return { data: plain.subarray(1) };
  }
  async json(ms) {
    const m = await this.next(ms);
    if (!m.json) throw new Error('Something unexpected arrived.');
    return m.json;
  }
  frame(body) {
    const head = Buffer.alloc(4);
    head.writeUInt32BE(body.length, 0);
    return this.sock.write(Buffer.concat([head, body]));
  }
  seal(kind, payload) {
    if (!this.sendKey) return this.frame(payload);
    const ci = crypto.createCipheriv('aes-256-gcm', this.sendKey, this.nonce(this.sendN++));
    const out = Buffer.concat([ci.update(Buffer.from([kind])), ci.update(payload), ci.final(), ci.getAuthTag()]);
    return this.frame(out);
  }
  async send(obj) { if (!this.seal(1, Buffer.from(JSON.stringify(obj), 'utf8'))) await this.drain(); }
  async sendData(buf) { if (!this.seal(2, buf)) await this.drain(); }
  drain() {
    if (this.ended) return Promise.reject(this.ended);
    return new Promise((resolve, reject) => {
      const done = () => { this.sock.off('drain', done); this.sock.off('close', fail); resolve(); };
      const fail = () => { this.sock.off('drain', done); reject(this.ended || new Error('closed')); };
      this.sock.once('drain', done);
      this.sock.once('close', fail);
    });
  }
  // both sides: after the greeting
  keys(myPriv, peerPub, nonceC, nonceS, isClient) {
    const secret = crypto.diffieHellman({ privateKey: privKey(myPriv), publicKey: pubKey(peerPub) });
    const okm = Buffer.from(crypto.hkdfSync('sha256', secret, Buffer.concat([nonceC, nonceS]), Buffer.from('tabexplorer-share v1'), 64));
    const c2s = okm.subarray(0, 32), s2c = okm.subarray(32, 64);
    this.sendKey = isClient ? c2s : s2c;
    this.recvKey = isClient ? s2c : c2s;
  }
}

// ---------------------------------------------------------------- the service
class Share extends EventEmitter {
  // dir: where share.json lives (the user's TabExplorer settings folder)
  // config(): { enabled, name, folder, conflict: 'replace' | 'keepboth' }
  // ask({ id, name, code, count, bytes }) -> Promise<boolean>: may this PC send us files?
  constructor({ dir, config, ask, log }) {
    super();
    this.file = path.join(dir, 'share.json');
    this.config = config;
    this.ask = ask;
    this.log = log || (() => {});
    this.peers = new Map(); // id -> { id, name, pub, address, port, lastSeen }
    this.server = null; this.udp = null; this.timer = null;
    this.port = 0;
    this.running = false;
    this.error = '';
    this.connect = (o) => net.connect(o);   // (tests put a stand-in here)
    this.connectTimeout = 8000;
    this.data = this.load();
    for (const [id, k] of Object.entries(this.data.known || {})) this.peers.set(id, { ...k, id, lastSeen: 0 });
  }

  load() {
    let d = null;
    try { d = JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch (_) {}
    if (!d || !d.id || !d.priv || !d.pub) {
      const k = crypto.generateKeyPairSync('x25519');
      d = {
        id: crypto.randomBytes(12).toString('hex'),
        priv: b64(k.privateKey.export({ type: 'pkcs8', format: 'der' })),
        pub: b64(k.publicKey.export({ type: 'spki', format: 'der' })),
        trusted: {}, known: {},
      };
      this.data = d;
      this.save();
    }
    d.trusted = d.trusted || {}; d.known = d.known || {};
    return d;
  }
  save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 });
      fs.renameSync(tmp, this.file);
    } catch (e) { this.log('share: could not save share.json: ' + e.message); }
  }
  get id() { return this.data.id; }
  get code() { return codeOf(this.data.pub); }
  name() { return cleanName(this.config().name) || defaultName(); }

  // ---- what the window shows
  status() {
    const c = this.config();
    const now = Date.now();
    // the PCs that are on now, and the ones this PC sends to or takes files from (a PC seen once and gone isn't kept on show)
    const kept = (p) => (this.running && now - (p.lastSeen || 0) < ONLINE_MS) || !!this.data.trusted[p.id] || !!(this.data.known[p.id] && this.data.known[p.id].sent);
    const list = [...this.peers.values()].filter((p) => p.id !== this.id && kept(p)).map((p) => ({
      id: p.id, name: p.name, code: codeOf(p.pub), online: this.running && now - (p.lastSeen || 0) < ONLINE_MS,
      canSend: this.isTrusted(p.id, p.pub), address: p.address || '',
    })).sort((a, b) => a.name.localeCompare(b.name) || a.code.localeCompare(b.code));
    return { enabled: !!c.enabled, running: this.running, name: this.name(), defaultName: defaultName(), code: this.code, id: this.id, port: this.port, error: this.error, peers: list };
  }
  isTrusted(id, pub) { const t = this.data.trusted[id]; return !!(t && t.pub === pub); }
  setTrusted(id, on) {
    const p = this.peers.get(id);
    if (on) { if (!p || !p.pub) return false; this.data.trusted[id] = { name: p.name, pub: p.pub, since: Date.now() }; }
    else delete this.data.trusted[id];
    this.save();
    this.changed();
    return true;
  }
  forget(id) {
    delete this.data.trusted[id];
    delete this.data.known[id];
    this.peers.delete(id);
    this.save();
    this.changed();
  }
  changed() { clearTimeout(this.changedTimer); this.changedTimer = setTimeout(() => this.emit('peers', this.status()), 50); }
  remember(p) {
    const k = this.data.known[p.id];
    if (k && k.name === p.name && k.pub === p.pub && k.address === p.address && k.port === p.port) return;
    this.data.known[p.id] = { ...(k && k.pub === p.pub ? k : {}), name: p.name, pub: p.pub, address: p.address, port: p.port };
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.save(), 1000);
  }

  // ---- on / off, as the settings say (one change at a time: settings can change twice in a row)
  apply() {
    this.applying = (this.applying || Promise.resolve()).then(() => this.applyNow()).catch((e) => this.log('share: ' + e.message));
    return this.applying;
  }
  async applyNow() {
    const want = !!this.config().enabled;
    if (want && !this.running) await this.start();
    else if (!want && this.running) await this.stop();
    else if (want) this.announce(); // the name may have changed
    this.changed();
  }
  async start() {
    this.error = '';
    try {
      this.server = net.createServer((sock) => this.receive(sock).catch((e) => { this.log('share: receiving failed: ' + friendly(e)); sock.destroy(); }));
      this.server.maxConnections = 8;
      this.port = await new Promise((resolve, reject) => {
        const tryPort = (p) => {
          const onErr = (e) => {
            this.server.off('listening', onOk);
            if (e.code === 'EADDRINUSE' && p !== 0) tryPort(0); else reject(e); // another copy (another account) has the port: any free one
          };
          const onOk = () => { this.server.off('error', onErr); resolve(this.server.address().port); };
          this.server.once('error', onErr);
          this.server.once('listening', onOk);
          this.server.listen(p, '0.0.0.0');
        };
        tryPort(PORT);
      });
      this.server.on('error', (e) => this.log('share: ' + e.message));
      this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.udp.on('error', (e) => this.log('share: discovery: ' + e.message));
      this.udp.on('message', (m, r) => this.onUdp(m, r));
      await new Promise((resolve, reject) => { this.udp.once('error', reject); this.udp.bind(UDP_PORT, () => { this.udp.off('error', reject); resolve(); }); });
      try { this.udp.setBroadcast(true); this.udp.setMulticastTTL(1); this.udp.setMulticastLoopback(true); } catch (_) {}
      this.join();
      this.running = true;
      this.log(`share: on as "${this.name()}" (code ${this.code}), files on TCP ${this.port}`);
      this.announce('who');
      this.timer = setInterval(() => { this.join(); this.announce(); this.changed(); }, ANNOUNCE_MS);
      if (this.timer.unref) this.timer.unref();
    } catch (e) {
      this.error = e.message;
      this.log('share: could not start: ' + e.message);
      await this.stop(true);
    }
  }
  async stop(quiet) {
    if (this.running && !quiet) this.announce('bye');
    this.running = false;
    clearInterval(this.timer); this.timer = null;
    const u = this.udp; this.udp = null;
    if (u) setTimeout(() => { try { u.close(); } catch (_) {} }, 150);
    const s = this.server; this.server = null;
    if (s) await new Promise((r) => s.close(() => r()));
    if (!quiet) this.log('share: off');
  }

  // ---- finding each other
  interfaces() {
    const out = [];
    for (const list of Object.values(os.networkInterfaces())) {
      for (const a of list || []) {
        if (a.family !== 'IPv4' && a.family !== 4) continue;
        if (a.address.startsWith('169.254.')) continue;
        out.push(a);
      }
    }
    return out;
  }
  join() {
    if (!this.udp) return;
    for (const a of this.interfaces()) { try { this.udp.addMembership(GROUP, a.address); } catch (_) { /* joined already */ } }
  }
  message(t) { return Buffer.from(JSON.stringify({ tx: TAG, v: 1, t, id: this.id, name: this.name(), port: this.port, pub: this.data.pub })); }
  announce(t = 'here') {
    const u = this.udp;
    if (!u) return;
    const m = this.message(t);
    const send = (addr) => { try { u.send(m, UDP_PORT, addr, () => {}); } catch (_) {} };
    for (const a of this.interfaces()) {
      try { u.setMulticastInterface(a.address); u.send(m, UDP_PORT, GROUP, () => {}); } catch (_) {}
      if (!a.internal && a.netmask) {
        const ip = a.address.split('.').map(Number), mask = a.netmask.split('.').map(Number);
        send(ip.map((x, i) => (x & mask[i]) | (~mask[i] & 255)).join('.'));
      }
    }
    send('255.255.255.255');
    // PCs seen before, straight to them (for networks that drop both multicast and broadcast)
    for (const p of this.peers.values()) if (p.address && p.id !== this.id) send(p.address);
  }
  onUdp(m, r) {
    let j;
    try { j = JSON.parse(String(m)); } catch (_) { return; }
    if (!j || j.tx !== TAG || j.v !== 1 || typeof j.id !== 'string' || j.id === this.id || !/^[0-9a-f]{8,64}$/.test(j.id)) return;
    if (j.t === 'bye') { const p = this.peers.get(j.id); if (p) { p.lastSeen = 0; this.changed(); } return; }
    if (typeof j.pub !== 'string' || j.pub.length > 200 || !(j.port > 0 && j.port < 65536)) return;
    try { pubKey(j.pub); } catch (_) { return; }
    const before = this.peers.get(j.id);
    const p = { id: j.id, name: cleanName(j.name) || 'PC', pub: j.pub, address: r.address, port: j.port, lastSeen: Date.now() };
    this.peers.set(j.id, p);
    this.remember(p);
    if (!before || !before.lastSeen || before.name !== p.name || before.address !== p.address) this.changed();
    if (j.t === 'who') { clearTimeout(this.replyTimer); this.replyTimer = setTimeout(() => this.announce(), 50 + Math.random() * 250); }
  }
  refresh() { this.announce('who'); }

  // ---- sending (a job of the job manager: progress, cancel and errors as for a copy)
  async send(job) {
    const peer = this.peers.get(job.spec.peer);
    if (!this.running) throw new Error('Sharing is off on this PC. Turn it on in Sharing settings.');
    if (!peer || !peer.address) throw new Error('That PC hasn\'t been found on the network.');
    const name = peer.name;
    // what to send: every file and folder under the sources, as paths relative to them
    job.phase = 'scan'; job.progress(true);
    const items = [];
    const files = [];
    const walk = async (abs, rel) => {
      if (job.canceled) return;
      if (items.length >= MAX_ITEMS) throw new Error('That is too many files to send at once.');
      let st;
      try { st = await fsp.stat(abs); } catch (e) { job.err(abs, e); return; }
      if (st.isDirectory()) {
        items.push({ rel, dir: true });
        let names = [];
        try { names = await fsp.readdir(abs); } catch (e) { job.err(abs, e); return; }
        for (const n of names) await walk(path.join(abs, n), rel + '/' + n);
      } else if (st.isFile()) {
        items.push({ rel, size: st.size, mtime: st.mtimeMs });
        files.push({ i: items.length - 1, abs, size: st.size });
        job.totalFiles++; job.totalBytes += st.size;
      }
    };
    for (const s of job.spec.sources || []) await walk(s, path.basename(s) || s.replace(/[\\/:]+/g, ''));
    if (job.canceled || !items.length) return;

    const sock = this.connect({ host: peer.address, port: peer.port });
    sock.setNoDelay(true);
    const ch = new Channel(sock);
    job.onCancel = () => sock.destroy();
    const connected = new Promise((resolve, reject) => {
      const t = setTimeout(() => { sock.destroy(); reject(Object.assign(new Error('timeout'), { code: 'TIMEOUT' })); }, this.connectTimeout);
      sock.once('connect', () => { clearTimeout(t); resolve(); });
      sock.once('error', (e) => { clearTimeout(t); reject(e); });
    });
    try {
      try { await connected; } catch (e) {
        // heard from a moment ago, but the connection gets no answer: something on that PC keeps it out
        const seen = Date.now() - (peer.lastSeen || 0) < ONLINE_MS;
        if (seen && ['TIMEOUT', 'ETIMEDOUT', 'EHOSTUNREACH'].includes(e.code)) {
          throw new Error(`${name} is on the network, but didn't let TabExplorer in: its firewall is most likely blocking it. On ${name}, open Sharing and click "Let other PCs in".`);
        }
        throw new Error(`Couldn't reach ${name}: ${friendly(e)}`);
      }
      const nonceC = crypto.randomBytes(16);
      await ch.send({ t: 'hello', v: 1, id: this.id, name: this.name(), pub: this.data.pub, nonce: nonceC.toString('hex') });
      const hi = await ch.json(10000);
      if (hi.t === 'no') throw new Error(`${name} isn't taking files: ${hi.message || 'sharing is off there'}.`);
      if (hi.t !== 'hello' || hi.id !== peer.id || hi.pub !== peer.pub) throw new Error(`The PC that answered isn't the ${name} found on the network. Nothing was sent.`);
      ch.keys(this.data.priv, hi.pub, nonceC, Buffer.from(String(hi.nonce), 'hex'), true);
      await ch.send({ t: 'offer', count: job.totalFiles, bytes: job.totalBytes, items });
      // the first time, someone at the other PC says yes
      job.phase = 'waiting'; job.progress(true);
      const ans = await ch.json(ASK_MS + 15000);
      if (ans.t === 'no') throw new Error(`${name} said no: ${ans.message || 'it didn\'t accept the files'}.`);
      if (ans.t !== 'go') throw new Error('Something unexpected arrived.');
      job.phase = 'copy'; job.progress(true);
      for (const f of files) {
        if (job.canceled) return;
        job.current = f.abs; job.progress();
        await ch.send({ t: 'file', i: f.i });
        let sent = 0;
        try {
          const rs = fs.createReadStream(f.abs, { highWaterMark: CHUNK });
          for await (const chunk of rs) {
            if (job.canceled) { rs.destroy(); return; }
            await ch.sendData(chunk);
            sent += chunk.length; job.doneBytes += chunk.length;
            job.progress();
          }
          await ch.send({ t: 'end', i: f.i, size: sent });
        } catch (e) {
          if (job.canceled || ch.ended) throw e;
          job.err(f.abs, e);
          await ch.send({ t: 'skip', i: f.i });
          job.doneBytes += Math.max(0, f.size - sent);
        }
        job.doneFiles++;
      }
      await ch.send({ t: 'done' });
      const res = await ch.json(60000);
      if (res.t !== 'saved') throw new Error('Something unexpected arrived.');
      for (const e of res.errors || []) job.errors.push({ path: String(e.path || ''), message: `${name}: ${e.message}` });
      job.sentTo = { name, count: res.count | 0, folder: String(res.folder || '') };
      if (this.data.known[peer.id]) { this.data.known[peer.id].sent = Date.now(); this.save(); }
      this.log(`share: sent ${res.count} file(s) to ${name}`);
    } catch (e) {
      if (job.canceled) return;
      this.log(`share: sending to ${name} failed: ${e.message}`);
      if (e.code === 'EAUTH') throw new Error(`${name} couldn't prove who it is. Nothing more was sent.`);
      if (e.code === 'ECONNRESET' || e.code === 'TIMEOUT') throw new Error(`The connection to ${name} was cut off: ${friendly(e)}`);
      throw e;
    } finally {
      sock.end();
      setTimeout(() => sock.destroy(), 2000);
    }
  }

  // ---- receiving
  async receive(sock) {
    sock.setNoDelay(true);
    const ch = new Channel(sock);
    const c = this.config();
    const hi = await ch.json(10000);
    if (hi.t !== 'hello' || hi.v !== 1 || typeof hi.id !== 'string' || typeof hi.pub !== 'string' || !/^[0-9a-f]{32}$/.test(String(hi.nonce))) { sock.destroy(); return; }
    const folder = c.folder;
    if (!c.enabled || !this.running) { await ch.send({ t: 'no', message: 'sharing is off there' }); sock.end(); return; }
    try { pubKey(hi.pub); } catch (_) { sock.destroy(); return; }
    const nonceS = crypto.randomBytes(16);
    await ch.send({ t: 'hello', v: 1, id: this.id, name: this.name(), pub: this.data.pub, nonce: nonceS.toString('hex') });
    ch.keys(this.data.priv, hi.pub, Buffer.from(hi.nonce, 'hex'), nonceS, false);
    const from = cleanName(hi.name) || 'Another PC';
    const offer = await ch.json(20000); // getting this far proves the sender has the key it named
    if (offer.t !== 'offer' || !Array.isArray(offer.items) || offer.items.length > MAX_ITEMS) { sock.destroy(); return; }
    // known now, even if it hadn't been seen on the network (the address it came from)
    const seen = this.peers.get(hi.id);
    if (!seen || seen.pub !== hi.pub) {
      const p = { id: hi.id, name: from, pub: hi.pub, address: (sock.remoteAddress || '').replace(/^::ffff:/, ''), port: (seen && seen.port) || PORT, lastSeen: Date.now() };
      this.peers.set(hi.id, p); this.remember(p); this.changed();
    }
    const count = offer.items.filter((x) => !x.dir).length;
    const bytes = offer.items.reduce((n, x) => n + (x.dir ? 0 : Math.max(0, +x.size || 0)), 0);
    if (!this.isTrusted(hi.id, hi.pub)) {
      this.log(`share: ${from} (code ${codeOf(hi.pub)}) asks to send ${count} file(s)`);
      const yes = await Promise.race([
        Promise.resolve(this.ask({ id: hi.id, name: from, code: codeOf(hi.pub), count, bytes })).catch(() => false),
        new Promise((r) => setTimeout(() => r(false), ASK_MS)),
        new Promise((r) => sock.once('close', () => r(false))),
      ]);
      if (!yes) { await ch.send({ t: 'no', message: 'nobody there accepted the files' }).catch(() => {}); sock.end(); return; }
      this.data.trusted[hi.id] = { name: from, pub: hi.pub, since: Date.now() };
      this.save();
      this.changed();
    }
    // the folder: there, and room for it all
    try {
      if (!folder) throw new Error('no folder is set for files sent to it');
      await fsp.mkdir(folder, { recursive: true });
      const st = await fsp.stat(folder);
      if (!st.isDirectory()) throw new Error('its folder for files sent to it is missing');
      if (fs.statfs) {
        const sf = await fsp.statfs(folder).catch(() => null);
        if (sf && sf.bavail * sf.bsize < bytes + 16 * 1024 * 1024) throw new Error('there isn\'t enough room on its disk');
      }
    } catch (e) {
      await ch.send({ t: 'no', message: e.message }).catch(() => {});
      sock.end();
      return;
    }
    await ch.send({ t: 'go' });
    const root = path.resolve(folder);
    const replace = c.conflict !== 'keepboth';
    const errors = [];
    const top = new Map(); // top-level name as sent -> name as saved
    const saved = [];
    let received = 0;
    // where an item goes: its top-level name settled once (replace, or a new name beside it)
    const target = async (rel, isDir) => {
      const parts = safeRel(rel);
      if (!parts) return null;
      let first = top.get(parts[0]);
      if (first === undefined) {
        const st = await exists(path.join(root, parts[0]));
        const whole = parts.length === 1;
        // a folder goes into a folder of that name (as File Explorer merges them); never a file over a folder
        if (!st) first = parts[0];
        else if (st.isDirectory() && (isDir || !whole)) first = parts[0];
        else if (!st.isDirectory() && whole && !isDir && replace) first = parts[0];
        else first = await uniqueName(root, parts[0]);
        top.set(parts[0], first);
        saved.push(path.join(root, first));
      }
      const out = path.join(root, first, ...parts.slice(1));
      const res = path.resolve(out);
      if (res !== root && !res.startsWith(root + path.sep)) return null;
      return res;
    };
    let cur = null; // { i, dest, part, ws, size, got }
    const closeCur = async (keep) => {
      if (!cur) return;
      const c0 = cur; cur = null;
      await new Promise((r) => c0.ws.end(r));
      if (!keep) { await fsp.rm(c0.part, { force: true }).catch(() => {}); return; }
      try {
        let dest = c0.dest;
        const st = await exists(dest);
        if (st && st.isDirectory()) dest = path.join(path.dirname(dest), await uniqueName(path.dirname(dest), path.basename(dest)));
        else if (st && !replace) dest = path.join(path.dirname(dest), await uniqueName(path.dirname(dest), path.basename(dest)));
        await fsp.rename(c0.part, dest);
        const it = offer.items[c0.i];
        if (it && it.mtime > 0) { const t = new Date(it.mtime); await fsp.utimes(dest, t, t).catch(() => {}); }
        received++;
      } catch (e) {
        errors.push({ path: offer.items[c0.i] ? offer.items[c0.i].rel : '', message: e.message });
        await fsp.rm(c0.part, { force: true }).catch(() => {});
      }
    };
    try {
      for (const it of offer.items) {
        if (!it || !it.dir) continue;
        const d = await target(String(it.rel), true);
        if (!d) { errors.push({ path: String(it.rel), message: 'a name that can\'t be used here' }); continue; }
        await fsp.mkdir(d, { recursive: true }).catch((e) => errors.push({ path: String(it.rel), message: e.message }));
      }
      for (;;) {
        const m = await ch.next(5 * 60 * 1000);
        if (m.data) {
          if (cur && cur.ws) {
            cur.got += m.data.length;
            if (!cur.failed && !cur.ws.write(m.data)) {
              const ws = cur.ws;
              await new Promise((r) => { const done = () => { ws.off('drain', done); ws.off('error', done); r(); }; ws.once('drain', done); ws.once('error', done); });
            }
          }
          continue;
        }
        const j = m.json;
        if (j.t === 'file') {
          await closeCur(false);
          const it = offer.items[j.i];
          if (!it || it.dir) throw new Error('Something unexpected arrived.');
          const dest = await target(String(it.rel), false);
          if (!dest) { errors.push({ path: String(it.rel), message: 'a name that can\'t be used here' }); cur = { i: j.i, ws: { write: () => true, end: (cb) => cb() }, part: '', got: 0, skip: true }; continue; }
          await fsp.mkdir(path.dirname(dest), { recursive: true });
          const part = dest + '.' + crypto.randomBytes(3).toString('hex') + PART;
          cur = { i: j.i, dest, part, ws: fs.createWriteStream(part), got: 0 };
          cur.ws.on('error', (e) => { if (cur && cur.part === part) cur.failed = e; });
        } else if (j.t === 'end') {
          if (!cur || cur.i !== j.i) throw new Error('Something unexpected arrived.');
          if (cur.skip) { cur = null; continue; }
          if (cur.failed) { errors.push({ path: String(offer.items[j.i].rel), message: cur.failed.message }); await closeCur(false); continue; }
          await closeCur(cur.got === j.size);
        } else if (j.t === 'skip') {
          await closeCur(false);
        } else if (j.t === 'done') {
          await closeCur(false);
          break;
        } else throw new Error('Something unexpected arrived.');
      }
      await ch.send({ t: 'saved', count: received, folder: root, errors: errors.slice(0, 200) });
      sock.end();
      this.log(`share: received ${received} file(s) from ${from} into ${root}`);
    } catch (e) {
      await closeCur(false);
      this.log(`share: receiving from ${from} stopped: ${friendly(e)}`);
      sock.destroy();
    }
    if (received || saved.length) this.emit('received', { from, folder: root, saved: saved.filter((p) => fs.existsSync(p)), count: received, bytes });
  }
}

module.exports = { Share, codeOf, safeRel, defaultName, PORT, UDP_PORT };
