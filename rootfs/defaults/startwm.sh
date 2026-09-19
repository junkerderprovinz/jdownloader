#!/usr/bin/env bash
# Replaces the Selkies base's /defaults/startwm.sh, which sends the whole desktop
# session to /dev/null. That would swallow everything autostart writes (the launch
# loop, the theme healer, the [jd-dialog-agent] lines and the READY banner), and the
# CI smoke gate could not see the JVM launch line. Otherwise it matches the base
# script, the Nvidia/zink block included.

# Enable Nvidia GPU support if detected
if which nvidia-smi > /dev/null 2>&1 && ls -A /dev/dri 2>/dev/null && [ "${DISABLE_ZINK}" == "false" ]; then
  export LIBGL_KOPPER_DRI2=1
  export MESA_LOADER_DRIVER_OVERRIDE=zink
  export GALLIUM_DRIVER=zink
fi

# The session keeps the service's stdio, so its output lands in the docker log.
exec dbus-launch --exit-with-session /usr/bin/openbox-session
