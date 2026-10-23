# Buick-main T45: idle boot safety & repeated crash evidence

Date: 2026-10-23. Scope: Buick-main only.

## Reproduction evidence (Gateway read-only)
- Previous CarPlay stopped; opening CarDeck without tapping connect still had startup Shizuku binding.
- App initialization automatically ran MainActivity startup residual-display cleanup (release/kill and orientation restore), and CarInputClient's ServiceConnection independently reaped stale shell UserService processes.
- The new system logs showed a previous-system crash stack near DisplayManager/WindowContainer, but a DropBox dump of retained archives does not by itself identify a fresh crash.

## T45 safety fix
- Opening the App is read-only for residual-display state: no automatic orientation reset, virtual-screen release, killing stale Shizuku processes, or binding CarInput UserService.
- Existing explicit residual-screen button can still trigger user-confirmed cleanup. Actual connection still binds UserService through HiddenDisplaySource.attach.
- Binding alone must not reap stale processes. Manual cleanup path remains available.

## Why 41 lines recur
- T43 selects at most five relevant lines from each of up to three previous App sessions, up to 16 previous-boot crash lines, and up to eight lines from each of four DropBox categories, total limit 64.
- A typical result can be 15 (three sessions) + 8 (system_server_crash) + 5 (watchdog metadata) + 8 (SYSTEM_RESTART) + 5 (last_kmsg metadata) = 41. The selected records can be identical across captures because Android DropBox archives persist.
- Preview now explains that the count is a bounded summary, not the number of events. It fingerprints a returned system crash stack (when available), compares it with the last user-submitted stack, and warns of repetition. It does not claim identical stacks are identical reboot events.

## Verify
- Fresh install/update and open only: no Shizuku bind/reap or residual virtual screen release before user requests CarPlay or explicit cleanup.
- Confirm log-only UI can be opened without initiating a CarPlay session. System logs still require explicit preview/confirmation.
- Validate manual connection, touch and video after explicit connect; verify log warning when submitting an identical retained system stack.
- Crash remains a system-level risk in actual virtual display mode: this is symptom isolation, not proven root-cause repair.
