package org.enthusia.rep.command;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.rep.RepTradingAlertAccess;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommendCommandPermissionsTest {
    @Test
    void operatorCanUseAlertsThroughNormalPermissionResolution() {
        CommandSender operator = sender(true, Set.of());

        assertTrue(CommendCommand.canUseTradingAlerts(operator));
        assertTrue(CommendCommand.rootSubcommands(operator).contains("alerts"));
    }

    @Test
    void explicitlyPermittedPlayerCanUseAlerts() {
        CommandSender permitted = sender(false, Set.of(RepTradingAlertAccess.PERMISSION));

        assertTrue(CommendCommand.canUseTradingAlerts(permitted));
        assertTrue(CommendCommand.rootSubcommands(permitted).contains("alerts"));
    }

    @Test
    void unauthorizedPlayerCannotUseOrCompleteAlerts() {
        CommandSender unauthorized = sender(false, Set.of());

        assertFalse(CommendCommand.canUseTradingAlerts(unauthorized));
        assertFalse(CommendCommand.rootSubcommands(unauthorized).contains("alerts"));
    }

    @Test
    void pluginDescriptorUsesOneOpDefaultPermissionForCommandAndDelivery() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(stream);
            YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String permissionPath = "permissions." + RepTradingAlertAccess.PERMISSION;

            assertEquals("op", descriptor.getString(permissionPath + ".default"));
            assertTrue(descriptor.getString(permissionPath + ".description", "").contains("/rep alerts"));
            assertTrue(descriptor.getBoolean("permissions.enthusiacommend.rep.admin.children."
                    + RepTradingAlertAccess.PERMISSION));
        }
    }

    private CommandSender sender(boolean operator, Set<String> explicitPermissions) {
        Set<String> permissions = new HashSet<>(explicitPermissions);
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOp" -> operator;
                    case "setOp" -> null;
                    case "hasPermission" -> hasPermission(operator, permissions, arguments);
                    case "getName" -> operator ? "Operator" : "Player";
                    case "isPermissionSet" -> true;
                    case "spigot" -> null;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestCommandSender";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private boolean hasPermission(boolean operator, Set<String> permissions, Object[] arguments) {
        if (arguments == null || arguments.length == 0 || !(arguments[0] instanceof String permission)) {
            return false;
        }
        if (permissions.contains(permission)) {
            return true;
        }
        return operator && (permission.equals(RepTradingAlertAccess.PERMISSION)
                || permission.equals("enthusiacommend.rep.admin"));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
