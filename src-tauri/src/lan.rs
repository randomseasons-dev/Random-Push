// Local network: HTTP server (receive), UDP broadcast discovery, subnet scan, direct push.
use crate::engine::*;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Read;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

pub fn start(core: Arc<Core>) {
    start_http(core.clone());
    start_udp(core);
}

pub fn info_json(core: &Core) -> Value {
    let s = core.settings.lock().unwrap().clone();
    json!({"app": "datapush", "v": 1, "group": keys(&s.key).group, "id": s.device_id, "name": s.device_name,
           "plat": PLATFORM, "port": core.http_port.load(Ordering::Relaxed)})
}

// ---------------- HTTP server ----------------

fn start_http(core: Arc<Core>) {
    let mut server = None;
    for p in TCP_PORT..TCP_PORT + 10 {
        if let Ok(s) = tiny_http::Server::http(("0.0.0.0", p)) {
            core.http_port.store(p, Ordering::Relaxed);
            server = Some(s);
            break;
        }
    }
    let Some(server) = server else {
        core.set_status("LAN server could not start (ports 47322-47331 busy)");
        return;
    };
    thread::spawn(move || {
        for req in server.incoming_requests() {
            let c = core.clone();
            thread::spawn(move || handle(c, req));
        }
    });
}

fn respond(req: tiny_http::Request, code: u16, body: Value) {
    let h = tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
    let _ = req.respond(tiny_http::Response::from_string(body.to_string()).with_status_code(code).with_header(h));
}

fn handle(core: Arc<Core>, mut req: tiny_http::Request) {
    let path = req.url().split('?').next().unwrap_or("").to_string();
    let method = req.method().as_str().to_uppercase();
    let h: HashMap<String, String> =
        req.headers().iter().map(|x| (x.field.as_str().as_str().to_ascii_lowercase(), x.value.as_str().to_string())).collect();
    let get = |k: &str| h.get(k).cloned().unwrap_or_default();
    let dec = |k: &str| urlencoding::decode(&get(k)).map(|s| s.into_owned()).unwrap_or_default();
    let remote_ip = req.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default();

    match (method.as_str(), path.as_str()) {
        ("GET", "/info") => respond(req, 200, info_json(&core)),
        ("POST", "/pair") => {
            let mut body = String::new();
            let _ = req.as_reader().take(64 * 1024).read_to_string(&mut body);
            let v: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
            if v["key"].as_str().map(norm_key).unwrap_or_default().is_empty() {
                return respond(req, 400, json!({"ok": false, "error": "no key"}));
            }
            let mut r = v.clone();
            r["addr"] = json!(format!("{}:{}", remote_ip, v["port"].as_u64().unwrap_or(TCP_PORT as u64)));
            let name = v["name"].as_str().unwrap_or("A device").to_string();
            *core.pair_req.lock().unwrap() = Some(r);
            core.alert("pair", "Pairing request", &format!("{name} wants to pair with this PC"), None);
            core.changed();
            respond(req, 200, json!({"ok": true, "pending": true}))
        }
        ("POST", "/push") => {
            let k = keys(&core.settings.lock().unwrap().key);
            if k.token.is_empty() || get("x-dp-token") != k.token {
                return respond(req, 401, json!({"ok": false, "error": "sync key mismatch"}));
            }
            let from_id = get("x-dp-from");
            let from_name = dec("x-dp-name");
            let port: u16 = get("x-dp-port").parse().unwrap_or(TCP_PORT);
            core.upsert_peer(&from_id, &from_name, &get("x-dp-plat"), Some(format!("{remote_ip}:{port}")), true, 0);
            let inc = Incoming {
                from_id,
                from_name,
                batch: get("x-dp-batch"),
                idx: get("x-dp-index").parse().unwrap_or(0),
                count: get("x-dp-count").parse().unwrap_or(1),
                via: "LAN".into(),
            };
            let len = req.body_length().map(|n| n as u64);
            if get("x-dp-kind") == "text" {
                let mut buf = Vec::new();
                let _ = req.as_reader().take(32 * 1024 * 1024).read_to_end(&mut buf);
                core.receive_text(&inc, String::from_utf8_lossy(&buf).into_owned());
                respond(req, 200, json!({"ok": true}))
            } else {
                let name = dec("x-dp-file");
                match core.receive_file(&inc, &name, req.as_reader(), len) {
                    Ok(()) => respond(req, 200, json!({"ok": true})),
                    Err(e) => respond(req, 507, json!({"ok": false, "error": e})),
                }
            }
        }
        _ => respond(req, 404, json!({"ok": false})),
    }
}

// ---------------- direct push ----------------

pub enum SendErr {
    Unreachable(String),
    Rejected(String),
}

struct Counting<'a, R> {
    inner: R,
    core: &'a Core,
    base: u64,
    n: u64,
}

impl<R: Read> Read for Counting<'_, R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let k = self.inner.read(buf)?;
        self.n += k as u64;
        self.core.set_done(self.base + self.n);
        Ok(k)
    }
}

pub fn send_item(core: &Arc<Core>, addr: &str, it: &OutItem, meta: &Meta, base: u64) -> Result<(), SendErr> {
    let s = core.settings.lock().unwrap().clone();
    let k = keys(&s.key);
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_millis(2500))
        .timeout_read(Duration::from_secs(120))
        .timeout_write(Duration::from_secs(120))
        .build();
    let req = agent
        .post(&format!("http://{addr}/push"))
        .set("X-DP-Token", &k.token)
        .set("X-DP-From", &s.device_id)
        .set("X-DP-Name", &urlencoding::encode(&s.device_name))
        .set("X-DP-Plat", PLATFORM)
        .set("X-DP-Port", &core.http_port.load(Ordering::Relaxed).to_string())
        .set("X-DP-Kind", &it.kind)
        .set("X-DP-File", &urlencoding::encode(&it.name))
        .set("X-DP-Batch", &meta.batch)
        .set("X-DP-Index", &meta.idx.to_string())
        .set("X-DP-Count", &meta.count.to_string())
        .set("Content-Type", "application/octet-stream")
        .set("Content-Length", &it.size.to_string());
    let res = if it.kind == "text" {
        req.send(Counting { inner: it.text.as_bytes(), core, base, n: 0 })
    } else {
        let path = it.path.as_ref().ok_or_else(|| SendErr::Rejected("no file".into()))?;
        let f = std::fs::File::open(path).map_err(|e| SendErr::Rejected(format!("cannot read file: {e}")))?;
        req.send(Counting { inner: f, core, base, n: 0 })
    };
    match res {
        Ok(_) => Ok(()),
        Err(ureq::Error::Status(401, _)) => Err(SendErr::Rejected("the sync key on the other device is different".into())),
        Err(ureq::Error::Status(code, r)) => {
            let body: Value = r.into_json().unwrap_or(Value::Null);
            Err(SendErr::Rejected(format!("rejected ({code}): {}", body["error"].as_str().unwrap_or(""))))
        }
        Err(e) => Err(SendErr::Unreachable(e.to_string())),
    }
}

pub fn send_pair(core: &Arc<Core>, addr: &str) -> Result<(), String> {
    let s = core.settings.lock().unwrap().clone();
    let body = json!({"id": s.device_id, "name": s.device_name, "plat": PLATFORM, "key": s.key,
                      "port": core.http_port.load(Ordering::Relaxed)});
    ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(5))
        .build()
        .post(&format!("http://{addr}/pair"))
        .send_json(body)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

// ---------------- discovery ----------------

fn bind_udp() -> std::io::Result<UdpSocket> {
    use socket2::{Domain, Protocol, Socket, Type};
    let s = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    s.set_reuse_address(true)?;
    s.set_broadcast(true)?;
    s.bind(&SocketAddr::from(([0, 0, 0, 0], UDP_PORT)).into())?;
    Ok(s.into())
}

fn broadcast_targets() -> Vec<SocketAddr> {
    let mut v: Vec<SocketAddr> = vec![SocketAddr::from(([255, 255, 255, 255], UDP_PORT))];
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            if let if_addrs::IfAddr::V4(a) = &i.addr {
                if a.ip.is_loopback() || a.ip.is_link_local() {
                    continue;
                }
                let b = a.broadcast.unwrap_or_else(|| Ipv4Addr::from(u32::from(a.ip) | !u32::from(a.netmask)));
                let sa = SocketAddr::from((b, UDP_PORT));
                if !v.contains(&sa) {
                    v.push(sa);
                }
            }
        }
    }
    v
}

fn packet(core: &Core, reply: bool) -> Vec<u8> {
    let mut v = info_json(core);
    v["reply"] = json!(reply);
    v.to_string().into_bytes()
}

fn start_udp(core: Arc<Core>) {
    let sock = match bind_udp().or_else(|_| {
        let s = UdpSocket::bind("0.0.0.0:0")?;
        s.set_broadcast(true)?;
        Ok::<_, std::io::Error>(s)
    }) {
        Ok(s) => Arc::new(s),
        Err(_) => return,
    };
    let (c, s2) = (core.clone(), sock.clone());
    thread::spawn(move || loop {
        let p = packet(&c, false);
        for t in broadcast_targets() {
            let _ = s2.send_to(&p, t);
        }
        thread::sleep(Duration::from_secs(4));
    });
    thread::spawn(move || {
        let mut buf = vec![0u8; 4096];
        loop {
            let Ok((n, src)) = sock.recv_from(&mut buf) else {
                thread::sleep(Duration::from_millis(200));
                continue;
            };
            let Ok(v) = serde_json::from_slice::<Value>(&buf[..n]) else { continue };
            if v["app"] != "datapush" {
                continue;
            }
            let id = v["id"].as_str().unwrap_or("");
            if id.is_empty() || id == core.my_id() {
                continue;
            }
            let name = v["name"].as_str().unwrap_or("");
            let group = v["group"].as_str().unwrap_or("");
            let addr = format!("{}:{}", src.ip(), v["port"].as_u64().unwrap_or(TCP_PORT as u64));
            let mine = keys(&core.settings.lock().unwrap().key).group;
            if !mine.is_empty() && group == mine {
                let was = core.peer_lan_online(id);
                core.upsert_peer(id, name, v["plat"].as_str().unwrap_or(""), Some(addr), true, 0);
                if !was && v["reply"] != true {
                    let _ = sock.send_to(&packet(&core, true), src);
                }
            } else {
                core.note_foreign(id, name, addr, !group.is_empty());
            }
        }
    });
}

/// Probe one address ("ip" or "ip:port"). Returns the device name when it shares our key.
pub fn probe(core: &Arc<Core>, addr: &str, timeout_ms: u64) -> Result<String, String> {
    let addr = if addr.contains(':') { addr.to_string() } else { format!("{addr}:{TCP_PORT}") };
    let v: Value = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_millis(timeout_ms))
        .timeout(Duration::from_millis(timeout_ms + 2000))
        .build()
        .get(&format!("http://{addr}/info"))
        .call()
        .map_err(|e| format!("no Random Push at {addr} ({e})"))?
        .into_json()
        .map_err(|e| e.to_string())?;
    if v["app"] != "datapush" {
        return Err(format!("{addr} is not Random Push"));
    }
    let id = v["id"].as_str().unwrap_or("");
    let name = v["name"].as_str().unwrap_or("").to_string();
    let host = addr.split(':').next().unwrap_or("");
    let addr = format!("{host}:{}", v["port"].as_u64().unwrap_or(TCP_PORT as u64));
    let mine = keys(&core.settings.lock().unwrap().key).group;
    if !mine.is_empty() && v["group"].as_str() == Some(mine.as_str()) {
        core.upsert_peer(id, &name, v["plat"].as_str().unwrap_or(""), Some(addr), true, 0);
        Ok(name)
    } else {
        core.note_foreign(id, &name, addr, !v["group"].as_str().unwrap_or("").is_empty());
        Err(format!("Found \"{name}\" but it is not paired (different sync key) — use Pair"))
    }
}

/// Probe every address of the local /24 networks for a Random Push server.
pub fn scan(core: Arc<Core>) {
    if core.scanning.swap(true, Ordering::Relaxed) {
        return;
    }
    core.changed();
    thread::spawn(move || {
        let mut hosts: Vec<String> = vec![];
        let mine = local_ips();
        for ip in &mine {
            let Ok(a) = ip.parse::<Ipv4Addr>() else { continue };
            let o = a.octets();
            for h in 1..255u8 {
                let s = format!("{}.{}.{}.{}", o[0], o[1], o[2], h);
                if !mine.contains(&s) && !hosts.contains(&s) {
                    hosts.push(s);
                }
            }
        }
        let queue = Arc::new(Mutex::new(hosts));
        let workers: Vec<_> = (0..48)
            .map(|_| {
                let (q, c) = (queue.clone(), core.clone());
                thread::spawn(move || loop {
                    let next = q.lock().unwrap().pop();
                    let Some(h) = next else { break };
                    let _ = probe(&c, &h, 600);
                })
            })
            .collect();
        for w in workers {
            let _ = w.join();
        }
        core.scanning.store(false, Ordering::Relaxed);
        core.changed();
    });
}
