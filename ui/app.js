const T = window.__TAURI__;
const invoke = T.core.invoke;
const $ = (id) => document.getElementById(id);

let S = null;              // last state from Rust
let staged = [];           // [{path, name, size, isDir}]
let target = localStorage.getItem('target') || '';
let histTab = 'in';
let lastResult = '';

function fmtSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}
function ago(ms) {
  if (!ms) return 'never seen';
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + ' min ago';
  if (s < 86400) return Math.floor(s / 3600) + ' h ago';
  return Math.floor(s / 86400) + ' d ago';
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
let toastTimer;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 3500);
}

// ---------- state ----------
let pending = false;
async function refresh() {
  if (pending) return;
  pending = true;
  try { S = await invoke('get_state'); render(); } finally { pending = false; }
}
T.event.listen('changed', () => refresh());
setInterval(refresh, 4000);

function render() {
  $('meName').textContent = '· ' + S.me.name;
  if (!$('appVer').textContent && T.app) T.app.getVersion().then((v) => { $('appVer').textContent = 'v' + v; }).catch(() => {});
  const n = S.unseen;
  $('btnReceived').classList.toggle('hidden', n === 0);
  $('recvCount').textContent = n;
  renderDevices();
  renderProgress();
  renderLast();
  if (!$('histPanel').classList.contains('hidden')) renderHistory();
  const lan = S.me.ips.length ? S.me.ips.join(', ') + ' : ' + S.me.port : 'no network';
  $('footer').textContent = `Local: ${lan}  ·  Relay: ${S.relay}`;
  if (S.pairReq && $('pairPanel').classList.contains('hidden')) {
    $('pairText').textContent = `"${S.pairReq.name || 'A device'}" wants to pair with this PC. Accepting replaces this PC's sync key with theirs.`;
    $('pairPanel').classList.remove('hidden');
  } else if (!S.pairReq) {
    $('pairPanel').classList.add('hidden');
  }
}

function renderDevices() {
  const box = $('devices');
  if (!S.peers.some((p) => p.id === target)) {
    const firstOnline = S.peers.find((p) => p.lan || p.relay);
    target = firstOnline ? firstOnline.id : (S.peers[0]?.id || '');
  }
  if (!S.peers.length) {
    box.innerHTML = `<div class="empty">No devices yet. Install Random Push on your phone and enter this sync key there:<br>
      <b class="mono">${esc(S.settings.key)}</b><br>— or press <b>Pair</b> below when the phone shows up on the network.</div>`;
  } else {
    box.innerHTML = S.peers.map((p) => {
      const st = p.lan ? '<span class="dot lan"></span>online · same network'
        : p.relay ? '<span class="dot net"></span>online · via internet'
        : `<span class="dot"></span>offline (last seen ${ago(p.lastSeen)}) · will try anyway`;
      const ic = p.platform === 'android' ? '📱' : '🖥️';
      return `<div class="dev ${p.id === target ? 'sel' : ''}" data-id="${esc(p.id)}">
        <span class="ic">${ic}</span>
        <div class="info"><div class="nm">${esc(p.name || p.id)}</div><div class="muted small-text">${st}${p.addr ? ' · ' + esc(p.addr) : ''}</div></div>
        <button class="x" data-forget="${esc(p.id)}" title="Forget this device">✕</button></div>`;
    }).join('');
  }
  $('foreign').innerHTML = S.foreign.map((f) => `<div class="foreign"><span>Found <b>${esc(f.name)}</b> (${esc(f.addr)}) — not paired${f.has_key ? ' (different sync key)' : ''}</span>
      <button class="small" data-pair="${esc(f.addr)}">Pair</button></div>`).join('');
  $('btnScan').textContent = S.scanning ? 'Scanning…' : 'Scan network';
  $('btnScan').disabled = S.scanning;
}

$('devices').addEventListener('click', async (e) => {
  const f = e.target.closest('[data-forget]');
  if (f) {
    e.stopPropagation();
    await invoke('forget_peer', { id: f.dataset.forget });
    return;
  }
  const d = e.target.closest('.dev');
  if (d) { target = d.dataset.id; localStorage.setItem('target', target); renderDevices(); }
});
$('foreign').addEventListener('click', async (e) => {
  const b = e.target.closest('[data-pair]');
  if (!b) return;
  try {
    await invoke('pair', { addr: b.dataset.pair });
    toast('Pair request sent — accept it on the other device');
  } catch (err) { toast('Pair failed: ' + err); }
});

function renderProgress() {
  const p = S.progress;
  $('btnPush').disabled = p.active;
  $('progWrap').classList.toggle('hidden', !p.active);
  if (p.active) {
    $('progBar').style.width = (p.total ? Math.min(100, (100 * p.done) / p.total) : 0) + '%';
    $('progLabel').className = 'muted small-text';
    $('progLabel').textContent = `${p.label}  ${fmtSize(p.done)} / ${fmtSize(p.total)}`;
  } else if (p.result) {
    $('progLabel').className = 'small-text ' + (p.ok ? 'result-ok' : 'result-bad');
    $('progLabel').textContent = p.result;
    if (p.result !== lastResult) {
      lastResult = p.result;
      if (p.ok) { staged = []; $('text').value = ''; renderStaged(); }
    }
  }
}

// ---------- last push (latest sent batch, refreshed after every push) ----------
function renderLast() {
  const first = S.history.find((h) => h.dir === 'out');
  $('lastCard').classList.toggle('hidden', !first);
  if (!first) return;
  const items = S.history.filter((h) => h.dir === 'out' && h.batch === first.batch).reverse();
  const okN = items.filter((h) => h.ok).length;
  $('lastTitle').textContent = `Last push · to ${first.peer} · ${new Date(first.time).toLocaleTimeString()} · ${okN}/${items.length} delivered`;
  $('lastList').innerHTML = items.map((h) => {
    let acts = '';
    if (h.kind === 'text') acts = `<button class="small" data-copy="${esc(h.id)}">Copy</button>`;
    else if (h.path) acts = `<button class="small" data-open="${esc(h.id)}">Open</button><button class="small" data-reveal="${esc(h.id)}">Show in folder</button>`;
    const via = h.ok ? (h.via === 'LAN' ? 'local network' : 'internet relay') : '';
    return `<div class="li"><span class="st ${h.ok ? 'ok' : 'bad'}">${h.ok ? '✓' : '✗'}</span>
      <span>${h.kind === 'text' ? '📝' : '📄'}</span>
      <span class="nm" title="${esc(h.kind === 'text' ? h.text : h.name)}">${esc(h.name)}</span>
      <span class="meta">${h.kind === 'file' ? fmtSize(h.size) + ' · ' : ''}${via}</span>
      ${h.ok ? '' : `<span class="err" title="${esc(h.error)}">failed</span>`}${acts}</div>`;
  }).join('');
}
$('lastAll').onclick = (e) => { e.preventDefault(); openHistory('out'); };

// ---------- staging ----------
async function addPaths(paths) {
  if (!paths || !paths.length) return;
  const info = await invoke('file_info', { paths });
  for (const i of info) if (!staged.some((s) => s.path === i.path)) staged.push(i);
  renderStaged();
}
function renderStaged() {
  $('staged').innerHTML = staged.map((s, i) => `<li><span>${s.isDir ? '📁' : '📄'}</span>
    <span class="nm" title="${esc(s.path)}">${esc(s.name)}</span><span class="muted small-text">${fmtSize(s.size)}</span>
    <button class="x" data-i="${i}" title="Remove">✕</button></li>`).join('');
}
$('staged').addEventListener('click', (e) => {
  const b = e.target.closest('[data-i]');
  if (b) { staged.splice(+b.dataset.i, 1); renderStaged(); }
});
$('browse').onclick = async (e) => { e.preventDefault(); addPaths(await invoke('pick_files')); };
$('browseDir').onclick = async (e) => { e.preventDefault(); const d = await invoke('pick_folder'); if (d) addPaths([d]); };

// ---------- Ctrl+V a picture (screenshot / Snipping Tool / copied image) -> JPG attachment ----------
async function imageToJpeg(blob) {
  const bmp = await createImageBitmap(blob);
  const c = document.createElement('canvas');
  c.width = bmp.width; c.height = bmp.height;
  const g = c.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); // JPG has no transparency
  g.drawImage(bmp, 0, 0);
  bmp.close && bmp.close();
  return c.toDataURL('image/jpeg', 0.92);
}
function pasteName() {
  const d = new Date(), p = (n) => String(n).padStart(2, '0');
  return `Screenshot ${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}-${p(d.getMinutes())}-${p(d.getSeconds())}`;
}
document.addEventListener('paste', async (e) => {
  const items = [...((e.clipboardData && e.clipboardData.items) || [])];
  const imgs = items.filter((it) => it.kind === 'file' && it.type.startsWith('image/')).map((it) => it.getAsFile()).filter(Boolean);
  if (!imgs.length) return; // plain text: normal paste
  e.preventDefault();
  try {
    const paths = [];
    for (const f of imgs) paths.push(await invoke('save_paste', { data: await imageToJpeg(f), name: pasteName() }));
    await addPaths(paths);
    toast(imgs.length > 1 ? `${imgs.length} pictures attached` : 'Picture attached as JPG');
  } catch (err) {
    toast('Could not paste the picture: ' + err);
  }
});

T.webview.getCurrentWebview().onDragDropEvent((e) => {
  const p = e.payload;
  if (p.type === 'enter' || p.type === 'over') $('dz').classList.add('hover');
  else if (p.type === 'leave') $('dz').classList.remove('hover');
  else if (p.type === 'drop') { $('dz').classList.remove('hover'); addPaths(p.paths); }
});

// ---------- push ----------
$('btnPush').onclick = async () => {
  const text = $('text').value;
  if (!staged.length && !text.trim()) return toast('Drop some files or type text first');
  if (!target) return toast('Choose a device to send to');
  lastResult = '';
  try {
    await invoke('push', { target, paths: staged.map((s) => s.path), text });
  } catch (err) { toast(String(err)); }
};
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && e.ctrlKey) $('btnPush').click();
  if (e.key === 'Escape') document.querySelectorAll('.panel').forEach((p) => p.id !== 'pairPanel' && p.classList.add('hidden'));
});

$('btnScan').onclick = () => invoke('scan');
$('btnAddIp').onclick = async () => {
  const ip = prompt('IP address of the other device (e.g. 192.168.1.23):');
  if (!ip) return;
  try { toast('Added ' + await invoke('add_ip', { ip })); } catch (err) { toast(String(err)); }
};

// ---------- history ----------
function openHistory(tab) {
  histTab = tab;
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === tab));
  $('histPanel').classList.remove('hidden');
  renderHistory();
  invoke('mark_seen');
}
function renderHistory() {
  const list = S.history.filter((h) => h.dir === histTab);
  if (!list.length) { $('histList').innerHTML = '<div class="empty">Nothing here yet.</div>'; return; }
  $('histList').innerHTML = list.map((h) => {
    const when = new Date(h.time).toLocaleString();
    const who = (h.dir === 'in' ? 'from ' : 'to ') + h.peer;
    const via = h.via ? ' · ' + (h.via === 'LAN' ? 'local network' : 'internet relay') : '';
    let acts = '';
    if (h.kind === 'text') acts = `<button class="small" data-copy="${esc(h.id)}">Copy</button>`;
    else if (h.ok && h.path) acts = (h.dir === 'in' ? `<button class="small" data-open="${esc(h.id)}">Open</button>` : '')
      + `<button class="small" data-reveal="${esc(h.id)}">Show in folder</button>`;
    acts += `<button class="small ghost" data-del="${esc(h.id)}" title="Remove from list">✕</button>`;
    return `<div class="hi ${h.dir === 'in' && !h.seen ? 'new' : ''}">
      <div class="top"><span>${h.kind === 'text' ? '📝' : '📄'}</span><span class="nm" title="${esc(h.name)}">${esc(h.kind === 'text' ? 'Text' : h.name)}</span><div class="acts">${acts}</div></div>
      <div class="meta">${esc(who)} · ${when}${h.kind === 'file' ? ' · ' + fmtSize(h.size) : ''}${via}</div>
      ${h.kind === 'text' ? `<div class="txt">${esc(h.text)}</div>` : ''}
      ${!h.ok ? `<div class="err">Failed: ${esc(h.error)}</div>` : ''}</div>`;
  }).join('');
}
async function itemAction(e) {
  const b = e.target.closest('button');
  if (!b) return;
  try {
    if (b.dataset.copy) { await invoke('copy_text', { text: S.history.find((h) => h.id === b.dataset.copy).text }); toast('Copied'); }
    if (b.dataset.open) await invoke('open_item', { id: b.dataset.open });
    if (b.dataset.reveal) await invoke('reveal_item', { id: b.dataset.reveal });
    if (b.dataset.del) await invoke('delete_item', { id: b.dataset.del });
  } catch (err) { toast(String(err)); }
}
$('histList').addEventListener('click', itemAction);
$('lastList').addEventListener('click', itemAction);
T.event.listen('open-received', () => openHistory('in'));
document.querySelectorAll('.tab').forEach((t) => (t.onclick = () => openHistory(t.dataset.tab)));
$('btnReceived').onclick = () => openHistory('in');
$('btnHistory').onclick = () => openHistory('in');
$('btnOpenFolder').onclick = () => invoke('open_folder');
$('btnClear').onclick = () => { if (confirm('Clear the list? (files stay on disk)')) invoke('clear_history'); };
document.querySelectorAll('[data-close]').forEach((b) => (b.onclick = () => $(b.dataset.close).classList.add('hidden')));

// ---------- settings ----------
$('btnSettings').onclick = () => {
  const s = S.settings;
  $('sName').value = s.device_name;
  $('sKey').value = s.key;
  $('sAuto').checked = s.autostart;
  $('sCopy').checked = s.auto_copy_text;
  $('sPopup').checked = s.popup;
  $('sSound').checked = s.sound;
  $('sDir').value = s.save_dir;
  $('sRelay').checked = s.relay_enabled;
  $('sServer').value = s.relay_server;
  $('sMax').value = s.relay_max_mb;
  $('netInfo').textContent = `This PC: ${S.me.ips.join(', ') || 'no network'} · TCP ${S.me.port} · UDP 47321 · id ${S.me.id}`;
  $('setPanel').classList.remove('hidden');
};
$('btnCopyKey').onclick = async () => { await invoke('copy_text', { text: $('sKey').value }); toast('Key copied'); };
$('btnNewKey').onclick = async () => {
  if (confirm('Make a new sync key? Every other device must then use the new key (or be paired again).')) $('sKey').value = await invoke('new_key');
};
$('btnDir').onclick = async () => { const d = await invoke('pick_folder'); if (d) $('sDir').value = d; };
$('btnSave').onclick = async () => {
  await invoke('save_settings', { s: {
    device_name: $('sName').value, key: $('sKey').value, autostart: $('sAuto').checked,
    auto_copy_text: $('sCopy').checked, popup: $('sPopup').checked, sound: $('sSound').checked, save_dir: $('sDir').value, relay_enabled: $('sRelay').checked,
    relay_server: $('sServer').value, relay_max_mb: Math.max(1, parseInt($('sMax').value || '15', 10)),
  } });
  $('setPanel').classList.add('hidden');
  toast('Saved');
};

// ---------- pairing ----------
$('btnPairYes').onclick = () => invoke('answer_pair', { accept: true }).then(() => toast('Paired'));
$('btnPairNo').onclick = () => invoke('answer_pair', { accept: false });

refresh();
