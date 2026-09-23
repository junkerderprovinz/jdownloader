# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
#
# JDownloader 2 for Unraid on the LinuxServer Selkies base image.
# https://github.com/junkerderprovinz/jdownloader
# AGPL-3.0-only for this wrapper; JDownloader 2 has its own licence.

# The Selkies base breaks compatibility between flavors, so the flavor is pinned.
# ubunturesolute is Ubuntu 26.04 with Selkies 2.0.
ARG BASE_TAG=ubunturesolute@sha256:6cfa54196b6e0dade64f5e51517fd12c4275ceda7519c0e18ad168cb4508c050

# The agent clicks through the first-run and update installer dialogs, which JD
# forces whenever the GUI is visible (UpdateController) and no config can suppress.
# It also enforces the dark chrome and guards two AppWork/jsyntaxpane NPEs that fire
# under FlatLaf and break the Event Scripter editor; those bytecode guards need ASM,
# shaded into the agent jar.
FROM eclipse-temurin:25.0.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS agent-builder
WORKDIR /build
# ASM (BSD-3-Clause) is pinned and checked against its SHA-256, so a swapped
# artifact fails the build.
ADD https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar /build/asm.jar
COPY agent/ /build/
# JD runs on the Java 21 runtime, so --release 21 keeps a newer build JDK from
# producing class files that runtime refuses (UnsupportedClassVersionError). ASM is
# unpacked into the jar so the -javaagent is self-contained; JD loads its own ASM
# through a separate launcher loader, so the two do not collide.
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
LABEL org.opencontainers.image.description="JDownloader 2 für Unraid: schlanke, moderne Dark-Mode-GUI (komplettes monochromes Carbon #161616, nicht nur die Menüleiste) auf Selkies, Multi-Language"
LABEL org.opencontainers.image.source="https://github.com/junkerderprovinz/jdownloader"
LABEL org.opencontainers.image.licenses="AGPL-3.0-only"
LABEL org.opencontainers.image.vendor="junkerderprovinz"

# TITLE feeds the PWA manifest and SELKIES_UI_TITLE the tab and sidebar title
# of the Selkies client; this base needs both.
#
# Selkies turns basic auth on by default and will not start without a password,
# so SELKIES_ENABLE_BASIC_AUTH=false keeps a container without one free of a
# login. Selkies listens on localhost only, so a real CUSTOM_USER/PASSWORD is
# enforced by nginx, the one reachable entry point.
#
# MAX_RES has no default here. The X server allocates its whole framebuffer up
# front at about 4 bytes per pixel, so the base's 15360x8640 costs 530 MB
# before anything else runs (1.19 GiB measured for the whole container). The
# template offers a preset dropdown (MAX_RES) and a free field (MAX_RES_CUSTOM)
# that wins, and init-screen-size settles the two before svc-xorg reads them.
#
# Unlike the sibling Selkies images, RESTART_APP stays unset: autostart
# supervises JD itself, with a fast-exit counter, a capped backoff and a pause
# while JD relaunches for an update, so the base watchdog would race it for the
# same process. PIXELFLUX_WAYLAND stays unset because JD's window and agent
# handling is built on X11.
ENV TITLE="JDownloader 2" \
    SELKIES_UI_TITLE="JDownloader 2" \
    SELKIES_ENABLE_BASIC_AUTH="false"

# Swing takes its scale once, when the JVM starts, and cannot follow the DPI
# Selkies hands each browser, so a HiDPI browser streaming in physical pixels
# would show JD at half size. Streaming every browser at its CSS size keeps JD
# the same size on any display. HiDPI can still be switched on per browser in
# the Selkies sidebar; the DPI stays at 96 because JD would not follow a higher
# one.
ENV SELKIES_USE_CSS_SCALING="true" \
    SELKIES_SCALING_DPI="96"

RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        # A full JRE, since the JD GUI needs AWT and Swing
        openjdk-21-jre \
        wget ca-certificates \
        # JD needs ffmpeg and ffprobe to mux DASH streams (YouTube serves video and
        # audio separately). On Linux JD fetches no ffmpeg build of its own, so
        # without a system binary the streams stay apart and JD opens its "FFmpeg
        # missing" dialog. 10-jdownloader-setup writes the path into FFmpegSetup.
        ffmpeg \
        # Java renders text through fontconfig
        fontconfig \
        fonts-noto fonts-noto-color-emoji \
        fonts-dejavu fonts-dejavu-core fonts-dejavu-extra \
        fonts-liberation fonts-liberation2 \
        fonts-hack \
        locales coreutils \
        # openbox-xdg-autostart needs PyXDG
        python3-xdg; \
    # Build the font cache so Java finds the fonts on the first start
    fc-cache -f -v >/dev/null 2>&1 || true; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*


# Firefox for JD's captcha flow, off by default. Nothing launches it and it is not
# JD's URL handler unless JD_ENABLE_BROWSER=true, in which case 10-jdownloader-setup
# wires it up at runtime (mimeapps default, XDG_CURRENT_DESKTOP, BROWSER). With it
# on, JD's "solve in browser" flow for reCAPTCHA, hCaptcha and Turnstile opens on
# the Selkies desktop and is solved from the container's IP, the same IP the
# download uses, since the tokens are bound to it. JD's built-in JAC still solves
# classic image captchas, so most users never need this.
#
# The packages come from Mozilla's own apt repo (amd64 and arm64), because Ubuntu's
# "firefox" package is a Snap stub and Snaps do not run inside containers.
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
    # JD opens links through xdg-open, gio and this .desktop Exec. ff-launch moves
    # Firefox's stdio off JD's ProcessBuilder pipe, so Firefox is not killed by
    # SIGPIPE when JD reaps xdg-open. DBusActivatable=false makes gio honour Exec
    # instead of activating Firefox over D-Bus and bypassing the wrapper.
    sed -i -E 's#^Exec=(/usr/lib/firefox/)?firefox#Exec=/usr/local/bin/ff-launch#' \
        /usr/share/applications/firefox.desktop; \
    if grep -q '^DBusActivatable' /usr/share/applications/firefox.desktop; then \
        sed -i 's/^DBusActivatable=.*/DBusActivatable=false/' /usr/share/applications/firefox.desktop; \
    else \
        echo 'DBusActivatable=false' >> /usr/share/applications/firefox.desktop; \
    fi; \
    # Without systemd, dbus-daemon cannot exec these services and logs "Activated
    # service '...' failed: Permission denied" on every link click.
    for svc in login1 timedate1 hostname1 locale1 network1 systemd1; do \
        rm -f "/usr/share/dbus-1/system-services/org.freedesktop.${svc}.service"; \
    done; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Keeps an enabled Firefox from starting crash-reporter background tasks on the
# GPU-less Xvfb display.
ENV MOZ_CRASHREPORTER_DISABLE=1


COPY rootfs/ /

# rootfs/ adds svc-xorg/dependencies.d/init-screen-size so our oneshot settles
# MAX_RES before Xvfb reads it. If a base update renamed that service, the COPY
# above would create svc-xorg as a directory with a dependency and no `type` file.
# s6-rc-compile would then abort in stage 2 and every container would exit at
# boot while the build stays green, so the failure would only show up in users'
# logs. Checking for the base's own `type` file makes it a build error instead.
RUN set -eux; \
    t=/etc/s6-overlay/s6-rc.d/svc-xorg/type; \
    [ -f "$t" ] || { echo "ERROR: $t missing: the selkies base renamed or dropped svc-xorg; re-point rootfs/etc/s6-overlay/s6-rc.d/svc-xorg/dependencies.d/init-screen-size at the new service"; exit 1; }; \
    echo "jdownloader: screen-size oneshot ordered before svc-xorg"

# The banner's source is .github/assets/banner-raw.txt. tr strips the Windows CRs
# and is byte-safe for the block characters.
COPY .github/assets/banner-raw.txt /usr/local/share/banner-raw.txt
RUN tr -d '\r' < /usr/local/share/banner-raw.txt > /usr/local/share/banner.txt

# Keeps our banner (print-banner.sh) the only branding in the init log. The
# linuxserver.io logo comes from init-adduser's `branding` file, which is emptied,
# and the "To support LSIO projects visit / donate" lines are echoed by
# init-adduser/run, which loses those two lines. The GID/UID block stays because it
# confirms the applied PUID/PGID; its echo stays valid because the donate lines sit
# between the opening `echo '` and the closing quote.
RUN set -eux; \
    : > /etc/s6-overlay/s6-rc.d/init-adduser/branding 2>/dev/null || true; \
    run=/etc/s6-overlay/s6-rc.d/init-adduser/run; \
    if [ -f "$run" ]; then \
        sed -i -e '/To support LSIO projects visit:/d' -e '\#linuxserver\.io/donate#d' "$run"; \
    fi

# autostart loads the agent through JAVA_TOOL_OPTIONS.
COPY --from=agent-builder /build/jd-dialog-agent.jar /opt/JDownloader/jd-dialog-agent.jar

RUN chmod +x \
    /usr/local/bin/ff-launch \
    /usr/local/bin/selkies-resolution.sh \
    /etc/s6-overlay/s6-rc.d/init-screen-size/run \
    /etc/s6-overlay/s6-rc.d/init-dpi/run \
    /usr/local/bin/jdownloader-language.sh \
    /usr/local/bin/jdownloader-theme.sh \
    /usr/local/bin/jdownloader-downloaddir.sh \
    /usr/local/bin/disable-tray.py \
    /usr/local/bin/jdownloader-noads.py \
    /usr/local/bin/kill-tray-extension.py \
    /usr/local/bin/print-banner.sh \
    /etc/cont-init.d/10-jdownloader-setup \
    /etc/s6-overlay/s6-rc.d/init-jdownloader/run \
    /etc/s6-overlay/s6-rc.d/svc-de/finish \
    /defaults/autostart \
    /defaults/startwm.sh

# init-nginx copies /usr/share/selkies/www/icon.png to favicon.ico and icon.png in
# the served web root on every start and builds the PWA manifest around ${TITLE},
# so replacing that one PNG brands the whole web UI. The check fails the build if
# the base moves the file, so CI and the weekly rebuild catch it.
COPY .github/assets/icon.png /usr/local/share/jdownloader-icon.png
RUN set -eux; \
    dst=/usr/share/selkies/www/icon.png; \
    [ -f "$dst" ] || { echo "ERROR: $dst missing: the selkies base layout changed, update the branding override"; exit 1; }; \
    cp /usr/local/share/jdownloader-icon.png "$dst"; \
    echo "jdownloader: branded selkies icon at $dst"

# JD writes its settings only in its JVM shutdown hook, which needs longer than
# s6's default grace time of 3 s; cut short, it loses settings such as hidden
# columns. svc-de's finish script sends SIGTERM to the JVM and waits for it.
ENV S6_KILL_GRACETIME=30000 \
    S6_SERVICES_GRACETIME=30000

# Defaults the Unraid template can override:
# JD_LANG       UI language as an ISO code (de, en, fr, ...)
# JD_THEME      Dark (Carbon #161616) or Light
# JD_SELFUPDATE false turns off JD's self-update checks. The same channel delivers
#               the hoster plugins, which go stale within weeks.
# JD_INST_DIR   install path, only worth changing for debugging
# JD_UI_SCALE   optional Swing scale such as 1.5 or 2, empty for 1x. The GUI renders
#               larger at full pixel density, so text stays sharp where browser
#               zoom would blur it by scaling up the Selkies H.264 stream.
ENV JD_LANG=en \
    JD_THEME=Dark \
    JD_SELFUPDATE=true \
    JD_INST_DIR=/config/JDownloader \
    JD_UI_SCALE= \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# CI passes the commit so users can check what their image was built from with
# `docker exec jdownloader cat /etc/jdownloader-build`. This is the last layer
# because BUILD_SHA changes on every commit and would bust the cache of every
# layer after it.
ARG BUILD_SHA=dev
ARG BUILD_DATE=unknown
RUN echo "sha=${BUILD_SHA}"   >  /etc/jdownloader-build && \
    echo "date=${BUILD_DATE}" >> /etc/jdownloader-build

# The base image exposes 3000 (HTTP) and 3001 (HTTPS).
