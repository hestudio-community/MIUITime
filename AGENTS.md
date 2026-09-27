# AGENTS.md

MIUITime: LSPosed/Xposed module (legacy API 82, pure Java, no UI) that restores the MIUI 12h status bar clock on HyperOS and hides the status bar clock on launcher pages containing an official clock widget.

## Build / verify / deploy
- Build: `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`). Static check: `./gradlew lintDebug`. No unit tests exist.
- JDK is pinned in `gradle.properties` (`org.gradle.java.home` → Android Studio bundled JBR, OpenJDK 25); AGP 9.4.1 requires JDK 17+ and Gradle 9.x (wrapper is 9.7.1, needed to run on JDK 25).
- `app/build.gradle.kts` sets `enableKotlin = false` (the supported per-module opt-out of AGP 9 built-in Kotlin); without it AGP injects kotlin-stdlib into this pure-Java module (~2.4MB APK). Don't replace it with `android.builtInKotlin=false` in `gradle.properties` — that flag is deprecated and removed in AGP 10.
- Deploy: `./gradlew installDebug` (builds and installs in one step; the LSPosed-recommended install path for module developers). With multiple devices attached, set `ANDROID_SERIAL=<id>` first.
- A first install of a new package may fail with `INSTALL_FAILED_USER_RESTRICTED`; retry while the phone screen is unlocked (an on-device confirm dialog appears).
- `installDebug` signs with `~/.android/debug.keystore`; if a differently-signed build (e.g. `app/release/app-release.apk`) is installed on the phone, uninstall it first or install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Reload after install: `adb shell am crash com.android.systemui` (`am force-stop` is ignored for SystemUI). Verify with `adb logcat -s MIUITime:V`.
- This directory is not a git repository; don't assume git history/commands.
- Target device verified: Xiaomi HyperOS 4 (Android 17), SystemUI `17.03.260226`, launcher `8.01.02.7719`.

## Non-obvious architecture
- **Never try to hook `com.miui.home`.** The HyperOS 4 launcher is a native process (`hasCode=false`, spawned by `hyos_spawner`, entry `libapp_launcher.so`); LSPosed cannot inject into it (HyperCeiler can't either). `LauncherBridge`/`WidgetRecon` are unreachable reference code.
- Feature 2 detection lives entirely in SystemUI and parses the launcher's own logcat (`LauncherLogMonitor`). SystemUI runs as uid 1000 with gid 1007 (log), so it may read logs. Signals:
  - `[Info][LayoutInfo] overview: ... currentScreenId=N` + `screen(index: N, id:S, ...)=>(items)` — settled page layout incl. widget title/type/pkg.
  - `AllAppsTransitionController setState...: state=...` — `normalState` vs `allAppsState`/`overViewState`/`assistantOverlayState`; only `normalState` may hide the clock.
- Clock widget matching: widget items (`type=maml|appwidget|gadget`) from `com.android.deskclock`/`com.android.alarmclock`, OR built-in MAML with an empty package (`pkg=#<uuid>`) whose title contains `时钟`/`clock`. Never match `type=application` (that's the clock app icon).
- Hide/show reuses `HomeStatusBarViewBinderInjector.hideClock/showClock(false)` → `MiuiClock.setPolicyVisibility(4|0)`, plus a `View.GONE` collapse of the clock so its layout width is freed (plain INVISIBLE leaves a blank gap in front of the status bar notification icons when 通知栏图标显示 is on); `ClockPageController` hooks `setPolicyVisibility`/`View.setVisibility` to enforce the hidden state.
- Foreground test: `ActivityManagerWrapper.sInstance` (OS4 removed `getInstance()`) + home-activity whitelist (`com.miui.home.launcher.Launcher`, `SecondaryDisplayLauncher`, `safemode.SafeLauncher`). All other `com.miui.home` activities (settings/recents/pickers) must show the clock. Fallback: `ActivityTaskManager.getService().getTasks(1)`.
- Feature 1 hooks `MiuiClock.updateTime()` and only rewrites instances whose resource entry id is `clock`; 24h mode is left untouched. Format is `aa h:mm` (→ `下午 3:48`).
- Scope is `com.android.systemui` only. Recommended scope is the manifest meta-data `xposedscope` → `@array/xposed_scope` (`res/values/arrays.xml`); an `assets/xposed_scope` file is ignored by LSPosed and was removed on purpose.
- Entry: `assets/xposed_init` → `net.hestudio.miuitime.XposedEntry`.

## Working on it
- Log tag is `MIUITime`; `XLog` mirrors to logcat and the LSPosed log.
- Launcher log parsing is undocumented and may change with launcher updates: keep regexes tolerant and fail inert (never mis-hide). `ClockPageController` adds a 250ms apply debounce, 3s polling while on a clock page, and a retry after SystemUI cold start.
- Device regression checklist for feature 2: page swipe clock↔non-clock, drawer open/close, recents, assistant overlay, open app + return, launcher settings, SystemUI restart (state must self-heal).
- No UI by design (no launcher activity); install/manage via adb + LSPosed only. Don't re-add an activity unless asked.
