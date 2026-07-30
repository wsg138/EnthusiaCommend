package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepRulesTest {
    @Test
    void detectsRecentReciprocityOnlyWithinWindow() {
        long now = 1_000_000_000L;
        Commendation recent = new Commendation(UUID.randomUUID(), UUID.randomUUID(), true,
                RepCategory.WAS_KIND, "", now - RepRules.RECIPROCITY_WINDOW_MILLIS + 1, now - 1000, null, 1);
        Commendation stale = new Commendation(UUID.randomUUID(), UUID.randomUUID(), true,
                RepCategory.WAS_KIND, "", now - RepRules.RECIPROCITY_WINDOW_MILLIS - 1,
                now - RepRules.RECIPROCITY_WINDOW_MILLIS - 1, null, 1);
        assertTrue(RepRules.isRecentReciprocal(recent, now));
        assertFalse(RepRules.isRecentReciprocal(stale, now));
    }

    @Test
    void clusterCountsDistinctRecentNegativeGivers() {
        long now = 2_000_000_000L;
        UUID target = UUID.randomUUID();
        UUID giverA = UUID.randomUUID();
        List<Commendation> entries = List.of(
                new Commendation(giverA, target, false, RepCategory.GRIEFED, "", now, now - 100, null, -2),
                new Commendation(giverA, target, false, RepCategory.SCAMMED, "", now, now - 50, null, -2),
                new Commendation(UUID.randomUUID(), target, false, RepCategory.TRAPPED, "", now, now - 20, null, -2),
                new Commendation(UUID.randomUU% ¤°Ñ…É•Ğ°ÑÉÕ”°I•Á…Ñ•½Éä¹]M}-%9°€ˆˆ°¹½Ü°¹½Ü€´€ÄÀ°¹Õ±°°€Ä¤°(€€€€€€€€€€€€€€€¹•Ü½µµ•¹‘…Ñ¥½¸¡UU%¹É…¹‘½µUU% ¤°Ñ…É•Ğ°™…±Í”°I•Á…Ñ•½Éä¹M5}MQ10°€ˆˆ°(€€€€€€€€€€€€€€€€€€€€€€€¹½Ü°¹½Ü€´I•ÁIÕ±•Ì¹1UMQI}]%9=]}5%11%L€´€Ä°¹Õ±°°€´È¤(€€€€€€€€¤ì(€€€€€€€M•ĞñUU%ø¥Ù•ÉÌ€ôI•ÁIÕ±•Ì¹É••¹Ñ9•…Ñ¥Ù•¥Ù•ÉÌ¡•¹ÑÉ¥•Ì°¹½Ü¤ì(€€€€€€€…ÍÍ•ÉÑÅÕ…±Ì È°¥Ù•ÉÌ¹Í¥é” ¤¤ì(€€€€€€€…ÍÍ•ÉÑQÉÕ”¡¥Ù•ÉÌ¹½¹Ñ…¥¹Ì¡¥Ù•É¤¤ì(€€€ô((€€€Q•ÍĞ(€€€Ù½¥±•…å=Ñ¡•É…Ñ•½É¥•ÍÉ•9½ÑM•±•Ñ…‰±” ¤ì(€€€€€€€…ÍÍ•ÉÑ…±Í”¡I•Á…Ñ•½Éä¹=Q!I}A=M%Q%Y¹¥ÍM•±•Ñ…‰±” ¤¤ì(€€€€€€€…ÍÍ•ÉÑ…±Í”¡I•Á…Ñ•½Éä¹=Q!I}9Q%Y¹¥ÍM•±•Ñ…‰±” ¤¤ì(€€€€€€€…ÍÍ•ÉÑÅÕ…±Ì¡I•Á…Ñ•½Éä¹]M}-%9°I•Á…Ñ•½Éä¹=Q!I}A=M%Q%Y¹µ¥É…Ñ•‘…Ñ•½Éä ¤¤ì(€€€€€€€…ÍÍ•ÉÑÅÕ…±Ì¡I•Á…Ñ•½Éä¹M55°I•Á…Ñ•½Éä¹=Q!I}9Q%Y¹µ¥É…Ñ•‘…Ñ•½Éä ¤¤ì(€€€€€€€…ÍÍ•ÉÑÅÕ…±Ì ´È°I•Á…Ñ•½Éä¹I%¹‘•™…Õ±ÑM½É•Y…±Õ” ¤¤ì(€€€ô)ô(