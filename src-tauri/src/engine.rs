// Shared state: settings, known devices, history, receive + push logic.
// Wire protocol is mirrored by the Android app (android/app/src/main/java/.../Core.kt).
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};

pub const UDP_PORT: u16 = 47321;
pub const TCP_PORT: u16 = 47322;
pub const LAN_ONLINE_MS: u64 = 15_000;
pub const RELAY_ONLINE_MS: u64 = 15 * 60_000;
pub const PLATFORM: &str = "windows";
const HISTORY_MAX: usize = 300;

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn random_hex(bytes: usize) -> String {
    let v: Vec<u8> = (0..bytes).map(|_| rand::random::<u8>()).collect();
    hex::encode(v)
}

pub fn new_key() -> String {
    const A: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let mut s = String::new();
    for i in 0..16 {
        if i > 0 && i % 4 == 0 {
            s.push('-');
        }
        s.push(A[rand::random::<u8>() as usize % A.len()] as char);
    }
    s
}

/// "k7qm 2xpa-..." and "K7QM2XPA..." are the same key.
pub fn norm_key(k: &str) -> String {
    k.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_uppercase()).collect()
}

fn sha(s: &str) -> [u8; 32] {
    Sha256::digest(s.as_bytes()).into()
}

pub struct Keys {
    pub group: String, // public id of the key, sent in LAN announces
    pub token: String, // LAN auth header
    pub topic: String, // relay topic
    pub enc: [u8; 32], // AES-256-GCM key for the relay
}

pub fn keys(key: &str) -> Keys {
    let k = norm_key(key);
    if k.is_empty() {
        return Keys { group: String::new(), token: String::new(), topic: String::new(), enc: [0; 32] };
    }
    Keys {
        group: hex::encode(sha(&format!("datapush-group:{k}")))[..16].to_string(),
        token: hex::encode(sha(&format!("datapush-auth:{k}"))),
        topic: format!("dp_{}", &hex::encode(sha(&format!("datapush-topic:{k}")))[..32]),
        enc: sha(&format!("datapush-enc:{k}")),
    }
}

/// nonce(12) || ciphertext || tag(16)
pub fn encrypt(key: &[u8; 32], data: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new_from_slice(key).expect("key");
    let nonce: [u8; 12] = rand::random();
    let mut out = nonce.to_vec();
    out.extend(cipher.encrypt(Nonce::from_slice(&nonce), data).expect("encrypt"));
    out
}

pub fn decrypt(key: &[u8; 32], data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 28 {
        return None;
    }
    Aes256Gcm::new_from_slice(key).ok()?.decrypt(Nonce::from_slice(&data[..12]), &data[12..]).ok()
}

pub fn b64e(b: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(b)
}

pub fn b64d(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(s.trim()).ok()
}

pub fn preview(s: &str, n: usize) -> String {
    let one: String = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if one.chars().count() > n {
        format!("{}…", one.chars().take(n).collect::<String>())
    } else {
        one
    }
}

pub fn local_ips() -> Vec<String> {
    let mut v = vec![];
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            if let if_addrs::IfAddr::V4(a) = &i.addr {
                if !a.ip.is_loopback() && !a.ip.is_link_local() {
                    v.push(a.ip.to_string());
                }
            }
        }
    }
    v
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub device_id: String,
    pub device_name: String,
    pub key: String,
    pub relay_enabled: bool,
    pub relay_server: String,
    pub relay_max_mb: u64,
    pub auto_copy_text: bool,
    pub save_dir: String,
    pub autostart_init: bool,
    pub popup: bool,
    pub sound: bool,
    pub relay_last_time: u64,
    pub relay_recent_ids: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            device_id: String::new(),
            device_name: String::new(),
            key: String::new(),
            relay_enabled: true,
            relay_server: "https://ntfy.sh".into(),
            relay_max_mb: 15,
            auto_copy_text: true,
            save_dir: String::new(),
            autostart_init: false,
            popup: true,
            sound: true,
            relay_last_time: 0,
            relay_recent_ids: vec![],
        }
    }
}

#[derive(Clone, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Peer {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub addrs: Vec<String>, // "ip:port", most recent first
    pub lan_seen: u64,
    pub relay_seen: u64,
}

#[derive(Clone, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Item {
    pub id: String,
    pub dir: String, // "in" | "out"
    pub time: u64,
    pub peer: String,
    pub kind: String, // "text" | "file"
    pub name: String,
    pub size: u64,
    pub path: String,
    pub text: String,
    pub seen: bool,
    pub via: String,
    pub batch: String,
    pub ok: bool,
    pub error: String,
}

#[derive(Clone, Serialize, Default)]
pub struct Progress {
    pub active: bool,
    pub label: String,
    pub done: u64,
    pub total: u64,
    pub result: String,
    pub ok: bool,
}

#[derive(Clone, Serialize)]
pub struct Foreign {
    pub id: String,
    pub name: String,
    pub addr: String,
    pub seen: u64,
    pub has_key: bool,
}

pub struct Incoming {
    pub from_id: String,
    pub from_name: String,
    pub batch: String,
    pub idx: u32,
    pub count: u32,
    pub via: String,
}

pub struct Meta {
    pub batch: String,
    pub idx: u32,
    pub count: u32,
}

pub struct OutItem {
    pub kind: String,
    pub name: String,
    pub path: Option<PathBuf>,
    pub text: String,
    pub size: u64,
}

pub struct Core {
    pub app: AppHandle,
    dir: PathBuf,
    pub settings: Mutex<Settings>,
    pub peers: Mutex<HashMap<String, Peer>>,
    pub history: Mutex<Vec<Item>>,
    pub progress: Mutex<Progress>,
    pub relay_status: Mutex<String>,
    pub foreign: Mutex<HashMap<String, Foreign>>,
    pub pair_req: Mutex<Option<Value>>,
    pub popup_data: Mutex<Value>,
    pub http_port: AtomicU16,
    pub relay_gen: AtomicU64,
    pub scanning: AtomicBool,
    last_emit: AtomicU64,
}

fn read_json<T: DeserializeOwned>(p: &Path) -> Option<T> {
    serde_json::from_slice(&fs::read(p).ok()?).ok()
}

fn write_json<T: Serialize>(p: &Path, v: &T) {
    if let Ok(b) = serde_json::to_vec_pretty(v) {
        let tmp = p.with_extension("tmp");
        if fs::write(&tmp, &b).is_ok() {
            let _ = fs::rename(&tmp, p);
        }
    }
}

/// Keep sub-folders of a pushed folder, but never escape the save folder.
pub fn sanitize_rel(name: &str) -> PathBuf {
    let mut p = PathBuf::new();
    for part in name.split(['/', '\\']) {
        let clean: String = part
            .chars()
            .map(|c| if "<>:\"|?*".contains(c) || (c as u32) < 32 { '_' } else { c })
            .collect();
        let clean = clean.trim().trim_end_matches('.').to_string();
        if clean.is_empty() || clean == "." || clean == ".." {
            continue;
        }
        p.push(clean);
    }
    if p.as_os_str().is_empty() {
        p.push("file");
    }
    p
}

fn unique_path(p: &Path) -> PathBuf {
    if !p.exists() {
        return p.to_path_buf();
    }
    let stem = p.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let ext = p.extension().map(|s| format!(".{}", s.to_string_lossy())).unwrap_or_default();
    for i in 1.. {
        let c = p.with_file_name(format!("{stem} ({i}){ext}"));
        if !c.exists() {
            return c;
        }
    }
    unreachable!()
}

fn collect_files(p: &Path, prefix: &str, out: &mut Vec<OutItem>) {
    let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let rel = if prefix.is_empty() { name } else { format!("{prefix}/{name}") };
    if p.is_dir() {
        if let Ok(rd) = fs::read_dir(p) {
            for e in rd.flatten() {
                collect_files(&e.path(), &rel, out);
            }
        }
    } else if let Ok(m) = fs::metadata(p) {
        out.push(OutItem { kind: "file".into(), name: rel, path: Some(p.to_path_buf()), text: String::new(), size: m.len() });
    }
}

impl Core {
    pub fn new(app: AppHandle) -> Arc<Core> {
        let dir = app.path().app_config_dir().unwrap_or_else(|_| std::env::temp_dir().join("DataPush"));
        let _ = fs::create_dir_all(&dir);
        let mut s: Settings = read_json(&dir.join("settings.json")).unwrap_or_default();
        if s.device_id.is_empty() {
            s.device_id = random_hex(6);
        }
        if s.device_name.trim().is_empty() {
            s.device_name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".into());
        }
        if s.key.is_empty() {
            s.key = new_key();
        }
        if s.save_dir.is_empty() {
            s.save_dir = dirs::download_dir().unwrap_or_else(|| dir.clone()).join("Random Push").to_string_lossy().to_string();
        }
        let peers: Vec<Peer> = read_json(&dir.join("peers.json")).unwrap_or_default();
        let history: Vec<Item> = read_json(&dir.join("history.json")).unwrap_or_default();
        let core = Arc::new(Core {
            app,
            dir,
            settings: Mutex::new(s),
            peers: Mutex::new(peers.into_iter().map(|p| (p.id.clone(), p)).collect()),
            history: Mutex::new(history),
            progress: Mutex::new(Progress::default()),
            relay_status: Mutex::new("starting".into()),
            foreign: Mutex::new(HashMap::new()),
            pair_req: Mutex::new(None),
            popup_data: Mutex::new(Value::Null),
            http_port: AtomicU16::new(TCP_PORT),
            relay_gen: AtomicU64::new(0),
            scanning: AtomicBool::new(false),
            last_emit: AtomicU64::new(0),
        });
        core.save_settings();
        core
    }

    pub fn my_id(&self) -> String {
        self.settings.lock().unwrap().device_id.clone()
    }

    pub fn save_settings(&self) {
        let s = self.settings.lock().unwrap().clone();
        write_json(&self.dir.join("settings.json"), &s);
    }

    pub fn save_peers(&self) {
        let v: Vec<Peer> = self.peers.lock().unwrap().values().cloned().collect();
        write_json(&self.dir.join("peers.json"), &v);
    }

    pub fn save_history(&self) {
        let v = self.history.lock().unwrap().clone();
        write_json(&self.dir.join("history.json"), &v);
    }

    pub fn changed(&self) {
        let _ = self.app.emit("changed", ());
        let unseen = self.history.lock().unwrap().iter().filter(|i| i.dir == "in" && !i.seen).count();
        if let Some(t) = self.app.tray_by_id("main") {
            let tip = if unseen > 0 { format!("Random Push — {unseen} new") } else { "Random Push".into() };
            let _ = t.set_tooltip(Some(tip));
        }
    }

    pub fn set_status(&self, s: &str) {
        let mut r = self.relay_status.lock().unwrap();
        if *r != s {
            *r = s.to_string();
            drop(r);
            self.changed();
        }
    }

    pub fn state_json(&self) -> Value {
        let s = self.settings.lock().unwrap().clone();
        let now = now_ms();
        let mut peers: Vec<(u8, Value)> = self
            .peers
            .lock()
            .unwrap()
            .values()
            .map(|p| {
                let lan = now.saturating_sub(p.lan_seen) < LAN_ONLINE_MS;
                let relay = now.saturating_sub(p.relay_seen) < RELAY_ONLINE_MS;
                let rank = if lan { 0 } else if relay { 1 } else { 2 };
                (
                    rank,
                    json!({"id": p.id, "name": p.name, "platform": p.platform, "lan": lan, "relay": relay,
                           "lastSeen": p.lan_seen.max(p.relay_seen), "addr": p.addrs.first()}),
                )
            })
            .collect();
        peers.sort_by(|a, b| a.0.cmp(&b.0).then(a.1["name"].as_str().cmp(&b.1["name"].as_str())));
        let history = self.history.lock().unwrap().clone();
        let unseen = history.iter().filter(|i| i.dir == "in" && !i.seen).count();
        let foreign: Vec<Foreign> = self
            .foreign
            .lock()
            .unwrap()
            .values()
            .filter(|f| now.saturating_sub(f.seen) < LAN_ONLINE_MS * 2)
            .cloned()
            .collect();
        use tauri_plugin_autostart::ManagerExt;
        let autostart = self.app.autolaunch().is_enabled().unwrap_or(false);
        json!({
            "me": {"id": s.device_id, "name": s.device_name, "ips": local_ips(), "port": self.http_port.load(Ordering::Relaxed)},
            "settings": {"device_name": s.device_name, "key": s.key, "relay_enabled": s.relay_enabled, "relay_server": s.relay_server,
                         "relay_max_mb": s.relay_max_mb, "auto_copy_text": s.auto_copy_text, "save_dir": s.save_dir, "autostart": autostart,
                         "popup": s.popup, "sound": s.sound},
            "peers": peers.into_iter().map(|p| p.1).collect::<Vec<_>>(),
            "history": history,
            "unseen": unseen,
            "progress": *self.progress.lock().unwrap(),
            "relay": *self.relay_status.lock().unwrap(),
            "foreign": foreign,
            "pairReq": *self.pair_req.lock().unwrap(),
            "scanning": self.scanning.load(Ordering::Relaxed),
        })
    }

    pub fn peer_lan_online(&self, id: &str) -> bool {
        self.peers.lock().unwrap().get(id).map(|p| now_ms().saturating_sub(p.lan_seen) < LAN_ONLINE_MS).unwrap_or(false)
    }

    pub fn upsert_peer(&self, id: &str, name: &str, platform: &str, addr: Option<String>, lan: bool, relay_time: u64) {
        if id.is_empty() || id == self.my_id() {
            return;
        }
        let now = now_ms();
        let (mut save, mut changed) = (false, false);
        {
            let mut peers = self.peers.lock().unwrap();
            let p = peers.entry(id.to_string()).or_insert_with(|| {
                save = true;
                Peer { id: id.to_string(), ..Default::default() }
            });
            if !name.is_empty() && p.name != name {
                p.name = name.to_string();
                save = true;
            }
            if !platform.is_empty() && p.platform != platform {
                p.platform = platform.to_string();
                save = true;
            }
            if let Some(a) = addr {
                if p.addrs.first() != Some(&a) {
                    p.addrs.retain(|x| x != &a);
                    p.addrs.insert(0, a);
                    p.addrs.truncate(4);
                    save = true;
                }
            }
            if lan {
                if now.saturating_sub(p.lan_seen) >= LAN_ONLINE_MS {
                    changed = true;
                }
                p.lan_seen = now;
            }
            if relay_time > p.relay_seen {
                p.relay_seen = relay_time;
                changed = true;
            }
        }
        self.foreign.lock().unwrap().remove(id);
        if save {
            self.save_peers();
        }
        if save || changed {
            self.changed();
        }
    }

    pub fn note_foreign(&self, id: &str, name: &str, addr: String, has_key: bool) {
        if id.is_empty() || id == self.my_id() {
            return;
        }
        let new = self
            .foreign
            .lock()
            .unwrap()
            .insert(id.into(), Foreign { id: id.into(), name: name.into(), addr, seen: now_ms(), has_key })
            .is_none();
        if new {
            self.changed();
        }
    }

    pub fn forget_peer(&self, id: &str) {
        self.peers.lock().unwrap().remove(id);
        self.save_peers();
        self.changed();
    }

    fn promote_addr(&self, id: &str, addr: &str) {
        if let Some(p) = self.peers.lock().unwrap().get_mut(id) {
            p.addrs.retain(|x| x != addr);
            p.addrs.insert(0, addr.to_string());
        }
    }

    // ---------- receiving ----------

    pub fn receive_text(&self, inc: &Incoming, text: String) {
        if self.settings.lock().unwrap().auto_copy_text {
            if let Ok(mut cb) = arboard::Clipboard::new() {
                let _ = cb.set_text(text.clone());
            }
        }
        let item = Item {
            kind: "text".into(),
            name: preview(&text, 80),
            size: text.len() as u64,
            text,
            ..Default::default()
        };
        self.add_in_item(inc, item);
    }

    pub fn receive_file<R: Read>(&self, inc: &Incoming, rel_name: &str, reader: R, expected: Option<u64>) -> Result<(), String> {
        let save_dir = PathBuf::from(self.settings.lock().unwrap().save_dir.clone());
        let rel = sanitize_rel(rel_name);
        let dest = unique_path(&save_dir.join(&rel));
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("cannot create {}: {e}", parent.display()))?;
        }
        let fname = dest.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let part = dest.with_file_name(format!("{fname}.part"));
        let mut f = fs::File::create(&part).map_err(|e| e.to_string())?;
        let res = match expected {
            Some(n) => std::io::copy(&mut reader.take(n), &mut f),
            None => {
                let mut r = reader;
                std::io::copy(&mut r, &mut f)
            }
        };
        drop(f);
        let n = match res {
            Ok(n) => n,
            Err(e) => {
                let _ = fs::remove_file(&part);
                return Err(e.to_string());
            }
        };
        if let Some(want) = expected {
            if n < want {
                let _ = fs::remove_file(&part);
                return Err("transfer interrupted".into());
            }
        }
        fs::rename(&part, &dest).map_err(|e| e.to_string())?;
        let item = Item {
            kind: "file".into(),
            name: rel.to_string_lossy().replace('\\', "/"),
            size: n,
            path: dest.to_string_lossy().to_string(),
            ..Default::default()
        };
        self.add_in_item(inc, item);
        Ok(())
    }

    fn add_in_item(&self, inc: &Incoming, mut item: Item) {
        item.id = random_hex(8);
        item.dir = "in".into();
        item.time = now_ms();
        item.peer = inc.from_name.clone();
        item.via = inc.via.clone();
        item.batch = format!("{}:{}", inc.from_id, inc.batch);
        item.ok = true;
        {
            let mut h = self.history.lock().unwrap();
            h.insert(0, item);
            h.truncate(HISTORY_MAX);
        }
        self.save_history();
        if inc.idx + 1 >= inc.count {
            self.notify_batch(inc);
        }
        self.changed();
    }

    fn notify_batch(&self, inc: &Incoming) {
        let key = format!("{}:{}", inc.from_id, inc.batch);
        let items: Vec<Item> = self.history.lock().unwrap().iter().filter(|i| i.dir == "in" && i.batch == key).cloned().collect();
        if items.is_empty() {
            return;
        }
        if items.len() == 1 && items[0].kind == "text" {
            self.alert("recv", &format!("Text from {}", inc.from_name), &preview(&items[0].text, 200), Some(&items[0].text));
        } else {
            let names: Vec<String> = items.iter().rev().map(|i| i.name.clone()).collect();
            self.alert("recv", &format!("{} item(s) from {}", items.len(), inc.from_name), &preview(&names.join(", "), 200), None);
        }
    }

    /// Outside notification: own popup window (bottom-right, on top) + ping sound.
    pub fn alert(&self, kind: &str, title: &str, body: &str, text: Option<&str>) {
        let (popup, sound) = {
            let s = self.settings.lock().unwrap();
            (s.popup, s.sound)
        };
        *self.popup_data.lock().unwrap() = json!({"kind": kind, "title": title, "body": body, "text": text});
        if sound {
            crate::popup::ping();
        }
        if popup {
            crate::popup::show(&self.app);
        }
    }

    pub fn mark_seen(&self) {
        for i in self.history.lock().unwrap().iter_mut() {
            i.seen = true;
        }
        self.save_history();
        self.changed();
    }

    // ---------- pushing ----------

    pub fn set_done(&self, done: u64) {
        self.progress.lock().unwrap().done = done;
        let now = now_ms();
        if now.saturating_sub(self.last_emit.load(Ordering::Relaxed)) > 200 {
            self.last_emit.store(now, Ordering::Relaxed);
            let _ = self.app.emit("changed", ());
        }
    }

    fn set_label(&self, l: String) {
        self.progress.lock().unwrap().label = l;
        let _ = self.app.emit("changed", ());
    }

    pub fn start_push(self: &Arc<Self>, target: String, paths: Vec<String>, text: String) -> Result<(), String> {
        if self.progress.lock().unwrap().active {
            return Err("A push is already running".into());
        }
        if norm_key(&self.settings.lock().unwrap().key).is_empty() {
            return Err("Set a sync key first (Settings)".into());
        }
        let peer = self.peers.lock().unwrap().get(&target).cloned().ok_or("Choose a target device")?;
        let mut items = vec![];
        if !text.trim().is_empty() {
            items.push(OutItem { kind: "text".into(), name: String::new(), path: None, size: text.len() as u64, text });
        }
        for p in paths {
            collect_files(Path::new(&p), "", &mut items);
        }
        if items.is_empty() {
            return Err("Nothing to push — drop files or type text".into());
        }
        let total = items.iter().map(|i| i.size).sum();
        *self.progress.lock().unwrap() = Progress { active: true, label: format!("Pushing to {}…", peer.name), total, ..Default::default() };
        self.changed();
        let core = self.clone();
        std::thread::spawn(move || core.run_push(peer, items));
        Ok(())
    }

    fn run_push(self: Arc<Self>, peer: Peer, items: Vec<OutItem>) {
        let batch = random_hex(6);
        let count = items.len() as u32;
        let relay_on = self.settings.lock().unwrap().relay_enabled;
        let mut lan_alive = true;
        let (mut n_lan, mut n_relay) = (0, 0);
        let mut errors: Vec<String> = vec![];
        let mut base = 0u64;
        for (i, it) in items.iter().enumerate() {
            let meta = Meta { batch: batch.clone(), idx: i as u32, count };
            let label = if it.kind == "text" { "text".to_string() } else { it.name.clone() };
            let mut via: Option<&str> = None;
            let mut err: Option<String> = None;
            if lan_alive {
                self.set_label(format!("Sending {label} over the local network ({}/{count})", i + 1));
                let addrs = self.peers.lock().unwrap().get(&peer.id).map(|p| p.addrs.clone()).unwrap_or_default();
                let mut reached = false;
                for a in addrs.iter().take(3) {
                    self.set_done(base);
                    match crate::lan::send_item(&self, a, it, &meta, base) {
                        Ok(()) => {
                            via = Some("LAN");
                            reached = true;
                            self.promote_addr(&peer.id, a);
                            break;
                        }
                        Err(crate::lan::SendErr::Unreachable(_)) => {}
                        Err(crate::lan::SendErr::Rejected(m)) => {
                            err = Some(m);
                            reached = true;
                            break;
                        }
                    }
                }
                if !reached {
                    lan_alive = false;
                }
            }
            if via.is_none() && err.is_none() && relay_on {
                self.set_label(format!("Sending {label} via internet relay ({}/{count})", i + 1));
                self.set_done(base);
                match crate::relay::send_item(&self, &peer.id, it, &meta) {
                    Ok(()) => via = Some("internet"),
                    Err(e) => err = Some(e),
                }
            }
            if via.is_none() && err.is_none() {
                err = Some(if relay_on {
                    "device unreachable".into()
                } else {
                    "not reachable on the local network (internet relay is off)".into()
                });
            }
            base += it.size;
            self.set_done(base);
            match via {
                Some("LAN") => n_lan += 1,
                Some(_) => n_relay += 1,
                None => {}
            }
            let item = Item {
                id: random_hex(8),
                dir: "out".into(),
                time: now_ms(),
                peer: peer.name.clone(),
                kind: it.kind.clone(),
                name: if it.kind == "text" { preview(&it.text, 80) } else { it.name.clone() },
                size: it.size,
                path: it.path.as_ref().map(|p| p.to_string_lossy().to_string()).unwrap_or_default(),
                text: if it.kind == "text" { it.text.chars().take(20_000).collect() } else { String::new() },
                seen: true,
                via: via.unwrap_or("").into(),
                batch: batch.clone(),
                ok: via.is_some(),
                error: err.clone().unwrap_or_default(),
            };
            {
                let mut h = self.history.lock().unwrap();
                h.insert(0, item);
                h.truncate(HISTORY_MAX);
            }
            if let Some(e) = err {
                errors.push(format!("{label}: {e}"));
            }
        }
        self.save_history();
        let ok = errors.is_empty();
        let mut parts = vec![];
        if n_lan > 0 {
            parts.push(format!("{n_lan} over local network"));
        }
        if n_relay > 0 {
            parts.push(format!("{n_relay} via internet relay"));
        }
        let mut result = if parts.is_empty() {
            format!("Push to {} failed", peer.name)
        } else {
            format!("Pushed to {}: {}", peer.name, parts.join(", "))
        };
        if !ok {
            result.push_str(&format!(" — {} failed: {}", errors.len(), errors.join("; ")));
        }
        *self.progress.lock().unwrap() = Progress { active: false, label: String::new(), done: base, total: base, result, ok };
        self.changed();
    }

    // ---------- settings ----------

    pub fn update_settings(&self, v: &Value) {
        let mut restart_relay = false;
        {
            let mut s = self.settings.lock().unwrap();
            if let Some(x) = v["device_name"].as_str() {
                if !x.trim().is_empty() {
                    s.device_name = x.trim().to_string();
                }
            }
            if let Some(x) = v["key"].as_str() {
                if norm_key(x) != norm_key(&s.key) {
                    restart_relay = true;
                    self.foreign.lock().unwrap().clear();
                }
                s.key = x.trim().to_string();
            }
            if let Some(x) = v["relay_enabled"].as_bool() {
                restart_relay |= x != s.relay_enabled;
                s.relay_enabled = x;
            }
            if let Some(x) = v["relay_server"].as_str() {
                restart_relay |= x.trim() != s.relay_server;
                s.relay_server = x.trim().to_string();
            }
            if let Some(x) = v["relay_max_mb"].as_u64() {
                s.relay_max_mb = x.clamp(1, 2048);
            }
            if let Some(x) = v["auto_copy_text"].as_bool() {
                s.auto_copy_text = x;
            }
            if let Some(x) = v["popup"].as_bool() {
                s.popup = x;
            }
            if let Some(x) = v["sound"].as_bool() {
                s.sound = x;
            }
            if let Some(x) = v["save_dir"].as_str() {
                if !x.trim().is_empty() {
                    s.save_dir = x.trim().to_string();
                }
            }
        }
        if restart_relay {
            self.relay_gen.fetch_add(1, Ordering::Relaxed);
        }
        self.save_settings();
        self.changed();
    }
}
