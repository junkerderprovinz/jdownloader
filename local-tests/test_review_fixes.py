"""Local unit tests for JDDEFAULT / theme independence contracts (r1 + r2 + Metal fix).

Run from repo root:

  python -m pytest local-tests -q
"""
from __future__ import annotations

import importlib.util
import json
import re
import tempfile
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
HELPERS_PATH = ROOT / "rootfs" / "usr" / "local" / "bin" / "jdownloader-laf-helpers.py"
THEME_SH = ROOT / "rootfs" / "usr" / "local" / "bin" / "jdownloader-theme.sh"
AUTOSTART = ROOT / "rootfs" / "defaults" / "autostart"
SETUP = ROOT / "rootfs" / "etc" / "cont-init.d" / "10-jdownloader-setup"
AGENT_JAVA = (
    ROOT
    / "agent"
    / "src"
    / "io"
    / "github"
    / "junkerderprovinz"
    / "DialogConfirmAgent.java"
)


def load_helpers():
    spec = importlib.util.spec_from_file_location("jdownloader_laf_helpers", HELPERS_PATH)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


h = load_helpers()


# --------------------------------------------------------------------------- #1 casing + whitespace
@pytest.mark.parametrize(
    "theme,laf,classic",
    [
        ("JDDEFAULT", "DEFAULT", True),
        ("jddefault", "DEFAULT", True),
        ("JdDefault", "DEFAULT", True),
        (" JDDEFAULT ", "DEFAULT", True),
        ("\tjddefault\n", "DEFAULT", True),
        ("Dark", "FLATLAF_DARK", False),
        ("dark", "FLATLAF_DARK", False),
        ("Light", "FLATLAF_LIGHT", False),
        ("light", "FLATLAF_LIGHT", False),
        ("JD_Plain_Dark", "FLATLAF_DARK", False),
        ("JD_Plain", "FLATLAF_LIGHT", False),
        ("SomethingElse", "FLATLAF_DARK", False),
    ],
)
def test_01_theme_casing_no_split_brain(theme, laf, classic):
    r = h.resolve_jd_theme(theme)
    assert r["laf"] == laf
    assert r["is_classic"] is classic
    assert (r["expect_classic"] == "1") is classic
    if classic:
        assert r["expect_laf"] == ""
    else:
        assert r["expect_laf"] in ("flatdark", "flatlight")


def test_r2_autostart_uses_expect_classic_not_raw_tr_case():
    auto = AUTOSTART.read_text(encoding="utf-8")
    # Launch loop must key off EXPECT_CLASSIC (not a bare jddefault case on JD_THEME)
    assert 'if [ "${EXPECT_CLASSIC}" = "1" ]; then' in auto
    assert "disabled flatlaf.jar" in auto


def test_r2_setup_uses_helpers_or_trim():
    setup = SETUP.read_text(encoding="utf-8")
    assert "jdownloader-laf-helpers.py" in setup
    assert "is_classic" in setup
    assert "sed 's/^[[:space:]]*//" in setup or "strip" in setup.lower()


def test_r2_expect_laf_safety_default():
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert 'EXPECT_LAF:=flatdark' in auto
    assert 'EXPECT_CLASSIC}" = "1"' in auto or 'EXPECT_CLASSIC" = "1"' in auto
    # Metal must not match READY when EXPECT_LAF empty on non-classic
    assert '[ -n "${EXPECT_LAF}" ] || return 1' in auto or '[ -n "${EXPECT_LAF}" ]' in auto


def test_r2_selfupdate_freeze_requires_synthetica_when_classic():
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "any_synthetica_valid" in auto
    assert "synthetica-license.key" in auto
    # Old gate: empty EXPECT_LAF alone freezes — must not be the only classic path
    assert "JD_SELFUPDATE" in auto
    # Must not freeze solely because EXPECT_LAF is empty
    assert '{ [ -z "${EXPECT_LAF}" ] || flatlaf_jar_valid' not in auto


def test_r2_heal_only_core_synthetica_jar():
    auto = AUTOSTART.read_text(encoding="utf-8")
    # Core path only inside heal_invalid_synthetica
    assert 'libs/laf/synthetica.jar"' in auto or "libs/laf/synthetica.jar" in auto
    # Must not glob-delete all synthetica*.jar in heal
    heal = re.search(r"heal_invalid_synthetica\(\) \{(.*?).\n\}", auto, re.S)
    assert heal, "heal_invalid_synthetica not found"
    body = heal.group(1)
    assert "synthetica*.jar" not in body
    assert "synthetica.jar" in body
    assert '[ -f "${HELPERS}" ]' in body or "[ -f \"${HELPERS}\" ]" in body


def test_r2_asm_progressbar_short_names_not_foreground():
    """#1 DENIED: LAFOptions getters are getColorForProgressbarN (no Foreground)."""
    src = AGENT_JAVA.read_text(encoding="utf-8")
    # ASM invoke uses short names
    assert re.search(r'getColorForProgressbar"\s*\+\s*\(i\s*\+\s*1\)', src) or \
           'getColorForProgressbar" + (i + 1)' in src
    # Must not use Foreground inside the ASM progress painter patch block
    prog = src.split("patchCustomProgressbarPainter")[1].split("patchJdDefaultGetDisabledIcon")[0]
    assert "getColorForProgressbarForeground" not in prog
    # Seed/cfg layer DOES use Foreground
    assert "getColorForProgressbarForeground" in src


def test_r2_seed_classic_uses_putcfg_and_conditional_latch():
    src = AGENT_JAVA.read_text(encoding="utf-8")
    seed = src.split("seedClassicLafColors()")[1].split("putCfgStringIfBlank")[0] \
        if False else src  # full file checks
    assert 'putCfgStringIfBlank(cfg,\n                        "getColorForProgressbarForeground"' in src \
        or 'putCfgStringIfBlank(cfg,\n                        "getColorForProgressbarForeground" + i' in src \
        or 'putCfgStringIfBlank(cfg,' in src and "getColorForProgressbarForeground" in src
    assert "classicLafColorsSeeded = true" in src
    # Latch after verifying progress getters non-blank
    assert "getColorForProgressbarForeground" in src
    assert "return;" in src.split("seedClassicLafColors")[1].split("// keep retrying")[0]


def test_r2_shared_laf_class_writer():
    src = AGENT_JAVA.read_text(encoding="utf-8")
    assert "lafClassWriter(" in src
    assert src.count("lafClassWriter(") >= 5


def test_r2_theme_sh_no_dead_progress_loop():
    text = THEME_SH.read_text(encoding="utf-8")
    assert "d.update(classic)" in text
    # Dead force loop after update removed
    assert not re.search(
        r"d\.update\(classic\)\s*\n\s*# Always force progress.*?\nfor i in range\(1, 6\):",
        text,
        re.S,
    )


def test_r2_no_flatlaf_parked_param():
    helper = HELPERS_PATH.read_text(encoding="utf-8")
    assert "flatlaf_present_or_parked" not in helper
    assert "--flatlaf" not in helper
    # Autostart should not pass --flatlaf to should-heal-classic
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "should-heal-classic" in auto
    assert "--flatlaf" not in auto


# --------------------------------------------------------------------------- jar integrity
def _make_jar(path: Path, entry: str, payload: bytes = b"ok"):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(entry, payload)


def test_03_synthetica_jar_valid_and_truncated():
    with tempfile.TemporaryDirectory() as td:
        good = Path(td) / "synthetica.jar"
        _make_jar(good, h.SYNTHETICA_ENTRY)
        assert h.synthetica_jar_valid(str(good)) is True

        bad = Path(td) / "broken.jar"
        bad.write_bytes(b"not-a-zip")
        assert h.synthetica_jar_valid(str(bad)) is False


def test_03_heal_deregisters_extension_ids():
    with tempfile.TemporaryDirectory() as td:
        inst = Path(td) / "extensions.installed.json"
        inst.write_text(
            json.dumps(["flatlaf-themes", "synthetica", "synthetica-themes", "other"]),
            encoding="utf-8",
        )
        left = h.deregister_extensions(str(inst), list(h.SYNTHETICA_REQUEST_IDS))
        assert "synthetica" not in left
        assert "synthetica-themes" not in left


def test_05_license_error_and_marker_in_autostart():
    text = AUTOSTART.read_text(encoding="utf-8")
    assert "/tmp/.jd-synthetica-license-missing" in text
    assert "WARNING: cfg/synthetica-license.key missing" not in text


@pytest.mark.parametrize(
    "classic,matches,syn,lic,expect",
    [
        (True, False, False, False, False),
        (True, False, False, True, False),
        (True, False, True, False, False),
        (True, False, True, True, True),
        (True, True, True, True, False),
        (False, False, False, False, False),
    ],
)
def test_06_should_increment_classic_mismatch(classic, matches, syn, lic, expect):
    got = h.should_increment_classic_mismatch(
        expect_classic=classic,
        laf_matches=matches,
        has_valid_synthetica=syn,
        has_license=lic,
    )
    assert got is expect


def test_minor_classic_colors_use_hash():
    text = THEME_SH.read_text(encoding="utf-8")
    m = re.search(r"classic\s*=\s*\{(.*?)\n\}", text, re.S)
    assert m
    block = m.group(1)
    for v in re.findall(r'"color[^"]+"\s*:\s*"([^"]+)"', block):
        assert v.startswith("#")


def test_02_get_disabled_icon_uses_get_super_name():
    src = AGENT_JAVA.read_text(encoding="utf-8")
    assert "cr.getSuperName()" in src
    assert "skip getDisabledIcon patch" in src


def test_minor_progress_painter_skips_cache_on_fallback():
    src = AGENT_JAVA.read_text(encoding="utf-8")
    assert "skipCache" in src


# --------------------------------------------------------------------------- Light must not get Carbon #161616 chrome
def _want_dark_laf_method(src: str) -> str:
    m = re.search(
        r"private static boolean wantDarkLaf\(\)\s*\{(.*?)\n    \}",
        src,
        re.S,
    )
    assert m, "wantDarkLaf() not found"
    return m.group(1)


def test_want_dark_laf_light_and_jd_plain_are_false():
    """JD_THEME=Light / JD_Plain must not want dark chrome (#161616 remaps)."""
    src = AGENT_JAVA.read_text(encoding="utf-8")
    body = _want_dark_laf_method(src)
    assert 'wantClassicLaf()' in body
    assert '"light".equals(n)' in body
    assert '"jd_plain".equals(n)' in body
    assert "return false" in body
    # Empty/default still Dark
    assert "return true" in body


def test_tick_gates_enforce_dark_chrome_behind_want_dark_laf():
    """tick() must call enforceDarkChrome / Kayn / progress retint only when wantDarkLaf()."""
    src = AGENT_JAVA.read_text(encoding="utf-8")
    # Flat path in tick: wantDarkLaf() { enforceDarkChrome(); themeKaynExtras(); retintProgressBars(); }
    assert re.search(
        r"if\s*\(\s*wantDarkLaf\s*\(\s*\)\s*\)\s*\{\s*"
        r"enforceDarkChrome\s*\(\s*\)\s*;\s*"
        r"themeKaynExtras\s*\(\s*\)\s*;.*?retintProgressBars\s*\(\s*\)\s*;",
        src,
        re.S,
    ), "tick() must gate dark chrome trio behind wantDarkLaf()"
    # enforceDarkChrome itself must early-return when not dark
    chrome = src.split("private static void enforceDarkChrome()")[1].split(
        "private static", 1
    )[0]
    assert "if (!wantDarkLaf()) return;" in chrome
    # premain: installProgressBarDefaults always (clears dark overrides on Light)
    premain = src.split("public static void premain")[1].split("public static void watch")[0]
    assert "installProgressBarDefaults();" in premain
    # LAF listener: always refresh defaults; retint only on Dark
    assert "installProgressBarDefaults();" in src.split("ensureLafChangeListener")[1]
    assert re.search(
        r"installProgressBarDefaults\s*\(\s*\)\s*;\s*"
        r"if\s*\(\s*wantDarkLaf\s*\(\s*\)\s*\)\s*retintProgressBars\s*\(\s*\)\s*;",
        src,
        re.S,
    )
    # applyCustomDefaults skips dark re-apply on Light
    acd = src.split("private static void applyCustomDefaults()")[1].split(
        "// -------------------------------------------------------- speed graph"
    )[0]
    assert "if (!wantDarkLaf())" in acd and "lafRefreshDone = true" in acd


def test_autostart_theme_aware_xsetroot():
    """Desktop root color must follow JD_THEME (not always #161616)."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "xsetroot -solid '#f2f2f2'" in auto
    assert "xsetroot -solid '#d7e7f0'" in auto
    assert "xsetroot -solid '#161616'" in auto
    assert re.search(r"light\|jd_plain\)\s*xsetroot", auto)
    assert re.search(r"jddefault\)\s*xsetroot", auto)
    # Theme case drives xsetroot (not a lone unconditional Carbon fill)
    assert re.search(
        r"_case_theme=.*\ncase \"\$\{_case_theme\}\" in",
        auto,
        re.S,
    )


# --------------------------------------------------------------------------- theme independence (Dark / Light / JDDEFAULT)
def test_theme_independence_resolve_three_ways():
    """Switching helpers must resolve Dark / Light / JDDEFAULT to distinct LAFs."""
    dark = h.resolve_jd_theme("Dark")
    light = h.resolve_jd_theme("Light")
    classic = h.resolve_jd_theme("JDDEFAULT")
    assert dark["laf"] == "FLATLAF_DARK" and dark["expect_classic"] == "0"
    assert light["laf"] == "FLATLAF_LIGHT" and light["expect_classic"] == "0"
    assert classic["laf"] == "DEFAULT" and classic["expect_classic"] == "1"
    assert dark["expect_laf"] == "flatdark"
    assert light["expect_laf"] == "flatlight"
    assert classic["expect_laf"] == ""


def test_classic_jars_ready_requires_jdcustom_not_core_alone():
    """Core synthetica.jar alone must NOT count as classic-ready (Metal path)."""
    with tempfile.TemporaryDirectory() as td:
        laf = Path(td)
        _make_jar(laf / "synthetica.jar", h.SYNTHETICA_ENTRY)
        assert h.any_valid_synthetica(str(laf)) is True
        assert h.classic_laf_jars_ready(str(laf)) is False
        _make_jar(laf / h.JD_CUSTOM_JAR, h.JD_CUSTOM_ENTRY)
        assert h.classic_laf_jars_ready(str(laf)) is True


def test_seed_synthetica_rearms_themes_when_jdcustom_missing():
    """If JDCustom was deleted, seed must deregister synthetica-themes and re-request."""
    with tempfile.TemporaryDirectory() as td:
        laf = Path(td) / "laf"
        laf.mkdir()
        _make_jar(laf / "synthetica.jar", h.SYNTHETICA_ENTRY)
        req = Path(td) / "extensions.requestedinstalls.json"
        inst = Path(td) / "extensions.installed.json"
        req.write_text(json.dumps(["synthetica", "synthetica-themes"]), encoding="utf-8")
        inst.write_text(json.dumps(["synthetica", "synthetica-themes"]), encoding="utf-8")
        # Invoke CLI the same way autostart does
        import subprocess
        import sys

        r = subprocess.run(
            [
                sys.executable,
                str(HELPERS_PATH),
                "seed-synthetica",
                str(req),
                str(laf),
                str(inst),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        assert r.returncode == 0
        installed = json.loads(inst.read_text(encoding="utf-8"))
        assert "synthetica-themes" not in installed
        assert "synthetica" in installed
        requested = json.loads(req.read_text(encoding="utf-8"))
        assert "synthetica-themes" in requested


def test_heal_never_globs_all_synthetica_jars():
    """heal_invalid_synthetica must only touch core synthetica.jar (not JDCustom)."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    heal = re.search(r"heal_invalid_synthetica\(\) \{(.*?).\n\}", auto, re.S)
    assert heal
    body = heal.group(1)
    assert "synthetica*.jar" not in body
    assert "synthetica.jar" in body
    assert "EXPECT_CLASSIC" in body


def test_flatlaf_parking_gated_classic_only_and_restorable():
    """Classic parks flatlaf; non-classic restores — never permanent delete."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "flatlaf.jar.disabled-for-classic" in auto
    assert "re-enabled flatlaf.jar for FlatLaf theme" in auto
    assert "disabled flatlaf.jar (JD_THEME=JDDEFAULT" in auto
    # Parking uses mv into .disabled-for-classic (restorable), not a bare rm of flatlaf.jar
    assert re.search(
        r'mv -f "\$\{JD_DIR\}/libs/laf/flatlaf\.jar" "\$\{JD_DIR\}/libs/laf/flatlaf\.jar\.disabled-for-classic"',
        auto,
    )
    assert re.search(
        r'mv -f "\$\{JD_DIR\}/libs/laf/flatlaf\.jar\.disabled-for-classic" "\$\{JD_DIR\}/libs/laf/flatlaf\.jar"',
        auto,
    )


def test_agent_light_skips_dark_chrome_classic_skips_flat():
    """Agent: Light ≠ dark chrome; classic ≠ FlatLaf path; Dark still gets chrome."""
    src = AGENT_JAVA.read_text(encoding="utf-8")
    # tick() classic branch
    tick = src.split("private static void tick()")[1].split(
        "// ------------------------------------------------------ Kayn plain-dark fixes"
    )[0]
    assert "wantClassicLaf()" in tick
    assert "exposeSyntheticaToSystemLoader" in tick
    assert "enforceDarkChrome" in tick
    # Classic path must not call dark chrome; Flat path gates it
    classic_arm = tick.split("if (wantClassicLaf()) {")[1].split("} else {")[0]
    assert "exposeSyntheticaToSystemLoader" in classic_arm
    assert "enforceDarkChrome" not in classic_arm
    flat_arm = tick.split("} else {")[1]
    assert "wantDarkLaf()" in flat_arm
    assert "enforceDarkChrome" in flat_arm
    # wantClassicLaf is exact jddefault (trim + lower)
    w = src.split("private static boolean wantClassicLaf()")[1].split(
        "private static boolean wantDarkLaf()"
    )[0]
    assert "trim()" in w
    assert '"jddefault".equals' in w


def test_autostart_classic_ready_uses_classic_jars_ready():
    """Headless settle + healer + freeze must key off classic_jars_ready, not core-alone."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "classic_jars_ready" in auto
    assert "classic-jars-ready" in auto
    assert "syntheticaJDCustom.jar" in auto


def test_headless_flatlaf_does_not_wait_for_jar():
    """Dark/Light must not block headless on flatlaf.jar (never arrives → Metal after 15 min)."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    start = auto.index('if [ ! -f "${JD_DIR}/Core.jar" ]; then')
    end = auto.index("# The READY marker must be cleared")
    block = auto[start:end]
    assert "classic_jars_ready && theme_ok=1" in block
    assert "flatlaf_jar_valid" not in block
    assert "FlatLaf is installed on the first GUI start" in block


def test_seed_flatlaf_requires_valid_jar_not_mere_existence():
    auto = AUTOSTART.read_text(encoding="utf-8")
    fn = auto.split("seed_flatlaf_request() {")[1].split("\n}\n")[0]
    assert "flatlaf_jar_valid" in fn
    assert '[ -f "${JD_DIR}/libs/laf/flatlaf.jar" ] && return 0' not in fn


def test_autostart_classic_bootstrap_before_gui_on_template_switch():
    """Existing Dark/Light -> JDDEFAULT must bootstrap Synthetica before GUI (classic-only)."""
    auto = AUTOSTART.read_text(encoding="utf-8")
    assert "bootstrap_classic_jars_if_needed" in auto
    assert '[ "${EXPECT_CLASSIC}" = "1" ] || return 0' in auto
    # Called once before the launch loop, not inside Dark/Light branches
    pos = auto.index("bootstrap_classic_jars_if_needed")
    loop = auto.index("while true; do", pos)
    assert pos < loop
    body = auto.split("bootstrap_classic_jars_if_needed() {")[1].split("\n}\n")[0]
    assert "headless" in body.lower()
    assert "classic_jars_ready" in body
    assert "/tmp/.jd-classic-bootstrap-tried" in auto
