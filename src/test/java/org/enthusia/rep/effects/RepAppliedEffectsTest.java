package org.enthusia.rep.effects;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepAppliedEffectsTest {
    @Test
    void describesNoEffects() {
        assertEquals(
                "You currently have no rep-based buffs or penalties.",
                RepAppliedEffects.NONE.describe()
        );
    }

    @Test
    void describesActiveEffectsInDisplayOrder() {
        RepAppliedEffects effects = new RepAppliedEffects(
                -1,
                10,
                -15,
                7,
                5,
                true,
                ChatColor.RED,
                true,
                5
        );

        assertEquals(
                "Movement: -1%\n"
                        + "Potion duration: +10%\n"
                        + "Rocket flight duration: -15%\n"
                        + "Ender pearl cooldown: 7s\n"
                        + "Wind charge cooldown: 5s\n"
                        + "Glow: RED\n"
                        + "Stalkable\n"
                        + "Cashback: 5%",
                effects.describe()
        );
    }
}
