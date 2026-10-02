package net.hestudio.miuitime;

/**
 * Pure hide-gate state for feature 2: decides whether the status bar clock must be hidden and
 * owns the <em>publication ordering</em> of that decision to the visibility enforcement hooks.
 *
 * <p>The enforcement hooks ({@code MiuiClock.setPolicyVisibility} / {@code View.setVisibility})
 * never call back into the controller: they only read the flag published through
 * {@link Sink}. The single hard rule of this class is therefore <strong>publish before act</strong>:
 * the settled decision must be published to the sink <em>before</em> the apply action runs,
 * otherwise the still-stale flag makes the hooks veto the module's own restore calls (the
 * regression where every hidden→shown transition left the clock {@code GONE}).</p>
 *
 * <p>Everything defaults toward showing the clock: without boot completion, before the post-boot
 * keyguard gate has opened, or without launcher foreground state, the decision is {@code false}.
 * No Android types here — this class is JVM unit-tested ({@code HideGateTest}).</p>
 */
final class HideGate {

    /** Publishes the force-hide flag to the enforcement hooks. */
    interface Sink {
        void publish(boolean forceHide);
    }

    /** The settled apply step (hide or show the clock). */
    interface ApplyAction {
        void run(boolean hide);
    }

    private final Sink sink;

    // Inputs of the decision. Volatile: written from the launcher-log listener / probe threads,
    // read on the main thread (same contract as the former controller fields).
    private volatile boolean bootReady;
    private volatile boolean keyguardGateOpen;
    private volatile boolean launcherForeground;
    private volatile boolean showPending;
    private volatile boolean clockPage;
    private volatile boolean overlayShowing;

    HideGate(Sink sink) {
        this.sink = sink;
    }

    /**
     * Hide/enforce decision. Pure state computation, no side effects.
     *
     * <p>Everything defaults toward showing the clock: without boot completion, before the
     * post-boot keyguard gate has opened, or without launcher foreground state, the answer is
     * {@code false}.</p>
     */
    static boolean compute(boolean bootReady, boolean keyguardGateOpen,
                           boolean launcherForeground, boolean showPending,
                           boolean clockPage, boolean overlayShowing) {
        return bootReady && keyguardGateOpen && launcherForeground
                && (showPending || (clockPage && !overlayShowing));
    }

    boolean compute() {
        return compute(bootReady, keyguardGateOpen, launcherForeground,
                showPending, clockPage, overlayShowing);
    }

    /** Publishes the current decision to the sink. */
    void publish() {
        sink.publish(compute());
    }

    /**
     * Launcher page/overlay state from the log listener (any thread).
     *
     * <p>Deliberately does <em>not</em> publish: the debounced apply path publishes through
     * {@link #pendingShow(boolean)} so the clock stays collapsed for the whole settle window
     * (publishing here would un-enforce mid-debounce and let the clock flash on page swipes).</p>
     */
    void onLauncherState(boolean clockPage, boolean overlayShowing) {
        this.clockPage = clockPage;
        this.overlayShowing = overlayShowing;
    }

    /**
     * Probe result (boot / first-unlock gate / launcher foreground), applied on the main thread.
     * Publishes the refreshed decision and reports whether any input changed.
     */
    boolean onProbe(boolean boot, boolean unlocked, boolean foreground) {
        boolean changed = boot != bootReady
                || (unlocked && !keyguardGateOpen)
                || foreground != launcherForeground;
        bootReady = boot;
        if (unlocked) {
            // One-shot: opens on the first observed unlock and never re-closes, so later
            // lock/unlock cycles cannot flash the clock.
            keyguardGateOpen = true;
        }
        launcherForeground = foreground;
        publish();
        return changed;
    }

    /**
     * Sets the "show is deferred" latch used to keep the clock collapsed during the (longer)
     * show debounce, and publishes the resulting decision.
     */
    void pendingShow(boolean pending) {
        showPending = pending;
        publish();
    }

    /**
     * One settle-apply cycle: clear the deferred-show latch, compute the settled decision,
     * <strong>publish it</strong>, then run the action. Publishing inside this method — before
     * {@code action} — is what keeps the enforcement hooks from vetoing the module's own
     * hide/show calls; never reorder these steps.
     */
    void beginApply(ApplyAction action) {
        showPending = false;
        boolean hide = compute();
        sink.publish(hide);
        action.run(hide);
    }

    boolean clockPage() {
        return clockPage;
    }

    boolean overlayShowing() {
        return overlayShowing;
    }

    boolean keyguardGateOpen() {
        return keyguardGateOpen;
    }
}
