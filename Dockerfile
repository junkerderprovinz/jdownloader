# syntax=docker/dockerfile:1.25
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
# Builder stage — compiles the dialog-confirm + theme agent. JD FORCES its first-run /
# update installer dialogs whenever the GUI is visible (UpdateController), so no
# config can suppress them; this agent auto-clicks them, enforces the dark chrome, and
# (BUG 4) load-time bytecode-guards two latent AppWork/jsyntaxpane NPEs that only fire
# under FlatLaf and otherwise break the Event Scripter script editor. The bytecode
# guards need ASM, bundled (shaded) into the agent jar below.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jdk AS agent-builder
WORKDIR /build
# ASM (BSD-3-Clause) for the load-time bytecode guards. Pinned + SHA-256 verified so a
# supply-chain swap of the artifact fails the build (never build an unverified download).
ADD https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar /build/asm.jar
COPY agent/ /build/
# JDownloader runs on the Java 21 runtime (openjdk-21-jre below), so the agent's class
# files must target 21. Pin javac --release 21 so a newer build JDK (e.g. a Renovate
# bump of the temurin tag above) can't ship a higher-classfile agent the 21 runtime
# refuses to load (UnsupportedClassVersionError). ASM classes are unpacked into out/
# (shaded) so the -javaagent jar is self-contained; JD keeps its own ASM on a separate
# launcher loader, so there is no collision.
RUN set -eux; \
    echo "8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281  /build/asm.jar" > asm.jar.sha256; \
    sha256sum -c asm.jar.sha256; \
    mkdir -p out; \
    find src -name '*.java' > sources.txt; \
    javac --release 21 -cp asm.jar -d out @sources.txt; \
    jar xf asm.jar org/objectweb/asm; \
    jar cfm jd-dialog-agent.jar manifest.mf -C out . org

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
        # ffmpeg/ffprobe – JD braucht BEIDE, um DASH-Streams zu muxen (YouTube
        # liefert Video- und Audiospur getrennt). Auf Linux lädt JD KEINEN eigenen
        # ffmpeg-Build herunter, sondern erwartet ein System-Binary — ohne es
        # scheitert das YouTube-Muxing (Video + Audio bleiben getrennt liegen) und
        # JD öffnet stattdessen den "FFmpeg fehlt"-Installationsdialog. Der Pfad
        # wird beim Init in die FFmpegSetup-Config geschrieben (10-jdownloader-setup).
        ffmpeg \
        # Font-Support (Java rendert Schrift über fontconfig)
        fontconfig \
        fonts-noto fonts-noto-color-emoji \
        fonts-dejavu fonts-dejavu-core fonts-dejavu-extra \
        fonts-liberation fonts-liberation2 \
        fonts-hack \
        # Locale
        locales coreutils \
        # openbox-xdg-autostart braucht PyXDG
        python3-xdg \
        # picom: minimal compositor whose ONLY job is to round the pop-up dialog window corners
        # (xrender backend works on the GPU-less Xvfb display; config /defaults/picom.conf turns
        # off everything expensive so it stays out of the Selkies encoder's way)
        picom; \
    # Font-Cache aufbauen damit Java die Fonts beim ersten Start sofort findet
    fc-cache -f -v >/dev/null 2>&1 || true; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*


# ---------------------------------------------------------------------------
# Firefox — OPTIONAL in-container browser for JD's captcha flow (opt-in)
# ---------------------------------------------------------------------------
# Baked in but INERT by default: nothing launches Firefox and it is NOT wired as
# JD's URL handler unless JD_ENABLE_BROWSER=true (the wiring — mimeapps default +
# XDG_CURRENT_DESKTOP + BROWSER — is done at runtime in 10-jdownloader-setup, only
# when the switch is on). So a default container never runs a browser process; the
# only cost when off is the on-disk size. With it on, JD's "solve in browser" flow
# (reCAPTCHA/hCaptcha/Turnstile) opens on the Selkies desktop and is solved from the
# CONTAINER's IP — the same IP the download uses (tokens are IP-bound). Classic image
# captchas are still auto-solved by JD's built-in JAC, so most users never need this.
# (Firefox portion of community PR #2 by @ahmed-abdelrazek, reworked as opt-in.)
#
# Source is Mozilla's OFFICIAL apt repo packages.mozilla.org (amd64+arm64); Ubuntu's
# own "firefox" package is a Snap stub (Snaps don't run inside containers).
RUN set -eux; \
    install -d -m 0755 /etc/apt/keyrings; \
    wget -qO /etc/apt/keyrings/packages.mozilla.org.asc \
        https://packages.mozilla.org/apt/repo-signing-key.gpg; \
    echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
        > /etc/apt/sources.list.d/mozilla.list; \
    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' \
        > /etc/apt/preferences.d/mozilla; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        firefox \
        xdg-utils; \
    # Route the firefox .desktop launch through ff-launch (COPYed via rootfs/ below):
    # JD -> xdg-open -> gio -> this .desktop Exec. ff-launch redirects Firefox's stdio
    # off JD's ProcessBuilder pipe so Firefox is not SIGPIPE-killed when JD reaps
    # xdg-open. DBusActivatable=false forces gio to honor Exec (else it D-Bus-activates
    # Firefox, bypassing the wrapper).
    sed -i -E 's#^Exec=(/usr/lib/firefox/)?firefox#Exec=/usr/local/bin/ff-launch#' \
        /usr/share/applications/firefox.desktop; \
    if grep -q '^DBusActivatable' /usr/share/applications/firefox.desktop; then \
        sed -i 's/^DBusActivatable=.*/DBusActivatable=false/' /usr/share/applications/firefox.desktop; \
    else \
        echo 'DBusActivatable=false' >> /usr/share/applications/firefox.desktop; \
    fi; \
    # No systemd in the container -> dbus-daemon fails to exec these manifests and spams
    # "Activated service '...' failed: Permission denied" on every link click; drop them.
    for svc in login1 timedate1 hostname1 locale1 network1 systemd1; do \
        rm -f "/usr/share/dbus-1/system-services/org.freedesktop.${svc}.service"; \
    done; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Only ever affects a Firefox that actually runs (opt-in): no crash-reporter
# background tasks on the GPU-less Xvfb display.
ENV MOZ_CRASHREPORTER_DISABLE=1


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
    /usr/local/bin/ff-launch \
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
    /defaults/autostart \
    /defaults/startwm.sh

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
# JD_THEME      – UI-Theme: Dark (Carbon #161616) | Light | jd-highlighter (borderless, freely-accented dark)
# JD_ACCENT     – jd-highlighter accent, any hex (default #ffee00)
# JD_SELFUPDATE – true (Default) | false = JDs Self-Update-Checks deaktivieren
#                 (opt-in "frozen appliance"; Achtung: derselbe Kanal liefert die
#                 Hoster-Plugins — die veralten in Wochen)
# JD_INST_DIR   – Installations-Pfad (nicht ändern außer für Debugging)
ENV JD_LANG=en \
    JD_THEME=Dark \
    JD_ACCENT=#ffee00 \
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
