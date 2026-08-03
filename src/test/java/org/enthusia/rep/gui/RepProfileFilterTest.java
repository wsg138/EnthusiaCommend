package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepProfileFilterTest {

    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void overallFilterKeepsEveryEntryAndScore() {
        List<Commendation> entries = sampleEntries();
        RepProfileFilter filter = RepProfileFilter.overall();

        assertTrue(filter.isOverall());
        assertEquals(3, filter.count(entries));
        assertEquals(0, filter.score(entries));
        assertEquals("All Reputation", filter.displayName());
    }

    @Test
    void polarityFiltersSeparatePositiveAndNegativeEntries() {
        List<Commendation> entries = sampleEntries();
        RepProfileFilter positive = RepProfileFilter.polarity(true);
        RepProfileFilter negative = RepProfileFilter.polarity(false);

        assertTrue(positive.isPolarity());
        assertEquals(2, positive.count(entries));
        assertEquals(2, positive.score(entries));
        assertEquals("All Positive Reputation", positive.displayName());

        assertEquals(1, negative.count(entries));
        assertEquals(-2, negative.score(entries));
        assertEquals("All Negative Reputation", negative.displayName());
    }

    @Test
    void categoryFilterUsesCanonicalCategoryAndPolarity() {
        List<Commendation> entries = sampleEntries();
        RepProfileFilter filter = RepProfileFilter.category(RepCategory.HELPED_ME);

        assertFalse(filter.isOverall());
        assertFalse(filter.isPolarity());
        assertTrue(filter.positive());
        assertEquals(1, filter.count(entries));
        assertEquals(1, filter.score(entries));
        assertEquals("Helped Me", filter.displayName());
    }

    private List<Commendation> sampleEntries() {
        return List.of(
                entry(true, RepCategory.WAS_KIND, 1),
                entry(true, RepCategory.HELPED_ME, 1),
                entry(false, RepCategory.SCAMMED, -2)
        );
    }

    private Commendation entry(boolean positive, RepCategory category, int score) {
        return new Commendation(
                UUID.randomUUID(), TARGET, positive, category, "reason",
                1L, 1L, null, score
        );
    }
}
