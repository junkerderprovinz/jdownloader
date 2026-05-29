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
# Builder stage — compiles the JVM agent that forces dark UIManager colours
# after JD's LAF has initialized. This bypasses JD's internal colour logic
# entirely — colours are pushed at the Swing UIManager layer directly.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS agent-builder
WORKDIR /build
COPY agent/ /build/
RUN set -eux; \
    mkdir -p out; \
    find src -name '*.java' > sources.txt; \
    javac -d out @sources.txt; \
    jar cfm jd-dark-agent.jar manifest.mf -C out .

FROM ghcr.io/linuxserver/baseimage-kasmvnc:${BASE_TAG}

LABEL maintainer="junkerderprovinz"
LABEL org.opencontainers.image.title="jdownloader"
LABEL org.opencontainers.image.description="JDownloader 2 für Unraid mit KasmVNC, Dark Mode und Multi-Language-UI"
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
COPY scripts/ /scripts/

# Banner: single source of truth at .github/assets/banner-raw.txt — edit there.
# COPY rootfs/ also placed a stale copy under /usr/local/share/; we overwrite
# it with the canonical asset and strip Windows CR (tr is byte-safe, no locale
# issues with the block characters).
COPY .github/assets/banner-raw.txt /usr/local/share/banner-raw.txt
RUN tr -d '\r' < /usr/local/share/banner-raw.txt > /usr/local/share/banner.txt

# Suppress LSIO base-image branding: the ASCII art "linuxserver.io" block plus
# the donate URL are printed by init-adduser via this file. Emptying it leaves
# the rest of LSIO's GID/UID setup intact.
RUN : > /etc/s6-overlay/s6-rc.d/init-adduser/branding 2>/dev/null || \
    true

# Download FlatLaf and patch FlatDarkLaf.properties with Breeze Dark colours.
# Result: FLATLAF_DARK visually = JD Plain Dark (no install dialog, no extra step).
# Bake in the JVM dark-theme agent built in the agent-builder stage.
COPY --from=agent-builder /build/jd-dark-agent.jar /opt/JDownloader/jd-dark-agent.jar

RUN wget -q -O /tmp/flatlaf-orig.jar \
        "https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7/flatlaf-3.7.jar" && \
    python3 /usr/local/bin/patch-flatlaf.py \
        /tmp/flatlaf-orig.jar /opt/JDownloader/flatlaf.jar && \
    rm /tmp/flatlaf-orig.jar

# ---------------------------------------------------------------------------
# Pre-install JDownloader 2 into a snapshot directory so the user's first
# container start is a copy operation — no bootstrap, no install dialogs,
# no tray popup. Xvfb provides a virtual display; xdotool dismisses any
# popup that appears during install.
#
# The resulting /opt/JDownloader/snapshot/ is restored to /config/JDownloader
# by cont-init.d/10-jdownloader-setup on first start if the volume is empty.
# ---------------------------------------------------------------------------
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        xvfb xdotool; \
    chmod +x /usr/local/bin/seed-flatlaf.py \
             /usr/local/bin/disable-tray.py \
             /usr/local/bin/kill-tray-extension.py \
             /scripts/pre-install-jd.sh; \
    /scripts/pre-install-jd.sh; \
    mkdir -p /opt/JDownloader/snapshot; \
    cp -a /tmp/JDownloader/. /opt/JDownloader/snapshot/; \
    rm -rf /tmp/JDownloader; \
    apt-get purge -y xvfb xdotool; \
    apt-get autoremove -y; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

RUN chmod +x \
    /usr/local/bin/jdownloader-language.sh \
    /usr/local/bin/jdownloader-theme.sh \
    /usr/local/bin/jdownloader-create-dark-theme.py \
    /usr/local/bin/patch-flatlaf.py \
    /usr/local/bin/seed-flatlaf.py \
    /usr/local/bin/disable-tray.py \
    /usr/local/bin/overlay-dark-colors.py \
    /usr/local/bin/kill-tray-extension.py \
    /usr/local/bin/print-banner.sh \
    /etc/cont-init.d/10-jdownloader-setup \
    /etc/s6-overlay/s6-rc.d/init-jdownloader/run \
    /defaults/autostart

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
