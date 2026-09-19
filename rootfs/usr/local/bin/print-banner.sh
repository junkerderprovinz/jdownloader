#!/usr/bin/env bash
# Usage: print-banner.sh <container-name> <subtitle>
# Prints the init-log banner shared by the junkerderprovinz containers.

CONTAINER="${1:-Container}"
SUBTITLE="${2:-}"
BANNER_FILE="/usr/local/share/banner.txt"

echo ""

if [ -f "${BANNER_FILE}" ]; then
    cat "${BANNER_FILE}"
    # The banner file has no trailing newline.
    echo ""
    echo ""
else
    echo ""
    echo "  Junker der Provinz"
    echo ""
fi

# The caller's status line follows the blank line printed last, so banner,
# title and status close the container's boot log together.
if [ -n "${SUBTITLE}" ]; then
    printf '  %s · %s\n' "${CONTAINER}" "${SUBTITLE}"
else
    printf '  %s\n' "${CONTAINER}"
fi
echo ""
