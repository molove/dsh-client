<p align="center">
  <img src="docs/assets/icon.png" width="128" height="128" alt="dsh-client icon" />
</p>

# dsh-client

A dedicated native Android companion client for [`dsh-web`](https://github.com/molove/dsh-web) (DeepSeek Harness).

`dsh-client` brings the desktop AI harness experience to Android devices with a seamless embedded SSH tunnel, automated ephemeral token discovery, background keepalive, and tailored responsive UI optimizations for mobile form factors.

---

## Features

- **Embedded SSH Tunneling (`mwiede/jsch`)**:
  - Direct, self-contained SSH tunnel running inside an Android foreground service.
  - Dynamically binds a local loopback port (`127.0.0.1:3080`) to the remote web interface.
  - Supports modern OpenSSH Ed25519 keypairs and standard RSA keys.
  - Encrypted storage for private keys and credentials via Android Jetpack `EncryptedSharedPreferences` backed by Android Keystore.
- **Automated Ephemeral Token & Port Scraping**:
  - Queries `systemd` journal logs (`journalctl -u dsh-web.service`) over an SSH execution channel on connection.
  - Automatically extracts the dynamic authentication token and port without requiring manual copy-pasting or deep link handling on each launch.
- **Resilient Connection & Background Keepalive**:
  - Managed by an Android Foreground Service (`connectedDevice` type) with persistent status notifications.
  - Acquires partial `WakeLock` to maintain tunnel connectivity during long-running streaming completions while the screen is off or backgrounded.
  - Automatic exponential backoff reconnection (2s, 4s, 8s, up to 15s) with real-time UI status indicators and cancel controls.
- **Trust-On-First-Use (TOFU) Host Key Pinning**:
  - Automatically captures and pins remote server host key fingerprints on initial connection.
  - Verifies fingerprints on subsequent connections to protect against Man-In-The-Middle (MITM) attacks.
  - Full in-app control to inspect or reset pinned fingerprints when server keys rotate.
- **Key Generation & Import**:
  - Built-in Ed25519 keypair generator with one-tap public key export.
  - Support for importing existing private keys from file (Storage Access Framework) or pasting directly from clipboard.
- **Mobile-Responsive UI Injection**:
  - Dynamically injects tailored CSS overrides into the embedded WebView.
  - Optimizes navigation rails, modal sheets, and the plugin market for comfortable touch interaction in both portrait and landscape orientations.
  - Native Android back navigation integration (`BackHandler`) preserving in-app browsing history.

---

## Recommended Companion Tools

To provide the smoothest and most secure mobile experience, the following companion open-source Android tools are recommended:

### 1. Key Generation & Remote Deployment: Haven SSH
For generating SSH keys and easily deploying them to your remote host, we recommend **Haven**:
- **[Haven on F-Droid](https://f-droid.org/en/packages/sh.haven.app/)**
- **[Haven on GitHub](https://github.com/GlassHaven/Haven)**

Haven is an excellent, modern Android SSH client and key manager. You can use Haven to generate an Ed25519 keypair on your phone and deploy your public key directly into the remote server's `~/.ssh/authorized_keys` file before configuring `dsh-client`.

### 2. Remote Access: RethinkDNS+ (WireGuard)
For securely accessing your server outside your local home network without exposing SSH to the public internet:
- **[RethinkDNS+ on F-Droid](https://f-droid.org/en/packages/com.celzero.bravedns/)**
- **[RethinkDNS on GitHub](https://github.com/celzero/rethink-app)**

RethinkDNS+ provides a powerful open-source DNS-over-HTTPS/TLS client, firewall, and WireGuard client. Its split-tunneling feature allows you to route traffic destined for your home subnet or VPN IP range through WireGuard while allowing normal internet traffic to route directly through your mobile network.

---

## Architecture Overview

```
 ┌─────────────────────────────────────────────────────────────┐
 │                       Android Device                        │
 │                                                             │
 │  ┌──────────────────────┐        ┌───────────────────────┐  │
 │  │   WebView / UI       │ ───►   │ Local Loopback        │  │
 │  │ (Compose + CustomCSS)│        │ http://127.0.0.1:3080 │  │
 │  └──────────────────────┘        └───────────┬───────────┘  │
 │                                              │              │
 │  ┌───────────────────────────────────────────▼───────────┐  │
 │  │      SshTunnelService (Foreground Service)            │  │
 │  │  - JSch SSH Engine        - WakeLock Keepalive        │  │
 │  │  - TOFU Host Key Pinning  - Token/Port Scraper        │  │
 │  └───────────────────────────────┬───────────────────────┘  │
 └──────────────────────────────────┼──────────────────────────┘
                                    │ Encrypted SSH (Port 22)
                                    ▼ (LAN or WireGuard via Rethink)
 ┌─────────────────────────────────────────────────────────────┐
 │                       Remote Host                           │
 │                                                             │
 │  ┌──────────────────────┐        ┌───────────────────────┐  │
 │  │  systemd / journald  │ ◄───   │  SSHD Service         │  │
 │  │  (dsh-web.service)   │        │  Port 22              │  │
 │  └──────────┬───────────┘        └───────────┬───────────┘  │
 │             │                                │              │
 │             └───────────────► ┌──────────────▼───────────┐  │
 │                               │ dsh-web HTTP Service     │  │
 │                               │ (e.g. 127.0.0.1:3232)    │  │
 │                               └──────────────────────────┘  │
 └─────────────────────────────────────────────────────────────┘
```

---

## Getting Started

### 1. Server Prerequisites
- A Linux host running `dsh-web` managed by `systemd` (e.g. `dsh-web.service` or a user unit).
- SSH access with public key authentication enabled.
- Ensure your user has permission to read the journal for the service unit (`journalctl -u dsh-web.service`).

### 2. Client Setup
1. Install `dsh-client` on your Android device (Android 8.0+ / API 26+, recommended Android 14+ / API 34+).
2. On initial launch, the app navigates automatically to the **SSH Settings** screen:
   - **Host**: Enter your server's hostname, LAN IP, or WireGuard IP (e.g. `10.0.0.50` or `myhost.lan`).
   - **Port**: Default is `22`.
   - **User**: The SSH username on the remote host.
   - **Private Key**:
     - Tap **Generate New Keypair** to create an on-device Ed25519 key, then copy the public key to append to your server's `~/.ssh/authorized_keys`.
     - Or tap **Paste from Clipboard** / **Import from File** to use an existing key generated via [Haven](https://f-droid.org/en/packages/sh.haven.app/) or OpenSSH.
   - **Passphrase**: Optional, if your imported key is passphrase-protected.
3. Tap **Test Connection** to verify the handshake and TOFU host key fingerprint.
4. Tap **Save & Connect**. The foreground service will start, establish the tunnel, scrape the active session token, and load the interface.

---

## Building

Requires JDK 17 and Android SDK 35/36.

```bash
# Clone the repository
git clone https://github.com/molove/dsh-client.git
cd dsh-client

# Run JVM unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Credits & Acknowledgments

- **Engineered by G** (Gemini paired with [@molove](https://github.com/molove) in Google Antigravity).
- Built with [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), and [mwiede/jsch](https://github.com/mwiede/jsch).
- Special thanks to the teams behind [Haven](https://github.com/GlassHaven/Haven) and [RethinkDNS](https://github.com/celzero/rethink-app) for first-class Android companion tooling.

---

## License

This project is open-source and available under the [MIT License](LICENSE).
