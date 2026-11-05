# Random Push

Push files and text between your Android phone and your Windows PC, the way Pushbullet does. Drop something on one device, pick the other device and press **Push**. The other device shows a notification and a **Received** button.

- **Windows**: Tauri 2 app (`src-tauri/` + `ui/`). It runs in the system tray, starts with Windows (minimized) and keeps running when you close the window.
- **Android**: native Kotlin app (`android/`). It runs as a foreground service (the Android version of a tray icon), starts after a reboot, and appears in the **Share** menu of every app.

## How the devices find and reach each other

Both apps try these in order:

| # | Method | When it works |
|---|--------|---------------|
| 1 | **UDP broadcast discovery** (port 47321), every 4 s, to 255.255.255.255 and to each network's own broadcast address | Same Wi-Fi/LAN, or the PC is connected to the phone's hotspot |
| 2 | **Network scan**: the *Scan* button probes every address in the local /24 for port 47322 | The router blocks broadcasts |
| 3 | **Add by IP** | Anything that can route to the other device (VPN, Tailscale, other subnet) |
| 4 | **Direct HTTP transfer** (TCP 47322) to the device's last known addresses | Used first for every push. No size limit, full LAN speed |
| 5 | **Internet relay**, an [ntfy](https://ntfy.sh) server (default `https://ntfy.sh`, you can self-host). Text and files are AES-256-GCM encrypted with a key made from the sync key, and the topic name is a hash of it | Different networks, a blocked LAN, or a phone that is asleep. ntfy.sh keeps messages for 12 h and attachments for about 3 h. Files are limited to 15 MB |

A device counts as "online · same network" while its broadcasts keep arriving. It counts as "online · via internet" if it sent a relay heartbeat in the last 15 minutes. You can still push to an offline device: the app tries its last address and then the relay.

## Pairing

Every device in your set uses the same **sync key**. The PC generates one on first run (Settings → Sync key). To pair a phone, do one of these:

- Type the key on the phone, or
- Wait until the phone appears on the PC as "Found … — not paired" and press **Pair**. Then accept the request on the phone. Pairing works the same way in the other direction.

The key does three jobs: LAN pushes are rejected without it, it encrypts everything that goes over the relay, and it keeps other people's Random Push devices out of your list.

## Where received data goes

- PC: `Downloads\Random Push\` (you can change this in Settings). Folders keep their structure.
- Android: `Download/Random Push/`.
- Received text is copied to the clipboard automatically (you can turn this off). It is also listed under **Received**, with a Copy button.

## Build

Windows (the PC has little RAM, so build with one job):

```
npm install
CARGO_BUILD_JOBS=1 npx tauri build
```

Output: `src-tauri/target/release/bundle/nsis/Random Push_1.1.0_x64-setup.exe`. The installer asks for admin rights so it can add the Windows Firewall rule "Random Push" (inbound, for this exe only). The uninstaller removes that rule.

Android (toolchain in `D:\AndroidDev`):

```
set JAVA_HOME=D:\AndroidDev\jdk17
set ANDROID_HOME=D:\AndroidDev\sdk
set GRADLE_USER_HOME=D:\AndroidDev\gradle
cd android && gradlew assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`. It is signed with `D:\AndroidDev\keys\data-push.jks`, using `android/keystore.properties`. That file is git-ignored, and a copy is kept in `D:\AndroidDev\keys\data-push-keystore.properties`.

## Phone tips

- Tap **Allow background running** on the main screen once. Without it, Android may stop receiving while the screen is off.
- If the PC does not see the phone, press **Scan** on either device. Also check that the Wi-Fi does not have "client/AP isolation" turned on (common on guest networks).
