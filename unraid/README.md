# Unraid install (compact toolbar fork)

Image: `ghcr.io/milfer322/jdownloader:latest`

## One-time: make the GHCR package public

After the first GitHub Actions build finishes:

1. Open https://github.com/milfer322?tab=packages
2. Open **jdownloader**
3. **Package settings** → change visibility to **Public**

Until it is public, Unraid cannot pull the image without a GitHub login.

## Install on Unraid

```bash
wget -O /boot/config/plugins/dockerMan/templates-user/my-JDownloader-Compact.xml \
    https://raw.githubusercontent.com/milfer322/jdownloader/feat/compact-toolbar/unraid/jdownloader.xml
```

Then in Unraid: **Docker** → **Add Container** → select **JDownloader-Compact**.

`Compact Toolbar (JD_COMPACT_TOOLBAR)` defaults to `true`.

## Or patch your existing container

Keep `junkerderprovinz/jdownloader` only if you do **not** need this fork. To use this image instead:

1. Edit the container
2. Repository: `ghcr.io/milfer322/jdownloader:latest`
3. Add variable `JD_COMPACT_TOOLBAR` = `true`
4. Apply / restart
