package org.enthusia.rep.analytics;

import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReputationChangeRecordTest {
    @Test
    void loadsLegacyRecordWithoutOptionalMetadata() {
        UUID targetId = UUID.randomUUID();
        Map<String, Object> stored = Map.of(
                "id", "legacy-entry",
                "timestamp", 1234L,
                "target", targetId.toString(),
                "amount", "1",
                "action", ReputationChangeAction.ADD.name(),
                "source", ReputationChangeSource.PLAYER_ACTION.name(),
                "reason", "Helpful player",
                "oldTotal", "4",
                "newTotal", 5
        );

        ReputationChangeRecord record = ReputationChangeRecord.fromMap(stored);

        assertEquals("legacy-entry", record.id());
        assertEquals(targetId, record.targetId());
        assertNull(record.actorId());
        assertNull(record.actorName());
        assertNull(record.category());
        assertEquals(ReputationChangeOutcome.SUCCEEDED, record.outcome());
        assertEquals(1, record.amount());
        assertEquals(4, record.oldTotal());
        assertEquals(5, record.newTotal());
    }

    @Test
    void roundTripsCompleteRecord() {
        ReputationChangeRecord original = new ReputationChangeRecord(
                "entry-id",
                9876L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "StaffMember",
                -2,
                ReputationChangeAction.REMOVE,
                ReputationChangeSource.STAFF_COMMAND,
                ReputationChangeOutcome.SUCCEEDED,
                "Correction",
                RepCategory.SCAMMED,
                7,
                5
        );

        assertEquals(original, ReputationChangeRecord.fromMap(original.serialize()));
    }

    @Test
    void rejectsMalformedRequiredFields() {
        assertNull(ReputationChangeRecord.fromMap(Map.of("target", "not-a-uuid")));
        assertNull(ReputationChangeRecord.fromMap(null));
    }
}
