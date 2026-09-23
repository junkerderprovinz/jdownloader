#!/usr/bin/env bash
# Replaces the Selkies base's /defaults/startwm.sh, which sends the whole desktop
# session to /dev/null. That would swallow everything autostart writes (the launch
# loop, the theme healer, the [jd-dialog-agent] lines and the READY banner), and the
# CI smoke gate could not see the JVM launch line.

# The session keeps the service's stdio, so its output lands in the docker log.
exec dbus-launch --exit-with-session /usr/bin/openbox-session
