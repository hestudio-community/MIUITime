package net.hestudio.miuitime;

import static org.junit.Assert.assertEquals;

import android.view.View;

import org.junit.Test;

/**
 * Pure rewrite rules of the visibility enforcement hooks. The hook bodies only do a cheap
 * {@code forceHide()} fast-path read and delegate the decision here, so the semantics stay
 * JVM-testable: nothing is rewritten while no hide is enforced, and only the module's own
 * captured {@code id=clock} views are collapsed while one is.
 */
public class SystemUiClockHookTest {

    private static final int VISIBLE = View.VISIBLE;
    private static final int INVISIBLE = View.INVISIBLE;
    private static final int GONE = View.GONE;

    @Test
    public void viewVisibility_passesThrough_whenNotEnforcing() {
        assertEquals(VISIBLE, SystemUiClockHook.enforceVisibilityArg(VISIBLE, false, true));
        assertEquals(GONE, SystemUiClockHook.enforceVisibilityArg(GONE, false, true));
    }

    @Test
    public void viewVisibility_collapsesOnlyCapturedClocks_whenEnforcing() {
        assertEquals(GONE, SystemUiClockHook.enforceVisibilityArg(VISIBLE, true, true));
        assertEquals(VISIBLE, SystemUiClockHook.enforceVisibilityArg(VISIBLE, true, false));
    }

    @Test
    public void policyVisibility_passesThrough_whenNotEnforcing() {
        assertEquals(VISIBLE, SystemUiClockHook.enforcePolicyArg(VISIBLE, false, true));
    }

    @Test
    public void policyVisibility_redirectsOnlyClocks_whenEnforcing() {
        assertEquals(INVISIBLE, SystemUiClockHook.enforcePolicyArg(VISIBLE, true, true));
        assertEquals(VISIBLE, SystemUiClockHook.enforcePolicyArg(VISIBLE, true, false));
    }
}
