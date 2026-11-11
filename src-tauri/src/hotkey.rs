//! Ctrl+Shift+P quick push (Windows): when no app is in front (taskbar, desktop, tray)
//! or Random Push itself is, push the clipboard (text, or a picture as JPG) to the
//! device used for the last push. Inside other apps the keys are left alone, so their
//! own Ctrl+Shift+P keeps working.

use crate::engine::Core;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, OnceLock};

static FIRE: OnceLock<Sender<()>> = OnceLock::new();
static P_DOWN: AtomicBool = AtomicBool::new(false);

const WH_KEYBOARD_LL: i32 = 13;
const WM_KEYDOWN: usize = 0x0100;
const WM_KEYUP: usize = 0x0101;
const WM_SYSKEYDOWN: usize = 0x0104;
const WM_SYSKEYUP: usize = 0x0105;
const VK_SHIFT: i32 = 0x10;
const VK_CONTROL: i32 = 0x11;
const VK_MENU: i32 = 0x12;
const VK_LWIN: i32 = 0x5B;
const VK_RWIN: i32 = 0x5C;
const VK_P: u32 = 0x50;

#[repr(C)]
struct KbdLlHook {
    vk_code: u32,
    scan_code: u32,
    flags: u32,
    time: u32,
    extra: usize,
}

#[repr(C)]
struct Msg {
    hwnd: isize,
    message: u32,
    wparam: usize,
    lparam: isize,
    time: u32,
    pt_x: i32,
    pt_y: i32,
    private: u32,
}

#[repr(C)]
#[derive(Default)]
struct SystemTime {
    year: u16,
    month: u16,
    day_of_week: u16,
    day: u16,
    hour: u16,
    minute: u16,
    second: u16,
    ms: u16,
}

#[link(name = "user32")]
extern "system" {
    fn SetWindowsHookExW(id: i32, proc_: extern "system" fn(i32, usize, isize) -> isize, hmod: isize, thread: u32) -> isize;
    fn CallNextHookEx(hhk: isize, code: i32, wparam: usize, lparam: isize) -> isize;
    fn GetMessageW(msg: *mut Msg, hwnd: isize, min: u32, max: u32) -> i32;
    fn GetAsyncKeyState(vk: i32) -> i16;
    fn GetForegroundWindow() -> isize;
    fn GetClassNameW(hwnd: isize, buf: *mut u16, max: i32) -> i32;
    fn GetWindowThreadProcessId(hwnd: isize, pid: *mut u32) -> u32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetModuleHandleW(name: *const u16) -> isize;
    fn GetCurrentProcessId() -> u32;
    fn GetLocalTime(t: *mut SystemTime);
}

fn held(vk: i32) -> bool {
    unsafe { (GetAsyncKeyState(vk) as u16 & 0x8000) != 0 }
}

/// True when the taskbar / desktop / tray (or our own window) is in front.
fn outside_apps() -> bool {
    unsafe {
        let w = GetForegroundWindow();
        if w == 0 {
            return true;
        }
        let mut pid = 0u32;
        GetWindowThreadProcessId(w, &mut pid);
        if pid == GetCurrentProcessId() {
            return true;
        }
        let mut buf = [0u16; 128];
        let n = GetClassNameW(w, buf.as_mut_ptr(), buf.len() as i32);
        let class = String::from_utf16_lossy(&buf[..n.max(0) as usize]);
        matches!(
            class.as_str(),
            "Shell_TrayWnd"
                | "Shell_SecondaryTrayWnd"
                | "Progman"
                | "WorkerW"
                | "NotifyIconOverflowWindow"
                | "TopLevelWindowForOverflowXamlIsland"
                | "Windows.UI.Core.CoreWindow"
                | "XamlExplorerHostIslandWindow"
        )
    }
}

extern "system" fn hook(code: i32, wparam: usize, lparam: isize) -> isize {
    if code >= 0 && lparam != 0 {
        let k = unsafe { &*(lparam as *const KbdLlHook) };
        if k.vk_code == VK_P {
            match wparam {
                WM_KEYDOWN | WM_SYSKEYDOWN => {
                    if held(VK_CONTROL) && held(VK_SHIFT) && !held(VK_MENU) && !held(VK_LWIN) && !held(VK_RWIN) && outside_apps() {
                        // fire once per press (ignore key repeat), and swallow the key
                        if !P_DOWN.swap(true, Ordering::SeqCst) {
                            if let Some(tx) = FIRE.get() {
                                let _ = tx.send(());
                            }
                        }
                        return 1;
                    }
                }
                WM_KEYUP | WM_SYSKEYUP => {
                    if P_DOWN.swap(false, Ordering::SeqCst) {
                        return 1;
                    }
                }
                _ => {}
            }
        }
    }
    unsafe { CallNextHookEx(0, code, wparam, lparam) }
}

pub fn start(core: Arc<Core>) {
    let (tx, rx) = channel::<()>();
    if FIRE.set(tx).is_err() {
        return;
    }
    // worker: does the actual clipboard read + push, off the hook thread
    std::thread::spawn(move || {
        for _ in rx {
            quick_push(&core);
        }
    });
    // hook thread: a low-level keyboard hook needs a message loop on its own thread
    std::thread::spawn(|| unsafe {
        let h = SetWindowsHookExW(WH_KEYBOARD_LL, hook, GetModuleHandleW(std::ptr::null()), 0);
        if h == 0 {
            return;
        }
        let mut msg: Msg = std::mem::zeroed();
        while GetMessageW(&mut msg, 0, 0, 0) > 0 {}
    });
}

fn stamp() -> String {
    let mut t = SystemTime::default();
    unsafe { GetLocalTime(&mut t) };
    format!("Screenshot {:04}-{:02}-{:02} {:02}-{:02}-{:02}", t.year, t.month, t.day, t.hour, t.minute, t.second)
}

fn quick_push(core: &Arc<Core>) {
    let fail = |msg: &str| core.alert("info", "Quick push (Ctrl+Shift+P)", msg, None);
    let Some(target) = core.quick_target() else {
        return fail("No device yet. Pair a device in Random Push first.");
    };
    let name = core.peers.lock().unwrap().get(&target).map(|p| p.name.clone());
    let Some(name) = name else {
        return fail("The last device is no longer in your list. Push once from the app to pick a new one.");
    };
    let mut cb = match arboard::Clipboard::new() {
        Ok(c) => c,
        Err(e) => return fail(&format!("Cannot open the clipboard ({e})")),
    };
    let (paths, text, what) = if let Ok(t) = cb.get_text().map(|t| t.to_string()).and_then(|t| if t.trim().is_empty() { Err(arboard::Error::ContentNotAvailable) } else { Ok(t) }) {
        (vec![], t, "text".to_string())
    } else if let Ok(img) = cb.get_image() {
        let jpg = match encode_jpeg(img.width as u32, img.height as u32, &img.bytes) {
            Ok(b) => b,
            Err(e) => return fail(&format!("Cannot convert the picture ({e})")),
        };
        match crate::save_pasted_file(&core.app, &jpg, &stamp()) {
            Ok(p) => (vec![p], String::new(), "picture".to_string()),
            Err(e) => return fail(&format!("Cannot save the picture ({e})")),
        }
    } else {
        return fail("The clipboard is empty (no text or picture).");
    };
    drop(cb);
    match core.start_push(target, paths, text) {
        Ok(()) => {
            let (popup, sound) = { let s = core.settings.lock().unwrap(); (s.popup, s.sound) };
            if sound { crate::popup::ping(); }
            if popup {
                *core.popup_data.lock().unwrap() = serde_json::json!({"kind": "info", "title": "Quick push", "body": format!("Pushing the clipboard {what} to {name}…"), "text": null});
                crate::popup::show(&core.app);
            }
        }
        Err(e) => fail(&e),
    }
}

/// RGBA clipboard pixels -> JPG (on white, since JPG has no transparency).
fn encode_jpeg(w: u32, h: u32, rgba: &[u8]) -> Result<Vec<u8>, String> {
    let mut rgb = Vec::with_capacity((w * h * 3) as usize);
    for px in rgba.chunks_exact(4) {
        let a = px[3] as u32;
        for c in 0..3 {
            rgb.push(((px[c] as u32 * a + 255 * (255 - a)) / 255) as u8);
        }
    }
    let mut out = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 92)
        .encode(&rgb, w, h, image::ExtendedColorType::Rgb8)
        .map_err(|e| e.to_string())?;
    Ok(out)
}
