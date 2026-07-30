package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommendationResultTest {
    @Test
    void invalidAndCooldownFailuresAreDistinct() {
        assertEquals(RepService.CommendationResult.Failure.INVALID_CATEGORY,
                RepService.CommendationResult.invalid().failure());
        assertEquals(RepService.CommendationResult.Failure.COOLDOWN,
                RepService.CommendationResult.cooldown(1000L).failure());
    }
}
