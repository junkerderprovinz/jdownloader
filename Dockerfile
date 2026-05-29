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

# Strip Windows CR and copy banner (tr is byte-safe, no locale issues with block chars).
# The old sed 's/â/█/g' ran in POSIX locale and corrupted block-art bytes (E2 prefix).
RUN tr -d '\r' < /usr/local/share/banner-raw.txt > /usr/local/share/banner.txt

# Download FlatLaf and patch FlatDarkLaf.properties with Breeze Dark colours.
# Result: FLATLAF_DARK visually = JD Plain Dark (no install dialog, no extra step).
RUN wget -q -O /tmp/flatlaf-orig.jar \
        "https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7/flatlaf-3.7.jar" && \
    python3 /usr/local/bin/patch-flatlaf.py \
        /tmp/flatlaf-orig.jar /opt/JDownloader/flatlaf.jar && \
    rm /tmp/flatlaf-orig.jar

RUN chmod +x \
    /usr/local/bin/jdownloader-language.sh \
    /usr/local/bin/jdownloader-theme.sh \
    /usr/local/bin/jdownloader-create-dark-theme.py \
    /usr/local/bin/patch-flatlaf.py \
    /usr/local/bin/seed-flatlaf.py \
    /usr/local/bin/disable-tray.py \
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
