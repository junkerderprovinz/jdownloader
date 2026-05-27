<h1 align="center">JDownloader 2 for Unraid</h1>

<a href="https://jdownloader.org">
  <img src="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/jdownloader-banner.svg" alt="JDownloader 2 for Unraid" width="100%">
</a>

<p align="center">
  <a href="https://github.com/junkerderprovinz/jdownloader/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/junkerderprovinz/jdownloader/build.yml?branch=main&label=Build&style=for-the-badge&logo=githubactions&logoColor=white" alt="Build" height="36"></a>&nbsp;
  <a href="https://github.com/junkerderprovinz/jdownloader/actions/workflows/lint.yml"><img src="https://img.shields.io/github/actions/workflow/status/junkerderprovinz/jdownloader/lint.yml?branch=main&label=Lint&style=for-the-badge&logo=githubactions&logoColor=white" alt="Lint" height="36"></a>&nbsp;
  <a href="https://github.com/junkerderprovinz/jdownloader/pkgs/container/jdownloader"><img src="https://img.shields.io/badge/Image-ghcr.io-1d99f3?style=for-the-badge&logo=docker&logoColor=white" alt="Image" height="36"></a>&nbsp;
  <a href="https://github.com/junkerderprovinz/jdownloader/pkgs/container/jdownloader"><img src="https://img.shields.io/badge/Arch-amd64%20%7C%20arm64-success?style=for-the-badge&logo=linux&logoColor=white" alt="Arch" height="36"></a>&nbsp;
  <a href="https://github.com/kasmtech/KasmVNC"><img src="https://img.shields.io/badge/Web-KasmVNC-3daee9?style=for-the-badge&logo=kde&logoColor=white" alt="KasmVNC" height="36"></a>&nbsp;
  <a href="#languages"><img src="https://img.shields.io/badge/Languages-17-3daee9?style=for-the-badge&logo=googletranslate&logoColor=white" alt="Languages" height="36"></a>&nbsp;
  <a href="https://unraid.net"><img src="https://img.shields.io/badge/Unraid-Template-f15a2c?style=for-the-badge&logo=unraid&logoColor=white" alt="Unraid" height="36"></a>&nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge&logo=opensourceinitiative&logoColor=white" alt="License" height="36"></a>&nbsp;
  <a href="https://buymeacoffee.com/junkerderprovinz"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy me a coffee" height="36"></a>
</p>

<br>

<p align="center">
A modern, plug-and-play Docker image for <b>JDownloader 2</b> on Unraid. Full GUI in your browser,
powered by KasmVNC — with a dark interface enabled out of the box, 17 UI languages, and zero
first-run configuration required.
</p>

<br>

This image packages [JDownloader 2](https://jdownloader.org) into a self-contained Docker container that runs in any modern web browser. It is built on top of [`linuxserver/baseimage-kasmvnc`](https://github.com/linuxserver/docker-baseimage-kasmvnc), so it benefits from LSIO's hardware-accelerated KasmVNC stack and weekly security updates, while everything JDownloader-specific (dark theme, language, Java runtime, auto-install) is layered on top.

What's included beyond bare JDownloader:

- **KasmVNC** instead of noVNC — hardware-accelerated rendering, real browser clipboard, native file upload and download, high-DPI ready
- **Modern dark interface** pre-applied (JD_Plain_Dark with Breeze Dark colour palette); switch to light with one variable
- **Java 21 JRE** — full AWT/Swing support for the JDownloader GUI, not headless
- **Auto-install** — downloads and installs JDownloader 2 on first container start, no manual JAR setup
- **Self-updating** — JDownloader updates itself on every start as it normally does
- **17 UI languages** picked from a dropdown in the Unraid template
- **Update-safe config** — all settings, links and session state live in `/config` and survive every `docker pull`
- **Multi-arch** — amd64 and arm64

| | **This image** | jlesage | jaymoulin |
|---|:---:|:---:|:---:|
| Web stack | **KasmVNC** | noVNC | — (headless) |
| HW-accelerated rendering | ✅ | ❌ | ❌ |
| Browser clipboard | ✅ | ⚠️ | ❌ |
| File upload via WebUI | ✅ | ❌ | ❌ |
| Dark interface default | ✅ | ❌ | ❌ |
| Language dropdown | ✅ (17) | ❌ | ❌ |
| Auto-install on first start | ✅ | ✅ | ✅ |
| Multi-arch | ✅ amd64 + arm64 | ✅ | ✅ |
| Base | LinuxServer/KasmVNC | jlesage/Alpine | Alpine |

<br>

## Quick Start

### Step 1 — Install the template

In Unraid: **Apps** → search for **JDownloader** → click **Install**.

Or add the template URL directly in **Docker** → **Add Container** → Template dropdown → enter:

```
https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/templates/jdownloader.xml
```

### Step 2 — Adjust paths and start

The defaults work out of the box, but you may want to tweak:

- **Config (`/config`)** — defaults to `/mnt/user/appdata/jdownloader`
- **Downloads (`/downloads`)** — defaults to `/mnt/user/downloads`; this is where JDownloader saves files
- **UI Language** — dropdown, default `de`
- **Theme** — default `JD_Plain_Dark` (dark Breeze palette); switch to `JD_Plain` for light
- **KasmVNC Password** — leave empty for LAN-only, set anything for exposure beyond the LAN

Click **Apply**. The first start takes **up to 5 minutes** while JDownloader downloads and installs itself.

### Step 3 — Open the WebUI

`http://<unraid-ip>:3000/` (HTTP) or `https://<unraid-ip>:3001/` (HTTPS, self-signed).

The JDownloader GUI appears automatically once the install completes.

<details>
<summary>docker-compose (non-Unraid)</summary>

```yaml
services:
  jdownloader:
    image: ghcr.io/junkerderprovinz/jdownloader:latest
    container_name: jdownloader
    environment:
      - PUID=99
      - PGID=100
      - TZ=Europe/Vienna
      - JD_LANG=de
      - JD_THEME=JD_Plain_Dark
    volumes:
      - /mnt/user/appdata/jdownloader:/config
      - /mnt/user/downloads:/downloads
    ports:
      - 3000:3000
      - 3001:3001
    restart: unless-stopped
    shm_size: 1gb
```

**`shm_size: 1gb`** is required for smooth KasmVNC rendering.

</details>

<br>

## Configuration

| Variable | Default | Description |
|---|---|---|
| `JD_LANG` | `de` | UI language — see [Languages](#languages) |
| `JD_THEME` | `JD_Plain_Dark` | UI theme — `JD_Plain_Dark` = dark (Breeze Dark), `JD_Plain` = light, `JDDEFAULT` = JD default |
| `PUID` | `99` | User ID — Unraid's *nobody* |
| `PGID` | `100` | Group ID — Unraid's *users* |
| `TZ` | `Europe/Vienna` | Timezone |
| `CUSTOM_USER` | _(empty)_ | KasmVNC username — leave empty for no auth |
| `PASSWORD` | _(empty)_ | KasmVNC password — **set this if exposed beyond LAN** |
| `TITLE` | `JDownloader 2` | Browser tab title |
| `UMASK` | `022` | File-creation mask |

| Port | Purpose | | Volume | Purpose |
|---|---|---|---|---|
| `3000` | KasmVNC HTTP | | `/config` | Persistent JDownloader config, links, session |
| `3001` | KasmVNC HTTPS *(self-signed)* | | `/downloads` | Download destination |

<br>

## Languages

The Unraid template ships a **dropdown** with **17 UI languages**. Language is applied on every container start — switching takes effect after a restart.

| Region | Languages |
|---|---|
| **Western Europe** | 🇩🇪 `de` Deutsch · 🇬🇧 `en` English · 🇫🇷 `fr` Français · 🇪🇸 `es` Español · 🇮🇹 `it` Italiano · 🇵🇹 `pt` Português · 🇳🇱 `nl` Nederlands |
| **Central / Eastern Europe** | 🇵🇱 `pl` Polski · 🇨🇿 `cs` Čeština · 🇸🇰 `sk` Slovenčina · 🇭🇺 `hu` Magyar · 🇷🇴 `ro` Română · 🇷🇺 `ru` Русский |
| **Asia** | 🇯🇵 `ja` 日本語 · 🇰🇷 `ko` 한국어 · 🇨🇳 `zh` 中文 |
| **Other** | 🇹🇷 `tr` Türkçe |

*Default: `de` (Deutsch). Set via `JD_LANG` or the Unraid dropdown.*

<br>

## Customisation & Persistence

On the **first start**, JDownloader installs itself into `/config/JDownloader/`. All settings, link lists, accounts and download history live there and survive every `docker pull` and container update.

```
/config/
└── JDownloader/
    ├── cfg/           # all JDownloader config files (theme, lang, accounts, …)
    ├── downloads/     # optional: JD's own download dir (we map /downloads instead)
    ├── themes/
    │   └── JD_Plain_Dark/   # pre-built dark theme (copied from image on every start)
    └── JDownloader2.jar
```

The env-driven settings `JD_LANG` and `JD_THEME` are re-applied on **every start**, so you can change them at any time via the Unraid template.

The base image also supports `/config/custom-cont-init.d/` for your own init scripts — see the [LinuxServer docs](https://docs.linuxserver.io/general/container-customization/).

<br>

## Troubleshooting

<details>
<summary><b>WebUI is black / desktop never appears</b></summary>

- Make sure `shm_size` is at least `512mb` (Unraid template sets `1gb`)
- Check the container log for KasmVNC startup errors
- Try `https://<ip>:3001/` — sometimes browsers block WebSockets over plain HTTP
- Wait up to **5 minutes** on the very first start — JDownloader is downloading and installing itself. The screen will be black during this time — this is normal.
</details>

<details>
<summary><b>JDownloader GUI doesn't appear after 2 minutes</b></summary>

- Open the container log and look for `init-jdownloader` messages
- If you see `JDownloader2.jar missing after installer run` — the installer needs an internet connection on first start. Ensure the container has internet access.
- Restart the container once — the installer retries automatically
</details>

<details>
<summary><b>Dark mode not active</b></summary>

- Verify `JD_THEME=JD_Plain_Dark` is set in your template
- Check the container log for `[jdownloader-theme]` lines
- The theme is applied at container start, not live — restart after changing `JD_THEME`
</details>

<details>
<summary><b>Language change doesn't take effect</b></summary>

- Restart the container — language is applied at start, not live
- Check the env value matches a code from the [Languages](#languages) table
</details>

<details>
<summary><b>"Permission denied" on /downloads</b></summary>

- Check `PUID` / `PGID`. On Unraid, `99:100` (nobody:users) matches share permissions.
- Verify your `/downloads` share has the right permissions: **Docker** → **Edit** → check the path
</details>

<details>
<summary><b>KasmVNC password not accepted</b></summary>

- Open in a private/incognito window once — your browser may have cached old credentials
</details>

<br>

## Screenshots

*Screenshots will be added once the container has been tested on Unraid.*

<br>

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  ghcr.io/linuxserver/baseimage-kasmvnc:ubuntunoble               │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  s6-overlay v3 init                                        │  │
│  │   ↓                                                        │  │
│  │  init-jdownloader/run                                      │  │
│  │   ↓ installs JD on first start (bootstraps JDownloader.jar)│  │
│  │   ↓ copies JD_Plain_Dark theme from image → /config        │  │
│  │   ↓ seeds language + dark-mode config                      │  │
│  │   ↓                                                        │  │
│  │  KasmVNC ← /defaults/autostart                             │  │
│  │              → JDownloader 2 (Java Swing GUI)              │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

<br>

## Contributing / License

Pull requests welcome. Issues: <https://github.com/junkerderprovinz/jdownloader/issues>.

**Licensing — dual:**

- This **wrapper repository** (Dockerfile, `rootfs/`, scripts, Unraid template, README and banner/icon artwork) is licensed under the [MIT License](LICENSE).
- **JDownloader 2** itself retains its own license (see [jdownloader.org/license](https://jdownloader.org/license)). When you run or redistribute the resulting container image, you must comply with JDownloader's license as well.

```bash
# Run lints locally (same as CI)
docker run --rm -i hadolint/hadolint < Dockerfile
find rootfs -name '*.sh' | xargs shellcheck --severity=warning --shell=bash
find . -name '*.xml' | xargs xmllint --noout
```

### Credits

- [**JDownloader 2**](https://jdownloader.org) — AppWork GmbH & the JDownloader team
- [**LinuxServer.io**](https://www.linuxserver.io) — for the excellent [`baseimage-kasmvnc`](https://github.com/linuxserver/docker-baseimage-kasmvnc)
- [**KasmVNC**](https://github.com/kasmtech/KasmVNC) — for remote desktop in a browser that actually works
- Inspiration: jlesage and jaymoulin JDownloader containers — they paved the way

<br>

## Support this project

If this image saves you time or a debug night, consider buying me a coffee:

<p align="center">
  <a href="https://buymeacoffee.com/junkerderprovinz">
    <img src=".github/assets/button-buy-me-a-coffee.svg" alt="Buy me a coffee" width="220">
  </a>
</p>
