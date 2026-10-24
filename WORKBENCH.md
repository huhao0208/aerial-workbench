# Workbench provenance and verification

## Source lineage

The repository is a GitHub fork of pengchujin/aerial-tv, retaining upstream Git history. `workbench` starts at the exact v1.41 commit `db54bede313aa26ccd5779569c51436d9896360f`; `main` retains the newer upstream fork snapshot. No newer upstream feature set is claimed by the v1.41-derived branch.

On 2026-10-24, the maintainer migrated only tracked launcher project sources from the existing private maintenance workspace. The private aggregate repository, full firmware archives, local signing directory, caches, build outputs and TV owner data were not imported. The original MIT license and Inter license remain unchanged.

## Workbench changes

- Package `app.hzf.aerial`, independent label, stable signed update channel; source namespace retained for upstream comparison.
- Theme definitions, presets, validated phone editing and import/export.
- Opt-in Root TCL backlight and native low-blue-light controls; SDR aerial selection preserves the selected picture settings.
- Configurable shopping-key target and a separate Remote Key Bridge package.
- GitHub updater with package/certificate/digest checks and bounded TCL install-policy restoration.
- Source fork adds complete bundled third-party notices and a token-protected read-only `/licenses` endpoint. Runtime inputs include 69 resolved artifacts, POM parent-inherited licenses and notices extracted from JAR/AAR archives.
- `-PunsignedRelease` makes public CI outputs explicitly unsigned. No signing secrets are installed in this fork.

## Version and device boundary

Current source candidate is `1.41-wb.15` / code 59; bridge `1.1-dev` / code 2. These versions distinguish the new notice bundle and source-fork build from older artifacts. They are not promoted to the signed stable channel. The existing TV stable release remains wb.10 / code 54. wb.11 / code 55 remains the firmware-builder's pinned candidate input.

The historical TV validation belongs to the prior signed versions. Migrating source and passing unit tests does not prove a new APK has been installed or verified on television. The source-fork migration itself did not install to the television; the later Root-preparation validation below records the separately installed candidate.

## External material

Apple videos and Bing images are fetched from their external sources; no downloaded media caches are imported into this fork. GeoNames data retains attribution and its upstream transformation history. TCL firmware redistribution rights are outside this source fork's MIT license. Magisk is separately GPL-3.0. A full firmware distributor must establish the rights/obligations for those separate components independently.

## Source-fork validation · 2026-10-24

- Local JDK 17 / Android SDK 36: 34 JVM tests, zero failures/errors; standard, guard and bridge unsigned release builds passed.
- Verified that all three APKs contain the complete notice bundle, and that upstream MIT / Inter OFL files are byte-for-byte unchanged.
- 69 resolved runtime artifacts (including Java backport/configuration): coordinate + SHA-256 + inherited POM license inventory matches the bundled notices.
- Source-only inspection and builds were used. No TV installation, update-channel promotion, signing-secret migration or firmware-input replacement was performed.

## Root startup preparation (current change)

Goal: when the owner has explicitly enabled TCL Root picture controls, prepare Root after Home's first displayed frame and after subsequent resumes. Read current brightness / low-blue state off the UI thread; do not change picture settings. Retain those values for the control center while its normal live refresh continues. Root denial, timeout or unavailable middleware must leave Home usable. A closed control center retains its Root helper while Home remains foreground, including the in-Home screensaver, with no idle polling. Leaving Home releases the helper; returning prepares a new read-only connection after resume. Startup preparation has bounded cleanup and shares its session lock with menu requests. Magisk may still notify about later authorized Root usage when a new helper is started; this feature does not suppress those notifications.

Device behavior must be recorded separately from unit/build checks. Existing firmware pinned inputs and the signed stable update channel are not automatically replaced by this source change.

Startup preparation validation: 41 local JVM tests passed, including inactive read-only startup, single attempt, off-caller-thread I/O, menu-open-during-authorization reuse, fresh reads after external changes / menu reopen, live-read failure, denial, timeout/backoff and cancellation cleanup. The menu still reads hardware on open and every three seconds; overlapping polls are coalesced, and older read results are ignored after an edit or panel close. The wb.13 signed candidate was installed and verified on the owner's Android 11 TCL: startup preparation preceded menu entry, and menu values followed external brightness / low-blue changes. This is not a stable-channel promotion.

Historical wb.14 follow-up: closing and recreating app_process caused cold-read latency even after permission preparation. wb.14 reused the idle connection for 60 seconds. wb.15 supersedes that timeout with foreground ownership; pipe EOF / EXIT release the helper on client death / close, and initialization keeps its 20-second deadline. On menu entry, hide stale numeric/eye values behind localized read-in-progress text until a fresh response; edits are blocked only for that initial read. Periodic live reads retain normal labels. This changes connection lifetime, not authorization or the theme layout.

Device follow-up, 2026-10-24: the signed wb.14 / code 58 candidate is installed with the existing signing identity and application data. 43 JVM tests and both release builds passed; the runtime notice check verified 69 artifacts. Warm menu reads took 123, 43 and 50 ms on this device, reusing the same helper. External picture changes appeared in the menu and were restored to the pre-test values. A cold helper read after pause/resume took 1703 ms and displayed localized reading text instead of stale values. These are individual samples, not a latency guarantee. No TV reboot or firmware flash was performed. Stable feed and firmware-builder pinned APK inputs remain unchanged.


## wb.15 foreground connection and screensaver picture policy

The owner requested keeping the connection throughout Home and using the normal signed update channel. There is no idle timer, keepalive or closed-menu polling. Menu GET still reads hardware; backgrounding releases the connection and resets resume preparation. Denial/backoff and stale-read protection remain.

Reproduced on wb.14: the saved screensaver preference was explicitly Dolby Vision, so the old Auto-only SDR rule did not apply. Home read brightness 1 / low-blue 1; video read brightness 100 / low-blue 1. On the explicitly Root-opted-in TCL, wb.15 now resolves both Auto and saved Dolby choices to 4K SDR (or 1080p fallback), with five-language explanation. Preference files are not rewritten and other apps / non-opted-in devices retain their HDR behavior. Keeping a saved low-blue flag is not a measurement of emitted blue light. Tests/build and device/online-update validation are recorded separately.
