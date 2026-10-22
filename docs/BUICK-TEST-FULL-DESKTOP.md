# Buick-Test complete desktop and touch experiment

Merged Buick-main commit `3b9140c144dcfbe95220f20536874f8ce630ff68` into Buick-Test while retaining the
Test package, update channel, Gateway configuration and signing workflow.

Buick-Test uses the Buick-main B233 iAP2, AirPlay, audio and reconnection
implementation. Test-only `BuickTestVideoTouchMode.RESTORE_FULL_DESKTOP_AND_TOUCH`
bypasses T28 **on this branch only**. After RECORD, video routes through
`HiddenDisplaySource.attach()` to project actual app content, and CarPlay
Digitizer HID contacts route to `TouchRouter.onContact()`.

**Risk:** Sony Xperia 1 V / Android 15 / Buick B233 previously crashed
`system_server` during virtual display creation. Restoring this path can
cause another phone reboot. Buick-main retains protected EGL-only testing.
Shizuku must be authorized for the Test app separately.

Diagnostic log checkpoints:
- `BUICK-TEST WARNING`: test override active on risky hardware
- `MODE: 隐形屏 已启动`: actual virtual display attached
- `VIDEO: 帧`: H.264 frames transmitted
- `EVENT: 触摸`: car HID input received
- Touch operation on the vehicle display must be verified manually

Test isolation:
- Package: `io.github.buick.test.cardeck`
- Display name: `CarDeck-Buick-Test`
- Update release: `buick-test-latest`
- Entry alias: `cardeck.Buick-Test.entry`
- Private config file: `buick-test-entry.json`
