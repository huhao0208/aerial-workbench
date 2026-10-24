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

Current source candidate is `1.41-wb.12-dev` / code 56; bridge `1.1-dev` / code 2. These versions distinguish the new notice bundle and source-fork build from older artifacts. They are not promoted to the signed stable channel. The existing TV stable release remains wb.10 / code 54. wb.11 / code 55 remains the firmware-builder's pinned candidate input.

The historical TV validation belongs to the prior signed versions. Migrating source and passing unit tests does not prove a new APK has been installed or verified on television. This task does not install to or change the television.

## External material

Apple videos and Bing images are fetched from their external sources; no downloaded media caches are imported into this fork. GeoNames data retains attribution and its upstream transformation history. TCL firmware redistribution rights are outside this source fork's MIT license. Magisk is separately GPL-3.0. A full firmware distributor must establish the rights/obligations for those separate components independently.

## Source-fork validation · 2026-10-24

- Local JDK 17 / Android SDK 36: 34 JVM tests, zero failures/errors; standard, guard and bridge unsigned release builds passed.
- Verified that all three APKs contain the complete notice bundle, and that upstream MIT / Inter OFL files are byte-for-byte unchanged.
- 69 resolved runtime artifacts (including Java backport/configuration): coordinate + SHA-256 + inherited POM license inventory matches the bundled notices.
- Source-only inspection and builds were used. No TV installation, update-channel promotion, signing-secret migration or firmware-input replacement was performed.
