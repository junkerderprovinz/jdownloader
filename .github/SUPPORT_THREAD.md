<!--
Template for the Unraid Community Applications support thread.
Create it at https://forums.unraid.net (Docker Containers board), then point the
template's <Support> at the thread URL. Title format matches the sister apps.
-->

# Title

[Support] junkerderprovinz - jdownloader

# Body

**JDownloader** runs the full JDownloader 2 download manager in your browser on
Unraid — with a clean, **sleek**, fully **dark** UI out of the box. The whole interface
(download list, link grabber *and* settings) renders in a sleek monochrome IBM Carbon
(`#161616`) dark theme over a hardware-accelerated Selkies web desktop with **full
two-way browser clipboard**. Nothing to set up: it installs and themes itself on first start.

**Links**
- Source: https://github.com/junkerderprovinz/jdownloader
- Image: `ghcr.io/junkerderprovinz/jdownloader:latest` (amd64 + arm64) · also `junkerderprovinz/jdownloader` on Docker Hub
- Changelog: https://github.com/junkerderprovinz/jdownloader/releases

**Features** (what sets it apart first)
- 🌑 **Complete, sleek dark UI** — monochrome IBM Carbon `#161616` across the *whole* interface (download list, link grabber *and* settings), not just the menu bar; the sleek dark JDownloader you won't find anywhere else
- 📋 **Full clipboard support** — over HTTPS the browser clipboard works **both ways**: copy a link on your PC and paste it straight into JDownloader, and copy text back out
- 🧊 **Turnkey & self-healing** — auto-installs JDownloader, auto-confirms its prompts, and the theme self-heals after JD's own updates
- 💾 **Update-safe** — config, links and session state live in `/config`; even hidden columns and layout survive restarts
- 🖥️ Selkies web desktop — hardware-accelerated rendering, native file upload/download, high-DPI
- 🔗 My.JDownloader-ready — pair it and manage downloads remotely like any JD install
- 🧩 One container, amd64 + arm64 — built on LinuxServer's `baseimage-selkies`

**Installation**
Search "JDownloader" in Community Applications and click Install. Map the **Config**
(`/config`) and **Downloads** (`/downloads`) directories and keep the **HTTPS** WebUI
port (`3001`, needed for the browser clipboard). Apply, then open the container **log**:
on the first start JDownloader installs and themes itself, so wait for the
`JDOWNLOADER IS READY` banner before opening the WebUI — and don't restart during the install.

**Configuration (key variables)**
- `JD_THEME` — `Dark` (monochrome Carbon `#161616`) or `Light`
- WebUI ports — `3001` HTTPS (clipboard) and `3000` HTTP
- `CUSTOM_USER` / `PASSWORD` — optional WebUI login; leave empty for none
- `PUID` / `PGID` — user / group IDs (default `99` / `100` on Unraid)
- `TZ` — container timezone

**Support**
Reply here, or open an issue: https://github.com/junkerderprovinz/jdownloader/issues

If this saved you a setup evening, you can buy me a coffee:
https://buymeacoffee.com/junkerderprovinz
