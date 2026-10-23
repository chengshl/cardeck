# Buick-main T53 — combine system warnings and crash forensics

Scope: **Buick-main only**. Keep Buick-Test, Buick-E5 and upstream settings untouched.

- Merge the previous "预览系统日志" and "重启崩溃取证" into one
  "系统诊断与崩溃取证" action under Buick Settings -> 日志管理.
- One explicitly user-initiated capture reads existing prior-session App
  breadcrumbs, persistent stage trail, current/previous crash buffer and
  Android DropBox crash events, then recent main/system warnings when
  Shizuku is ready.
- A failure or lack of Shizuku must not prevent showing the locally persisted
  crash evidence. Current system warnings are clearly tagged.
- Bound unified preview to 256 redacted lines, each max 256 characters:
  retain the earliest/prioritized 208 crash-evidence lines when warnings are
  abundant; reserve at most 48 slots for the most recent warnings. If
  warning collection returns nothing, keep up to 256 crash-evidence lines.
- Preserve dated crash identity, repeated-event warning and original crash
  occurrence times. Old DropBox entries are not misrepresented as new.
- "保存本地" uses the new CarDeck-diagnostic-* offline file and never
  uploads. Existing full offline bundle and App-only export remain available.
- "确认上传" still requires a healthy Gateway, explicit preview and user
  confirmation. It uses the unchanged <=8-line acknowledged upload
  queue; no automatic system-log upload or additional startup actions.
- No CarPlay/Buick display, iAP2, touch, audio or Gateway/MCP server changes.

Unit coverage: 256-line limit, crash-time preservation, crash-stack
priority, most-recent system warnings, no-Shizuku fallback, short evidence,
empty-warning case.
