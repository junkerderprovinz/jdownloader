# syntax=docker/dockerfile:1.24
#
# JDownloader 2 for Unraid – community edition (Selkies)
# -------------------------------------------------------
# Built on the LinuxServer Selkies base image (successor of their EOL KasmVNC
# packaging) for a smooth, hardware-accelerated, web-native Linux desktop.
#
# Features:
#   * JDownloader 2 (self-updating Java download manager)
#   * Java 21 JRE (full AWT/Swing, not headless)
#   * Auto-install JDownloader on first start into /config/JDownloader
#   * Selectable theme via JD_THEME (Dark = Carbon #161616 monochrome, Light)
#
# Repository:  https://github.com/junkerderprovinz/jdownloader
# License:     MIT (this wrapper) – JDownloader 2 has its own license
#
# Flavor-PINNED on purpose: the Selkies base makes deliberate breaking changes
# between flavors; ubunturesolute = Ubuntu 25.10, same flavor as krusader.
ARG BASE_TAG=ubunturesolute

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

FROM ghcr.io/linuxserver/baseimage-selkies:${BASE_TAG}

LABEL maintainer="junkerderprovinz"
LABEL org.opencontainers.image.title="jdownloader"
LABEL org.opencontainers.image.description="JDownloader 2 für Unraid — schlanke, moderne Dark-Mode-GUI (komplettes monochromes Carbon #161616, nicht nur die Menüleiste) auf Selkies, Multi-Language"
LABEL org.opencontainers.image.source="https://github.com/junkerderprovinz/jdownloader"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.vendor="junkerderprovinz"

# TITLE feeds the PWA manifest; SELKIES_UI_TITLE is the visible tab/sidebar
# title of the Selkies web client — both must be set on this base.
#
# SELKIES_ENABLE_BASIC_AUTH=false: Selkies' server enables basic auth by DEFAULT
# with the well-known default credentials (ubuntu / mypasswd), which would pop a
# login on a container that never set a password — worse, an insecure default
# one. The KasmVNC base required no login unless CUSTOM_USER/PASSWORD were set,
# so we keep that: no login by default. The base's nginx would still turn a
# merely-SET (even empty) PASSWORD into a login, so the init-nologin oneshot
# drops an empty PASSWORD/CUSTOM_USER before nginx starts. Selkies binds to
# localhost only, so when a user DOES set a real CUSTOM_USER/PASSWORD the base's
# nginx enforces HTTP-basic-auth on the proxy (the single reachable entry
# point), exactly as before.
#
# NOTE deliberately UNSET: RESTART_APP (the base watchdog would fight our
# launcher loop + theme healer in autostart) and PIXELFLUX_WAYLAND (X11 mode is
# the default and is what JD's whole window/agent mechanic is built on).
ENV TITLE="JDownloader 2" \
    SELKIES_UI_TITLE="JDownloader 2" \
    SELKIES_ENABLE_BASIC_AUTH="false"

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

# Suppress LSIO base-image branding so OUR ASCII banner (print-banner.sh) is the only
# branding in the init log. Two sources: the "linuxserver.io" ASCII logo comes from the
# init-adduser `branding` file (emptied), and the "To support LSIO projects visit / donate"
# solicitation is echoed SEPARATELY inside init-adduser/run — strip those two lines from it.
# The GID/UID block is left intact (it confirms the applied PUID/PGID); its echo stays valid
# because the donate lines sit between the opening `echo '` and the closing quote.
RUN set -eux; \
    : > /etc/s6-overlay/s6-rc.d/init-adduser/branding 2>/dev/null || true; \
    run=/etc/s6-overlay/s6-rc.d/init-adduser/run; \
    if [ -f "$run" ]; then \
        sed -i -e '/To support LSIO projects visit:/d' -e '\#linuxserver\.io/donate#d' "$run"; \
    fi

# Dialog-confirm agent (compiled in the builder stage); loaded via JAVA_TOOL_OPTIONS
# in autostart so it auto-confirms JD's forced installer dialogs.
COPY --from=agent-builder /build/jd-dialog-agent.jar /opt/JDownloader/jd-dialog-agent.jar

RUN chmod +x \
    /usr/local/bin/jdownloader-language.sh \
    /usr/local/bin/jdownloader-theme.sh \
    /usr/local/bin/jdownloader-downloaddir.sh \
    /usr/local/bin/disable-tray.py \
    /usr/local/bin/jdownloader-noads.py \
    /usr/local/bin/kill-tray-extension.py \
    /usr/local/bin/print-banner.sh \
    /etc/cont-init.d/10-jdownloader-setup \
    /etc/s6-overlay/s6-rc.d/init-jdownloader/run \
    /etc/s6-overlay/s6-rc.d/init-nologin/run \
    /etc/s6-overlay/s6-rc.d/svc-de/finish \
    /defaults/autostart

# ---------------------------------------------------------------------------
# Browser-tab favicon / branding
# ---------------------------------------------------------------------------
# On the Selkies base the branding is a single file: init-nginx copies
# /usr/share/selkies/www/icon.png to favicon.ico + icon.png in the served web
# root on every start and writes the PWA manifest around ${TITLE}. Replacing
# that one PNG brands the whole web UI — the entire kclient/kasm multi-path
# surgery of the old base is gone. Fail loudly if the path moves (base layout
# change), so CI / the weekly rebuild surfaces the regression. (Same as krusader.)
COPY .github/assets/icon.png /usr/local/share/jdownloader-icon.png
RUN set -eux; \
    dst=/usr/share/selkies/www/icon.png; \
    [ -f "$dst" ] || { echo "ERROR: $dst missing — selkies base layout changed, update the branding override"; exit 1; }; \
    cp /usr/local/share/jdownloader-icon.png "$dst"; \
    echo "jdownloader: branded selkies icon at $dst"

# ---------------------------------------------------------------------------
# Graceful shutdown so JD can persist column layout etc.
# ---------------------------------------------------------------------------
# JD writes its settings only in its JVM shutdown hook. The s6 default kill-gracetime is
# 3 s — too short for that flush, so JD got SIGKILLed mid-save and hidden columns came back
# after a restart. The svc-de `finish` script SIGTERMs the JVM and waits; these gracetimes
# keep s6 from SIGKILLing before the save completes. (Shutdown-only — no effect on startup.)
ENV S6_KILL_GRACETIME=30000 \
    S6_SERVICES_GRACETIME=30000

# ---------------------------------------------------------------------------
# Standard-ENV (durch Unraid-Template überschreibbar)
# ---------------------------------------------------------------------------
# JD_LANG       – UI-Sprache: ISO-Code (de, en, fr, ...)
# JD_THEME      – UI-Theme: Dark (Carbon #161616) | Light
# JD_SELFUPDATE – true (Default) | false = JDs Self-Update-Checks deaktivieren
#                 (opt-in "frozen appliance"; Achtung: derselbe Kanal liefert die
#                 Hoster-Plugins — die veralten in Wochen)
# JD_INST_DIR   – Installations-Pfad (nicht ändern außer für Debugging)
ENV JD_LANG=en \
    JD_THEME=Dark \
    JD_SELFUPDATE=true \
    JD_INST_DIR=/config/JDownloader \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# ---------------------------------------------------------------------------
# Build provenance — passed from CI, written to image so users can verify
# exactly which commit their running image was built from.
# Inspect at runtime: `docker exec jdownloader cat /etc/jdownloader-build`
# Deliberately the LAST layer: BUILD_SHA changes on every commit, so an earlier
# placement would bust the build cache for all layers that follow it.
# ---------------------------------------------------------------------------
ARG BUILD_SHA=dev
ARG BUILD_DATE=unknown
RUN echo "sha=${BUILD_SHA}"   >  /etc/jdownloader-build && \
    echo "date=${BUILD_DATE}" >> /etc/jdownloader-build

# Ports werden vom Baseimage freigegeben (3000/HTTP, 3001/HTTPS).
