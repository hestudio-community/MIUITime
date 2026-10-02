package net.hestudio.miuitime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Persistence format of the inventory cache that bridges the mid-session SystemUI restart gap
 * (launcher layout facts survive the restart; see {@link InventoryCache}). Malformed or foreign
 * content must be rejected — a bad cache can only ever fail toward showing the clock.
 */
public class InventoryCacheTest {

    @Test
    public void encodeDecodeRoundTrip() {
        InventoryCache cache = new InventoryCache("boot-1", 4, Arrays.asList(9, 1));
        InventoryCache decoded = InventoryCache.decode(cache.encode());
        assertNotNull(decoded);
        assertEquals("boot-1", decoded.bootId);
        assertEquals(4, decoded.currentScreenId);
        assertEquals("screens must be ordered and deduplicated",
                new TreeSet<>(Arrays.asList(1, 9)), decoded.clockScreens);
    }

    @Test
    public void emptyStateRoundTrips() {
        InventoryCache decoded = InventoryCache.decode(
                new InventoryCache("boot-1", -1, Collections.emptyList()).encode());
        assertNotNull(decoded);
        assertEquals(-1, decoded.currentScreenId);
        assertTrue(decoded.clockScreens.isEmpty());
    }

    @Test
    public void malformedContentIsRejected() {
        assertNull(InventoryCache.decode(null));
        assertNull(InventoryCache.decode(""));
        assertNull(InventoryCache.decode("garbage"));
        assertNull(InventoryCache.decode("v=2\nboot=x\ncurrent=1\nscreens="));
        assertNull(InventoryCache.decode("v=1\ncurrent=1\nscreens="));           // missing boot id
        assertNull(InventoryCache.decode("v=1\nboot=x\ncurrent=abc\nscreens=")); // bad number
        assertNull(InventoryCache.decode("v=1\nboot=x\ncurrent=1\nscreens=1,-2")); // negative screen
        assertNull(InventoryCache.decode("v=1\nboot=x\ncurrent=1\nscreens=1\nextra=1")); // unknown key
        assertNull(InventoryCache.decode("boot=x\ncurrent=1\nscreens=1\nv=1"));  // version not first
    }

    @Test
    public void usableOnlyWithinTheSameBoot() {
        InventoryCache cache = new InventoryCache("boot-1", 1, Collections.singletonList(1));
        assertTrue(cache.usableFor("boot-1"));
        assertFalse("a cache from a previous boot must never be adopted", cache.usableFor("boot-2"));
        assertFalse(cache.usableFor(null));
    }
}
