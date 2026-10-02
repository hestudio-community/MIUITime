package net.hestudio.miuitime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tiny persisted snapshot of the launcher layout facts feature 2 needs, used to bridge the
 * gap after a mid-session SystemUI restart: the launcher's cold-start item lines have rolled
 * out of the logcat ring buffer and it only re-emits {@code screen()} snapshots on a page
 * change, so without a cache the clock-page inventory stays empty until the user swipes once
 * (the clock wrongly shows on a clock-widget page).
 *
 * <p>Safety rules, all failing toward showing the clock:</p>
 * <ul>
 *   <li>only plain desktop clock items are cached — stacked-widget pages are volatile
 *       (their visible member flips) and are never restored;</li>
 *   <li>the cache is valid only within one boot ({@link #usableFor} compares the kernel
 *       boot id, which changes on every reboot);</li>
 *   <li>the consumer drops the cache at the first fresh layout/item line from the launcher,
 *       so real data always wins and staleness is bounded by the SystemUI restart window.</li>
 * </ul>
 *
 * <p>Pure JVM logic (encode/decode/usableFor are unit-tested); only {@link #readBootId()}
 * touches the filesystem.</p>
 */
final class InventoryCache {

    private static final String VERSION = "v=1";
    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    final String bootId;
    final int currentScreenId;
    final Set<Integer> clockScreens;

    InventoryCache(String bootId, int currentScreenId, Collection<Integer> clockScreens) {
        this.bootId = bootId;
        this.currentScreenId = currentScreenId;
        this.clockScreens = new TreeSet<>(clockScreens);
    }

    String encode() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(VERSION).append('\n');
        sb.append("boot=").append(bootId).append('\n');
        sb.append("current=").append(currentScreenId).append('\n');
        sb.append("screens=");
        boolean first = true;
        for (int screenId : clockScreens) {
            if (!first) {
                sb.append(',');
            }
            sb.append(screenId);
            first = false;
        }
        return sb.toString();
    }

    /** Returns {@code null} for anything malformed — a bad cache must never mis-hide. */
    static InventoryCache decode(String text) {
        if (text == null) {
            return null;
        }
        String bootId = null;
        int current = -1;
        List<Integer> screens = new ArrayList<>();
        boolean versionSeen = false;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!versionSeen) {
                // The version line must come first; unknown formats are never adopted.
                if (!VERSION.equals(line)) {
                    return null;
                }
                versionSeen = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            try {
                switch (key) {
                    case "boot":
                        if (value.isEmpty()) {
                            return null;
                        }
                        bootId = value;
                        break;
                    case "current":
                        current = Integer.parseInt(value);
                        break;
                    case "screens":
                        if (!value.isEmpty()) {
                            for (String part : value.split(",")) {
                                int screenId = Integer.parseInt(part.trim());
                                if (screenId < 0) {
                                    return null;
                                }
                                screens.add(screenId);
                            }
                        }
                        break;
                    default:
                        return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!versionSeen || bootId == null) {
            return null;
        }
        return new InventoryCache(bootId, current, screens);
    }

    /** A cache is only ever reused within the boot it was written in. */
    boolean usableFor(String currentBootId) {
        return bootId != null && bootId.equals(currentBootId);
    }

    /** Test seam: overrides {@link #readBootId()} on dev hosts that have no {@code /proc}. */
    static String bootIdForTests;

    /** Kernel boot id: changes on every reboot, readable without special permissions. */
    static String readBootId() {
        if (bootIdForTests != null) {
            return bootIdForTests;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(BOOT_ID_PATH), StandardCharsets.US_ASCII);
            if (!lines.isEmpty()) {
                String id = lines.get(0).trim();
                return id.isEmpty() ? null : id;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
