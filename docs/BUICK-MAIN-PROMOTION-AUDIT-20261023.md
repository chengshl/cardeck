# Buick-main promotion audit · 2026-10-23

Source: Buick-Test at commit 0d3566d64cba9ec79e15dfef95762bc594f148a3.
Target: Buick-main at commit 3b9140c144dcfbe95220f20536874f8ce630ff68.

## Promoted in this commit (bounded, independently testable)

1. AirPlay HID report field compatibility. Parse either top-level `hidReport/uuid` or `params.hidReport/params.uuid` using `AirPlayHidCommand`, retaining descriptor-based touch/media-key classification. Unit tests include top-level, nested, absent and malformed reports. Only anonymized event counts/field presence and report byte lengths are logged.
2. The in-app APK update installer hands off to system installer only after `SessionController.disconnect(context)`. This uses the existing orderly Bluetooth/CarPlay/media teardown so the old process does not simply disappear. It does not intercept an external browser or file-manager install, and it is not proof that the receiver will always acknowledge teardown.

## Not promoted (insufficient real-vehicle validation or risk)

- T31 0x4300 DETECT marker recovery: after real testing 0x4300 sometimes still lacked ACK and E5 head-unit restart remained necessary.
- T32 15s stalled video recovery: repeated real runs had not reproduced the 0-frame stall on the new build, so fix is not field-confirmed.
- T33–T38 Safe Area UI/render/compositor, full-screen application uniform-fit and wallpaper: field reports still show interior Gaode black letterbox even after T38; do not silently enable.
- T39 redesigned app display management: Android CI build succeeds but user has not yet reported phone-side visual validation.
- Test-only Sony Android 15 real virtual display bypass: Buick-main retains the T28 protective guard, to avoid reintroducing system_server/reboot risk without explicit risk acceptance.
- B233 compatibility memory across renamed Bluetooth devices: unit tests exist, but real renamed-vehicle recognition has not yet been confirmed.
- Audio changes (including A2DP/CarPlay media forwarding) and all unconfirmed iAP2 transport retry changes.
- Branch package name, build workflow, update channel, release tag, diagnostics config, and key/credentials remain **Buick-main independent**. No fork-wide merges or branch ref rewrites.

## Validation plan
Run Buick-main privacy guard, required Android unit tests, lint, signed APK build, release identity checks. Main release success proves compilation, not real-world reliability. Follow-up E5 testing needed before promoting larger UI or protocol features.
