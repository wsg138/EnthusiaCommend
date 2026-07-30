package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemovedRepMigrationTest {
    @Test
    void oldRemovedEntryRestoresHistoricalNegativeValue() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "abc12345");
        raw.put("giver", UUID.randomUUID().toString());
        raw.put("target", UUID.randomUUID().toString());
        raw.put("positive", false);
        raw.put("category", "OTHER_NEGATIVE");
        raw.put("reason", "legacy");
        raw.put("createdAt", 1L);
        raw.put("lastEditedAt", 2L);
        raw.put("removedAt", 3L);
        RepService.RemovedRep loaded = RepService.RemovedRep.fromMap(raw);
        assertNotNull(loaded);
        assertEquals(-1, loaded.commendation().getScoreValue());
        assertEquals(RepCategory.SCAMMED, loaded.commendation().getCategory());
    }
}
