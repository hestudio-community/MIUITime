package net.hestudio.miuitime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gate logic for the status bar clock hide feature: the visibility hooks only ever read a
 * precomputed flag (see {@link ClockPageController#computeForceHide}), and every unknown or
 * pre-unlock state must resolve to "show the clock" (fail toward showing).
 */
public class ClockPageControllerTest {

    private static final boolean READY = true;
    private static final boolean GATE_OPEN = true;
    private static final boolean FOREGROUND = true;

    @Test
    public void clockPageOnLauncherForeground_hides() {
        assertTrue(ClockPageController.computeForceHide(
                READY, GATE_OPEN, FOREGROUND, false, true, false));
    }

    @Test
    public void deferredShow_keepsClockCollapsed() {
        assertTrue(ClockPageController.computeForceHide(
                READY, GATE_OPEN, FOREGROUND, true, false, false));
    }

    @Test
    public void beforeBootCompleted_neverHides() {
        assertFalse(ClockPageController.computeForceHide(
                false, GATE_OPEN, FOREGROUND, false, true, false));
        assertFalse(ClockPageController.computeForceHide(
                false, GATE_OPEN, FOREGROUND, true, true, false));
    }

    @Test
    public void beforeFirstUnlockAfterBoot_neverHides() {
        // Boot window: the home task sits behind the not-yet-shown keyguard and would otherwise
        // look "foreground"; the post-boot keyguard gate must block any hide until first unlock.
        assertFalse(ClockPageController.computeForceHide(
                READY, false, FOREGROUND, false, true, false));
        assertFalse(ClockPageController.computeForceHide(
                READY, false, FOREGROUND, true, true, false));
    }

    @Test
    public void launcherNotForeground_neverHides() {
        assertFalse(ClockPageController.computeForceHide(
                READY, GATE_OPEN, false, false, true, false));
        assertFalse(ClockPageController.computeForceHide(
                READY, GATE_OPEN, false, true, true, false));
    }

    @Test
    public void nonClockPage_shows() {
        assertFalse(ClockPageController.computeForceHide(
                READY, GATE_OPEN, FOREGROUND, false, false, false));
    }

    @Test
    public void overlayShowing_shows() {
        assertFalse(ClockPageController.computeForceHide(
                READY, GATE_OPEN, FOREGROUND, false, true, true));
    }

    @Test
    public void idleState_shows() {
        assertFalse(ClockPageController.computeForceHide(
                false, false, false, false, false, false));
    }
}
