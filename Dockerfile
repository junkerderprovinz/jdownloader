# syntax=docker/dockerfile:1.24
#
# JDownloader 2 for Unraid – community edition (KasmVNC)
# -------------------------------------------------------
# Built on the LinuxServer KasmVNC base image for a smooth,
# hardware-accelerated, web-native Linux desktop.
#
# Features:
#   * JDownloader 2 (self-updating Java download manager)
#   * Java 21 JRE (full AWT/Swing, not headless)
#   * Auto-install JDownloader on first start into /config/JDownloader
#   * Selectable theme via JD_THEME (JD_Plain_Dark = dark, JD_Plain = light, JDDEFAULT)
#
# Repository:  https://github.com/junkerderprovinz/jdownloader
# License:     MIT (this wrapper) – JDownloader 2 has its own license
#
ARG BASE_TAG=ubuntunoble

# ---------------------------------------------------------------------------
# Builder stage — compiles the tiny dialog-confirm agent. JD FORCES its first-run /
# update installer dialogs whenever the GUI is visible (UpdateController), so no
# config can suppress them; this agent just auto-clicks them. It does NOT touch
# colours (those come from JD's native colorfor* config).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS agent-builder
WORKDIR /build
COPY agent/ /build/
RUN set -eux; \
    mkdir -p out; \
    find src -name '*.java' > sources.txt; \
    javac -d out @sources.txt; \
    jar cfm jd-dialog-agent.jar manifest.mf -C out .

FROM ghcr.io/linuxserver/baseimage-kasmvnc:${BASE_TAG}

LABEL maintainer="junkerderprovinz"
LABEL org.opencontainers.image.title="jdownloader"
LABEL org.opencontainers.image.description="JDownloader 2 für Unraid — schlanke, moderne Dark-Mode-GUI (komplettes KDE Breeze Dark, nicht nur die Menüleiste) auf KasmVNC, Multi-Language"
LABEL org.opencontainers.image.source="https://github.com/junkerderprovinz/jdownloader"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.vendor="junkerderprovinz"

ENV TITLE="JDownloader 2"

# Build provenance — passed from CI, written to image so users can verify
# exactly which commit their running image was built from.
# Inspect at runtime: `docker exec jdownloader cat /etc/jdownloader-build`
ARG BUILD_SHA=dev
ARG BUILD_DATE=unknown
RUN echo "sha=${BUILD_SHA}"   >  /etc/jdownloader-build && \
    echo "date=${BUILD_DATE}" >> /etc/jdownloader-build

# ---------------------------------------------------------------------------
# Java 21 + Basis-Tools
# ---------------------------------------------------------------------------
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        # Java – volles JRE (AWT/Swing für JDownloader-GUI erforderlich)
        openjdk-21-jre \
        # Download-Tools für Installer
        wget ca-certificates \
        # ASCII-Banner im Init-Log
        figlet \
        # Font-Support (Java rendert Schrift über fontconfig)
        fontconfig \
        fonts-noto fonts-noto-color-emoji \
        fonts-dejavu fonts-dejavu-core fonts-dejavu-extra \
        fonts-liberation fonts-liberation2 \
        fonts-hack \
        # Locale
        locales coreutils \
        # openbox-xdg-autostart braucht PyXDG
        python3-xdg; \
    # Font-Cache aufbauen damit Java die Fonts beim ersten Start sofort findet
    fc-cache -f -v >/dev/null 2>&1 || true; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*


# ---------------------------------------------------------------------------
# Skeleton-Configs + s6-overlay init scripts
# ---------------------------------------------------------------------------
COPY rootfs/ /

# Banner: single source at .github/assets/banner-raw.txt. Strip Windows CR
# (tr is byte-safe, no locale issues with the block characters used).
COPY .github/assets/banner-raw.txt /usr/local/share/banner-raw.txt
RUN tr -d '\r' < /usr/local/share/banner-raw.txt > /usr/local/share/banner.txt

# Suppress LSIO base-image branding: the ASCII art "linuxserver.io" block plus
# the donate URL are printed by init-adduser via this file. Emptying it leaves
# the rest of LSIO's GID/UID setup intact.
RUN : > /etc/s6-overlay/s6-rc.d/init-adduser/branding 2>/dev/null || \
    true

# Dialog-confirm agent (compiled in the builder stage); loaded via JAVA_TOOL_OPTIONS
# in autostart so it auto-confirms JD's forced installer dialogs.
COPY --from=agent-builder /build/jd-dialog-agent.jar /opt/JDownloader/jd-dialog-agent.jar

RUN chmod +x \
    /usr/local/bin/jdownloader-language.sh \
    /usr/local/bin/jdownloader-theme.sh \
    /usr/local/bin/disable-tray.py \
    /usr/local/bin/kill-tray-extension.py \
    /usr/local/bin/print-banner.sh \
    /etc/cont-init.d/10-jdownloader-setup \
    /etc/s6-overlay/s6-rc.d/init-jdownloader/run \
    /defaults/autostart

# ---------------------------------------------------------------------------
# Browser-tab favicon
# ---------------------------------------------------------------------------
# The web UI is served by the "kclient" wrapper (Node) on top of KasmVNC. The
# browser tab favicon is its /favicon.ico — the page has no working <link rel=icon>
# (only an apple-touch-icon that 404s), so the browser falls back to /favicon.ico,
# i.e. the file /kclient/public/favicon.ico. We overwrite the real kclient favicon
# (+ the kclient app icon.png, served at /public/icon.png, + the inner client icons
# for good measure). The build fails loudly if the kclient favicon is gone (layout
# changed), so CI / the weekly rebuild surfaces the regression. (Same fix as krusader.)
COPY .github/assets/icon.png    /usr/local/share/jdownloader-icon.png
COPY .github/assets/favicon.ico /usr/local/share/jdownloader-favicon.ico
RUN set -eux; \
    fav=/kclient/public/favicon.ico; \
    [ -f "$fav" ] || { echo "ERROR: $fav missing — kclient layout changed, update the favicon override"; exit 1; }; \
    cp /usr/local/share/jdownloader-favicon.ico "$fav"; \
    echo "jdownloader: overwrote tab favicon $fav"; \
    if [ -f /kclient/public/icon.png ]; then \
        cp /usr/local/share/jdownloader-icon.png /kclient/public/icon.png; \
        echo "jdownloader: overwrote /kclient/public/icon.png"; \
    fi; \
    n=0; \
    for dest in /usr/share/kasmvnc/www/app/images/icons/368_kasm_logo_only_*.png; do \
        [ -f "$dest" ] || continue; \
        cp /usr/local/share/jdownloader-icon.png "$dest"; \
        n=$((n + 1)); \
    done; \
    echo "jdownloader: also overwrote $n inner KasmVNC client icon(s)"

# ---------------------------------------------------------------------------
# Standard-ENV (durch Unraid-Template überschreibbar)
# ---------------------------------------------------------------------------
# JD_LANG     – UI-Sprache: ISO-Code (de, en, fr, ...)
# JD_THEME    – UI-Theme: JD_Plain_Dark | JD_Plain | JDDEFAULT | ...
# JD_INST_DIR – Installations-Pfad (nicht ändern außer für Debugging)
ENV JD_LANG=de \
    JD_THEME=JD_Plain_Dark \
    JD_INST_DIR=/config/JDownloader \
    LANG=de_DE.UTF-8 \
    LANGUAGE=de_DE:de:en \
    LC_ALL=de_DE.UTF-8

# Ports werden vom Baseimage freigegeben (3000/HTTP, 3001/HTTPS).
