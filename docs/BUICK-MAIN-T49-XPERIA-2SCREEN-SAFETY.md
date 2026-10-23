# Buick-main T49: Xperia 1 V / B233 virtual-display crash isolation

Date: 2026-10-23. Apply to `Buick-main` only.

Observed DropBox: system_server NullPointerException at WindowContextListenerImpl / DisplayContent.onParentChanged / RootWindowContainer.onDisplayAdded on Sony XQ-DQ72 Android15.
The cause is not fully proven; avoid assuming a particular virtual display was responsible.

Guarded scope: only when SonyB233CrashGuard matches Sony XQ-DQ72 Android SDK35+ and Buick B233.
The guarded path:
- keeps trusted Dock + native App displays and their normal touch routing;
- does not allocate AppHi (supersampled) or separate Map display or GL inputs;
- does a read-only residual-display check, refuses connection with residue instead of auto-reaping;
- refuses PUBLIC fallback when trusted UserService is unavailable;
- records timestamped, fsync-like SharedPreferences.commit() breadcrumbs before/after each protected display operation and startup;
- disables split-map dashboard while independent map display is intentionally absent. Maps can still open as normal apps on the App display.

Non-matching receivers keep existing behavior. No changes to audio or iAP2.
The phone's crash history may still contain older incidents, so compare original DropBox event time, not upload time.
