# Buick-main T57 — restore Test-proven virtual-display path

Observed 2026-10-23 23:54:11+0800:
- The app stage ring ended near DISPLAY_CALLBACK_ENTER (23:54:10.875).
- Android DropBox recorded system_server NullPointerException in
  WindowContextListenerController, DisplayContent.onParentChanged and
  RootWindowContainer.onDisplayAdded at 23:54:11.058.
- Buick-Test and Buick-E5 successfully started virtual screens on the same phone;
  this narrows the investigation but does not yet prove one root cause.

Change:
- Remove the leftover T49 Sony-specific two-screen branch from the *running*
  main display launcher by selecting the current Buick-Test VirtualLauncher and
  HiddenDisplaySource implementation verbatim.
- Restore the normal Dock/App/AppHi/Map display creation and the per-screen
  CREATE_DOCK_BEGIN, CREATE_DOCK_OK, CREATE_APP_BEGIN and CREATE_APP_OK breadcrumbs
  already exercised in Test.
- Remove the now-unused Sony two-screen selector from SessionController.
- Preserve the main-only video/audio policy, pairing, Update and diagnostics.
- Physical validation after this commit remains required. Build success does
  not prove Xperia Android 15 is immune to system_server crashes.
