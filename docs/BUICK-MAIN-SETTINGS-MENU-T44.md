# Buick-main T44 — Independent Settings, Log Upload and Update

Date: 2026-10-23. Scope: Buick-main only.

The upstream-compatible Car Settings / PhoneSettingsActivity is restored to the original SettingsMenus items only. Buick Settings now opens a dedicated menu with: Display & Safe Area, 日志上传, 在线更新, 自动检查更新.

Display & Safe Area is the existing BuickSettingsActivity and retains independent vehicle profiles, safe-area insets, app-level safe/full-screen preferences and the visible safe-area toggle. The T28 "真实桌面与车机触摸" switch is removed.

The existing EntryConfigActivity appears as 日志上传. Persisted endpoint credentials, the Gateway/MCP protocol and explicit user confirmation before uploading system crash logs are unchanged. BuickUpdater continues to check only the independent Buick-main release channel, and its auto-check preference is displayed as a discoverable menu item rather than long-press in generic Car Settings.

SessionController no longer selects or starts T28 EGL-only video, and it always injects HID contacts when a genuine virtual display session is available. The obsolete BuickMainDisplayMode implementation and test are deleted. Previously saved T28 disabled preferences have no effect. The old SafeDiagnosticFrameSource source file remains in the repository for historical reference but is unreachable in Buick-main.

**Warning:** This change does not repair the Xperia Android 15 / Buick B233 system_server display-add crash. Validate only while parked, and keep collecting explicit crash evidence using the moved log-upload page.

Test on device: (1) generic Car Settings contains no Buick-only entries; (2) Buick Settings has four submenu items; (3) Display & Safe Area has no T28 control; (4) log credentials persist; (5) manual system-log upload is still opt-in; (6) Buick updater checks independent release; (7) old T28 OFF setting cannot produce diagnostic stripes.
