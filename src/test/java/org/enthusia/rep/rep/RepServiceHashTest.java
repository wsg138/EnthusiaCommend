package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepServiceHashTest {
    @Test
    void createsStableProtectedIpIdentifier() {
        String first = RepService.hashIpValue("203.0.113.42");
        String second = RepService.hashIpValue("10.0.0.110");

        assertEquals("h1:d1c934c619b3764c369349b858122f80", first);
        assertEquals("h1:8a5c21595b8d0410385fc1a3c1cc0c67", second);
        assertTrue(IpAddressHasher.isProtectedIdentifier(first));
        assertTrue(IpAddressHasher.isProtectedIdentifier(second));
    }

    @Test
    void omitsMissingIpAddresses() {
        assertNull(RepService.hashIpValue(null));
        assertNull(RepService.hashIpValue("  "));
    }
}
