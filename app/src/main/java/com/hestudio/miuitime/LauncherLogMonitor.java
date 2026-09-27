package com.hestudio.miuitime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the launcher's own logcat output.
 *
 * <p>HyperOS 4's launcher is a native process (hyos_spawner + libapp_launcher.so)
 * so no Xposed module can be injected into it. Fortunately the launcher logs its
 * workspace layout on logcat, and SystemUI runs as uid system with the log group,
 * so it can read those lines:</p>
 *
 * <pre>
 * [Info][LayoutInfo]  overview: totalPages=5, currentPageIndex=4, currentScreenId=9, screenIds=[1, 2, 4, 8, 9]
 * [Info][LayoutInfo]  screen(index: 4, id:9, itemsCount:7 7)=>(0,3,4-2):默认经典时钟（与锁屏同步）[id=1384,type=maml(19),pkg=com.android.deskclock#52b8238d-...](...)
 * [Info]AllAppsTransitionController setState: state=allAppsState, belowState=normalState
 * </pre>
 *
 * <p>Note: built-in MAML clock widgets can have an empty package in the log
 * ({@code pkg=#<uuid>}), so they are also matched by their title.</p>
 */
public final class LauncherLogMonitor {

    public interface Listener {
        void onLauncherStateChanged(boolean clockPage, boolean overlayShowing);
    }

    private static final String LOGCAT = "/system/bin/logcat";
    private static final String FLUTTER_TAG = "flutter:I";

    private static final Pattern OVERVIEW = Pattern.compile(
            "\\[Info\\]\\[LayoutInfo\\]\\s+overview:.*?currentScreenId=(\\d+)");
    private static final Pattern SCREEN = Pattern.compile(
            "\\[Info\\]\\[LayoutInfo\\]\\s+screen\\(index:\\s*\\d+,\\s*id:(\\d+),.*?\\)=>(.*)$");
    private static final Pattern ITEM = Pattern.compile(
            "\\((\\d+),(\\d+),(\\d+)-(\\d+)\\):([^\\[]*)\\[id=(\\d+),type=([a-zA-Z_0-9]+)\\((\\d+)\\),pkg=([^,\\]]*),ext=(-?\\d+)\\]");
    private static final Pattern LAUNCHER_STATE = Pattern.compile(
            "AllAppsTransitionController setState\\w*: state=(\\w+)");

    private static final String[] CLOCK_PACKAGES = {
            "com.android.deskclock",
            "com.android.alarmclock",
    };

    private final Map<Integer, Boolean> screenHasClock = new ConcurrentHashMap<>();

    private volatile Listener listener;
    private volatile int currentScreenId = -1;
    private volatile boolean clockPage;
    private volatile boolean overlayShowing;
    private volatile String launcherState = "normalState";
    private volatile boolean running;
    private Thread worker;

    public void start(Listener listener) {
        this.listener = listener;
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "MIUITimeLogcat");
        worker.setDaemon(true);
        worker.start();
    }

    private void runLoop() {
        while (running) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(LOGCAT, "-v", "brief", "-s", FLUTTER_TAG);
                builder.redirectErrorStream(true);
                process = builder.start();
                XLog.i("launcher logcat reader started");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (running && (line = reader.readLine()) != null) {
                    try {
                        parse(line);
                    } catch (Throwable t) {
                        XLog.w("log parse error: " + line, t);
                    }
                }
            } catch (Throwable t) {
                XLog.w("launcher logcat reader failed", t);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
            if (running) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void parse(String line) {
        Matcher m = LAUNCHER_STATE.matcher(line);
        if (m.find()) {
            String state = m.group(1);
            boolean overlay = !"normalState".equals(state);
            if (!state.equals(launcherState) || overlay != overlayShowing) {
                launcherState = state;
                overlayShowing = overlay;
                XLog.i("launcher state: " + state);
                notifyState();
            }
            return;
        }

        m = OVERVIEW.matcher(line);
        if (m.find()) {
            int screenId = Integer.parseInt(m.group(1));
            boolean changed = screenId != currentScreenId;
            currentScreenId = screenId;
            Boolean hasClock = screenHasClock.get(screenId);
            if (hasClock != null) {
                updateClockPage(hasClock);
            } else if (changed) {
                XLog.i("current screen id=" + screenId + " (inventory pending)");
            }
            return;
        }

        m = SCREEN.matcher(line);
        if (m.find()) {
            int screenId = Integer.parseInt(m.group(1));
            boolean hasClock = containsOfficialClock(m.group(2));
            screenHasClock.put(screenId, hasClock);
            if (screenId == currentScreenId) {
                updateClockPage(hasClock);
            }
        }
    }

    private void updateClockPage(boolean hasClock) {
        if (clockPage != hasClock) {
            clockPage = hasClock;
            XLog.i("current page has clock=" + hasClock);
            notifyState();
        }
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) {
            l.onLauncherStateChanged(clockPage, overlayShowing);
        }
    }

    private static boolean containsOfficialClock(String items) {
        Matcher m = ITEM.matcher(items);
        while (m.find()) {
            String title = m.group(5).trim();
            String type = m.group(7);
            String pkg = m.group(9);
            if (!isWidgetType(type)) {
                continue;
            }
            String pkgBase = pkg;
            int hash = pkgBase.indexOf('#');
            if (hash >= 0) {
                pkgBase = pkgBase.substring(0, hash);
            }
            if (isClockPackage(pkgBase)) {
                XLog.i("clock widget: title=" + title + " type=" + type + " pkg=" + pkg);
                return true;
            }
            if (pkgBase.isEmpty() && isClockTitle(title)) {
                XLog.i("clock widget (built-in): title=" + title + " type=" + type);
                return true;
            }
        }
        return false;
    }

    /** Only widget items count; the clock app icon is type=application and must not match. */
    private static boolean isWidgetType(String type) {
        return "maml".equals(type) || type.startsWith("appwidget") || type.startsWith("gadget");
    }

    private static boolean isClockPackage(String pkgBase) {
        for (String clockPackage : CLOCK_PACKAGES) {
            if (pkgBase.startsWith(clockPackage)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClockTitle(String title) {
        if (title.isEmpty()) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("时钟") || lower.contains("时鐘") || lower.contains("clock");
    }
}
