# Security Findings — JDownloader Container

Generated: 2026-05-28

## Scorecard

| Severity | Count |
|----------|-------|
| High     | 5     |
| Medium   | 4     |
| Low      | 2     |
| **Total**| **11**|

Top CWE categories: CWE-78 (OS Command Injection) × 3, CWE-494 (Download without Integrity Check) × 2, CWE-22 (Path Traversal) × 1

---

## Findings

| ID | Severity | CWE | File:Line | Summary |
|----|----------|-----|-----------|---------|
| SEC-001 | High | CWE-78 | `jdownloader-language.sh:91` | JD_LANG injected raw into inline Python heredoc → OS command injection |
| SEC-002 | High | CWE-78 | `10-jdownloader-setup:44` | JD_INST_DIR interpolated into Python heredoc → path/code injection |
| SEC-003 | High | CWE-78 | `jdownloader-theme.sh:26` | JD_THEME/JD_INST_DIR interpolated into Python heredoc → code injection |
| SEC-004 | High | CWE-494 | `autostart:23` | JDownloader.jar downloaded over plain HTTP, no checksum |
| SEC-006 | High | CWE-22 | `jdownloader-create-dark-theme.py:115` | `zipfile.extractall()` without path validation → Zip Slip |
| SEC-005 | Medium | CWE-494 | `Dockerfile:68` | FlatLaf JAR downloaded without SHA256 verification |
| SEC-007 | Medium | CWE-601 | `release.yml:22` | Tag name used unsanitised as file path component |
| SEC-008 | Medium | CWE-829 | `build.yml:23` | All GitHub Actions pinned to mutable tags, not commit SHAs |
| SEC-009 | Medium | CWE-306 | `jdownloader.xml:67` | Default template ships with no authentication (empty user/password) |
| SEC-010 | Low | CWE-1188 | `Dockerfile:17` | Base image pulled by mutable floating tag, no digest pin |
| SEC-011 | Low | CWE-732 | `autostart:42` | Inline Python source visible in /proc, should use script file instead |

---

## Remediation Log

| ID | Fix | Status |
|----|-----|--------|
| SEC-001 | Shell injection: move to standalone script, validate JD_LANG allowlist | Patch provided |
| SEC-002 | Shell injection: replace heredoc blocks with standalone scripts | Patch provided |
| SEC-003 | Shell injection: validate JD_THEME against allowlist before heredoc | Patch provided |
| SEC-004 | Change HTTP → HTTPS for JD bootstrap download | **Applied** (`autostart`) |
| SEC-005 | Add SHA256 verification after FlatLaf wget in Dockerfile | Patch provided |
| SEC-006 | Add Zip Slip guard before `zipfile.extractall()` | **Applied** (`jdownloader-create-dark-theme.py`) |
| SEC-007 | Validate tag name format in release.yml | Patch provided |
| SEC-008 | Pin all GitHub Actions to immutable commit SHAs | Patch provided (manual step required) |
| SEC-009 | Add non-empty default for CUSTOM_USER or mark as required | Patch provided |
| SEC-010 | Pin base image to digest via Renovate | Low priority |
| SEC-011 | Replace inline -c guard with call to disable-tray.py loop | **Applied** (previous simplify pass) |

---

## Patch Review

Fixes applied directly in this session (SEC-004, SEC-006, SEC-011) are minimal and targeted.
Remaining patches in `security_remediation.patch` address the harder injection issues and action pinning.
SEC-001/002/003 require refactoring three scripts — recommended as a follow-up PR.
