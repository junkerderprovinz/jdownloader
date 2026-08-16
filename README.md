<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/jdownloader-banner-dark.png">
    <img src="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/jdownloader-banner.png" alt="JDownloader 2 for Unraid" width="100%">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/junkerderprovinz/jdownloader/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/junkerderprovinz/jdownloader/build.yml?branch=main&label=Build&style=for-the-badge&logo=githubactions&logoColor=white" alt="Build" height="36"></a>&nbsp;
  <a href="https://github.com/junkerderprovinz/jdownloader/actions/workflows/lint.yml"><img src="https://img.shields.io/github/actions/workflow/status/junkerderprovinz/jdownloader/lint.yml?branch=main&label=Lint&style=for-the-badge&logo=githubactions&logoColor=white" alt="Lint" height="36"></a>&nbsp;
  <a href="https://hub.docker.com/r/junkerderprovinz/jdownloader"><img src="https://img.shields.io/docker/pulls/junkerderprovinz/jdownloader?style=for-the-badge&logo=docker&logoColor=white&label=Pulls&color=1d99f3" alt="Docker Pulls" height="36"></a>&nbsp;
  <a href="https://hub.docker.com/r/junkerderprovinz/jdownloader"><img src="https://img.shields.io/docker/image-size/junkerderprovinz/jdownloader/latest?style=for-the-badge&logo=docker&logoColor=white&label=Size&color=1d99f3" alt="Image Size" height="36"></a>&nbsp;
  <a href="https://github.com/junkerderprovinz/jdownloader/pkgs/container/jdownloader"><img src="https://img.shields.io/badge/Arch-amd64%20%7C%20arm64-success?style=for-the-badge&logo=linux&logoColor=white" alt="Arch" height="36"></a>&nbsp;
  <a href="https://github.com/selkies-project/selkies"><img src="https://img.shields.io/badge/Web-Selkies-3daee9?style=for-the-badge&logo=kde&logoColor=white" alt="Selkies" height="36"></a>&nbsp;
  <a href="https://unraid.net"><img src="https://img.shields.io/badge/Unraid-Template-f15a2c?style=for-the-badge&logo=unraid&logoColor=white" alt="Unraid" height="36"></a>&nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue?style=for-the-badge&logo=gnu&logoColor=white" alt="License: AGPL-3.0" height="36"></a>
</p>

<br>

<p align="center">
A modern, plug-and-play Docker image for <b>JDownloader 2</b> on Unraid with a
<b>clean, sleek, fully dark and ad-free UI</b> out of the box — a monochrome IBM&nbsp;Carbon&nbsp;<code>#161616</code>
dark across the <i>entire</i> interface (download list, link grabber <b>and</b> settings, not just
the menu bar), with light fills + readable text on the progress bars and a borderless,
maximised kiosk window. JDownloader's built-in advertisements are switched off, so the
download graph keeps its full height. Full GUI in your browser via Selkies, zero first-run setup.
</p>

<br>

<p align="center">
A solo, free-time project. Bugs and ideas via <a href="https://github.com/junkerderprovinz/jdownloader/issues">GitHub issues</a>; if it's useful to you, a coffee is always welcome.
</p>

<br>

<p align="center">
  <a href="https://buymeacoffee.com/junkerderprovinz">
    <img src=".github/assets/button-buy-me-a-coffee.svg" alt="Buy me a coffee" width="220">
  </a>
</p>

<br>

## Table of Contents

1. [Overview](#1-overview)
2. [Screenshots](#2-screenshots)
3. [Quick Start](#3-quick-start)
4. [Configuration](#4-configuration)
5. [Customisation & Persistence](#5-customisation--persistence)
6. [Troubleshooting](#6-troubleshooting)
7. [Architecture](#7-architecture)
8. [Contributing / License](#8-contributing--license)
9. [License](#9-license)
10. [Support this project](#10-support-this-project)
<br>

## 1. Overview

This image packages [JDownloader 2](https://jdownloader.org) into a self-contained Docker container that runs in any modern web browser. It is built on top of [`linuxserver/baseimage-selkies`](https://github.com/linuxserver/docker-baseimage-selkies), so it benefits from LSIO's actively-maintained Selkies desktop-streaming stack (a hybrid VNC/H.264 pipeline) and weekly security updates, while everything JDownloader-specific (dark theme, **ad-free defaults**, Java runtime, auto-install) is layered on top.

What's included beyond bare JDownloader:

- **Selkies** instead of noVNC — a hybrid VNC/H.264 pipeline for a smooth 60fps web desktop, real bidirectional browser clipboard, native file upload and download, high-DPI ready
- **Sleek, complete Dark Mode** pre-applied — a monochrome IBM Carbon (#161616) dark across the *entire* GUI (download list, link grabber **and** settings, not just the menu bar), in a clean maximised kiosk window; switch to a matching Light theme with one variable
- **Ad-free by default** — JDownloader's built-in advertisements (the *"Become premium user"* banner, the premium-alert column nags, the special-deal popups) are switched off, so the GUI stays clean and the download speed graph keeps its **full height**
- **Java 21 JRE** — full AWT/Swing support for the JDownloader GUI, not headless
- **Auto-install** — downloads and installs JDownloader 2 on first container start, no manual JAR setup
- **Self-updating** — JDownloader updates itself on every start as it normally does
- **Update-safe config** — all settings, links and session state live in `/config` and survive every `docker pull`
- **Multi-arch** — amd64 and arm64

| | **This image** | jlesage | jaymoulin |
|---|:---:|:---:|:---:|
| Web stack | **Selkies** | noVNC | — (headless) |
| HW-accelerated rendering | ✅ | ❌ | ❌ |
| Browser clipboard | ✅ | ⚠️ | ❌ |
| File upload via WebUI | ✅ | ❌ | ❌ |
| Full dark UI (content too) | ✅ | ❌ | ❌ |
| Ad-free by default | ✅ | ❌ | ❌ |
| Auto-install on first start | ✅ | ✅ | ✅ |
| Multi-arch | ✅ amd64 + arm64 | ✅ | ✅ |
| Base | LinuxServer/Selkies | jlesage/Alpine | Alpine |

<br>

## 2. Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/screenshots/jdownloader-1.jpg" alt="JDownloader download list with the Carbon dark theme and right-click menu" width="90%">
  <br><em>Download list in monochrome Carbon <code>#161616</code> — right-click menu, per-file priority, live speed/ETA.</em>
</p>

<br>

<p align="center">
  <img src="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/screenshots/jdownloader-2.jpg" alt="JDownloader downloading multiple packages with the dark theme" width="90%">
  <br><em>Multiple packages downloading — uniform dark rows, light progress bars, green speed graph.</em>
</p>

<br>

<p align="center">
  <img src="https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/.github/assets/screenshots/jdownloader-3.jpg" alt="JDownloader Settings — User Interface tab, fully dark" width="90%">
  <br><em>Settings &rarr; User Interface — fully dark, the same Carbon palette across the whole app.</em>
</p>

<br>

## 3. Quick Start

### Step 1 — Install the template

In Unraid: **Apps** → search for **JDownloader** → click **Install**.

The Community Applications template is published from the
[`unraid-apps`](https://github.com/junkerderprovinz/unraid-apps) feed
(one feed for all of junkerderprovinz's apps). If the Template dropdown in **Docker → Add
Container** no longer accepts a URL on your Unraid version, drop the XML directly into the
templates-user folder via SSH (or WinSCP). **Important:** the filename must be
`my-JDownloader.xml` with the `my-` prefix and capital `J` — otherwise Unraid sees it as a
separate template and a `Force Update` will reset all customizations.

```bash
wget -O /boot/config/plugins/dockerMan/templates-user/my-JDownloader.xml \
    https://raw.githubusercontent.com/junkerderprovinz/unraid-apps/main/jdownloader/jdownloader.xml
```

### Step 2 — Adjust paths and start

The defaults work out of the box, but you may want to tweak:

- **Config (`/config`)** — defaults to `/mnt/user/appdata/jdownloader`
- **Downloads (`/downloads`)** — defaults to `/mnt/user/downloads`; this is where JDownloader saves files
- **Theme** — default `Dark` (JD Plain Dark, Carbon #161616 palette); switch to `Light`, or `JDDEFAULT` for the classic official JDownloader look, any time
- **WebUI Password** — leave empty for LAN-only, set anything for exposure beyond the LAN

Click **Apply**.

> ## ⏳ First start — wait for the READY banner
>
> On the **first start (and after every image update)** JDownloader installs/updates itself
> and applies the dark theme. **The WebUI stays black for a few minutes.** Open the container
> **log** and wait for this banner *before* you open the WebUI:
>
> ```text
> ############################################################
>  JDOWNLOADER IS READY  ->  open the WebUI now (HTTPS 3001)
> ############################################################
> ```
>
> **Do not restart the container while it installs.** The banner is shown only once the GUI is
> up **and** the dark theme is fully applied — so when you see it, the UI is already dark and
> ready. (It self-heals JDownloader's first-run theme reset, then prints the banner.)

### Step 3 — Open the WebUI

Use **`https://<unraid-ip>:3001/`** (this is what the template's WebUI button opens) and accept the self-signed certificate warning once. **HTTPS is required for direct access:** the Selkies web client needs a browser *secure context* — for the WebCodecs video decoder it streams through, and for **seamless clipboard** (copy on your PC, paste straight into JD). Opening `http://<unraid-ip>:3000/` therefore stops at a *"This application requires a secure connection (HTTPS)"* error and never loads the desktop. Port `3000` exists for a reverse proxy that terminates TLS in front of the container and forwards plain HTTP to it.

The JDownloader GUI appears automatically once the install completes.

<details>
<summary>docker-compose (non-Unraid)</summary>

```yaml
services:
  jdownloader:
    image: junkerderprovinz/jdownloader:latest
    container_name: jdownloader
    environment:
      - PUID=99
      - PGID=100
      - TZ=Europe/Vienna
      - JD_THEME=Dark
    volumes:
      - /mnt/user/appdata/jdownloader:/config
      - /mnt/user/downloads:/downloads
    ports:
      - 3000:3000
      - 3001:3001
    restart: unless-stopped
    shm_size: 1gb
```

**`shm_size: 1gb`** is required for smooth Selkies rendering.

</details>

<br>

## 4. Configuration

| Variable | Default | Description |
|---|---|---|
| `JD_THEME` | `Dark` | UI theme — `Dark` = monochrome Carbon `#161616`, `Light` = FlatLaf light, `JDDEFAULT` = classic official JDownloader look (Synthetica). Default stays `Dark`; existing themes are unchanged. |
| `JD_SELFUPDATE` | `true` | `false` disables JD's periodic self-update checks (opt-in "frozen appliance"). **Note:** the same update channel delivers the hoster plugins, which go stale within weeks — downloads may start failing. First install always uses the updater. |
| `JD_ENABLE_BROWSER` | `false` | `true` enables JD's "solve captcha in browser" flow: reCAPTCHA/hCaptcha/Turnstile open in a bundled **Firefox** (with **uBlock Origin**) on the web desktop, solved with one click from the container's own IP (tokens are IP-bound); the profile persists in `/config/.config/mozilla`. Off by default — no browser process runs. Only enable it if a hoster you use needs browser captchas (classic image captchas are auto-solved either way); enabling it runs a full browser (more resources + attack surface). |
| `JD_UI_SCALE` | _(empty)_ | Optional HiDPI scaling for the whole JDownloader UI, e.g. `1.5` or `2`; empty keeps 1x. Renders the UI crisp and large at the desktop's native resolution so you do not need browser zoom (browser zoom upscales the Selkies video stream and blurs it). See Troubleshooting if text looks tiny or blurry. |
| `JD_COMPACT_TOOLBAR` | _(empty)_ | `true` / `1` keeps JDownloader's stock ~32px toolbar row (icons with less vertical padding). Empty / `false` keeps this image's default: the speed-graph row is grown to 64px so the download graph has full height. |
| `PUID` | `99` | User ID — Unraid's *nobody* |
| `PGID` | `100` | Group ID — Unraid's *users* |
| `TZ` | `Europe/Vienna` | Timezone |
| `CUSTOM_USER` | _(empty)_ | WebUI login username — leave empty with `PASSWORD` for no login |
| `PASSWORD` | _(empty)_ | WebUI password — **set this if exposed beyond LAN** |
| `UMASK` | `022` | File-creation mask |

| Port | Purpose | | Volume | Purpose |
|---|---|---|---|---|
| `3001` | Selkies HTTPS *(self-signed)* — **default WebUI, needed for clipboard** | | `/config` | Persistent JDownloader config, links, session |
| `3000` | Selkies HTTP *(reverse-proxy only — direct access needs HTTPS)* | | `/downloads` | Download destination |

> **Web file transfers:** the Selkies sidebar's upload/download panel and the WebUI's `/files` browser both use the base image's `FILE_MANAGER_PATH`, which defaults to **`/config/Desktop`** — so anything you upload through the browser lands there, inside the persisted `/config` volume, and survives a container update. Point `FILE_MANAGER_PATH` somewhere else if you prefer (e.g. a folder under `/downloads`), but pick the directory deliberately: without `PASSWORD` set, `/files` serves it to anyone who can reach the WebUI.

> **Language:** the UI is **English** by default. Change it any time in JDownloader's own language menu (top toolbar → the flag icon, or *Settings → Language*) — your choice is saved and persists across restarts.

<br>

## 5. Customisation & Persistence

On the **first start**, JDownloader installs itself into `/config/JDownloader/`. All settings, link lists, accounts and download history live there and survive every `docker pull` and container update.

```
/config/
└── JDownloader/
    ├── cfg/
    │   └── laf/      # the Carbon #161616 colorfor* palette (re-applied every start)
    ├── libs/laf/     # JD's own STOCK FlatLaf (unpatched) — the dark chrome comes from the image, not from here
    ├── themes/flat/  # bundled flat icon set (re-seeded from the image every start)
    └── JDownloader.jar
```

The env-driven setting `JD_THEME` is re-applied on **every start**, so you can change it at any time via the Unraid template.

The base image also supports `/config/custom-cont-init.d/` for your own init scripts — see the [LinuxServer docs](https://docs.linuxserver.io/general/container-customization/).

<br>

## 6. Troubleshooting

<details>
<summary><b>WebUI is black / desktop never appears</b></summary>

- Make sure `shm_size` is at least `512mb` (Unraid template sets `1gb`)
- Check the container log for Selkies startup errors
- Make sure you opened **`https://<ip>:3001/`** and not `http://<ip>:3000/` — over plain HTTP the Selkies client aborts with *"requires a secure connection (HTTPS)"* and the desktop never appears
- **First start takes a few minutes** — JDownloader installs itself + its dark theme; the screen stays black until done. Watch the container log for the **`JDOWNLOADER IS READY`** banner, then refresh. Don't restart the container.
- **First start only:** JDownloader may ask once to install its design + a few extensions — click **OK** / **Install now**. Afterwards it stays dark with no prompts.
</details>

<details>
<summary><b>Can't paste into JD / seamless clipboard doesn't work</b></summary>

- Open the WebUI over **HTTPS** (`https://<ip>:3001/` — the template's WebUI button). Browsers only allow the seamless clipboard API in a **secure context**; over plain HTTP it's blocked.
- If prompted, allow the browser's clipboard permission (lock icon → site settings).
</details>

<details>
<summary><b>JDownloader GUI doesn't appear after 2 minutes</b></summary>

- Open the container log and look for `init-jdownloader` messages
- If you see `JDownloader2.jar missing after installer run` — the installer needs an internet connection on first start. Ensure the container has internet access.
- Restart the container once — the installer retries automatically
</details>

<details>
<summary><b>Dark mode not active</b></summary>

- Verify `JD_THEME=Dark` is set in your template
- Check the container log for `[jdownloader-theme]` lines
- The theme is applied at container start, not live — restart after changing `JD_THEME`
</details>

<details>
<summary><b>Text looks tiny at high resolution, or blurry when I zoom the browser</b></summary>

- This is how a streamed desktop works: Selkies sends the whole X display as one video stream, so zooming your browser upscales that video and blurs the text.
- Instead of browser zoom, set **`JD_UI_SCALE`** (for example `1.5` or `2`). JDownloader then renders its UI larger at full pixel density, so text stays crisp at the desktop's native resolution.
- Keep the browser at 100% zoom once `JD_UI_SCALE` is set, and restart the container after changing it.
</details>

<details>
<summary><b>"Permission denied" on /downloads</b></summary>

- Check `PUID` / `PGID`. On Unraid, `99:100` (nobody:users) matches share permissions.
- Verify your `/downloads` share has the right permissions: **Docker** → **Edit** → check the path
</details>

<details>
<summary><b>WebUI password not accepted</b></summary>

- Open in a private/incognito window once — your browser may have cached old credentials
</details>

<br>

## 7. Architecture

```
ghcr.io/linuxserver/baseimage-selkies:ubunturesolute   (s6-overlay v3 · Selkies · weekly LSIO updates)
      │
      ▼  cont-init.d/10-jdownloader-setup        (runs once, before the desktop starts)
      │     • writes JD's native Carbon #161616 colorfor* palette   → cfg/laf
      │     • seeds the bundled flat icon set                       → themes/flat
      │     • language · tray off · openbox kiosk (no title bar, dialogs not maximised)
      ▼
   svc-de  →  /defaults/autostart   (the JDownloader launcher loop)
      │     • FIRST install: silent HEADLESS pre-install (no GUI = no forced
      │       dialogs), then the GUI starts with the core already in place
      │     • java -jar JDownloader.jar     (the regular GUI launch)
      │     • re-applies the colorfor*/icons theme config before each launch
      │     • a -javaagent auto-confirms JD's forced install dialogs AND registers
      │       /opt/JDownloader/flatlaf-defaults as a FlatLaf custom-defaults source
      │       (official API) — the #161616 chrome; flatlaf.jar stays STOCK
      │     • theme auto-heal: restarts JD once if a self-update reset the theme
      │       (ground truth: the LAF actually applied inside the JVM, max 3/hour)
      │     • prints "JDOWNLOADER IS READY" only when the JVM confirms the dark LAF
      ▼
   JDownloader 2   (Java Swing GUI, streamed to your browser by Selkies)
      ▲
   svc-de/finish  →  SIGTERMs the JVM on stop so it flushes column layout / settings
```

<br>

## 8. Contributing / License

Pull requests welcome. Issues: <https://github.com/junkerderprovinz/jdownloader/issues>.

**Licensing — dual:**

- This **wrapper repository** (Dockerfile, `rootfs/`, scripts, Unraid template, README and banner/icon artwork) is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).
- **JDownloader 2** itself retains its own license (see [jdownloader.org/license](https://jdownloader.org/license)). When you run or redistribute the resulting container image, you must comply with JDownloader's license as well.

```bash
# Run lints locally (same as CI)
docker run --rm -i hadolint/hadolint < Dockerfile
find rootfs -name '*.sh' | xargs shellcheck --severity=warning --shell=bash
find . -name '*.xml' | xargs xmllint --noout
```

### Credits

- [**JDownloader 2**](https://jdownloader.org) — AppWork GmbH & the JDownloader team
- [**LinuxServer.io**](https://www.linuxserver.io) — for the excellent [`baseimage-selkies`](https://github.com/linuxserver/docker-baseimage-selkies)
- [**Selkies**](https://github.com/selkies-project/selkies) — for a modern, actively-developed browser desktop stack
- [**Icons8**](https://icons8.com) — the bundled "JD Plain" flat icon set uses JDownloader's Icons8 icons, redistributed verbatim under [CC BY-ND 3.0](https://icons8.com/license)
- Inspiration: jlesage and jaymoulin JDownloader containers — they paved the way

<br>

## 9. License

**Copyright (C) 2026 Junker der Provinz.**

This repository packages JDownloader as a container for Unraid. The packaging in this repository (Dockerfile, scripts, theme, web assets and everything else original here) is free software under the **GNU Affero General Public License v3.0** (AGPL-3.0); see [LICENSE](LICENSE). If you distribute it, or run a modified version as a network service, you must release your source under the same AGPL-3.0 terms and keep the existing copyright and attribution notices intact.

**Scope.** The AGPL applies to this repository's own code and assets. JDownloader itself is a separate project under its own license and name; this repository does not claim it. The banner, logo, theme and other branding original to this repository remain reserved: a fork must use its own branding and may not present itself as this project.

<br>

## 10. Support this project

If this image saves you time or a debug night, consider buying me a coffee:

JDownloader 2 for Unraid is a one-person project. I write, test, and support it myself, in whatever free time is left after work. Found a bug or have an idea? Please [open a GitHub issue](https://github.com/junkerderprovinz/jdownloader/issues) so it doesn't get lost.

If you'd like to support the time that goes into it, you're welcome to buy me a coffee. Genuinely appreciated either way.

<p align="center">
  <a href="https://buymeacoffee.com/junkerderprovinz">
    <img src=".github/assets/button-buy-me-a-coffee.svg" alt="Buy me a coffee" width="220">
  </a>
</p>
