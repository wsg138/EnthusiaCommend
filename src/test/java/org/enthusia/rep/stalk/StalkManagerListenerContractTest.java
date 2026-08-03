package org.enthusia.rep.stalk;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StalkManagerListenerContractTest {
    @Test
    void completedMovementIsObservedAtMonitorAndIgnoresCancelledEvents() throws Exception {
        assertFinalMovementHandler("onMove", PlayerMoveEvent.class);
        assertFinalMovementHandler("onTeleport", PlayerTeleportEvent.class);
    }

    private void assertFinalMovementHandler(String name, Class<?> eventType) throws Exception {
        Method method = StalkManager.class.getDeclaredMethod(name, eventType);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }
}
