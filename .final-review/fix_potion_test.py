from pathlib import Path

manager_path = Path("src/main/java/org/enthusia/rep/effects/RepEffectManager.java")
manager = manager_path.read_text(encoding="utf-8")
old = '''    static boolean wasPotionEffectAppliedOrRefreshed(PotionEffect before, PotionEffect after) {
        return after != null && (before == null
                || before.getAmplifier() != after.getAmplifier()
                || after.getDuration() > before.getDuration() + 2);
    }'''
new = '''    private static boolean wasPotionEffectAppliedOrRefreshed(PotionEffect before, PotionEffect after) {
        return after != null && wasPotionEffectAppliedOrRefreshed(
                before != null,
                before != null ? before.getDuration() : 0,
                before != null ? before.getAmplifier() : 0,
                after.getDuration(),
                after.getAmplifier());
    }

    static boolean wasPotionEffectAppliedOrRefreshed(boolean hadBefore, int beforeDuration,
                                                     int beforeAmplifier, int afterDuration,
                                                     int afterAmplifier) {
        return !hadBefore
                || beforeAmplifier != afterAmplifier
                || afterDuration > beforeDuration + 2;
    }'''
if old not in manager:
    raise SystemExit("Potion helper block not found")
manager_path.write_text(manager.replace(old, new, 1), encoding="utf-8")

Path("src/test/java/org/enthusia/rep/effects/RepEffectManagerTest.java").write_text('''package org.enthusia.rep.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepEffectManagerTest {
    @Test
    void onlyNewRefreshedOrAmplifierChangedEffectsAreModified() {
        assertFalse(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 199, 0));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 240, 0));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 199, 1));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(false, 0, 0, 200, 0));
    }
}
''', encoding="utf-8")
