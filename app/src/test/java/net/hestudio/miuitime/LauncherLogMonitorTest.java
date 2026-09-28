package net.hestudio.miuitime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-level tests driven by real launcher logcat lines captured on HyperOS 4
 * (see the class doc of {@link LauncherLogMonitor} for the background).
 */
public class LauncherLogMonitorTest {

    private static final String OVERVIEW_HOME =
            "[Info][LayoutInfo]  overview: totalPages=5, currentPageIndex=0, currentScreenId=1, screenIds=[1, 2, 4, 8, 9]";
    private static final String OVERVIEW_SCREEN9 =
            "[Info][LayoutInfo]  overview: totalPages=5, currentPageIndex=4, currentScreenId=9, screenIds=[1, 2, 4, 8, 9]";
    /** Cold start placeholder: logged before workspace items are loaded. */
    private static final String EMPTY_SCREEN_HOME =
            "[Info][LayoutInfo]  screen(index: 0, id:1, itemsCount:0 0)=>";
    private static final String PROCESS_ITEM_WORLD_CLOCK =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.maml id=944 screenId=1 x=0 y=0 container=-100 pkg=null title=世界时钟 gadgetId=101410023 uri=/data/user_de/0/com.miui.home/files/maml/res/0/64d771d5-327b-46d9-8f43-ca298a8c227e/116/64d771d5-327b-46d9-8f43-ca298a8c227e/widget_4x2 extendContainer=-1";
    /** The clock *app icon* must never count as a clock widget. */
    private static final String PROCESS_ITEM_CLOCK_APP_ICON =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.application id=20 screenId=1 x=2 y=3 container=-100 pkg=com.android.deskclock title=时钟 gadgetId=-1 uri=null extendContainer=-1";
    private static final String PROCESS_ITEM_FOLDER_CHILD =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.application id=7 screenId=-1 x=8 y=3 container=1 pkg=com.miui.video title=小米视频 gadgetId=-1 uri=null extendContainer=-1";
    private static final String SCREEN_9_WITH_CLOCK =
            "[Info][LayoutInfo]  screen(index: 4, id:9, itemsCount:6 6)=>(0,0,1-1):BOSS直聘[id=1046,type=application(0),pkg=com.hpbr.bosszhipin,ext=-1](0,1,1-1):微信[id=1381,type=application(0),pkg=com.tencent.mm,ext=-1](0,2,4-2):默认经典时钟（与锁屏同步）[id=1384,type=maml(19),pkg=com.android.deskclock#52b8238d-848f-4608-a0e6-28458d4b1f2c,ext=-1](1,0,1-1):智联招聘[id=1048,type=application(0),pkg=com.zhaopin.social,ext=-1](2,0,1-1):爱企查[id=1380,type=application(0),pkg=com.baidu.xin.aiqicha,ext=-1](3,0,1-1):企查查[id=1068,type=application(0),pkg=com.android.icredit,ext=-1]";
    private static final String SCREEN_9_WITHOUT_CLOCK =
            "[Info][LayoutInfo]  screen(index: 4, id:9, itemsCount:5 5)=>(0,0,1-1):BOSS直聘[id=1046,type=application(0),pkg=com.hpbr.bosszhipin,ext=-1](0,1,1-1):微信[id=1381,type=application(0),pkg=com.tencent.mm,ext=-1](1,0,1-1):智联招聘[id=1048,type=application(0),pkg=com.zhaopin.social,ext=-1](2,0,1-1):爱企查[id=1380,type=application(0),pkg=com.baidu.xin.aiqicha,ext=-1](3,0,1-1):企查查[id=1068,type=application(0),pkg=com.android.icredit,ext=-1]";
    private static final String DELETE_ITEM_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1384 title=默认经典时钟（与锁屏同步） pkg=null type=19/ItemType.maml screen=9 cell=(0,2) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490163 provider=null productId=52b8238d-848f-4608-a0e6-28458d4b1f2c uri=/data/user_de/0/com.miui.home/files/maml/res/0/52b8238d-848f-4608-a0e6-28458d4b1f2c/40127/52b8238d-848f-4608-a0e6-28458d4b1f2c/widget_4x2";
    private static final String DELETE_ITEM_DIAG_WARNING =
            "[Warning][DataPersistence][LauncherModelManager] [StackMemberWriteDiag] op=deleteItem stackRelatedCount=1/1 items=[id=1384 title=默认经典时钟（与锁屏同步） pkg=null type=19/ItemType.maml screen=9 cell=(0,2) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490163 provider=null productId=52b8238d-848f-4608-a0e6-28458d4b1f2c uri=/data/user_de/0/com.miui.home/files/maml/res/0/52b8238d-848f-4608-a0e6-28458d4b1f2c/40127/52b8238d-848f-4608-a0e6-28458d4b1f2c/widget_4x2] caller=*** *** ***";
    private static final String INSERT_ITEM_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=-1 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490162 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";
    private static final String DELETE_ITEM_BY_APPWIDGET_ID =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1383 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490162 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=/data/user_de/0/com.miui.home/files/maml/res/0/bc128052-1c50-4da8-b920-0728aa957a98/40126/bc128052-1c50-4da8-b920-0728aa957a98/widget_4x2";
    /** Clock widget inside a folder: container is the folder's row id, not -100. */
    private static final String INSERT_ITEM_FOLDER_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=-1 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=5 extendContainer=-1 sortMode=0 appwidgetId=101490164 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";
    /** Clock widget in the hotseat (container=-101). */
    private static final String INSERT_ITEM_HOTSEAT_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=-1 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=-101 extendContainer=-1 sortMode=0 appwidgetId=101490165 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";
    /** Desktop clock tracked under the pid: key (no id, no appwidgetId). */
    private static final String INSERT_ITEM_PID_KEYED_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=-1 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=-1 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";
    /** Folder child sharing the desktop widget's productId must not remove it. */
    private static final String DELETE_ITEM_FOLDER_SAME_PRODUCT =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1400 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,0) span=(4,2) container=5 extendContainer=-1 sortMode=0 appwidgetId=101490166 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";
    /** The matching desktop delete of the pid-keyed entry (productId fallback). */
    private static final String DELETE_ITEM_PID_KEYED =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1383 title=天气时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=-1 provider=null productId=bc128052-1c50-4da8-b920-0728aa957a98 uri=";

    private LauncherLogMonitor monitor;
    private List<Boolean> notifications;

    @Before
    public void setUp() {
        monitor = new LauncherLogMonitor();
        notifications = new ArrayList<>();
        monitor.start((clockPage, overlayShowing) -> notifications.add(clockPage));
    }

    @After
    public void tearDown() {
        monitor.stop();
    }

    @Test
    public void coldStartEmptySnapshotThenItemLoadDetectsClock() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(EMPTY_SCREEN_HOME);
        assertFalse("empty pre-load snapshot must not decide", monitor.clockPage());

        monitor.parse(PROCESS_ITEM_WORLD_CLOCK);
        assertTrue("clock widget in the item load stream must be detected", monitor.clockPage());
    }

    @Test
    public void deletingWidgetRestoresClockWithoutNewSnapshot() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_CLOCK);
        assertTrue(monitor.clockPage());

        // Shortcut-menu "remove" logs deleteItem only — no LayoutInfo snapshot follows.
        monitor.parse(DELETE_ITEM_CLOCK);
        assertFalse("deleteItem must clear the clock state", monitor.clockPage());
        assertFalse(notifications.isEmpty());
        assertFalse("last notification must report the restored clock", notifications.get(notifications.size() - 1));
    }

    @Test
    public void emptySnapshotDoesNotClearKnownClock() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(PROCESS_ITEM_WORLD_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse(EMPTY_SCREEN_HOME);
        assertTrue("empty snapshot must not wipe known items", monitor.clockPage());
    }

    @Test
    public void clockAppIconIsNotAClockWidget() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(PROCESS_ITEM_CLOCK_APP_ICON);
        assertFalse("the clock app icon must not hide the status bar clock", monitor.clockPage());
    }

    @Test
    public void folderChildrenAreIgnored() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(PROCESS_ITEM_FOLDER_CHILD);
        assertFalse(monitor.clockPage());
    }

    @Test
    public void modelItemsOutsideDesktopAreIgnored() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(INSERT_ITEM_FOLDER_CLOCK);
        assertFalse("a folder clock widget must not hide the status bar clock", monitor.clockPage());
        assertFalse(monitor.hasClockItem(9));

        monitor.parse(INSERT_ITEM_HOTSEAT_CLOCK);
        assertFalse("a hotseat clock widget must not hide the status bar clock", monitor.clockPage());
        assertFalse(monitor.hasClockItem(9));
    }

    @Test
    public void folderDeleteMustNotRemoveDesktopClockWithSameProduct() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(INSERT_ITEM_PID_KEYED_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse(DELETE_ITEM_FOLDER_SAME_PRODUCT);
        assertTrue("a folder child delete must not clear the desktop clock", monitor.clockPage());

        monitor.parse(DELETE_ITEM_PID_KEYED);
        assertFalse("the desktop delete still matches via the productId fallback", monitor.clockPage());
    }

    @Test
    public void completeSnapshotIsAuthoritative() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse(SCREEN_9_WITHOUT_CLOCK);
        assertFalse("a complete snapshot without the widget must update the state", monitor.clockPage());
    }

    @Test
    public void insertItemShowsImmediatelyAndDeleteByAppWidgetIdRestores() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(INSERT_ITEM_CLOCK);
        assertTrue("insertItem must hide without waiting for a snapshot", monitor.clockPage());

        monitor.parse(DELETE_ITEM_BY_APPWIDGET_ID);
        assertFalse("deleteItem must match the aw:<appwidgetId> key", monitor.clockPage());
    }

    @Test
    public void diagWarningLineMustNotDelete() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_CLOCK);
        assertTrue(monitor.clockPage());

        // The [StackMemberWriteDiag] duplicate of deleteItem must not be processed.
        monitor.parse(DELETE_ITEM_DIAG_WARNING);
        assertTrue("diag warning lines are not delete events", monitor.clockPage());
    }

    @Test
    public void nonCurrentScreenUpdatesDoNotFlipTheCurrentPage() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(PROCESS_ITEM_CLOCK_APP_ICON);
        assertFalse(monitor.clockPage());

        // A clock widget on another screen must not affect the current page.
        monitor.parse(SCREEN_9_WITH_CLOCK);
        assertFalse("other screens must not flip the current page", monitor.clockPage());
        assertTrue("but the other screen is tracked", monitor.hasClockItem(9));
    }

    @Test
    public void switchToClockPageHidesAndAwayRestores() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(PROCESS_ITEM_WORLD_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse("[Info][LayoutInfo]  overview: totalPages=5, currentPageIndex=1, currentScreenId=2, screenIds=[1, 2, 4, 8, 9]");
        assertFalse("leaving the clock page must restore the clock", monitor.clockPage());

        monitor.parse(OVERVIEW_HOME);
        assertTrue("returning to the clock page must hide again", monitor.clockPage());
    }
}
