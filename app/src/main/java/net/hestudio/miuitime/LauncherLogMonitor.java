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
 *     <li>dragging one widget onto another re-parents it into a stacked widget
 *     and is logged as {@code [LauncherModelManager] updateItem} (singular, not
 *     a batch) — if it is not parsed the old standalone entry survives and the
 *     clock stays hidden no matter which stack member is displayed.</li>
 * </ul>
 *
 * <p>An empty or truncated snapshot only upserts what it actually parsed; it never
 * clears known items (deletions always come through {@code deleteItem} or the
 * drop snapshots). When in doubt the state fails toward showing the clock
 * (never mis-hide).</p>
 *
 * <p>Stacked widgets (堆叠组件) need extra care: several widgets share one slot
 * and only the top member is visible. The {@code screen()} snapshot shows only
 * the container ({@code type=stackedWidget}) whose title must never be matched
 * as a clock; members are listed in the item streams with
 * {@code extendContainer=<containerId>} and are tracked in a separate stack
 * registry (a complete snapshot replacement would otherwise drop them). A
 * stacked page only hides the clock while its <em>top</em> member is a clock
 * widget — the top is followed via the launcher's own
 * {@code [STACK-CONSISTENCY] getCurrentDisplayWidget},
 * {@code MamlVisibility ... stackBlocked=false} and
 * {@code onVisible ... tag=<id>_stacked} lines; an unknown top fails toward
 * showing.</p>
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
     * {@code gadgetId} is the same instance identity the workspace model logs as
     * {@code appwidgetId}, so it links item-load entries to {@code aw:<n>} keys.
     * {@code extendContainer} is the id of the stacked-widget container the item
     * belongs to ({@code -1} = standalone).
     */
    private static final Pattern PROCESS_ITEM = Pattern.compile(
            "\\[ItemDataProcessor\\]:\\s*processItem type=(?:ItemType\\.)?(\\w+) id=(-?\\d+)"
                    + " screenId=(-?\\d+) x=-?\\d+ y=-?\\d+ container=(-?\\d+)"
                    + " pkg=(\\S+) title=(.*?) gadgetId=(-?\\d+)(?: uri=(\\S*))?"
                    + "(?:\\s+extendContainer=(-?\\d+))?");

    /**
     * Workspace persistence items, e.g.
     * {@code deleteItem id=1384 title=默认经典时钟（与锁屏同步） pkg=null type=19/ItemType.maml
     * screen=9 cell=(0,2) span=(4,2) container=-100 extendContainer=-1 sortMode=0
     * appwidgetId=101490163 provider=null productId=... uri=...}.
     * Shared by {@code insertItem}, {@code deleteItem}, {@code updateItem} and the
     * chunks of {@code updateItemBatch count=N first10=[...]}. Only
     * {@code container=-100} (desktop) items count; folder and hotseat items never
     * hide the clock. {@code extendContainer} links a stacked-widget member to its
     * container ({@code appwidgetId} is {@code null} for stack containers).
     */
    private static final Pattern MODEL_ITEM = Pattern.compile(
            "(?<![A-Za-z])id=(-?\\d+) title=(.*?) pkg=(\\S+)"
                    + " type=(?:\\d+/)?(?:ItemType\\.)?(\\w+) screen=(-?\\d+)"
                    + " .*?container=(-?\\d+)(?:.*?extendContainer=(-?\\d+))?"
                    + ".*?appwidgetId=(?:null|(-?\\d+))"
                    + " provider=(\\S+) productId=([^\\s\\]]*)");

    /**
     * Stacked-widget (堆叠组件) top-of-stack changes, e.g.
     * {@code [Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget:
     * currentDisplayWidgetId=1388 != last=1386, stackId=1387}.
     */
    private static final Pattern STACK_TOP = Pattern.compile(
            "\\[STACK-CONSISTENCY\\] getCurrentDisplayWidget: currentDisplayWidgetId=(-?\\d+)"
                    + "(?:\\s*!=\\s*last=-?\\d+)?,\\s*stackId=(-?\\d+)");

    /**
     * The currently visible member of a stack is the one evaluated with
     * {@code stackBlocked=false}, e.g.
     * {@code MamlVisibility | evaluate | mamlId=5 ... tag=1386_stacked title=经典时钟 |
     * source=scrollEnd visible=true isResumed=false stackBlocked=false}.
     * Fires at launcher cold start too, carrying the initial top member.
     */
    private static final Pattern STACK_TOP_BLOCKED = Pattern.compile(
            "MamlVisibility \\| evaluate \\| mamlId=-?\\d+ .*?\\btag=(-?\\d+)_stacked\\b"
                    + ".*?\\bstackBlocked=(true|false)");

    /**
     * A stacked member becoming visible is a top change, e.g.
     * {@code [MamlWidgetGetxController] MamlWidgetGetxController onVisible | mamlId=4 tag=1388_stacked}.
     */
    private static final Pattern STACK_MEMBER_VISIBLE = Pattern.compile(
            "MamlWidgetGetxController onVisible \\| mamlId=-?\\d+ tag=(-?\\d+)_stacked");

    /**
     * Member id+title facts, e.g.
     * {@code MamlVisibility | evaluate | mamlId=5 ... tag=1386_stacked title=经典时钟 | ...}.
     * Used as a fallback when the item streams (and thus the stack registry) were
     * missed — e.g. after a SystemUI restart, when the launcher's one-shot cold
     * start lines have already rolled out of the logcat ring buffer.
     */
    private static final Pattern STACK_MEMBER_TITLE = Pattern.compile(
            "tag=(-?\\d+)_stacked title=([^|]*?) \\|");

    /**
     * The stack controller logging the displayed widget's own title, e.g.
     * {@code [WidgetStackGetXController] WidgetStackGetXController _updateTitleFromWidget:
     * widgetId=1386, title='经典时钟', appName='时钟', label='经典时钟', resolved='时钟'}.
     */
    private static final Pattern STACK_TITLE_WIDGET = Pattern.compile(
            "_updateTitleFromWidget: widgetId=(-?\\d+), title='([^']*)', appName='([^']*)'");

    /**
     * Member id list of one stack, re-logged whenever the stack order is flushed,
     * e.g. {@code [WidgetStackContainer] _flushLocalDataToController:
     * stackTag=widget_stack_1387, localIds=[1388, 1386], localCount=2, ...} —
     * recovers member-to-stack binding when the item streams were missed.
     */
    private static final Pattern FLUSH_MEMBERS = Pattern.compile(
            "_flushLocalDataToController: stackTag=widget_stack_(\\d+), localIds=\\[([^\\]]*)\\]");

    /**
     * Stack visibility on the current page, e.g.
     * {@code [WidgetStackContainer] stack exposure valid start: stackTag=widget_stack_1387,
     * reason=visibilityChanged, visibleFraction=1.0} — the only line that names a
     * stack while it is on screen when the item streams have already rolled out of
     * the logcat ring buffer (e.g. after a SystemUI restart).
     */
    private static final Pattern STACK_EXPOSURE_START = Pattern.compile(
            "stack exposure valid start: stackTag=widget_stack_(\\d+),.*visibleFraction=([\\d.]+)");

    /** Exposure end, e.g. {@code track stack expose: stackTag=widget_stack_1387, reason=pageInvisible, ...}. */
    private static final Pattern STACK_EXPOSURE_END = Pattern.compile(
            "track stack expose: stackTag=widget_stack_(\\d+)");

    /**
     * Member id list from the stack's own state line, e.g.
     * {@code [WidgetStackContainer] [StackState] stackTag=widget_stack_4, explode true → false,
     * reason=editMode, members=2, ids=[127, 126]} — names both the stack and its members.
     */
    private static final Pattern STACK_STATE_MEMBERS = Pattern.compile(
            "\\[StackState\\] stackTag=widget_stack_(\\d+),.*?ids=\\[([^\\]]*)\\]");

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
        final int itemId;
        final boolean clock;
        /** Stacked-widget member: id of its container item ({@code -1} = standalone). */
        final int stackId;
        /** The item is a stacked-widget container (its members live in {@link StackInfo}). */
        final boolean stackContainer;

        Item(String key, String productId, int appwidgetId, int itemId, boolean clock,
                int stackId, boolean stackContainer) {
            this.key = key;
            this.productId = productId;
            this.appwidgetId = appwidgetId;
            this.itemId = itemId;
            this.clock = clock;
            this.stackId = stackId;
            this.stackContainer = stackContainer;
        }
    }

    /** Members and current top of one stacked-widget container. */
    private static final class StackInfo {
        final int stackId;
        /** memberKey -&gt; item. */
        final Map<String, Item> members = new LinkedHashMap<>();
        /** Item id of the member currently on top, {@code -1} = unknown (fail toward show). */
        int topId = -1;
        /** The stack is on the currently visible page (from exposure lines). */
        volatile boolean exposed;

        StackInfo(int stackId) {
            this.stackId = stackId;
        }
    }

    /** screenId -&gt; (itemKey -&gt; item); itemKey is {@code id:<n>}, {@code aw:<n>} or {@code pid:<...>}. */
    private final Map<Integer, Map<String, Item>> inventory = new ConcurrentHashMap<>();
    /** stackContainerId -&gt; stack members + current top. */
    private final Map<Integer, StackInfo> stacks = new ConcurrentHashMap<>();
    /** memberItemId -&gt; stackContainerId, to resolve top signals that only carry a member id. */
    private final Map<Integer, Integer> memberStack = new ConcurrentHashMap<>();
    /** memberItemId -&gt; isClock, learned from title-carrying stack lines (registry fallback). */
    private final Map<Integer, Boolean> memberClockHints = new ConcurrentHashMap<>();
    /** stackContainerId -&gt; page it sits on, learned from exposure/container lines. */
    private final Map<Integer, Integer> stackScreen = new ConcurrentHashMap<>();
    /** Latest stacked-member-visible event that could not be bound to a stack yet. */
    private volatile int pendingTopMember = -1;
    /** productIds of known clock widgets, so later model lines match even with an empty title. */
    private final Set<String> clockProductIds = ConcurrentHashMap.newKeySet();

    private volatile Listener listener;
    private volatile int currentScreenId = -1;
    private volatile boolean clockPage;
    private volatile boolean overlayShowing;
    private volatile String launcherState = "normalState";
    private volatile boolean running;
    private Thread worker;
    private volatile Process process;

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

    public void stop() {
        running = false;
        Process p = process;
        if (p != null) {
            p.destroy();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
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
                Process p = process;
                process = null;
                if (p != null) {
                    p.destroy();
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
                int itemId = Integer.parseInt(m.group(2));
                int gadgetId = Integer.parseInt(m.group(7));
                int stackId = m.group(9) != null ? Integer.parseInt(m.group(9)) : -1;
                String productId = productIdOf(pkg, m.group(8));
                boolean clock = isClockWidget(m.group(1), pkg, null, title, productId);
                if (clock) {
                    noteClockProduct(productId);
                    XLog.i("clock widget loaded: id=" + itemId + " screen=" + screenId
                            + " ext=" + stackId + " title=" + title + " type=" + m.group(1));
                }
                Item item = new Item("id:" + itemId, productId, gadgetId, itemId, clock,
                        stackId, isStackContainerType(m.group(1)));
                if (stackId >= 0) {
                    putMember(stackId, item);
                } else {
                    putItem(screenId, item);
                }
                updateClockPage(computeClock(currentScreenId));
            }
            return;
        }

        // Member id+title facts are collected from every matching line (also from
        // lines that the returning matchers below consume), so they run first.
        m = STACK_TITLE_WIDGET.matcher(line);
        if (m.find()) {
            int widgetId = Integer.parseInt(m.group(1));
            noteMemberHint(widgetId, m.group(2), m.group(3));
            // The stack controller names the widget it currently displays; this is
            // the authoritative displayed-member signal (it also fires on page
            // re-entry, when onVisible/evaluate can still report a stale member).
            noteDisplayedWidget(widgetId);
        } else {
            m = STACK_MEMBER_TITLE.matcher(line);
            if (m.find()) {
                noteMemberHint(Integer.parseInt(m.group(1)), m.group(2), null);
            }
        }

        m = STACK_TOP.matcher(line);
        if (m.find()) {
            setStackTop(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            return;
        }
        m = STACK_TOP_BLOCKED.matcher(line);
        if (m.find()) {
            if ("false".equals(m.group(2))) {
                setStackTopByMemberIfUnknown(Integer.parseInt(m.group(1)));
            }
            return;
        }
        m = STACK_MEMBER_VISIBLE.matcher(line);
        if (m.find()) {
            // onVisible alone can be a stale warm-up event on page re-entry; the
            // displayed-title and STACK-CONSISTENCY lines are authoritative. Only an
            // unknown top may be adopted from here (same rule as the evaluate line).
            setStackTopByMemberIfUnknown(Integer.parseInt(m.group(1)));
            return;
        }
        m = FLUSH_MEMBERS.matcher(line);
        if (m.find()) {
            bindStackMembers(Integer.parseInt(m.group(1)), m.group(2));
            return;
        }
        m = STACK_STATE_MEMBERS.matcher(line);
        if (m.find()) {
            bindStackMembers(Integer.parseInt(m.group(1)), m.group(2));
            return;
        }
        m = STACK_EXPOSURE_START.matcher(line);
        if (m.find()) {
            markStackExposed(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)));
            return;
        }
        m = STACK_EXPOSURE_END.matcher(line);
        if (m.find()) {
            markStackHidden(Integer.parseInt(m.group(1)));
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
            return;
        }
        // Dragging a widget onto another one re-parents it into a stack and is
        // logged as "updateItem" (singular) — the same payload shape as insertItem.
        // Must come after updateItemBatch (whose name contains "updateItem" too,
        // but the trailing space in the marker keeps the two apart).
        if (line.contains("[LauncherModelManager] updateItem ")) {
            m = MODEL_ITEM.matcher(line);
            if (m.find()) {
                upsertItem(m);
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
                noteStackContainer(item, screenId);
            }
            inventory.put(screenId, next);
            if (screenId == currentScreenId) {
                // Exposure without the container on the settled current page means
                // the stack moved or was dropped — fail toward showing the clock.
                for (StackInfo stack : stacks.values()) {
                    if (stack.exposed && !isOnScreen(next, stack.stackId)) {
                        stack.exposed = false;
                    }
                    if (!stack.exposed && !isOnAnyScreen(stack.stackId)
                            && !stackScreen.containsKey(stack.stackId)) {
                        // The stack is not known to live on any page at all: a stale
                        // top must not keep hiding the clock (fail toward showing).
                        stack.topId = -1;
                    }
                }
            }
        } else {
            // Empty (cold start placeholder) or truncated snapshot: only upsert what
            // was parsed. Removals are tracked via deleteItem, so keeping the unknown
            // entries is the safe direction.
            for (Item item : items) {
                screen.put(item.key, item);
                noteStackContainer(item, screenId);
            }
        }
    }

    /** Records which page a stack container sits on (container items carry the screen). */
    private void noteStackContainer(Item item, int screenId) {
        if (item.stackContainer && item.itemId >= 0) {
            noteStackScreen(item.itemId, screenId);
        }
    }

    private void noteStackScreen(int stackId, int screenId) {
        if (stackId < 0 || screenId < 0) {
            return;
        }
        Integer previous = stackScreen.put(stackId, screenId);
        if (previous == null || previous != screenId) {
            XLog.i("stack screen: stack=" + stackId + " screen=" + screenId);
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
        // A widget leaving a stack (member -> desktop) must not linger in the stack
        // registry: a stale member that is (or becomes) the top would hide the clock
        // regardless of the visible widget (fail toward showing).
        dropMatchingMembers(item);
        if (item.stackContainer && item.itemId >= 0) {
            stacks.computeIfAbsent(item.itemId, StackInfo::new);
            noteStackScreen(item.itemId, screenId);
        }
        inventory.computeIfAbsent(screenId, k -> new LinkedHashMap<>()).put(item.key, item);
    }

    /** Removes stack members that are the same physical widget as the flat {@code item}. */
    private void dropMatchingMembers(Item item) {
        boolean changed = false;
        for (StackInfo stack : stacks.values()) {
            boolean removedHere = false;
            for (Iterator<Map.Entry<String, Item>> it = stack.members.entrySet().iterator(); it.hasNext(); ) {
                Item member = it.next().getValue();
                if (member.key.equals(item.key)
                        || (item.appwidgetId >= 0 && member.appwidgetId == item.appwidgetId)
                        || (item.productId != null && member.key.startsWith("pid:")
                                && item.productId.equals(member.productId))) {
                    it.remove();
                    removedHere = true;
                    changed = true;
                    if (member.itemId >= 0) {
                        memberStack.remove(member.itemId);
                        if (stack.topId == member.itemId) {
                            stack.topId = -1;
                        }
                    }
                }
            }
            if (removedHere && stack.members.isEmpty()) {
                stacks.remove(stack.stackId);
            }
        }
        if (changed) {
            updateClockPage(computeClock(currentScreenId));
        }
    }

    private void upsertItem(Matcher m) {
        // Same container rule as PROCESS_ITEM: only desktop items belong to a
        // workspace page; folder (positive) and hotseat (-101) items must not
        // affect the per-page clock state.
        if (Integer.parseInt(m.group(6)) != CONTAINER_DESKTOP) {
            return;
        }
        int id = Integer.parseInt(m.group(1));
        int screenId = Integer.parseInt(m.group(5));
        String type = m.group(4);
        String pkg = m.group(3);
        String title = m.group(2).trim();
        int appwidgetId = parseAppwidgetId(m.group(8));
        String provider = m.group(9);
        String productId = emptyToNull(m.group(10));
        int stackId = m.group(7) != null ? Integer.parseInt(m.group(7)) : -1;
        boolean clock = isClockWidget(type, pkg, provider, title, productId);
        if (clock) {
            noteClockProduct(productId);
            XLog.i("clock widget added: id=" + id + " screen=" + screenId
                    + " ext=" + stackId + " title=" + title + " type=" + type);
        }
        String key = id >= 0 ? "id:" + id
                : appwidgetId >= 0 ? "aw:" + appwidgetId
                : "pid:" + productId + ":" + screenId;
        Item item = new Item(key, productId, appwidgetId, id, clock,
                stackId, isStackContainerType(type));
        if (stackId >= 0) {
            putMember(stackId, item);
        } else {
            putItem(screenId, item);
        }
    }

    private void removeItem(Matcher m) {
        // Mirrors upsertItem: folder/hotseat items are never inventoried, and the
        // productId fallback must not let them remove a same-product desktop entry.
        if (Integer.parseInt(m.group(6)) != CONTAINER_DESKTOP) {
            return;
        }
        int id = Integer.parseInt(m.group(1));
        String title = m.group(2).trim();
        int appwidgetId = parseAppwidgetId(m.group(8));
        String productId = emptyToNull(m.group(10));
        int stackId = m.group(7) != null ? Integer.parseInt(m.group(7)) : -1;
        if (stackId >= 0) {
            if (removeMember(stackId, id, appwidgetId, productId)) {
                XLog.i("stack member removed: stack=" + stackId + " id=" + id + " title=" + title);
            }
            // The same instance may still sit in the flat inventory (missed move or
            // an earlier standalone phase); never let it linger there.
            removeFlatEntries("id:" + id, appwidgetId, productId);
            updateClockPage(computeClock(currentScreenId));
            return;
        }
        boolean removedClock = removeFlatEntries("id:" + id, appwidgetId, productId);
        if (removedClock) {
            XLog.i("clock widget removed: id=" + id + " title=" + title);
        }
    }

    /**
     * Removes matching desktop entries from the flat inventory. id/appwidgetId
     * identify one instance; productId only as a fallback for entries that never
     * got an id (two instances may share a productId).
     */
    private boolean removeFlatEntries(String key, int appwidgetId, String productId) {
        boolean removedClock = false;
        for (Map<String, Item> screen : inventory.values()) {
            for (Iterator<Map.Entry<String, Item>> it = screen.entrySet().iterator(); it.hasNext(); ) {
                Item item = it.next().getValue();
                boolean match = key.equals(item.key)
                        || (appwidgetId >= 0 && item.appwidgetId == appwidgetId)
                        || (productId != null && item.key.startsWith("pid:") && productId.equals(item.productId));
                if (match) {
                    it.remove();
                    removedClock |= item.clock;
                    if (item.stackContainer && item.itemId >= 0) {
                        dropStack(item.itemId);
                    }
                }
            }
        }
        return removedClock;
    }

    private void putMember(int stackId, Item member) {
        StackInfo stack = stacks.computeIfAbsent(stackId, StackInfo::new);
        // Re-keying (aw: -> id:) or moves between stacks: drop stale entries first.
        for (Map.Entry<Integer, StackInfo> entry : stacks.entrySet()) {
            StackInfo other = entry.getValue();
            for (Iterator<Map.Entry<String, Item>> it = other.members.entrySet().iterator(); it.hasNext(); ) {
                Item existing = it.next().getValue();
                if (existing.key.equals(member.key)
                        || (member.appwidgetId >= 0 && existing.appwidgetId == member.appwidgetId)) {
                    it.remove();
                    if (existing.itemId >= 0) {
                        memberStack.remove(existing.itemId);
                        if (other.topId == existing.itemId && other != stack) {
                            other.topId = -1;
                        }
                    }
                }
            }
        }
        // A widget that moves from the desktop into a stack must not linger as a
        // flat page item: the stale entry would hide the clock regardless of the
        // visible stack top (fail toward showing).
        boolean mergedClock = false;
        for (Map<String, Item> screen : inventory.values()) {
            for (Iterator<Map.Entry<String, Item>> it = screen.entrySet().iterator(); it.hasNext(); ) {
                Item existing = it.next().getValue();
                if (existing.key.equals(member.key)
                        || (member.appwidgetId >= 0 && existing.appwidgetId == member.appwidgetId)
                        || (member.productId != null && existing.key.startsWith("pid:")
                                && member.productId.equals(existing.productId))) {
                    it.remove();
                    mergedClock |= existing.clock;
                }
            }
        }
        stack.members.put(member.key, member);
        if (member.itemId >= 0) {
            memberStack.put(member.itemId, stackId);
        }
        if (mergedClock) {
            XLog.i("standalone clock merged into stack: stack=" + stackId + " id=" + member.itemId);
        }
        updateClockPage(computeClock(currentScreenId));
    }

    private boolean removeMember(int stackId, int id, int appwidgetId, String productId) {
        // The member may only be known through a title hint (item stream missed);
        // forget it in any case so a late top signal cannot hide through it.
        if (id >= 0) {
            memberClockHints.remove(id);
        }
        StackInfo stack = stacks.get(stackId);
        if (stack == null) {
            return false;
        }
        boolean removed = false;
        for (Iterator<Map.Entry<String, Item>> it = stack.members.entrySet().iterator(); it.hasNext(); ) {
            Item item = it.next().getValue();
            boolean match = ("id:" + id).equals(item.key)
                    || (appwidgetId >= 0 && item.appwidgetId == appwidgetId)
                    || (productId != null && item.key.startsWith("pid:") && productId.equals(item.productId));
            if (match) {
                it.remove();
                removed = true;
                if (item.itemId >= 0) {
                    memberStack.remove(item.itemId);
                    memberClockHints.remove(item.itemId);
                    if (stack.topId == item.itemId) {
                        // The visible member is gone; fail toward showing until a new top is known.
                        stack.topId = -1;
                    }
                }
            }
        }
        boolean emptied = stack.members.isEmpty();
        if (emptied) {
            // An emptied stack must not linger in the registry: it would make
            // unambiguous top binding ambiguous again.
            stacks.remove(stackId);
        }
        if (removed || emptied) {
            updateClockPage(computeClock(currentScreenId));
        }
        return removed;
    }

    private void dropStack(int stackId) {
        StackInfo stack = stacks.remove(stackId);
        stackScreen.remove(stackId);
        if (stack != null) {
            for (Item member : stack.members.values()) {
                if (member.itemId >= 0) {
                    memberStack.remove(member.itemId);
                    memberClockHints.remove(member.itemId);
                }
            }
        }
        updateClockPage(computeClock(currentScreenId));
    }

    private void setStackTop(int stackId, int topId) {
        StackInfo stack = stacks.computeIfAbsent(stackId, StackInfo::new);
        if (stack.topId != topId) {
            stack.topId = topId;
            XLog.i("stack top changed: stack=" + stackId + " top=" + topId);
            updateClockPage(computeClock(currentScreenId));
        }
    }

    /**
     * The stack controller's displayed-widget title line is the launcher's own
     * "currently displayed member" statement, so it updates the top unconditionally.
     * Unresolvable ids just contribute the title hint; the cold-start initial top
     * still comes from the evaluate/exposure adoption path.
     */
    private void noteDisplayedWidget(int memberId) {
        Integer stackId = resolveStackForMember(memberId);
        if (stackId != null) {
            setStackTop(stackId, memberId);
        }
    }

    /**
     * Cold-start/evaluate-race signal: the {@code stackBlocked=false} evaluation and
     * the {@code onVisible} event both mark the initially visible member, but after a
     * flip or a page re-entry the launcher can re-emit them for members that are
     * <em>not</em> displayed (stale warm-up events). A top already established by a
     * {@code [STACK-CONSISTENCY]} or displayed-title line must win.
     */
    private void setStackTopByMemberIfUnknown(int memberId) {
        Integer stackId = resolveStackForMember(memberId);
        if (stackId == null) {
            // Unbound: remember it for exposure/flush adoption (cold-start ordering).
            pendingTopMember = memberId;
            return;
        }
        StackInfo stack = stacks.get(stackId);
        if (stack != null && stack.topId >= 0 && stack.topId != memberId) {
            XLog.i("stale stack member ignored: stack=" + stackId
                    + " id=" + memberId + " top=" + stack.topId);
            return;
        }
        setStackTop(stackId, memberId);
        pendingTopMember = -1;
    }

    /**
     * Binds a member id to its stack: direct registry mapping, member scan, then the
     * sole exposed stack, then the sole known stack. Ambiguous cases stay unknown
     * (fail toward showing).
     */
    private Integer resolveStackForMember(int memberId) {
        Integer stackId = memberStack.get(memberId);
        if (stackId != null) {
            return stackId;
        }
        for (Map.Entry<Integer, StackInfo> entry : stacks.entrySet()) {
            for (Item member : entry.getValue().members.values()) {
                if (member.itemId == memberId) {
                    return entry.getKey();
                }
            }
        }
        Integer exposed = null;
        boolean ambiguous = false;
        for (StackInfo candidate : stacks.values()) {
            if (candidate.exposed) {
                if (exposed != null) {
                    ambiguous = true;
                    break;
                }
                exposed = candidate.stackId;
            }
        }
        if (!ambiguous && exposed != null) {
            return exposed;
        }
        if (stacks.size() == 1) {
            return stacks.keySet().iterator().next();
        }
        return null;
    }

    /** The stack is on the visible page; only a full visibility counts. */
    private void markStackExposed(int stackId, double visibleFraction) {
        StackInfo stack = stacks.computeIfAbsent(stackId, StackInfo::new);
        if (visibleFraction < 0.9) {
            return;
        }
        stack.exposed = true;
        // The exposure line fires once the page has settled, so the current screen
        // is the page this stack lives on and later re-entries need no grace.
        noteStackScreen(stackId, currentScreenId);
        if (stack.topId < 0 && pendingTopMember >= 0 && isSoleExposed(stack)) {
            // Line-order race after a SystemUI restart: the visible-member event
            // arrived before this stack was known. This stack is the one the user
            // is looking at, so its top must be the pending member.
            stack.topId = pendingTopMember;
            XLog.i("stack top adopted: stack=" + stackId + " top=" + pendingTopMember);
            pendingTopMember = -1;
        }
        updateClockPage(computeClock(currentScreenId));
    }

    private boolean isSoleExposed(StackInfo self) {
        for (StackInfo candidate : stacks.values()) {
            if (candidate != self && candidate.exposed) {
                return false;
            }
        }
        return true;
    }

    private void markStackHidden(int stackId) {
        StackInfo stack = stacks.get(stackId);
        if (stack != null && stack.exposed) {
            stack.exposed = false;
            updateClockPage(computeClock(currentScreenId));
        }
    }

    /** Recovers member-to-stack binding from a flushed member id list. */
    private void bindStackMembers(int stackId, String idList) {
        StackInfo stack = stacks.computeIfAbsent(stackId, StackInfo::new);
        for (String token : idList.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int memberId = Integer.parseInt(trimmed);
                if (!stack.members.containsKey("id:" + memberId)) {
                    stack.members.put("id:" + memberId, new Item(
                            "id:" + memberId, null, -1, memberId, false, stackId, false));
                }
                memberStack.put(memberId, stackId);
            } catch (NumberFormatException ignored) {
                // Not an id list entry; the rest of the line is still parsed.
            }
        }
        updateClockPage(computeClock(currentScreenId));
    }

    /**
     * Title-based clock facts for stack members whose item-stream line was never
     * seen. Same clock-title rule as standalone widgets; only stack lines carry
     * these ids, so a hint can never affect non-stacked items.
     */
    private void noteMemberHint(int memberId, String title, String appName) {
        boolean clock = isClockTitle(title) || (appName != null && isClockTitle(appName));
        if (!clock) {
            // A non-clock title is no proof against a clock package; only record
            // positive facts and let known members stay authoritative.
            return;
        }
        if (!Boolean.TRUE.equals(memberClockHints.put(memberId, true))) {
            XLog.i("stack member clock hint: id=" + memberId + " title=" + title);
            updateClockPage(computeClock(currentScreenId));
        }
    }

    private boolean computeClock(int screenId) {
        Map<String, Item> screen = inventory.get(screenId);
        if (screen != null) {
            for (Item item : screen.values()) {
                if (!item.stackContainer && item.clock && item.stackId < 0) {
                    return true;
                }
            }
        }
        // A stacked-widget page only hides the clock while its top member is a clock
        // widget; buried members and unknown tops fail to show. The stack counts
        // when its container sits on this page, when the stack is known to live on
        // this page (survives the entry window before its exposure line), or when
        // it is currently exposed (the only evidence left after the item streams
        // rolled out of the buffer).
        for (StackInfo stack : stacks.values()) {
            boolean associated = screenId >= 0
                    && stackScreen.getOrDefault(stack.stackId, -1) == screenId;
            if (!stack.exposed && !isOnScreen(screen, stack.stackId) && !associated) {
                continue;
            }
            if (stack.topId < 0) {
                continue;
            }
            Item top = findMember(stack, stack.topId);
            // The registry and the title hints use the same clock-title rules;
            // a positive from either source means clock on top.
            if ((top != null && top.clock)
                    || Boolean.TRUE.equals(memberClockHints.get(stack.topId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnScreen(Map<String, Item> screen, int stackId) {
        if (screen == null) {
            return false;
        }
        for (Item item : screen.values()) {
            if (item.stackContainer && item.itemId == stackId) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnAnyScreen(int stackId) {
        for (Map<String, Item> screen : inventory.values()) {
            if (isOnScreen(screen, stackId)) {
                return true;
            }
        }
        return false;
    }

    private static Item findMember(StackInfo stack, int memberId) {
        for (Item member : stack.members.values()) {
            if (member.itemId == memberId) {
                return member;
            }
        }
        return null;
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
            // The snapshot's ext field is NOT the stacked-widget parent: plain
            // desktop items carry ext=0 or ext=-1 arbitrarily, so it is ignored.
            // Stack members never appear in snapshots (the container item stands
            // in for them) and are only registered from the item streams.
            Item item = new Item("id:" + id, productId, -1, id, clock, -1, isStackContainerType(type));
            if (item.stackContainer) {
                stacks.computeIfAbsent(id, StackInfo::new);
            }
            result.add(item);
        }
        return result;
    }

    /** The item is a stacked-widget (堆叠组件) container, not a regular widget. */
    private static boolean isStackContainerType(String type) {
        String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return lower.equals("stackedwidget") || lower.startsWith("stackedwidget");
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

    /** Stack containers log {@code appwidgetId=null}; every other item logs a number. */
    private static int parseAppwidgetId(String value) {
        return value == null ? -1 : Integer.parseInt(value);
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

    boolean clockPage() {
        return clockPage;
    }

    boolean hasClockItem(int screenId) {
        return computeClock(screenId);
    }
}
