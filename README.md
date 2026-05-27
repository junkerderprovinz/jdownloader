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
  <a href="#environment-variables"><img src="https://img.shields.io/badge/Languages-17-3daee9?style=for-the-badge&logo=googletranslate&logoColor=white" alt="Languages" height="36"></a>&nbsp;
  <a href="https://unraid.net"><img src="https://img.shields.io/badge/Unraid-Template-f15a2c?style=for-the-badge&logo=unraid&logoColor=white" alt="Unraid" height="36"></a>&nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge&logo=opensourceinitiative&logoColor=white" alt="License" height="36"></a>&nbsp;
  <a href="https://buymeacoffee.com/junkerderprovinz"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy me a coffee" height="36"></a>
</p>

<br>

<p align="center">
JDownloader 2 in a full web browser desktop via KasmVNC — no VNC client needed, no Java in the browser.<br>
Ships with a modern dark interface out of the box. UI language selectable from the Unraid template dropdown.<br>
Auto-installs and self-updates JDownloader on first container start.
</p>

<br>

## Features

- **KasmVNC web desktop** — open `http://your-unraid:3000` and JDownloader is ready
- **Modern dark interface** — enabled by default, togglable via `JD_DARK_MODE`
- **Selectable UI language** — 17 languages via `JD_LANG` dropdown in the Unraid template
- **Auto-install** — downloads and sets up JDownloader 2 on first start, no manual setup
- **Self-updating** — JDownloader updates itself on start as it normally does
- **Persistent** — all config, links, and session state survive container restarts in `/config`
- **Download volume** — separate `/downloads` mount keeps data separate from config

<br>

## Unraid Community Applications

Install via the CA template — search for **JDownloader** in Community Applications, or add the template URL directly:

```
https://raw.githubusercontent.com/junkerderprovinz/jdownloader/main/templates/jdownloader.xml
```

<br>

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `JD_LANG` | `de` | UI language: `de`, `en`, `fr`, `es`, `it`, `pt`, `nl`, `pl`, `cs`, `sk`, `hu`, `ro`, `ru`, `tr`, `ja`, `ko`, `zh` |
| `JD_DARK_MODE` | `true` | `true` = modern dark interface, `false` = light interface |
| `PUID` | `99` | User ID (nobody on Unraid) |
| `PGID` | `100` | Group ID (users on Unraid) |
| `TZ` | `Europe/Vienna` | Timezone |
| `CUSTOM_USER` | _(empty)_ | KasmVNC username — leave empty for no auth |
| `PASSWORD` | _(empty)_ | KasmVNC password — leave empty for no auth |
| `TITLE` | `JDownloader 2` | Browser tab title |
| `UMASK` | `022` | File permission mask |

<br>

## How It Works

The image is built on [`ghcr.io/linuxserver/baseimage-kasmvnc`](https://github.com/linuxserver/docker-baseimage-kasmvnc) and uses s6-overlay for service management.

On every container start:
1. **init-jdownloader** runs: installs JDownloader on first start, seeds language and theme configs
2. **KasmVNC** starts the web desktop
3. **autostart** launches JDownloader inside the desktop session

JDownloader stores all its data in `/config/JDownloader/` — this is part of the `/config` volume and persists across container updates.

<br>

## Support this project

If this image saves you time or a debug night, consider buying me a coffee:

<p align="center">
  <a href="https://buymeacoffee.com/junkerderprovinz">
    <img src=".github/assets/button-buy-me-a-coffee.svg" alt="Buy me a coffee" width="220">
  </a>
</p>
