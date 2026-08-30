# JDownloader (Unraid container) task runner — run `just` to list recipes.
# Recipes use sh (Git Bash on Windows).
# This is a container repo: no Go, no Node — the deliverable is the Docker image.

set shell := ["sh", "-cu"]

# List available recipes
default:
    @just --list

# Build the image locally (amd64 smoke tag, same as the CI gate)
build:
    docker build -t jdownloader:smoke-amd64 .

# Build + boot the container locally (Ctrl-C to stop; WebUI on 3000/HTTP, 3001/HTTPS)
smoke: build
    docker run --rm -it --name jd-smoke -p 3000:3000 -p 3001:3001 jdownloader:smoke-amd64

# Lint the Dockerfile (same ignores as CI: DL3008 apt pinning, DL3009 apt lists)
hadolint:
    hadolint Dockerfile --ignore DL3008 --ignore DL3009

# ShellCheck the init scripts (honor shebangs; no --shell override, like CI)
shellcheck:
    find rootfs -type f \( -name '*.sh' -o -path '*/cont-init.d/*' \
      -o -path '*/defaults/autostart' -o -path '*/s6-rc.d/*/run' \
      -o -path '*/s6-rc.d/*/finish' \) -print0 | xargs -0 shellcheck --severity=warning

# Static-check the Python helpers
pyflakes:
    find rootfs -name '*.py' -print0 | xargs -0 python3 -m pyflakes

# Validate every XML file (Unraid template etc.)
xml:
    find . -name '*.xml' -not -path './.git/*' -print0 | xargs -0 xmllint --noout

# Full pre-push chain: every lint CI runs
check: hadolint shellcheck pyflakes xml
    @echo "All lint checks passed."

# Secret-scan the working tree
secrets:
    gitleaks dir . --redact --no-banner

# Scaffold release notes for a version, e.g. `just notes 4.3.0`
notes version:
    printf '## v{{version}}\n\n### Added\n\n### Fixed\n\n### Changed\n' > .github/release-notes/v{{version}}.md
    @echo "Wrote .github/release-notes/v{{version}}.md — edit, then commit (NEVER tag without approval)."
