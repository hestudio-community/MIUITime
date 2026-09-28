package net.hestudio.miuitime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * [Info][LayoutInfo]  screen(index: 4, id:9, itemsCount:7 7)=&gt;(0,3,4-2):默认经典时钟（与锁屏同步）[id=1384,type=maml(19),pkg=com.android.deskclock#52b8238d-...](...)
 * [Info]AllAppsTransitionController setState: state=allAppsState, belowState=normalState
 * </pre>
 *
 * <p>Two gaps in that stream make a single-snapshot cache unreliable, so the
 * inventory is maintained from several sources instead:</p>
 * <ul>
 *     <li>at cold start the {@code screen()} snapshot is logged <em>before</em>
 *     workspace items are loaded ({@code itemsCount:0 0}) and never re-logged —
 *     the item load stream ({@code ItemDataProcessor processItem}) carries the
 *     real items and is parsed too;</li>
 *     <li>deleting a widget through its shortcut menu logs
 *     {@code [LauncherModelManager] deleteItem} but no fresh {@code screen()}
 *     snapshot — it must be parsed too, otherwise the clock stays hidden.</li>
 * </ul>
 *
 * <p>An empty or truncated snapshot only upserts what it actually parsed; it never
 * clears known items (deletions always come through {@code deleteItem} or the
 * drop snapshots). When in doubt the state fails toward showing the clock
 * (never mis-hide).</p>
 *
 * <p>Note: built-in MAML clock widgets can have an empty package in the log
 * ({@code pkg=#<uuid>} or {@code pkg=null}), so they are also matched by their
 * title.</p>
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
    private static final Pattern ITEMS_COUNT = Pattern.compile("itemsCount:(\\d+)\\s+(\\d+)");
    private static final Pattern ITEM = Pattern.compile(
            "\\((\\d+),(\\d+),(\\d+)-(\\d+)\\):([^\\[]*)\\[id=(\\d+),type=([a-zA-Z_0-9]+)\\((\\d+)\\),pkg=([^,\\]]*),ext=(-?\\d+)\\]");
    private static final Pattern LAUNCHER_STATE = Pattern.compile(
            "AllAppsTransitionController setState\\w*: state=(\\w+)");

    /**
     * Cold start item load, e.g.
     * {@code [ItemDataProcessor]: processItem type=ItemType.maml id=944 screenId=1 x=0 y=0
     * container=-100 pkg=null title=世界时钟 gadgetId=101410023 uri=... extendContainer=-1}.
     */
    private static final Pattern PROCESS_ITEM = Pattern.compile(
            "\\[ItemDataProcessor\\]:\\s*processItem type=(?:ItemType\\.)?(\\w+) id=(-?\\d+)"
                    + " screenId=(-?\\d+) x=-?\\d+ y=-?\\d+ container=(-?\\d+)"
                    + " pkg=(\\S+) title=(.*?) gadgetId=-?\\d+(?: uri=(\\S*))?");

    /**
     * Workspace persistence items, e.g.
     * {@code deleteItem id=1384 title=默认经典时钟（与锁屏同步） pkg=null type=19/ItemType.maml
     * screen=9 cell=(0,2) span=(4,2) container=-100 extendContainer=-1 sortMode=0
     * appwidgetId=101490163 provider=null productId=... uri=...}.
     * Shared by {@code insertItem}, {@code deleteItem} and the chunks of
     * {@code updateItemBatch count=N first10=[...]}.
     */
    private static final Pattern MODEL_ITEM = Pattern.compile(
            "(?<![A-Za-z])id=(-?\\d+) title=(.*?) pkg=(\\S+)"
                    + " type=(?:\\d+/)?(?:ItemType\\.)?(\\w+) screen=(-?\\d+)"
                    + " .*?container=(-?\\d+).*?appwidgetId=(-?\\d+)"
                    + " provider=(\\S+) productId=([^\\s\\]]*)");

    private static final Pattern MAML_RES_PRODUCT = Pattern.compile("maml/res/0/([^/]+)/");

    private static final String[] CLOCK_PACKAGES = {
            "com.android.deskclock",
            "com.android.alarmclock",
    };

    /** Desktop container in the launcher's item model (folders are positive, hotseat is -101). */
    private static final int CONTAINER_DESKTOP = -100;

    private static final class Item {
        final String key;
        final String productId;
        final int appwidgetId;
        final boolean clock;

        Item(String key, String productId, int appwidgetId, boolean clock) {
            this.key = key;
            this.productId = productId;
            this.appwidgetId = appwidgetId;
            this.clock = clock;
        }
    }

    /** screenId -&gt; (itemKey -&gt; item); itemKey is {@code id:<n>}, {@code aw:<n>} or {@code pid:<...>}. */
    private final Map<Integer, Map<String, Item>> inventory = new ConcurrentHashMap<>();
    /** productIds of known clock widgets, so later model lines match even with an empty title. */
    private final Set<String> clockProductIds = ConcurrentHashMap.newKeySet();

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

    void parse(String line) {
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
            currentScreenId = screenId;
            updateClockPage(computeClock(screenId));
            return;
        }

        m = SCREEN.matcher(line);
        if (m.find()) {
            int screenId = Integer.parseInt(m.group(1));
            List<Item> items = parseScreenItems(m.group(2));
            int declared = items.size();
            Matcher count = ITEMS_COUNT.matcher(line);
            if (count.find()) {
                declared = Math.max(Integer.parseInt(count.group(1)), Integer.parseInt(count.group(2)));
            }
            applySnapshot(screenId, items, declared);
            updateClockPage(computeClock(currentScreenId));
            return;
        }

        m = PROCESS_ITEM.matcher(line);
        if (m.find()) {
            int container = Integer.parseInt(m.group(4));
            int screenId = Integer.parseInt(m.group(3));
            if (container == CONTAINER_DESKTOP && screenId >= 0) {
                String pkg = m.group(5);
                String title = m.group(6).trim();
                String productId = productIdOf(pkg, m.group(7));
                boolean clock = isClockWidget(m.group(1), pkg, null, title, productId);
                if (clock) {
                    noteClockProduct(productId);
                    XLog.i("clock widget loaded: title=" + title + " type=" + m.group(1));
                }
                putItem(screenId, new Item("id:" + m.group(2), productId, -1, clock));
                updateClockPage(computeClock(currentScreenId));
            }
            return;
        }

        // [LauncherModelManager] <verb> ... — routed by verb so the duplicated
        // [StackMemberWriteDiag] "op=..." warning lines are never double counted.
        if (line.contains("[LauncherModelManager] deleteItem ")) {
            m = MODEL_ITEM.matcher(line);
            if (m.find()) {
                removeItem(m);
                updateClockPage(computeClock(currentScreenId));
            }
            return;
        }
        if (line.contains("[LauncherModelManager] insertItem ")) {
            m = MODEL_ITEM.matcher(line);
            if (m.find()) {
                upsertItem(m);
                updateClockPage(computeClock(currentScreenId));
            }
            return;
        }
        if (line.contains("[LauncherModelManager] updateItemBatch ")) {
            m = MODEL_ITEM.matcher(line);
            boolean changed = false;
            while (m.find()) {
                upsertItem(m);
                changed = true;
            }
            if (changed) {
                updateClockPage(computeClock(currentScreenId));
            }
        }
    }

    private void applySnapshot(int screenId, List<Item> items, int declared) {
        Map<String, Item> screen = inventory.computeIfAbsent(screenId, k -> new LinkedHashMap<>());
        if (!items.isEmpty() && items.size() >= declared) {
            // Complete snapshot: authoritative for this screen.
            Map<String, Item> next = new LinkedHashMap<>();
            for (Item item : items) {
                next.put(item.key, item);
            }
            inventory.put(screenId, next);
        } else {
            // Empty (cold start placeholder) or truncated snapshot: only upsert what
            // was parsed. Removals are tracked via deleteItem, so keeping the unknown
            // entries is the safe direction.
            for (Item item : items) {
                screen.put(item.key, item);
            }
        }
    }

    private void putItem(int screenId, Item item) {
        // The same widget may be re-keyed (aw:<n> at insert, id:<n> after its first
        // snapshot) or moved between screens: drop stale entries first.
        for (Map<String, Item> screen : inventory.values()) {
            for (Iterator<Map.Entry<String, Item>> it = screen.entrySet().iterator(); it.hasNext(); ) {
                Item existing = it.next().getValue();
                if (existing.key.equals(item.key)
                        || (item.appwidgetId >= 0 && existing.appwidgetId == item.appwidgetId)) {
                    it.remove();
                }
            }
        }
        inventory.computeIfAbsent(screenId, k -> new LinkedHashMap<>()).put(item.key, item);
    }

    private void upsertItem(Matcher m) {
        int id = Integer.parseInt(m.group(1));
        int screenId = Integer.parseInt(m.group(5));
        String type = m.group(4);
        String pkg = m.group(3);
        String title = m.group(2).trim();
        int appwidgetId = Integer.parseInt(m.group(7));
        String provider = m.group(8);
        String productId = emptyToNull(m.group(9));
        boolean clock = isClockWidget(type, pkg, provider, title, productId);
        if (clock) {
            noteClockProduct(productId);
            XLog.i("clock widget added: title=" + title + " type=" + type);
        }
        String key = id >= 0 ? "id:" + id
                : appwidgetId >= 0 ? "aw:" + appwidgetId
                : "pid:" + productId + ":" + screenId;
        putItem(screenId, new Item(key, productId, appwidgetId, clock));
    }

    private void removeItem(Matcher m) {
        int id = Integer.parseInt(m.group(1));
        String title = m.group(2).trim();
        int appwidgetId = Integer.parseInt(m.group(7));
        String productId = emptyToNull(m.group(9));
        boolean removedClock = false;
        for (Map<String, Item> screen : inventory.values()) {
            for (Iterator<Map.Entry<String, Item>> it = screen.entrySet().iterator(); it.hasNext(); ) {
                Item item = it.next().getValue();
                // id/appwidgetId identify one instance; productId only as a fallback for
                // entries that never got an id (two instances may share a productId).
                boolean match = ("id:" + id).equals(item.key)
                        || (appwidgetId >= 0 && item.appwidgetId == appwidgetId)
                        || (productId != null && item.key.startsWith("pid:") && productId.equals(item.productId));
                if (match) {
                    it.remove();
                    removedClock |= item.clock;
                }
            }
        }
        if (removedClock) {
            XLog.i("clock widget removed: title=" + title);
        }
    }

    private boolean computeClock(int screenId) {
        Map<String, Item> screen = inventory.get(screenId);
        if (screen == null) {
            return false;
        }
        for (Item item : screen.values()) {
            if (item.clock) {
                return true;
            }
        }
        return false;
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

    private void noteClockProduct(String productId) {
        if (productId != null && !productId.isEmpty()) {
            clockProductIds.add(productId);
        }
    }

    private List<Item> parseScreenItems(String items) {
        List<Item> result = new ArrayList<>();
        Matcher m = ITEM.matcher(items);
        while (m.find()) {
            String title = m.group(5).trim();
            String type = m.group(7);
            String pkg = m.group(9);
            int id = Integer.parseInt(m.group(6));
            String productId = productIdOf(pkg, null);
            boolean clock = isClockWidget(type, pkg, null, title, productId);
            if (clock) {
                noteClockProduct(productId);
            }
            result.add(new Item("id:" + id, productId, -1, clock));
        }
        return result;
    }

    private static String productIdOf(String pkg, String uri) {
        if (pkg != null) {
            int hash = pkg.indexOf('#');
            if (hash >= 0 && hash + 1 < pkg.length()) {
                return pkg.substring(hash + 1);
            }
        }
        if (uri != null && !uri.isEmpty() && !"null".equals(uri)) {
            Matcher m = MAML_RES_PRODUCT.matcher(uri);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty() || "null".equals(value)) ? null : value;
    }

    private boolean isClockWidget(String type, String pkg, String provider, String title, String productId) {
        if (!isWidgetType(type)) {
            return false;
        }
        String pkgBase = packageBase(pkg);
        if (isClockPackage(pkgBase)) {
            return true;
        }
        if (isClockPackage(packageBase(provider))) {
            return true;
        }
        // Built-in MAML widgets have no package: fall back to the title, or to a
        // productId already known to belong to a clock widget.
        return (pkgBase.isEmpty() && isClockTitle(title))
                || (productId != null && clockProductIds.contains(productId));
    }

    private static String packageBase(String pkg) {
        String base = emptyToNull(pkg);
        if (base == null) {
            return "";
        }
        int hash = base.indexOf('#');
        if (hash >= 0) {
            base = base.substring(0, hash);
        }
        int slash = base.indexOf('/');
        if (slash >= 0) {
            base = base.substring(0, slash);
        }
        return base;
    }

    /** Only widget items count; the clock app icon is type=application and must not match. */
    private static boolean isWidgetType(String type) {
        String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return lower.equals("maml") || lower.startsWith("appwidget") || lower.startsWith("gadget");
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
        if (title == null || title.isEmpty()) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("时钟") || lower.contains("時鐘") || lower.contains("clock");
    }

    // ---- package-private accessors for tests ----

    int currentScreenId() {
        return currentScreenId;
    }

    boolean clockPage() {
        return clockPage;
    }

    boolean hasClockItem(int screenId) {
        return computeClock(screenId);
    }
}
