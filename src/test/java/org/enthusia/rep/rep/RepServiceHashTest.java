package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepServiceHashTest {
    @Test
    void createsStableFixedWidthIpHash() {
        assertEquals("17af1cf3d1b5332c", RepService.hashIpValue("203.0.113.42"));
        assertEquals("001a300482762fdd", RepService.hashIpValue("10.0.0.110"));
    }

    @Test
    void omitsMissingIpAddresses() {
        assertNull(RepService.hashIpValue(null));
        assertNull(RepService.hashIpValue("  "));
    }
}
