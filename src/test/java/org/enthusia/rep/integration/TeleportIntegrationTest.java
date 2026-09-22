package org.enthusia.rep.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.rep.RepService;
import org.enthusia.teleport.api.TeleportApi;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;

class TeleportIntegrationTest {
    @ParameterizedTest
    @CsvSource({"20,0.5", "-10,1.4", "0,1.0"})
    void appliesWarmupAndResetsCooldownOnUpdateAndShutdown(int score, double multiplier) {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var plugin = mock(CommendPlugin.class);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            var api = mock(TeleportApi.class);
            var services = mock(ServicesManager.class);
            @SuppressWarnings("unchecked")
            RegisteredServiceProvider<TeleportApi> provider = mock(RegisteredServiceProvider.class);
            when(provider.getProvider()).thenReturn(api);
            when(services.getRegistration(TeleportApi.class)).thenReturn(provider);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            var player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            var reps = mock(RepService.class);
            when(reps.getEffects(id)).thenReturn(new RepConfig(new YamlConfiguration()).resolveEffects(score));
            var integration = new TeleportIntegration(plugin, reps);
            integration.updatePlayer(id);
            verify(api).setWarmupModifier(id, multiplier);
            verify(api).setCooldownModifier(id, 1.0);
            clearInvocations(api);
            integration.shutdown();
            verify(api).setWarmupModifier(id, 1.0);
            verify(api).setCooldownModifier(id, 1.0);
        }
    }
}
