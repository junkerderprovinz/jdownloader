#!/usr/bin/env bash
# Overrides the Selkies base image's /defaults/startwm.sh for ONE reason: the
# base redirects the whole desktop session to /dev/null (`> /dev/null 2>&1`),
# which swallows every line our autostart writes — the JD launcher loop, the
# theme healer, the [jd-dialog-agent] output and the READY banner would never
# reach the container log (and the CI smoke gate could not see the JVM launch
# line). Identical to the base script otherwise, incl. the Nvidia/zink block.

# Enable Nvidia GPU support if detected
if which nvidia-smi > /dev/null 2>&1 && ls -A /dev/dri 2>/dev/null && [ "${DISABLE_ZINK}" == "false" ]; then
  export LIBGL_KOPPER_DRI2=1
  export MESA_LOADER_DRIVER_OVERRIDE=zink
  export GALLIUM_DRIVER=zink
fi

# Start DE — output stays on the service's stdio so it lands in the docker log.
exec dbus-launch --exit-with-session /usr/bin/openbox-session
