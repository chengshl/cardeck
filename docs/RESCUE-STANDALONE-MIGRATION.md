# CarDeck 第三个独立 App：Rescue Collector

> 2026-10-28 | L3 isolated experiment | NOT yet phone-validated

| App | applicationId | Runtime |
| --- | --- | --- |
| Buick-E5 | `io.github.buick.e5.cardeck` | CarPlay video/touch |
| Buick-Test audio | `io.github.buick.test.cardeck` | AAC/RTCP #11 experiments |
| Rescue Standalone | `io.github.cardeck.rescue.collector` | user-started local-only log forensics |

Source: `experiment/buick-test-rescue-collector@c399a6cbb6f3f8a98abe390ca3c5bb192464153b` -> `experiment/cardeck-rescue-standalone`.
This collector does not expose Bluetooth, Wi-Fi, CarPlay, Gateway, microphone, MediaProjection or video services. The updater only checks its own release channel in the foreground and asks before installation.

## Safe migration, without uninstalling old data

1. Open the **old** Test-package collector and manually export all records to Downloads/CarDeck. Verify the exported file exists, then **stop the old capture**. Keep the old app installed through validation.
2. Install `CarDeck-Rescue-Standalone.apk` as a separate app. It will NOT import logs, permissions, or update preferences from the old package.
3. Open Shizuku and grant `io.github.cardeck.rescue.collector` a separate authorization. Allow notifications, tap Start manually, and verify a persistent foreground notification and retained records. The app never auto-restarts after phone reboot.
4. Verify local redaction, 10-minute heartbeats, 2 MiB ordinary rotation plus 512 KiB critical retention, manual export to Downloads/CarDeck and stop/restart. Check that source/version/package name are correctly recorded in a session.
5. Open E5 / Buick-Test audio during the new collector session; neither should be replaced or unexpectedly stopped. Do not run the old and new collector simultaneously and do not intentionally cause a phone reboot.
6. Validate foreground-only update checks and verify that Test audio, E5 and legacy Test collector releases are rejected. Only after complete migration and export should users consider removing the old collector, which deletes its private logs.

## Independent release and rollback

Workflow `.github/workflows/publish-rescue-standalone.yml` signs `CarDeck-Rescue-Standalone.apk`, verifies the package, signer, origin, SHA256, publishes `exp-cardeck-rescue-standalone-YYYYMMDDHHmmss` as a prerelease with `updaterSchema=2`, `sourceBranch=experiment/cardeck-rescue-standalone`, `baseBranch=Buick-E5`. A new package can have independent versionCode ordering; future upgrades of this package must strictly increase versionCode. Do not overwrite any Buick/E5/Test latest channel.

Rollback: stop standalone capture; use old Test collector only if still installed (with old records intact). Reinstalling an uninstalled package never recovers its deleted private logs. Re-authorize Shizuku for whichever package is used.
