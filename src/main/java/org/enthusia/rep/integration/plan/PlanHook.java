package org.enthusia.rep.integration.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.delivery.web.ResolverService;
import com.djrapitops.plan.delivery.web.ResourceService;
import com.djrapitops.plan.extension.ExtensionService;
import org.enthusia.rep.CommendPlugin;

public final class PlanHook {

    private final CommendPlugin plugin;
    private PlanReputationDataExtension dataExtension;
    private boolean pageRegistered;
    private boolean active = true;

    public PlanHook(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        CapabilityService capabilities = CapabilityService.getInstance();
        registerDataExtension(capabilities);
        if (plugin.getRepConfig().isPlanPageEnabled()) {
            registerPage(capabilities);
        }
        capabilities.registerEnableListener(enabled -> {
            if (enabled && active && plugin.isEnabled() && plugin.getRepConfig().isPlanIntegrationEnabled()) {
                registerDataExtension(CapabilityService.getInstance());
                if (plugin.getRepConfig().isPlanPageEnabled()) {
                    registerPage(CapabilityService.getInstance());
                }
            }
        });
    }

    public void shutdown() {
        active = false;
        if (dataExtension != null) {
            try {
                ExtensionService.getInstance().unregister(dataExtension);
            } catch (RuntimeException ignored) {
            }
        }
        dataExtension = null;
    }

    private void registerDataExtension(CapabilityService capabilities) {
        if (!capabilities.hasCapability("DATA_EXTENSION_VALUES") || !capabilities.hasCapability("DATA_EXTENSION_TABLES")) {
            return;
        }
        try {
            if (dataExtension != null) {
                ExtensionService.getInstance().unregister(dataExtension);
            }
            dataExtension = new PlanReputationDataExtension(plugin);
            ExtensionService.getInstance().register(dataExtension);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            plugin.getLogger().warning("Plan DataExtension registration failed: " + ex.getMessage());
        }
    }

    private void registerPage(CapabilityService capabilities) {
        if (pageRegistered || !capabilities.hasCapability("PAGE_EXTENSION_RESOLVERS") || !capabilities.hasCapability("PAGE_EXTENSION_RESOURCES")) {
            return;
        }
        ResolverService resolverService = ResolverService.getInstance();
        String basePath = PlanReputationResolver.BASE_PATH;
        if (resolverService.getResolver(basePath).isPresent()) {
            plugin.getLogger().warning("Plan page path " + basePath + " is already registered; reputation page skipped.");
            return;
        }
        ResourceService resourceService = ResourceService.getInstance();
        resolverService.registerResolver(plugin.getName(), basePath, new PlanReputationResolver(plugin, resourceService));
        pageRegistered = true;
    }
}
