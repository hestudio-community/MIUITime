package net.hestudio.miuitime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gate logic and publication ordering for the status bar clock hide feature: the visibility
 * hooks only ever read a precomputed flag (see {@link HideGate}), and every unknown or
 * pre-unlock state must resolve to "show the clock" (fail toward showing).
 *
 * <p>The ordering tests pin the regression where the settled decision was published
 * <em>after</em> the hide/show calls ran: the enforcement hooks read the still-stale flag and
 * vetoed every hidden→shown restore, leaving the clock {@code GONE} on non-clock pages.</p>
 */
public class HideGateTest {

    private static final boolean READY = true;
    private static final boolean GATE_OPEN = true;
    private static final boolean FOREGROUND = true;

    /** Records published flags and what an apply action saw at run time. */
    private static final class RecordingSink implements HideGate.Sink {
        boolean last;
        int publishes;

        @Override
        public void publish(boolean forceHide) {
            last = forceHide;
            publishes++;
        }
    }

    @Test
    public void clockPageOnLauncherForeground_hides() {
        assertTrue(HideGate.compute(READY, GATE_OPEN, FOREGROUND, false, true, false));
    }

    @Test
    public void deferredShow_keepsClockCollapsed() {
        assertTrue(HideGate.compute(READY, GATE_OPEN, FOREGROUND, true, false, false));
    }

    @Test
    public void beforeBootCompleted_neverHides() {
        assertFalse(HideGate.compute(false, GATE_OPEN, FOREGROUND, false, true, false));
        assertFalse(HideGate.compute(false, GATE_OPEN, FOREGROUND, true, true, false));
    }

    @Test
    public void beforeFirstUnlockAfterBoot_neverHides() {
        // Boot window: the home task sits behind the not-yet-shown keyguard and would otherwise
        // look "foreground"; the post-boot keyguard gate must block any hide until first unlock.
        assertFalse(HideGate.compute(READY, false, FOREGROUND, false, true, false));
        assertFalse(HideGate.compute(READY, false, FOREGROUND, true, true, false));
    }

    @Test
    public void launcherNotForeground_neverHides() {
        assertFalse(HideGate.compute(READY, GATE_OPEN, false, false, true, false));
        assertFalse(HideGate.compute(READY, GATE_OPEN, false, true, true, false));
    }

    @Test
    public void nonClockPage_shows() {
        assertFalse(HideGate.compute(READY, GATE_OPEN, FOREGROUND, false, false, false));
    }

    @Test
    public void overlayShowing_shows() {
        assertFalse(HideGate.compute(READY, GATE_OPEN, FOREGROUND, false, true, true));
    }

    @Test
    public void idleState_shows() {
        assertFalse(HideGate.compute(false, false, false, false, false, false));
    }

    @Test
    public void launcherState_doesNotPublish_pendingShowKeepsCollapsed() {
        // Page swipes must not un-enforce before the settled apply: publication happens via
        // pendingShow, never directly on the state change (anti-flash).
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        gate.onLauncherState(true, false);
        gate.pendingShow(true); // collapse while the (longer) show debounce runs
        assertTrue(sink.last);

        int before = sink.publishes;
        gate.onLauncherState(false, false); // leaving the clock page, apply not settled yet
        assertEquals("state changes must not publish (anti-flash)", before, sink.publishes);
        assertTrue("flag must stay published while the show is deferred", sink.last);
    }

    @Test
    public void beginApply_publishesBeforeApplyAction_show() {
        // Regression test: while the show ran, the hooks must already read the NEW decision.
        // With the old publish-after-act ordering, sink.last was still true here and the
        // enforcement hooks rewrote the module's own setVisibility(VISIBLE) back to GONE.
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        gate.onLauncherState(false, false); // left the clock page; the restore is deferred...
        gate.pendingShow(true);             // ...and the clock stays collapsed meanwhile
        assertTrue(sink.last);

        final boolean[] flagDuringApply = {true};
        final boolean[] hideDuringApply = {true};
        gate.beginApply(hide -> {
            flagDuringApply[0] = sink.last;
            hideDuringApply[0] = hide;
        });

        assertFalse(hideDuringApply[0]);
        assertFalse("decision must be published before the show action runs", flagDuringApply[0]);
    }

    @Test
    public void beginApply_publishesBeforeApplyAction_hide() {
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        gate.onLauncherState(true, false);

        final boolean[] flagDuringApply = {false};
        final boolean[] hideDuringApply = {false};
        gate.beginApply(hide -> {
            flagDuringApply[0] = sink.last;
            hideDuringApply[0] = hide;
        });

        assertTrue(hideDuringApply[0]);
        assertTrue(flagDuringApply[0]);
    }

    @Test
    public void beginApply_clearsDeferredShowLatch() {
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        gate.onLauncherState(false, false);
        gate.pendingShow(true);
        gate.beginApply(hide -> assertFalse(hide));

        assertFalse("the deferred-show latch must not survive an apply cycle", gate.compute());
    }

    @Test
    public void onProbe_gateOpensOnceAndNeverRecloses() {
        // Locking again after the first unlock must not flip the gate closed (no flash on
        // later unlock cycles).
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        gate.onLauncherState(true, false);
        assertTrue(gate.compute());

        gate.onProbe(READY, false, FOREGROUND); // locked again
        assertTrue(gate.compute());
    }

    @Test
    public void onProbe_reportsChanges() {
        HideGate gate = new HideGate(new RecordingSink());
        assertFalse(gate.onProbe(false, false, false));      // matches the default state
        assertTrue(gate.onProbe(READY, GATE_OPEN, FOREGROUND)); // everything changed
        assertTrue(gate.onProbe(READY, GATE_OPEN, false));   // foreground flip
        assertFalse(gate.onProbe(READY, GATE_OPEN, false));  // nothing changed
    }

    @Test
    public void probePublishes() {
        RecordingSink sink = new RecordingSink();
        HideGate gate = new HideGate(sink);
        int before = sink.publishes;
        gate.onProbe(READY, GATE_OPEN, FOREGROUND);
        assertEquals(before + 1, sink.publishes);
    }
}
