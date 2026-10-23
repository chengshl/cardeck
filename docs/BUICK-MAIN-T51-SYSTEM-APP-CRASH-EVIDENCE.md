# Buick-main T51: capture persistent system-process crashes

Source evidence: user-saved logs on 2026-10-23 21:51–21:52 Beijing time.

- Existing recovery captured a retained system_server_crash from 21:27:41.716,
  not a new 21:52 event. Current system sampler instead showed ActivityManager
  "Process system has crashed too many times" and SettingsProvider attempts to
  read /vendor/etc/aconfig_flags.pb (ENOENT).
- Do NOT infer ENOENT caused the crash without a FATAL stack.
- Add user-initiated read-only current-boot crash-buffer sample and DropBox
  system_app_crash / system_app_anr tags (in addition to existing four).
- Show newer dated events first in the crash summary; preserve original event
  times. Existing manual preview, redaction, 256-line bound and offline export
  are unchanged. No new automatic upload or extra Shizuku startup actions.
