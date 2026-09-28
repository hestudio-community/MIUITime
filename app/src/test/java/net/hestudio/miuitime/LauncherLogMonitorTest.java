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

    // ---- Stacked widgets (堆叠组件), real lines captured on HyperOS 4 ----

    /** Clock widget stacked under 电池; extendContainer points at the stack container 1387. */
    private static final String PROCESS_ITEM_STACK_MEMBER_CLOCK =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.maml id=1386 screenId=9 x=0 y=3 container=-100 pkg=null title=经典时钟 gadgetId=101490168 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2 extendContainer=1387";
    private static final String PROCESS_ITEM_STACK_MEMBER_BATTERY =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.maml id=1388 screenId=9 x=0 y=3 container=-100 pkg=null title=电池 gadgetId=101490169 uri=/data/user_de/0/com.miui.home/files/maml/res/0/f45ca1ae-5574-45d5-98da-f3601d07fab1/40154/f45ca1ae-5574-45d5-98da-f3601d07fab1/widget_2x2 extendContainer=1387";
    /**
     * The stack container itself. Its title follows one of its members and is
     * shown as-is in LayoutInfo snapshots — it must never be matched as a clock.
     */
    private static final String PROCESS_ITEM_STACK_CONTAINER =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.stackedWidget id=1387 screenId=9 x=0 y=3 container=-100 pkg=null title=经典时钟 gadgetId=-1 uri=null extendContainer=-1";
    /** LayoutInfo snapshot lists only the stack container (1387), never its members. */
    private static final String SCREEN_9_WITH_STACK =
            "[Info][LayoutInfo]  screen(index: 4, id:9, itemsCount:6 6)=>(0,0,1-1):BOSS直聘[id=1046,type=application(0),pkg=com.hpbr.bosszhipin,ext=-1](0,1,1-1):微信[id=1381,type=application(0),pkg=com.tencent.mm,ext=-1](0,3,2-2):经典时钟[id=1387,type=stackedWidget(1003),pkg=,ext=-1](1,0,1-1):智联招聘[id=1048,type=application(0),pkg=com.zhaopin.social,ext=-1](2,0,1-1):爱企查[id=1380,type=application(0),pkg=com.baidu.xin.aiqicha,ext=-1](3,0,1-1):企查查[id=1068,type=application(0),pkg=com.android.icredit,ext=-1]";
    /** Top-of-stack change on every flip: 电池 (1388) comes to the front. */
    private static final String STACK_TOP_TO_BATTERY =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=1388 != last=1386, stackId=1387";
    /** …and back to 经典时钟 (1386). */
    private static final String STACK_TOP_TO_CLOCK =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=1386 != last=1388, stackId=1387";
    /** Cold start evaluates members once; only the visible one gets stackBlocked=false. */
    private static final String STACK_TOP_BLOCKED_CLOCK =
            "[Info]MamlVisibility | evaluate | mamlId=5 mixinCtrl=Controller(id:5, hash:894775597, attached:287778271) guardCtrl=Controller(id:5, hash:894775597, attached:287778271) tag=1386_stacked title=经典时钟 | source=scrollEnd visible=true isResumed=false stackBlocked=false";
    private static final String STACK_TOP_BLOCKED_BURIED =
            "[Info]MamlVisibility | evaluate | mamlId=4 mixinCtrl=Controller(id:4, hash:965782150, attached:423487262) guardCtrl=Controller(id:4, hash:965782150, attached:423487262) tag=1388_stacked title=电池 | source=scrollEnd visible=true isResumed=false stackBlocked=true";
    /** A stacked member becoming visible is also a top change. */
    private static final String STACK_MEMBER_ON_VISIBLE_BATTERY =
            "[Info][MamlWidgetGetxController] MamlWidgetGetxController onVisible | mamlId=4 tag=1388_stacked";
    /** Removing the top member from the stack (adapted from the captured deleteItem shape). */
    private static final String DELETE_ITEM_STACK_MEMBER_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1386 title=经典时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(2,2) container=-100 extendContainer=1387 sortMode=0 appwidgetId=101490168 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2";
    /** Stack controller logging the displayed widget's own title. */
    private static final String STACK_TITLE_WIDGET_CLOCK =
            "[Info][WidgetStackGetXController] WidgetStackGetXController _updateTitleFromWidget: widgetId=1386, title='经典时钟', appName='时钟', label='经典时钟', resolved='时钟'";
    /** Flushed member list re-binds members to their stack after registry loss. */
    private static final String FLUSH_MEMBERS_1387 =
            "[Info][WidgetStackContainer] _flushLocalDataToController: stackTag=widget_stack_1387, localIds=[1388, 1386], localCount=2, controllerIds=[1386, 1388], controllerCount=2";
    /** Exposure lines name the stack while it sits on the visible page. */
    private static final String STACK_EXPOSURE_START_1387 =
            "[Info][WidgetStackContainer] stack exposure valid start: stackTag=widget_stack_1387, reason=visibilityChanged, visibleFraction=1.0";
    private static final String STACK_EXPOSURE_END_1387 =
            "[Info][WidgetStackContainer] track stack expose: stackTag=widget_stack_1387, reason=pageInvisible, durationMs=21540, visibleFraction=1.0";

    /**
     * Fresh-layout snapshot captured on device: plain desktop items mix ext=0 and
     * ext=-1, and the stack container itself carries ext=0 — the ext field is not
     * the stacked-widget parent and must not drive member routing. The page also
     * holds a standalone MAML clock (id=5) next to the stack (id=4).
     */
    private static final String SCREEN_1_WITH_EXT0_STACK =
            "[Info][LayoutInfo]  screen(index: 0, id:1, itemsCount:11 11)=>(0,0,4-2):默认经典时钟（与锁屏同步）[id=5,type=maml(19),pkg=#2353f0c3-6c7c-4404-8a5d-27d4a679c55c,ext=-1](0,2,2-2):[id=4,type=stackedWidget(1003),pkg=,ext=0](0,4,1-1):相册[id=7,type=application(0),pkg=com.miui.gallery,ext=0](0,5,1-1):小米商城[id=15,type=application(0),pkg=com.xiaomi.shop,ext=0](1,4,1-1):游戏中心[id=9,type=application(0),pkg=com.xiaomi.gamecenter,ext=0](1,5,1-1):超级小爱[id=17,type=application(0),pkg=com.miui.voiceassistProxy,ext=0](2,2,2-2):com.miui.home:string/default_folder_title_tools[id=1,type=folder_2X2_4(21),pkg=folder(contents=19),ext=0](2,4,1-1):计算器[id=11,type=application(0),pkg=com.miui.calculator,ext=0](2,5,1-1):应用商店[id=19,type=application(0),pkg=com.xiaomi.market,ext=0](3,4,1-1):米家[id=13,type=application(0),pkg=com.xiaomi.smarthome,ext=0](3,5,1-1):设置[id=21,type=application(0),pkg=com.android.settings,ext=0]";
    /** Stack state names both the stack and its member ids. */
    private static final String STACK_STATE_4 =
            "[Info][WidgetStackContainer] [StackState] stackTag=widget_stack_4, explode true → false, reason=editMode, members=2, ids=[127, 126]";
    /** 今日天气 (126) is the visible member — not a clock. */
    private static final String STACK_EVAL_WEATHER_TOP =
            "[Info]MamlVisibility | evaluate | mamlId=13 mixinCtrl=Controller(id:13, hash:451472064, attached:1032616848) guardCtrl=Controller(id:13, hash:451472064, attached:1032616848) tag=126_stacked title=今日天气 | source=scrollEnd visible=true isResumed=false stackBlocked=false";
    /** 经典时钟 (127) on top (adapted from the captured title-line shape). */
    private static final String STACK_TITLE_WIDGET_CLOCK_127 =
            "[Info][WidgetStackGetXController] WidgetStackGetXController _updateTitleFromWidget: widgetId=127, title='经典时钟', appName='时钟', label='经典时钟', resolved='时钟'";
    /** Removing the standalone clock so only the stack decides (captured deleteItem shape). */
    private static final String DELETE_ITEM_STANDALONE_CLOCK_5 =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=5 title=默认经典时钟（与锁屏同步） pkg=#2353f0c3-6c7c-4404-8a5d-27d4a679c55c type=19/ItemType.maml screen=1 cell=(0,0) span=(4,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=-1 provider=null productId=2353f0c3-6c7c-4404-8a5d-27d4a679c55c uri=";
    /** A stale second stack must not break unambiguous top binding. */
    private static final String FLUSH_MEMBERS_99 =
            "[Info][WidgetStackContainer] _flushLocalDataToController: stackTag=widget_stack_99, localIds=[77], localCount=1, controllerIds=[77], controllerCount=1";
    /** Top flip to 经典时钟 (127) on stack 4 (captured STACK-CONSISTENCY shape). */
    private static final String STACK_TOP_TO_CLOCK_127 =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=127 != last=126, stackId=4";

    // ---- Flat <-> stack-member transitions (the user's stacking repro) ----

    /** The standalone default clock (5) is dragged into stack 4 (extendContainer re-parents it). */
    private static final String PROCESS_ITEM_CLOCK_5_MOVED_INTO_STACK =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.maml id=5 screenId=1 x=0 y=2 container=-100 pkg=null title=默认经典时钟（与锁屏同步） gadgetId=101490170 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2353f0c3-6c7c-4404-8a5d-27d4a679c55c/40110/2353f0c3-6c7c-4404-8a5d-27d4a679c55c/widget_4x2 extendContainer=4";
    /** Member delete of the clock (5) inside stack 4 (captured deleteItem shape). */
    private static final String DELETE_ITEM_STACK_MEMBER_5 =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=5 title=默认经典时钟（与锁屏同步） pkg=null type=19/ItemType.maml screen=1 cell=(0,0) span=(4,2) container=-100 extendContainer=4 sortMode=0 appwidgetId=-1 provider=null productId=2353f0c3-6c7c-4404-8a5d-27d4a679c55c uri=";
    /** Deleting the stack container itself (extendContainer=-1). */
    private static final String DELETE_ITEM_STACK_CONTAINER_4 =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=4 title=经典时钟 pkg=null type=1003/ItemType.stackedWidget screen=1 cell=(0,2) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=-1 provider=null productId= uri=null";
    /** The stacked clock (1386) is dragged back out onto the desktop. */
    private static final String INSERT_ITEM_CLOCK_1386_FLAT =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=1386 title=经典时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490168 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=";
    /** …and then deleted from the desktop. */
    private static final String DELETE_ITEM_CLOCK_1386_FLAT =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=1386 title=经典时钟 pkg=null type=19/ItemType.maml screen=9 cell=(0,3) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490168 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=null";
    /** Late visibility event for the (already removed) clock member 1386. */
    private static final String STACK_MEMBER_ON_VISIBLE_CLOCK =
            "[Info][MamlWidgetGetxController] MamlWidgetGetxController onVisible | mamlId=5 tag=1386_stacked";

    // ---- Drawer-mode stacking flow (real lines captured on HyperOS 4) ----

    private static final String OVERVIEW_SCREEN2 =
            "[Info][LayoutInfo]  overview: totalPages=2, currentPageIndex=1, currentScreenId=2, screenIds=[1, 2]";
    /** A freshly placed clock has no item id yet: the model keys it {@code aw:<appwidgetId>}. */
    private static final String INSERT_ITEM_DRAWER_CLOCK =
            "[Info][DataPersistence][LauncherModelManager] insertItem id=-1 title=经典时钟 pkg=null type=19/ItemType.maml screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=101490189 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2";
    /** Dragging another widget onto the clock logs updateItem (singular), not a batch. */
    private static final String UPDATE_ITEM_DRAWER_CLOCK_INTO_STACK =
            "[Info][DataPersistence][LauncherModelManager] updateItem id=155 title=经典时钟 pkg=null type=19/ItemType.maml screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=156 sortMode=0 appwidgetId=101490189 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2";
    /** The stack container's own model line carries appwidgetId=null. */
    private static final String UPDATE_ITEM_BATCH_DRAWER_STACK =
            "[Info][DataPersistence][LauncherModelManager] updateItemBatch count=1 first10=[id=156 title=经典时钟 pkg=null type=1003/ItemType.stackedWidget screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=null provider=null productId=null uri=null]";
    private static final String DRAWER_STACK_TOP_TO_ACCELERATE =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=157 != last=155, stackId=156";
    private static final String DRAWER_STACK_TOP_TO_CLOCK =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=155 != last=157, stackId=156";
    /** Deleting the clock member of the drawer stack (captured shape). */
    private static final String DELETE_ITEM_DRAWER_CLOCK_MEMBER =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=155 title=经典时钟 pkg=null type=19/ItemType.maml screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=156 sortMode=0 appwidgetId=101490189 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2";
    /** Cold-start item load for the same clock member carries gadgetId == appwidgetId. */
    private static final String PROCESS_ITEM_DRAWER_CLOCK_MEMBER =
            "[Info][DataLoading][ItemDataProcessor]: processItem type=ItemType.maml id=155 screenId=2 x=2 y=4 container=-100 pkg=null title=经典时钟 gadgetId=101490189 uri=/data/user_de/0/com.miui.home/files/maml/res/0/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/40110/2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7/widget_2x2 extendContainer=156";
    /** The [StackMemberWriteDiag] duplicate of the re-parent event. */
    private static final String UPDATE_ITEM_DIAG_WARNING =
            "[Warning][DataPersistence][LauncherModelManager] [StackMemberWriteDiag] op=updateItem stackRelatedCount=1/1 items=[id=155 title=经典时钟 pkg=null type=19/ItemType.maml screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=156 sortMode=0 appwidgetId=101490189 provider=null productId=2e2f8bec-3f53-4df2-bc92-9db0cfcfdfe7 uri=null] caller=*** *** ***";

    // ---- Stack -> screen association (page-transition smoothness, stack 160) ----

    /** The container of the page-2 clock stack; appwidgetId=null (captured shape). */
    private static final String UPDATE_ITEM_BATCH_DRAWER_STACK_160 =
            "[Info][DataPersistence][LauncherModelManager] updateItemBatch count=1 first10=[id=160 title=自定义本地时钟 pkg=null type=1003/ItemType.stackedWidget screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=null provider=null productId=null uri=null]";
    private static final String STACK_EXPOSURE_START_160 =
            "[Info][WidgetStackContainer] stack exposure valid start: stackTag=widget_stack_160, reason=visibilityChanged, visibleFraction=1.0";
    private static final String STACK_EXPOSURE_END_160 =
            "[Info][WidgetStackContainer] track stack expose: stackTag=widget_stack_160, reason=pageInvisible, durationMs=22528, visibleFraction=1.0";
    private static final String STACK_TITLE_WIDGET_CLOCK_159 =
            "[Info][WidgetStackGetXController] WidgetStackGetXController _updateTitleFromWidget: widgetId=159, title='自定义本地时钟', appName='时钟', label='', resolved='时钟'";
    private static final String STACK_TOP_TO_CLOCK_159 =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=159 != last=161, stackId=160";
    /** Deleting the stack container itself (appwidgetId=null shape). */
    private static final String DELETE_ITEM_DRAWER_STACK_160 =
            "[Info][DataPersistence][LauncherModelManager] deleteItem id=160 title=自定义本地时钟 pkg=null type=1003/ItemType.stackedWidget screen=2 cell=(2,4) span=(2,2) container=-100 extendContainer=-1 sortMode=0 appwidgetId=null provider=null productId=null uri=null";
    /** Member list flush binds 161 (health) and 159 (clock) to the stack. */
    private static final String FLUSH_MEMBERS_160 =
            "[Info][WidgetStackContainer] _flushLocalDataToController: stackTag=widget_stack_160, localIds=[161, 159], localCount=2, controllerIds=[159, 161], controllerCount=2";
    private static final String STACK_TOP_TO_HEALTH_161 =
            "[Warning]StackedWidgetManager [STACK-CONSISTENCY] getCurrentDisplayWidget: currentDisplayWidgetId=161 != last=159, stackId=160";
    /**
     * A late {@code evaluateAll} after a flip marks the buried clock (159) as
     * unblocked although 161 is displayed (captured on device, fires ~1.1s after
     * the STACK-CONSISTENCY line).
     */
    private static final String STALE_EVAL_CLOCK_159_STACKED =
            "[Info]MamlVisibility | evaluate | mamlId=56 mixinCtrl=Controller(id:56, hash:803849557, attached:458660033) guardCtrl=Controller(id:56, hash:803849557, attached:458660033) tag=159_stacked title=自定义本地时钟 | source=evaluateAll(stackTopChanged(widget_stack_160,flushLocalDataToController)) visible=true isResumed=false stackBlocked=false";
    /** The stack controller names the actually displayed widget (health) on re-entry. */
    private static final String STACK_TITLE_WIDGET_HEALTH_161 =
            "[Info][WidgetStackGetXController] WidgetStackGetXController _updateTitleFromWidget: widgetId=161, title='女性健康', appName='小米运动健康', label='', resolved='小米运动健康'";
    /** A stale warm-up onVisible of the buried clock member on page re-entry. */
    private static final String STALE_ON_VISIBLE_CLOCK_159 =
            "[Info][MamlWidgetGetxController] MamlWidgetGetxController onVisible | mamlId=56 tag=159_stacked";

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

    // ---- Stacked widgets (堆叠组件) ----

    private void loadClockStack() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_CLOCK);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_BATTERY);
        monitor.parse(PROCESS_ITEM_STACK_CONTAINER);
    }

    @Test
    public void stackedClockOnlyHidesWhileOnTop() {
        loadClockStack();
        assertFalse("an unknown stack top must fail toward showing", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue("clock on top of the stack must hide the status bar clock", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_BATTERY);
        assertFalse("a buried clock member must not hide the status bar clock", monitor.clockPage());
    }

    @Test
    public void coldStartBlockedSignalRevealsInitialStackTop() {
        loadClockStack();
        // Buried member evaluations carry stackBlocked=true and must not count.
        monitor.parse(STACK_TOP_BLOCKED_BURIED);
        assertFalse(monitor.clockPage());

        monitor.parse(STACK_TOP_BLOCKED_CLOCK);
        assertTrue("stackBlocked=false marks the initial top member", monitor.clockPage());
    }

    @Test
    public void onVisibleAdoptsOnlyUnknownTop() {
        loadClockStack();
        assertFalse("an unknown stack top must fail toward showing", monitor.clockPage());

        // Cold start: the visible member marks the initial top.
        monitor.parse(STACK_MEMBER_ON_VISIBLE_BATTERY);
        assertFalse("the battery member on top must not hide", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue(monitor.clockPage());

        // A stale onVisible of the buried member must not override the known top.
        monitor.parse(STACK_MEMBER_ON_VISIBLE_BATTERY);
        assertTrue("a known top must not be overridden by a stale onVisible",
                monitor.clockPage());
    }

    @Test
    public void stackContainerTitleIsNeverMatchedAsClock() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_CLOCK);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_BATTERY);
        // The container is titled 经典时钟 but the top member is unknown here.
        monitor.parse(SCREEN_9_WITH_STACK);
        assertFalse("a stack container title must not hide the clock", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_BATTERY);
        assertFalse("the 电池 top must not hide the clock", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue("the clock member on top must hide", monitor.clockPage());
    }

    @Test
    public void completeSnapshotDoesNotDropStackMembers() {
        loadClockStack();
        // Complete snapshots replace the screen map and only list the container —
        // the members must survive in the stack registry.
        monitor.parse(SCREEN_9_WITH_STACK);
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue("stack members must survive a snapshot replacement", monitor.clockPage());
    }

    @Test
    public void deletingTopStackMemberRestoresClock() {
        loadClockStack();
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse(DELETE_ITEM_STACK_MEMBER_CLOCK);
        assertFalse("removing the top member must restore the clock", monitor.clockPage());
    }

    @Test
    public void stackedClockDoesNotAffectOtherScreens() {
        loadClockStack();
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue(monitor.hasClockItem(9));

        monitor.parse(OVERVIEW_HOME);
        assertFalse("the stack on screen 9 must not flip the current page", monitor.clockPage());
    }

    @Test
    public void titleHintsCoverMissingRegistryAfterSystemUiRestart() {
        // SystemUI restart: the launcher's one-shot processItem lines are gone, so
        // the registry only knows the container (via the re-logged snapshot).
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_STACK);
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertFalse("unknown member must fail toward showing", monitor.clockPage());

        monitor.parse(STACK_TITLE_WIDGET_CLOCK);
        assertTrue("the title hint must resolve the top member", monitor.clockPage());
    }

    @Test
    public void flushMembersRecoverStackBinding() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_STACK);
        monitor.parse(FLUSH_MEMBERS_1387);
        // The flushed placeholder member has no clock flag; the evaluate title
        // line (also the initial-top signal) supplies the clock hint.
        monitor.parse(STACK_TOP_BLOCKED_CLOCK);
        assertTrue("flushed binding + title hint must hide", monitor.clockPage());

        monitor.parse(STACK_TOP_TO_BATTERY);
        assertFalse("the same registry must not hide for the battery top", monitor.clockPage());
    }

    @Test
    public void exposureLinesResolveStackAfterSystemUiRestart() {
        // Worst case after a SystemUI restart: no item streams left in the buffer,
        // only live evaluate/title/exposure lines. The evaluate line carries both
        // the initial top (stackBlocked=false) and the clock title hint.
        monitor.parse(STACK_TOP_BLOCKED_CLOCK);
        assertFalse("no stack is known yet", monitor.clockPage());

        monitor.parse(STACK_EXPOSURE_START_1387);
        assertTrue("exposure + adopted top + title hint must hide", monitor.clockPage());

        monitor.parse(STACK_EXPOSURE_END_1387);
        assertFalse("leaving the page must restore the clock", monitor.clockPage());
    }

    @Test
    public void partialExposureDoesNotHide() {
        monitor.parse(STACK_TOP_BLOCKED_CLOCK);
        monitor.parse("[Info][WidgetStackContainer] stack exposure valid start: stackTag=widget_stack_1387, reason=pageVisible, visibleFraction=0.59");
        assertFalse("a partially visible stack must not hide", monitor.clockPage());
    }

    @Test
    public void snapshotExtZeroItemsAreNotStackMembers() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(SCREEN_1_WITH_EXT0_STACK);
        assertTrue("the standalone clock on the page still hides", monitor.hasClockItem(1));

        monitor.parse(DELETE_ITEM_STANDALONE_CLOCK_5);
        assertFalse("with only the stack left an unknown top must show", monitor.clockPage());

        // StackState binds 127/126 to stack 4; the weather member is on top.
        monitor.parse(STACK_STATE_4);
        monitor.parse(STACK_EVAL_WEATHER_TOP);
        assertFalse("a weather top must not hide", monitor.clockPage());

        monitor.parse(STACK_TITLE_WIDGET_CLOCK_127);
        monitor.parse(STACK_TOP_TO_CLOCK_127);
        assertTrue("the ext=0 stack container must still drive stack detection", monitor.clockPage());
    }

    @Test
    public void staleStackDoesNotBreakExposedTopBinding() {
        // A leftover stack (e.g. from a deleted container) must not make the
        // visible-member event ambiguous.
        monitor.parse(FLUSH_MEMBERS_99);
        monitor.parse(STACK_EXPOSURE_START_1387);
        monitor.parse(STACK_TOP_BLOCKED_CLOCK);
        assertTrue("the unbound top must bind to the sole exposed stack", monitor.clockPage());

        monitor.parse(STACK_EXPOSURE_END_1387);
        assertFalse("leaving the page must restore the clock", monitor.clockPage());
    }

    // ---- Flat <-> stack-member transitions (the user's stacking repro) ----

    @Test
    public void clockMovedIntoStackMustNotLingerAsFlatEntry() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(SCREEN_1_WITH_EXT0_STACK);
        assertTrue("the standalone clock on the page hides", monitor.clockPage());

        // The clock is dragged into stack 4; the item stream re-parents it.
        monitor.parse(PROCESS_ITEM_CLOCK_5_MOVED_INTO_STACK);
        monitor.parse(STACK_STATE_4);
        monitor.parse(STACK_EVAL_WEATHER_TOP);
        assertFalse("a clock moved into a stack must not linger as a flat item",
                monitor.clockPage());
    }

    @Test
    public void deletingMovedStackRestoresClockOnEveryPage() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(SCREEN_1_WITH_EXT0_STACK);
        monitor.parse(PROCESS_ITEM_CLOCK_5_MOVED_INTO_STACK);
        monitor.parse(STACK_STATE_4);
        monitor.parse(STACK_EVAL_WEATHER_TOP);
        assertFalse(monitor.clockPage());

        monitor.parse(DELETE_ITEM_STACK_MEMBER_5);
        monitor.parse(DELETE_ITEM_STACK_CONTAINER_4);

        monitor.parse("[Info][LayoutInfo]  overview: totalPages=2, currentPageIndex=1, currentScreenId=2, screenIds=[1, 2]");
        assertFalse("no page may keep hiding after the stack is gone", monitor.clockPage());
        monitor.parse(OVERVIEW_HOME);
        assertFalse(monitor.clockPage());
    }

    @Test
    public void memberMovedOutOfStackStopsCounting() {
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_CLOCK);
        monitor.parse(PROCESS_ITEM_STACK_MEMBER_BATTERY);
        monitor.parse(PROCESS_ITEM_STACK_CONTAINER);
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue(monitor.clockPage());

        // The clock is dragged out of the stack onto the desktop...
        monitor.parse(INSERT_ITEM_CLOCK_1386_FLAT);
        assertTrue("still a clock widget, now flat on the desktop", monitor.clockPage());

        // ...and deleted from the desktop.
        monitor.parse(DELETE_ITEM_CLOCK_1386_FLAT);
        assertFalse("the stale stack member must not keep hiding", monitor.clockPage());
    }

    @Test
    public void removedMemberHintMustNotResurrectHide() {
        // After a SystemUI restart only the container, the title hint and the top
        // signal are left; the hint is the only clock evidence.
        monitor.parse(OVERVIEW_SCREEN9);
        monitor.parse(SCREEN_9_WITH_STACK);
        monitor.parse(STACK_TITLE_WIDGET_CLOCK);
        monitor.parse(STACK_TOP_TO_CLOCK);
        assertTrue(monitor.clockPage());

        // The member is deleted although its item stream was never seen.
        monitor.parse(DELETE_ITEM_STACK_MEMBER_CLOCK);
        assertFalse("the removed member's title hint must be forgotten", monitor.clockPage());

        // A late visibility event for the same (now gone) member must not hide again.
        monitor.parse(STACK_MEMBER_ON_VISIBLE_CLOCK);
        assertFalse(monitor.clockPage());
    }

    @Test
    public void launcherRestartDoesNotResurrectMovedClock() {
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(SCREEN_1_WITH_EXT0_STACK);
        monitor.parse(PROCESS_ITEM_CLOCK_5_MOVED_INTO_STACK);
        assertFalse(monitor.clockPage());

        // Launcher restart: empty placeholder snapshot then the item load stream.
        monitor.parse(EMPTY_SCREEN_HOME);
        monitor.parse(PROCESS_ITEM_CLOCK_5_MOVED_INTO_STACK);
        monitor.parse(STACK_STATE_4);
        assertFalse("the moved clock must not come back as a flat item", monitor.clockPage());
    }

    // ---- Drawer-mode stacking flow ----

    @Test
    public void drawerStackClockOnlyHidesOnTop() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(INSERT_ITEM_DRAWER_CLOCK);
        assertTrue("the freshly placed clock hides", monitor.clockPage());

        // Dragging another widget onto the clock re-parents it into stack 156.
        monitor.parse(UPDATE_ITEM_DRAWER_CLOCK_INTO_STACK);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK);
        assertFalse("a clock in a stack without a known top must not hide",
                monitor.clockPage());

        monitor.parse(DRAWER_STACK_TOP_TO_CLOCK);
        assertTrue("the clock member on top must hide", monitor.clockPage());

        monitor.parse(DRAWER_STACK_TOP_TO_ACCELERATE);
        assertFalse("another member on top must show the status bar clock",
                monitor.clockPage());
    }

    @Test
    public void drawerStackMemberDeleteShowsClock() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(INSERT_ITEM_DRAWER_CLOCK);
        monitor.parse(UPDATE_ITEM_DRAWER_CLOCK_INTO_STACK);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK);
        monitor.parse(DRAWER_STACK_TOP_TO_CLOCK);
        assertTrue(monitor.clockPage());

        monitor.parse(DELETE_ITEM_DRAWER_CLOCK_MEMBER);
        assertFalse("deleting the displayed clock member must show the clock",
                monitor.clockPage());
    }

    @Test
    public void processItemGadgetIdPurgesStaleFlatClock() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(INSERT_ITEM_DRAWER_CLOCK);
        assertTrue(monitor.clockPage());

        // Launcher reload/replay: the clock is a stack member now; only the item
        // load line (gadgetId == appwidgetId) links it to the stale flat entry.
        monitor.parse(PROCESS_ITEM_DRAWER_CLOCK_MEMBER);
        assertFalse("the gadgetId must identify the same widget instance",
                monitor.clockPage());
    }

    @Test
    public void updateItemDiagWarningIsNotApplied() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK);
        monitor.parse(DRAWER_STACK_TOP_TO_CLOCK);
        assertFalse("unknown member must fail toward showing", monitor.clockPage());

        // The [StackMemberWriteDiag] duplicate carries op=updateItem and must not
        // be treated as a re-parent event.
        monitor.parse(UPDATE_ITEM_DIAG_WARNING);
        assertFalse("diag warning lines are not updateItem events", monitor.clockPage());
    }

    // ---- Stack -> screen association (page-transition smoothness) ----

    @Test
    public void stackScreenKeepsClockHiddenOnReentry() {
        // The container is deliberately NOT registered in the inventory (its snapshot
        // line is truncated on device), so only the exposure line links stack to page.
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(STACK_EXPOSURE_START_160);
        monitor.parse(STACK_TITLE_WIDGET_CLOCK_159);
        monitor.parse(STACK_TOP_TO_CLOCK_159);
        assertTrue("the clock on top of the exposed stack hides", monitor.clockPage());

        // Leaving the page clears the exposure but not the stack's screen.
        monitor.parse(STACK_EXPOSURE_END_160);
        monitor.parse(OVERVIEW_HOME);
        assertFalse("page 1 hosts no clock in this fixture", monitor.clockPage());

        // Re-entering: the overview arrives first, the exposure line follows later.
        // The known stack screen must already count with its remembered top.
        monitor.parse(OVERVIEW_SCREEN2);
        assertTrue("a known stack screen must hide immediately on re-entry",
                monitor.clockPage());
    }

    @Test
    public void droppedStackForgetsItsScreen() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK_160);
        monitor.parse(STACK_EXPOSURE_START_160);
        monitor.parse(STACK_TITLE_WIDGET_CLOCK_159);
        monitor.parse(STACK_TOP_TO_CLOCK_159);
        assertTrue(monitor.clockPage());

        monitor.parse(DELETE_ITEM_DRAWER_STACK_160);
        monitor.parse(OVERVIEW_HOME);
        monitor.parse(OVERVIEW_SCREEN2);
        assertFalse("a dropped stack must not hide through its old screen",
                monitor.clockPage());
    }

    @Test
    public void staleStackMemberEvaluationMustNotOverrideTop() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK_160);
        monitor.parse(FLUSH_MEMBERS_160);
        monitor.parse(STACK_TITLE_WIDGET_CLOCK_159);
        monitor.parse(STACK_TOP_TO_HEALTH_161);
        assertFalse("another member on top must show the clock", monitor.clockPage());

        // The flip animation re-evaluates the buried clock as unblocked; the top
        // established by STACK-CONSISTENCY must win.
        monitor.parse(STALE_EVAL_CLOCK_159_STACKED);
        assertFalse("a stale evaluation must not flip the top back to the clock",
                monitor.clockPage());

        monitor.parse(STACK_TOP_TO_CLOCK_159);
        assertTrue("a real flip to the clock hides again", monitor.clockPage());
    }

    @Test
    public void staleOnVisibleAfterPageReentryMustNotHide() {
        monitor.parse(OVERVIEW_SCREEN2);
        monitor.parse(UPDATE_ITEM_BATCH_DRAWER_STACK_160);
        monitor.parse(FLUSH_MEMBERS_160);

        // The stack displays the health widget; the title line reports it.
        monitor.parse(STACK_TITLE_WIDGET_HEALTH_161);
        assertFalse("the displayed member is not a clock", monitor.clockPage());

        // Page re-entry noise: a stale evaluate and onVisible of the buried clock.
        monitor.parse(STALE_EVAL_CLOCK_159_STACKED);
        monitor.parse(STALE_ON_VISIBLE_CLOCK_159);
        assertFalse("page re-entry must not flip the top back to the buried clock",
                monitor.clockPage());

        // A real flip to the clock: the title line announces the new displayed member.
        monitor.parse(STACK_TITLE_WIDGET_CLOCK_159);
        assertTrue("a real flip to the clock hides again", monitor.clockPage());
    }
}
