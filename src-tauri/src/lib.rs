mod engine;
mod lan;
mod popup;
mod relay;

use crate::engine::Core;
use serde_json::{json, Value};
use std::sync::Arc;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

type CoreS<'a> = State<'a, Arc<Core>>;

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

fn item_path(core: &Core, id: &str) -> Option<String> {
    core.history.lock().unwrap().iter().find(|i| i.id == id).map(|i| i.path.clone()).filter(|p| !p.is_empty())
}

#[tauri::command]
fn get_state(core: CoreS) -> Value {
    core.state_json()
}

#[tauri::command]
fn file_info(paths: Vec<String>) -> Vec<Value> {
    paths
        .into_iter()
        .filter_map(|p| {
            let m = std::fs::metadata(&p).ok()?;
            let name = std::path::Path::new(&p).file_name()?.to_string_lossy().to_string();
            let size = if m.is_dir() { dir_size(std::path::Path::new(&p)) } else { m.len() };
            Some(json!({"path": p, "name": name, "size": size, "isDir": m.is_dir()}))
        })
        .collect()
}

fn dir_size(p: &std::path::Path) -> u64 {
    std::fs::read_dir(p)
        .map(|rd| {
            rd.flatten()
                .map(|e| {
                    let path = e.path();
                    if path.is_dir() { dir_size(&path) } else { e.metadata().map(|m| m.len()).unwrap_or(0) }
                })
                .sum()
        })
        .unwrap_or(0)
}

#[tauri::command]
async fn pick_files(app: AppHandle) -> Vec<String> {
    app.dialog()
        .file()
        .blocking_pick_files()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|f| f.into_path().ok().map(|p| p.to_string_lossy().to_string()))
        .collect()
}

#[tauri::command]
async fn pick_folder(app: AppHandle) -> Option<String> {
    app.dialog().file().blocking_pick_folder().and_then(|f| f.into_path().ok()).map(|p| p.to_string_lossy().to_string())
}

#[tauri::command]
fn push(core: CoreS, target: String, paths: Vec<String>, text: String) -> Result<(), String> {
    core.start_push(target, paths, text)
}

#[tauri::command]
fn mark_seen(core: CoreS) {
    core.mark_seen();
}

#[tauri::command]
fn open_item(app: AppHandle, core: CoreS, id: String) -> Result<(), String> {
    let p = item_path(&core, &id).ok_or("file not found")?;
    app.opener().open_path(p, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn reveal_item(app: AppHandle, core: CoreS, id: String) -> Result<(), String> {
    let p = item_path(&core, &id).ok_or("file not found")?;
    app.opener().reveal_item_in_dir(p).map_err(|e| e.to_string())
}

#[tauri::command]
fn open_folder(app: AppHandle, core: CoreS) -> Result<(), String> {
    let dir = core.settings.lock().unwrap().save_dir.clone();
    let _ = std::fs::create_dir_all(&dir);
    app.opener().open_path(dir, None::<&str>).map_err(|e| e.to_string())
}

#[tauri::command]
fn copy_text(text: String) -> Result<(), String> {
    arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).map_err(|e| e.to_string())
}

#[tauri::command]
fn delete_item(core: CoreS, id: String) {
    core.history.lock().unwrap().retain(|i| i.id != id);
    core.save_history();
    core.changed();
}

#[tauri::command]
fn clear_history(core: CoreS) {
    core.history.lock().unwrap().clear();
    core.save_history();
    core.changed();
}

#[tauri::command]
fn save_settings(app: AppHandle, core: CoreS, s: Value) {
    if let Some(on) = s["autostart"].as_bool() {
        let al = app.autolaunch();
        let _ = if on { al.enable() } else { al.disable() };
    }
    core.update_settings(&s);
}

#[tauri::command]
fn new_key() -> String {
    engine::new_key()
}

#[tauri::command]
fn scan(core: CoreS) {
    lan::scan(core.inner().clone());
}

#[tauri::command]
async fn add_ip(core: CoreS<'_>, ip: String) -> Result<String, String> {
    let c = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || lan::probe(&c, ip.trim(), 2500)).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn pair(core: CoreS<'_>, addr: String) -> Result<(), String> {
    let c = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || lan::send_pair(&c, &addr)).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn answer_pair(core: CoreS, accept: bool) {
    let req = core.pair_req.lock().unwrap().take();
    if let (true, Some(r)) = (accept, req) {
        core.update_settings(&json!({"key": r["key"]}));
        if let Some(id) = r["id"].as_str() {
            core.upsert_peer(id, r["name"].as_str().unwrap_or(""), r["plat"].as_str().unwrap_or(""), r["addr"].as_str().map(String::from), true, 0);
        }
    }
    core.changed();
}

#[tauri::command]
fn forget_peer(core: CoreS, id: String) {
    core.forget_peer(&id);
}

#[tauri::command]
fn popup_data(core: CoreS) -> Value {
    core.popup_data.lock().unwrap().clone()
}

/// "Open" on the popup: bring up the main window on the Received list (or the pairing dialog).
#[tauri::command]
fn popup_open(app: AppHandle, core: CoreS) {
    use tauri::Emitter;
    show_main(&app);
    if core.popup_data.lock().unwrap()["kind"] == "recv" {
        let _ = app.emit_to("main", "open-received", ());
    }
    popup::close(&app);
}

#[tauri::command]
fn popup_close(app: AppHandle) {
    popup::close(&app);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--minimized"])))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let core = Core::new(app.handle().clone());
            app.manage(core.clone());
            lan::start(core.clone());
            relay::start(core.clone());

            // auto-run at sign-in: switched on once, the user can turn it off in Settings
            if !core.settings.lock().unwrap().autostart_init {
                let _ = app.autolaunch().enable();
                core.settings.lock().unwrap().autostart_init = true;
                core.save_settings();
            }

            let open = MenuItem::with_id(app, "open", "Open Random Push", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            TrayIconBuilder::with_id("main")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Random Push")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, e| match e.id.as_ref() {
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, e| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = e {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;
            core.changed();

            if !std::env::args().any(|a| a == "--minimized") {
                show_main(app.handle());
            }
            Ok(())
        })
        .on_window_event(|w, e| {
            // closing the window keeps Random Push running in the tray
            if let (WindowEvent::CloseRequested { api, .. }, "main") = (e, w.label()) {
                api.prevent_close();
                let _ = w.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_state, file_info, pick_files, pick_folder, push, mark_seen, open_item, reveal_item, open_folder,
            copy_text, delete_item, clear_history, save_settings, new_key, scan, add_ip, pair, answer_pair, forget_peer,
            popup_data, popup_open, popup_close
        ])
        .run(tauri::generate_context!())
        .expect("error while running Random Push");
}
