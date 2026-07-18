# JDownloader 2 for Unraid — Repo Guide

JDownloader 2 in the browser for Unraid: a self-installing Java download manager on the
LinuxServer **Selkies** base, pre-themed to a monochrome IBM Carbon `#161616` dark UI and
ad-free by default. This is a **container/wrapper repo** — no Go, no Node app; the deliverable
is a multi-arch Docker image (GHCR + Docker Hub) plus the Unraid template (published from the
separate `unraid-apps` feed).

## Layout

- `Dockerfile` — the whole build. Two stages: an `eclipse-temurin` **agent-builder** that
  compiles the tiny dialog-confirm Java agent, then the `baseimage-selkies` runtime with
  Java 21 JRE, ffmpeg, fonts and an opt-in Firefox.
- `agent/` — the `-javaagent` (`src/**/*.java` + `manifest.mf`). Auto-confirms JD's forced
  installer dialogs and registers the FlatLaf custom-defaults source. Compiled **inside** the
  Docker builder stage — there is no host Java build.
- `rootfs/` — everything COPYed into the image: s6-overlay init (`etc/s6-overlay/s6-rc.d/`,
  `etc/cont-init.d/10-jdownloader-setup`), the `defaults/autostart` launcher loop, shell +
  Python helpers under `usr/local/bin/`, and the seeded JD theme/icons under `opt/JDownloader/`.
- `.github/workflows/` — `build.yml` (smoke-gated build + push), `lint.yml`, `release.yml`,
  plus asset/experiment helpers (`generate-assets`, `theme-preview`, `geometry-probe`,
  `experiment-headless`, `registry-cleanup`).
- `.github/release-notes/vX.Y.Z.md` — one file per version; `release.yml` uses it as the
  GitHub release body. `.github/assets/` — banner/icon/screenshots. `analysis/` — design notes.

## Build / test / lint (run before every push)

There is no unit-test suite; correctness is proven by lint + a boot smoke test.

```sh
# Dockerfile lint — same ignores as CI (DL3008 apt pinning, DL3009 apt lists)
hadolint Dockerfile --ignore DL3008 --ignore DL3009

# ShellCheck the init scripts (honor shebangs; no --shell override, like CI)
find rootfs -type f \( -name '*.sh' -o -path '*/cont-init.d/*' \
  -o -path '*/defaults/autostart' -o -path '*/s6-rc.d/*/run' \
  -o -path '*/s6-rc.d/*/finish' \) -print0 | xargs -0 shellcheck --severity=warning

# Python helpers
find rootfs -name '*.py' -print0 | xargs -0 python3 -m pyflakes

# XML (Unraid template etc.)
find . -name '*.xml' -not -path './.git/*' -print0 | xargs -0 xmllint --noout

# Build + boot the image locally (the CI smoke gate, amd64)
docker build -t jdownloader:smoke-amd64 .
docker run -d --name jd -p 3000:3000 -p 3001:3001 jdownloader:smoke-amd64
```

`just check` runs the lint chain in one go (see `justfile`). The global pre-push hook runs
gitleaks + hadolint (gofmt is skipped — no `go.mod`); if it blocks, **fix the cause**, never
`--no-verify`.

## CI gates

- **Lint** (`lint.yml`) — hadolint (threshold `warning`, ignore DL3008/DL3009), shellcheck
  (`--severity=warning`), `xmllint --noout` on every XML, pyflakes on every `rootfs/**/*.py`.
- **Build & Push** (`build.yml`) — builds `jdownloader:smoke-amd64` (`load: true`), then a
  **boot smoke test**: the container must serve the Selkies WebUI (HTTP 3000 / HTTPS 3001)
  **and** JD's JVM must launch and **stay up** for 15s with no crash signature and no relaunch
  loop. Only then does it multi-arch build + push (amd64 + arm64) to GHCR (and Docker Hub when
  `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` are set) with SBOM + provenance attestations, followed
  by a **non-blocking Trivy** CVE scan (HIGH/CRITICAL, unfixed ignored) whose SARIF is uploaded
  to the Security tab. Trivy is report-only (`exit-code: "0"`) and never fails the build.
- Runs on push to `main`, on `v*.*.*` tags, weekly (Sunday 04:00 UTC), and on dispatch.

## Release (NEVER tag without explicit approval)

1. Write the changelog to `.github/release-notes/vX.Y.Z.md` (SemVer, 3-digit).
2. Commit + push; wait for **Lint** and **Build & Push** to go green.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` — the tag build publishes `:X.Y.Z / :X.Y / :X /
   :latest` to GHCR + Docker Hub, and `release.yml` creates the GitHub release from the notes
   file. Release **title = the version only** (`vX.Y.Z`), no repo-name heading.
4. Keep the `unraid-apps` template entry in sync if anything user-facing changed.

The image stamps its build SHA/date to `/etc/jdownloader-build` (`docker exec jdownloader cat
/etc/jdownloader-build`).

## Conventions / gotchas

- **Agent must target Java 21.** The runtime is `openjdk-21-jre`, so the agent is compiled with
  `javac --release 21` even though the build JDK is newer (Renovate bumps the temurin tag). A
  class-69 agent on the 21 runtime crash-loops the GUI (`UnsupportedClassVersionError`). The
  smoke gate exists to catch exactly this — it must prove the JVM **stays up**, not just that
  autostart printed its launch line.
- **Swing LAF needs the system classloader** — `setLookAndFeel(String)` resolves against the
  app loader; the agent appends to the system classloader search accordingly.
- **`SELKIES_ENABLE_BASIC_AUTH=false`** on purpose: the base enables basic auth with well-known
  default creds otherwise. No login unless the user sets `CUSTOM_USER`/`PASSWORD`.
- **READY banner** — the container prints `JDOWNLOADER IS READY` only after the JVM confirms the
  dark LAF actually applied. Keep that contract; the smoke test and users rely on it.
- Our container is named **`JDownloader`** (case-sensitive) — a second, different JD container
  also exists on the box; verify mounts before assuming.
- `rootfs/**` scripts and the agent MUST stay **LF** (see `.gitattributes`); CRLF breaks the
  shebang scripts inside the image.
- **German** chat/vault, **English** repo. No AI attribution in commits or code. No real user
  data / IPs. This repo is and has always been **public**.
