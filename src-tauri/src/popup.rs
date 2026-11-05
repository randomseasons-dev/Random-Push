// Outside notification for Windows: a small always-on-top window in the bottom-right corner
// (does not steal focus) and a ping sound. Windows toasts need a registered app id, this always works.
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};

static PING: &[u8] = include_bytes!("../ping.wav");

#[cfg(windows)]
#[link(name = "winmm")]
extern "system" {
    fn PlaySoundW(psz: *const u16, hmod: isize, flags: u32) -> i32;
}

pub fn ping() {
    // SND_ASYNC | SND_NODEFAULT | SND_MEMORY — PING is static, so it outlives the async playback
    #[cfg(windows)]
    unsafe {
        PlaySoundW(PING.as_ptr() as *const u16, 0, 0x0001 | 0x0002 | 0x0004);
    }
}

pub fn close(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("popup") {
        let _ = w.destroy();
    }
}

pub fn show(app: &AppHandle) {
    close(app);
    const W: f64 = 380.0;
    const H: f64 = 140.0;
    let mut b = WebviewWindowBuilder::new(app, "popup", WebviewUrl::App("popup.html".into()))
        .title("Random Push")
        .inner_size(W, H)
        .decorations(false)
        .resizable(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .focused(false)
        .shadow(true);
    if let Ok(Some(m)) = app.primary_monitor() {
        let sf = m.scale_factor();
        let wa = m.work_area();
        let x = (wa.position.x as f64 + wa.size.width as f64) / sf - W - 16.0;
        let y = (wa.position.y as f64 + wa.size.height as f64) / sf - H - 16.0;
        b = b.position(x, y);
    }
    let _ = b.build();
}
