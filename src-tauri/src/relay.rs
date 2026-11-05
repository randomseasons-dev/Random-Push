// Internet relay through an ntfy server (default https://ntfy.sh). Everything is AES-GCM encrypted
// with a key derived from the sync key; the topic name is also derived from it.
use crate::engine::*;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

fn agent(read_secs: u64) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(read_secs))
        .timeout_write(Duration::from_secs(300))
        .build()
}

fn server(s: &Settings) -> String {
    let mut v = s.relay_server.trim().trim_end_matches('/').to_string();
    if v.is_empty() {
        v = "https://ntfy.sh".into();
    }
    if !v.starts_with("http://") && !v.starts_with("https://") {
        v = format!("https://{v}");
    }
    v
}

fn host(s: &Settings) -> String {
    server(s).split("://").nth(1).unwrap_or("").to_string()
}

pub fn start(core: Arc<Core>) {
    let c = core.clone();
    thread::spawn(move || subscribe_loop(c));
    // presence: "hello" on start, on key change and every 10 minutes
    thread::spawn(move || {
        let (mut last, mut last_gen) = (0u64, u64::MAX);
        loop {
            thread::sleep(Duration::from_secs(5));
            let gen = core.relay_gen.load(Ordering::Relaxed);
            if gen != last_gen || now_ms().saturating_sub(last) >= 600_000 {
                let s = core.settings.lock().unwrap().clone();
                let env = json!({"t": "dp1", "k": "hello", "id": random_hex(8), "from": s.device_id, "name": s.device_name, "plat": PLATFORM});
                if publish(&core, &env, None).is_ok() || !s.relay_enabled {
                    last = now_ms();
                    last_gen = gen;
                }
            }
        }
    });
}

fn subscribe_loop(core: Arc<Core>) {
    let mut backoff = 5;
    loop {
        let gen = core.relay_gen.load(Ordering::Relaxed);
        let s = core.settings.lock().unwrap().clone();
        let k = keys(&s.key);
        if !s.relay_enabled || k.topic.is_empty() {
            core.set_status(if s.relay_enabled { "off (no sync key)" } else { "off" });
            thread::sleep(Duration::from_secs(3));
            continue;
        }
        let since = if s.relay_last_time == 0 { "all".to_string() } else { s.relay_last_time.to_string() };
        let url = format!("{}/{}/json?since={}", server(&s), k.topic, since);
        match agent(100).get(&url).call() {
            Ok(resp) => {
                core.set_status(&format!("connected ({})", host(&s)));
                backoff = 5;
                for line in BufReader::new(resp.into_reader()).lines() {
                    let Ok(line) = line else { break };
                    handle_line(&core, &k, &line);
                    if core.relay_gen.load(Ordering::Relaxed) != gen {
                        break;
                    }
                }
                core.set_status("reconnecting…");
            }
            Err(e) => {
                core.set_status(&format!("offline ({})", short_err(&e.to_string())));
                backoff = (backoff * 2).min(60);
            }
        }
        // wait, but wake early when settings change
        for _ in 0..backoff {
            if core.relay_gen.load(Ordering::Relaxed) != gen {
                break;
            }
            thread::sleep(Duration::from_secs(1));
        }
    }
}

fn short_err(e: &str) -> String {
    e.chars().take(60).collect()
}

fn download(url: &str) -> Option<Vec<u8>> {
    let r = agent(120).get(url).call().ok()?;
    let mut v = Vec::new();
    r.into_reader().take(2100 * 1024 * 1024).read_to_end(&mut v).ok()?;
    Some(v)
}

fn handle_line(core: &Arc<Core>, k: &Keys, line: &str) {
    let Ok(v) = serde_json::from_str::<Value>(line) else { return };
    if v["event"] != "message" {
        return;
    }
    let id = v["id"].as_str().unwrap_or("").to_string();
    let time = v["time"].as_u64().unwrap_or(0);
    {
        let mut s = core.settings.lock().unwrap();
        if s.relay_recent_ids.contains(&id) {
            return;
        }
        s.relay_recent_ids.push(id);
        if s.relay_recent_ids.len() > 200 {
            s.relay_recent_ids.remove(0);
        }
        if time > s.relay_last_time {
            s.relay_last_time = time;
        }
    }
    core.save_settings();
    let Some(env) = b64d(v["message"].as_str().unwrap_or(""))
        .and_then(|b| decrypt(&k.enc, &b))
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
    else {
        return;
    };
    if env["t"] != "dp1" {
        return;
    }
    let me = core.my_id();
    let from = env["from"].as_str().unwrap_or("").to_string();
    if from.is_empty() || from == me {
        return;
    }
    let name = env["name"].as_str().unwrap_or("").to_string();
    core.upsert_peer(&from, &name, env["plat"].as_str().unwrap_or(""), None, false, time * 1000);
    if env["k"] != "item" || env["to"].as_str() != Some(me.as_str()) {
        return;
    }
    let inc = Incoming {
        from_id: from,
        from_name: name,
        batch: env["batch"].as_str().unwrap_or("").into(),
        idx: env["idx"].as_u64().unwrap_or(0) as u32,
        count: env["count"].as_u64().unwrap_or(1) as u32,
        via: "internet".into(),
    };
    let attachment = || {
        v["attachment"]["url"].as_str().and_then(download).and_then(|b| decrypt(&k.enc, &b))
    };
    if env["kind"] == "text" {
        let text = match env["text"].as_str() {
            Some(t) => t.to_string(),
            None => match attachment() {
                Some(b) => String::from_utf8_lossy(&b).into_owned(),
                None => return,
            },
        };
        core.receive_text(&inc, text);
    } else if let Some(data) = attachment() {
        let len = data.len() as u64;
        let _ = core.receive_file(&inc, env["file"].as_str().unwrap_or("file"), &data[..], Some(len));
    }
}

pub fn publish(core: &Core, env: &Value, payload: Option<&[u8]>) -> Result<(), String> {
    let s = core.settings.lock().unwrap().clone();
    if !s.relay_enabled {
        return Err("internet relay is off".into());
    }
    let k = keys(&s.key);
    if k.topic.is_empty() {
        return Err("no sync key".into());
    }
    let msg = b64e(&encrypt(&k.enc, env.to_string().as_bytes()));
    let url = format!("{}/{}", server(&s), k.topic);
    let a = agent(300);
    let r = match payload {
        None => a.post(&url).send_string(&msg),
        Some(p) => a.put(&url).set("X-Message", &msg).set("X-Filename", "dp.bin").send_bytes(&encrypt(&k.enc, p)),
    };
    r.map(|_| ()).map_err(|e| match e {
        ureq::Error::Status(c, r) => format!("relay refused ({c}) {}", short_err(&r.into_string().unwrap_or_default())),
        e => format!("relay unreachable ({})", short_err(&e.to_string())),
    })
}

pub fn send_item(core: &Arc<Core>, to: &str, it: &OutItem, meta: &Meta) -> Result<(), String> {
    let s = core.settings.lock().unwrap().clone();
    let max = s.relay_max_mb * 1024 * 1024;
    let mut env = json!({"t": "dp1", "k": "item", "id": random_hex(8), "from": s.device_id, "name": s.device_name,
        "plat": PLATFORM, "to": to, "kind": it.kind, "file": it.name, "size": it.size,
        "batch": meta.batch, "idx": meta.idx, "count": meta.count});
    if it.kind == "text" {
        if it.text.len() <= 2000 {
            env["text"] = json!(it.text);
            return publish(core, &env, None);
        }
        return publish(core, &env, Some(it.text.as_bytes()));
    }
    if it.size + 28 > max {
        return Err(format!(
            "too big for the internet relay (limit {} MB) — put both devices on the same network",
            s.relay_max_mb
        ));
    }
    let path = it.path.as_ref().ok_or("no file")?;
    let data = std::fs::read(path).map_err(|e| format!("cannot read file: {e}"))?;
    publish(core, &env, Some(&data))
}
