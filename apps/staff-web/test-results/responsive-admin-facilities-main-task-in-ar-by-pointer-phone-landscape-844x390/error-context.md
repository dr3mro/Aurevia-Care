# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: responsive-admin.spec.ts >> facilities: main task in ar by pointer
- Location: e2e/responsive-admin.spec.ts:1099:7

# Error details

```
Error: Channel closed
```

```
Error: locator.tap: Target page, context or browser has been closed
Browser logs:

<launching> /github/home/.cache/ms-playwright/chromium_headless_shell-1255/chrome-headless-shell-linux64/chrome-headless-shell --disable-field-trial-config --disable-background-networking --disable-background-timer-throttling --disable-backgrounding-occluded-windows --disable-back-forward-cache --disable-breakpad --disable-component-extensions-with-background-pages --disable-component-update --no-default-browser-check --disable-default-apps --disable-dev-shm-usage --disable-edgeupdater --disable-extensions --disable-features=AvoidUnnecessaryBeforeUnloadCheckSync,DestroyProfileOnBrowserClose,HttpsUpgrades,HttpsFirstBalancedModeAutoEnable,LensOverlay,MediaRouter,PaintHolding,ThirdPartyStoragePartitioning,AvoidCorsURLLoaderRestartOnRedirect,TcpPortRandomizationWin,OptimizationHints,NetworkTimeServiceQuerying,AimEnabled,PreconnectToSearch,msForceBrowserSignIn,msEdgeUpdateLaunchServicesPreferredVersion --enable-features=CDPScreenshotNewSurface,SkipListAccountsWithoutGaiaCookies --allow-pre-commit-input --disable-hang-monitor --disable-ipc-flooding-protection --disable-popup-blocking --disable-prompt-on-repost --disable-renderer-backgrounding --disable-updater-scheduler --force-color-profile=srgb --metrics-recording-only --no-first-run --password-store=basic --use-mock-keychain --no-service-autorun --disable-search-engine-choice-screen --unsafely-disable-devtools-self-xss-warnings --enable-unsafe-swiftshader --edge-skip-compat-layer-relaunch --disable-stack-profiler --disable-infobars --disable-sync --do-not-de-elevate --headless --hide-scrollbars --mute-audio --blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4 --no-sandbox --user-data-dir=/tmp/playwright_chromiumdev_profile-5CqtX7 --remote-debugging-pipe --no-startup-window
<launched> pid=24116
[pid=24116][err] [1125/000835.993514:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Failed to connect to socket /run/dbus/system_bus_socket: No such file or directory
[pid=24116][err] [1125/000835.993613:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Could not parse server address: Unknown address type (examples of valid types are "tcp" and on UNIX "unix")
[pid=24116][err] [1125/000835.998682:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Could not parse server address: Unknown address type (examples of valid types are "tcp" and on UNIX "unix")
[pid=24116][err] [1125/000835.998699:WARNING:dbus/object_proxy.cc:439] Failed to connect to NameOwnerChanged signal for object: /org/freedesktop/portal/desktop
[pid=24116][err] [1125/000835.998719:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Could not parse server address: Unknown address type (examples of valid types are "tcp" and on UNIX "unix")
[pid=24116][err] [1125/000835.998730:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Could not parse server address: Unknown address type (examples of valid types are "tcp" and on UNIX "unix")
[pid=24116][err] [1125/000835.999591:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Failed to connect to socket /run/dbus/system_bus_socket: No such file or directory
[pid=24116][err] [1125/000835.999631:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Failed to connect to socket /run/dbus/system_bus_socket: No such file or directory
[pid=24116][err] [1125/000836.032332:WARNING:media/gpu/vaapi/vaapi_wrapper.cc:1651] drmGetDevices2() has not found any devices
[pid=24116][err] [1125/000836.039954:WARNING:sandbox/policy/linux/sandbox_linux.cc:406] InitializeSandbox() called with multiple threads in process gpu-process.
[pid=24116][err] [1125/000836.052176:ERROR:dbus/object_proxy.cc:572] Failed to call method: org.freedesktop.DBus.NameHasOwner: object_path= /org/freedesktop/DBus: unknown error type: 
[pid=24116][err] [1125/000836.052524:ERROR:dbus/bus.cc:405] Failed to connect to the bus: Could not parse server address: Unknown address type (examples of valid types are "tcp" and on UNIX "unix")
[pid=24116][err] [1125/000836.053650:WARNING:device/bluetooth/dbus/bluez_dbus_manager.cc:209] Floss manager service not available, cannot set Floss enable/disable.
[pid=24116][err] [1125/000836.060778:ERROR:dbus/object_proxy.cc:572] Failed to call method: org.freedesktop.DBus.NameHasOwner: object_path= /org/freedesktop/DBus: unknown error type: 
[pid=24116][err] [1125/000836.458697:INFO:CONSOLE:934] "[vite] connecting...", source: http://127.0.0.1:5173/@vite/client (934)
[pid=24116][err] [1125/000836.490601:INFO:CONSOLE:1043] "[vite] connected.", source: http://127.0.0.1:5173/@vite/client (1043)
[pid=24116][err] [1125/000836.669194:INFO:CONSOLE:15802] "%cDownload the React DevTools for a better development experience: https://react.dev/link/react-devtools font-weight:bold", source: http://127.0.0.1:5173/node_modules/.vite/deps/react-dom_client.js?v=3d7fc8c2 (15802)
[pid=24116][err] [1125/000837.061051:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.132240:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.160865:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.186965:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.223108:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.249361:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000837.249980:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:SYN-FAC-01", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000838.460398:INFO:CONSOLE:934] "[vite] connecting...", source: http://127.0.0.1:5173/@vite/client (934)
[pid=24116][err] [1125/000838.477808:INFO:CONSOLE:1043] "[vite] connected.", source: http://127.0.0.1:5173/@vite/client (1043)
[pid=24116][err] [1125/000838.554164:INFO:CONSOLE:15802] "%cDownload the React DevTools for a better development experience: https://react.dev/link/react-devtools font-weight:bold", source: http://127.0.0.1:5173/node_modules/.vite/deps/react-dom_client.js?v=3d7fc8c2 (15802)
[pid=24116][err] [1125/000838.906544:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000838.996240:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000839.073928:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000839.136462:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000839.195450:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000839.226853:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000839.228539:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:SYN-FAC-01", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.333297:INFO:CONSOLE:934] "[vite] connecting...", source: http://127.0.0.1:5173/@vite/client (934)
[pid=24116][err] [1125/000840.349880:INFO:CONSOLE:1043] "[vite] connected.", source: http://127.0.0.1:5173/@vite/client (1043)
[pid=24116][err] [1125/000840.436642:INFO:CONSOLE:15802] "%cDownload the React DevTools for a better development experience: https://react.dev/link/react-devtools font-weight:bold", source: http://127.0.0.1:5173/node_modules/.vite/deps/react-dom_client.js?v=3d7fc8c2 (15802)
[pid=24116][err] [1125/000840.786340:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.848552:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.894131:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.929619:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.953454:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000840.995355:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000841.035477:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000841.035859:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:SYN-FAC-01", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000842.170421:INFO:CONSOLE:934] "[vite] connecting...", source: http://127.0.0.1:5173/@vite/client (934)
[pid=24116][err] [1125/000842.181569:INFO:CONSOLE:1043] "[vite] connected.", source: http://127.0.0.1:5173/@vite/client (1043)
[pid=24116][err] [1125/000842.286138:INFO:CONSOLE:15802] "%cDownload the React DevTools for a better development experience: https://react.dev/link/react-devtools font-weight:bold", source: http://127.0.0.1:5173/node_modules/.vite/deps/react-dom_client.js?v=3d7fc8c2 (15802)
[pid=24116][err] [1125/000842.657032:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116][err] [1125/000842.712651:INFO:CONSOLE:364] "Encountered two children with the same key, `%s`. Keys should be unique so that components maintain their identity across updates. Non-unique keys may cause children to be duplicated and/or omitted — the behavior is unsupported and could change in a future version. 11111111-1111-1111-1111-111111111111:MAIN", source: http://127.0.0.1:5173/@vite/client (364)
[pid=24116] <gracefully close start>
Call log:
  - waiting for getByRole('form', { name: 'إنشاء مرفق' }).locator('button[type="submit"]')
    - locator resolved to <button type="submit" class="touch" data-variant="primary" data-breakpoint="medium">إنشاء مرفق</button>
  - attempting tap action
    2 × waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <html lang="ar" dir="rtl" data-theme="light">…</html> intercepts pointer events
    - retrying tap action
    - waiting 20ms
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <html lang="ar" dir="rtl" data-theme="light">…</html> intercepts pointer events
  2 × retrying tap action
      - waiting 100ms
      - waiting for element to be visible, enabled and stable
      - element is visible, enabled and stable
      - scrolling into view if needed
      - done scrolling
      - <a href="#main" class="skip-link">تخطَّ إلى المحتوى الرئيسي</a> intercepts pointer events
  24 × retrying tap action
       - waiting 500ms
       - waiting for element to be visible, enabled and stable
       - element is visible, enabled and stable
       - scrolling into view if needed
       - done scrolling
       - <html lang="ar" dir="rtl" data-theme="light">…</html> intercepts pointer events
     - retrying tap action
       - waiting 500ms
       - waiting for element to be visible, enabled and stable
       - element is visible, enabled and stable
       - scrolling into view if needed
       - done scrolling
       - <html lang="ar" dir="rtl" data-theme="light">…</html> intercepts pointer events
     - retrying tap action
       - waiting 500ms
       - waiting for element to be visible, enabled and stable
       - element is visible, enabled and stable
       - scrolling into view if needed
       - done scrolling
       - <a href="#main" class="skip-link">تخطَّ إلى المحتوى الرئيسي</a> intercepts pointer events
     - retrying tap action
       - waiting 500ms
       - waiting for element to be visible, enabled and stable
       - element is visible, enabled and stable
       - scrolling into view if needed
       - done scrolling
       - <a href="#main" class="skip-link">تخطَّ إلى المحتوى الرئيسي</a> intercepts pointer events
  - retrying tap action
    - waiting 500ms
    - waiting for element to be visible, enabled and stable
    - element is visible, enabled and stable
    - scrolling into view if needed
    - done scrolling
    - <html lang="ar" dir="rtl" data-theme="light">…</html> intercepts pointer events
  - retrying tap action
    - waiting 500ms
    - waiting for element to be visible, enabled and stable

```

```
Error: browserContext.close: Target page, context or browser has been closed
```