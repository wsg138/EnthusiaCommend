from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text and old not in text:
        return
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Persist cooldowns as structured data while preserving the existing six-argument snapshot constructor.
Path("src/main/java/org/enthusia/rep/storage/PluginDataSnapshot.java").write_text('''package org.enthusia.rep.storage;

import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PluginDataSnapshot(
        Map<UUID, Integer> scores,
        List<Commendation> commendations,
        List<RepService.RemovedRep> removedEntries,
        List<StalkEntry> stalkEntries,
        List<ReputationChangeRecord> reputationChanges,
        List<RepService.SuspiciousRepCase> suspiciousCases,
        List<RemovalCooldownEntry> removalCooldowns
) {
    public PluginDataSnapshot(
            Map<UUID, Integer> scores,
            List<Commendation> commendations,
            List<RepService.RemovedRep> removedEntries,
            List<StalkEntry> stalkEntries,
            List<ReputationChangeRecord> reputationChanges,
            List<RepService.SuspiciousRepCase> suspiciousCases
    ) {
        this(scores, commendations, removedEntries, stalkEntries, reputationChanges, suspiciousCases, List.of());
    }

    public static final PluginDataSnapshot EMPTY = new PluginDataSnapshot(
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public record StalkEntry(UUID stalkerId, UUID targetId, long expiresAt) {
    }

    public record RemovalCooldownEntry(UUID giverId, UUID targetId, long removedAt) {
    }
}
''', encoding="utf-8")

replace_once(
    "src/main/java/org/enthusia/rep/storage/PluginDataStore.java",
    "    void save(PluginDataSnapshot snapshot);",
    "    boolean save(PluginDataSnapshot snapshot);"
)

Path("src/main/java/org/enthusia/rep/storage/OrderedSnapshotWriter.java").write_text('''package org.enthusia.rep.storage;

/**
 * Serializes snapshot writes and prevents a delayed older autosave from overwriting
 * a newer autosave or the final shutdown snapshot.
 */
public final class OrderedSnapshotWriter {
    private final PluginDataStore dataStore;
    private long latestSavedSequence = Long.MIN_VALUE;

    public OrderedSnapshotWriter(PluginDataStore dataStore) {
        this.dataStore = dataStore;
    }

    public synchronized SaveResult saveIfNewer(long sequence, PluginDataSnapshot snapshot) {
        if (sequence <= latestSavedSequence) {
            return SaveResult.STALE;
        }
        if (!dataStore.save(snapshot)) {
            return SaveResult.FAILED;
        }
        latestSavedSequence = sequence;
        return SaveResult.SAVED;
    }

    public enum SaveResult {
        SAVED,
        STALE,
        FAILED
    }
}
''', encoding="utf-8")

# YAML datastore: versioned cooldown persistence and explicit save success/failure.
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    "    private static final int DATA_VERSION = 4;",
    "    private static final int DATA_VERSION = 5;"
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    "        List<RepService.SuspiciousRepCase> suspiciousCases = new ArrayList<>();",
    "        List<RepService.SuspiciousRepCase> suspiciousCases = new ArrayList<>();\n        List<PluginDataSnapshot.RemovalCooldownEntry> removalCooldowns = new ArrayList<>();"
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    '''        for (Map<?, ?> rawCase : config.getMapList("suspiciousCases")) {
            RepService.SuspiciousRepCase caseData = RepService.SuspiciousRepCase.fromMap(rawCase);
            if (caseData != null) {
                suspiciousCases.add(caseData);
            }
        }

        ConfigurationSection stalkSection''',
    '''        for (Map<?, ?> rawCase : config.getMapList("suspiciousCases")) {
            RepService.SuspiciousRepCase caseData = RepService.SuspiciousRepCase.fromMap(rawCase);
            if (caseData != null) {
                suspiciousCases.add(caseData);
            }
        }

        for (Map<?, ?> rawCooldown : config.getMapList("removalCooldowns")) {
            try {
                UUID giverId = UUID.fromString(String.valueOf(rawCooldown.get("giver")));
                UUID targetId = UUID.fromString(String.valueOf(rawCooldown.get("target")));
                long removedAt = rawCooldown.get("removedAt") instanceof Number value
                        ? value.longValue() : Long.parseLong(String.valueOf(rawCooldown.get("removedAt")));
                removalCooldowns.add(new PluginDataSnapshot.RemovalCooldownEntry(giverId, targetId, removedAt));
            } catch (Exception ignored) {
            }
        }

        ConfigurationSection stalkSection'''
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    "        return new PluginDataSnapshot(scores, commendations, removedEntries, stalkEntries, reputationChanges, suspiciousCases);",
    "        return new PluginDataSnapshot(scores, commendations, removedEntries, stalkEntries, reputationChanges, suspiciousCases, removalCooldowns);"
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    "    public void save(PluginDataSnapshot snapshot) {",
    "    public boolean save(PluginDataSnapshot snapshot) {"
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    '''        config.set("suspiciousCases", suspiciousCases);

        int stalkIndex = 0;''',
    '''        config.set("suspiciousCases", suspiciousCases);

        List<Map<String, Object>> removalCooldowns = new ArrayList<>();
        for (PluginDataSnapshot.RemovalCooldownEntry entry : snapshot.removalCooldowns()) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("giver", entry.giverId().toString());
            serialized.put("target", entry.targetId().toString());
            serialized.put("removedAt", entry.removedAt());
            removalCooldowns.add(serialized);
        }
        config.set("removalCooldowns", removalCooldowns);

        int stalkIndex = 0;'''
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    '''        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data directory.");
            return;
        }''',
    '''        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data directory.");
            return false;
        }'''
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    '''            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {''',
    '''            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {'''
)
replace_once(
    "src/main/java/org/enthusia/rep/storage/YamlPluginDataStore.java",
    '''            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }''',
    '''            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
            return false;
        }
    }'''
)

# Pure cooldown rule used during load and lookup.
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepRules.java",
    "    public static boolean isRecentReciprocal(Commendation reverse, long nowMillis) {",
    '''    public static boolean isCooldownActive(long removedAt, long nowMillis, long cooldownMillis) {
        return cooldownMillis > 0L
                && nowMillis >= removedAt
                && nowMillis - removedAt < cooldownMillis;
    }

    public static boolean isRecentReciprocal(Commendation reverse, long nowMillis) {'''
)

# Rep service: structured/persisted cooldowns and an actually complete, non-double-counted admin reset.
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    "    private final Map<String, Long> removalCooldowns = new ConcurrentHashMap<>();",
    "    private final Map<RepPair, Long> removalCooldowns = new ConcurrentHashMap<>();"
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''        removalCooldowns.clear();
        rebuildAntiAbuseIndex();''',
    '''        removalCooldowns.clear();
        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        for (PluginDataSnapshot.RemovalCooldownEntry entry : snapshot.removalCooldowns()) {
            if (RepRules.isCooldownActive(entry.removedAt(), now, cooldownMillis)) {
                removalCooldowns.put(new RepPair(entry.giverId(), entry.targetId()), entry.removedAt());
            }
        }
        rebuildAntiAbuseIndex();'''
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''        List<SuspiciousRepCase> cases;
        synchronized (suspiciousCases) {
            cases = suspiciousCases.stream().map(SuspiciousRepCase::copy).toList();
        }
        return new PluginDataSnapshot(
                scores,
                commendations,
                removed,
                base.stalkEntries(),
                base.reputationChanges(),
                cases
        );''',
    '''        List<SuspiciousRepCase> cases;
        synchronized (suspiciousCases) {
            cases = suspiciousCases.stream().map(SuspiciousRepCase::copy).toList();
        }
        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        List<PluginDataSnapshot.RemovalCooldownEntry> cooldowns = removalCooldowns.entrySet().stream()
                .filter(entry -> RepRules.isCooldownActive(entry.getValue(), now, cooldownMillis))
                .map(entry -> new PluginDataSnapshot.RemovalCooldownEntry(
                        entry.getKey().giverId(), entry.getKey().targetId(), entry.getValue()))
                .toList();
        return new PluginDataSnapshot(
                scores,
                commendations,
                removed,
                base.stalkEntries(),
                base.reputationChanges(),
                cases,
                cooldowns
        );'''
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    public void resetAllByStaff(UUID targetId, CommandSender actor) {
        int oldScore = getScore(targetId);
        resetAll(targetId);
        int newScore = getScore(targetId);
        recordStaffChange(targetId, actor, newScore - oldScore, ReputationChangeAction.RESET,
                ReputationChangeSource.ADMIN_CORRECTION, null, "Admin reset", oldScore, newScore);
    }''',
    '''    public void resetAllByStaff(UUID targetId, CommandSender actor) {
        UUID removerId = actor instanceof Player player ? player.getUniqueId() : null;
        List<Commendation> current = new ArrayList<>(getCommendationsAbout(targetId));
        for (Commendation commendation : current) {
            removeCommendationLogged(removerId, commendation.getGiver(), targetId, false);
        }
        int residualScore = getScore(targetId);
        if (residualScore != 0) {
            applyScore(targetId, 0, true);
            recordStaffChange(targetId, actor, -residualScore, ReputationChangeAction.RESET,
                    ReputationChangeSource.ADMIN_CORRECTION, null, "Admin reset residual", residualScore, 0);
        }
    }'''
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''        long remaining = repConfig.getEditCooldownMillis() - (System.currentTimeMillis() - removedAt);
        if (remaining <= 0L) {
            removalCooldowns.remove(key(giverId, targetId));
            return 0L;
        }
        return remaining;''',
    '''        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        if (!RepRules.isCooldownActive(removedAt, now, cooldownMillis)) {
            removalCooldowns.remove(key(giverId, targetId));
            return 0L;
        }
        return cooldownMillis - (now - removedAt);'''
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    private String key(UUID giverId, UUID targetId) {
        return giverId + "->" + targetId;
    }''',
    '''    private RepPair key(UUID giverId, UUID targetId) {
        return new RepPair(giverId, targetId);
    }'''
)
replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    "    private record AltRepRecord(UUID giverId, UUID targetId, boolean positive, long timestamp, String ipHash) {",
    '''    private record RepPair(UUID giverId, UUID targetId) {
    }

    private record AltRepRecord(UUID giverId, UUID targetId, boolean positive, long timestamp, String ipHash) {'''
)

# Ordered persistence in plugin lifecycle. Snapshots are still built synchronously; only file I/O is async.
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    "import org.enthusia.rep.storage.PluginDataStore;",
    "import org.enthusia.rep.storage.PluginDataStore;\nimport org.enthusia.rep.storage.OrderedSnapshotWriter;"
)
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    "import java.util.concurrent.atomic.AtomicBoolean;",
    "import java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicLong;"
)
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    '''    private BukkitTask autoSaveTask;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object saveLock = new Object();''',
    '''    private BukkitTask autoSaveTask;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicLong saveSequence = new AtomicLong();
    private OrderedSnapshotWriter snapshotWriter;'''
)
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    '''        this.dataStore = new YamlPluginDataStore(this);
        this.discordWebhookService''',
    '''        this.dataStore = new YamlPluginDataStore(this);
        this.snapshotWriter = new OrderedSnapshotWriter(dataStore);
        this.discordWebhookService'''
)
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    '''        PluginDataSnapshot snapshot = buildSnapshot();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            synchronized (saveLock) {
                dataStore.save(snapshot);
            }
        });''',
    '''        PluginDataSnapshot snapshot = buildSnapshot();
        long sequence = saveSequence.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveSnapshot(snapshot, sequence));'''
)
replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    '''        synchronized (saveLock) {
            dataStore.save(buildSnapshot());
        }
        dirty.set(false);
    }

    private PluginDataSnapshot buildSnapshot() {''',
    '''        long sequence = saveSequence.incrementAndGet();
        OrderedSnapshotWriter.SaveResult result = snapshotWriter.saveIfNewer(sequence, buildSnapshot());
        dirty.set(result == OrderedSnapshotWriter.SaveResult.FAILED);
    }

    private void saveSnapshot(PluginDataSnapshot snapshot, long sequence) {
        OrderedSnapshotWriter.SaveResult result = snapshotWriter.saveIfNewer(sequence, snapshot);
        if (result == OrderedSnapshotWriter.SaveResult.FAILED) {
            dirty.set(true);
        }
    }

    private PluginDataSnapshot buildSnapshot() {'''
)

# Prevent repeated extension of unrelated active potion effects.
replace_once(
    "src/main/java/org/enthusia/rep/effects/RepEffectManager.java",
    "import org.bukkit.inventory.meta.FireworkMeta;",
    "import org.bukkit.inventory.meta.FireworkMeta;\nimport org.bukkit.inventory.meta.PotionMeta;"
)
replace_once(
    "src/main/java/org/enthusia/rep/effects/RepEffectManager.java",
    '''import java.util.ArrayList;
import java.util.List;
import java.util.Map;''',
    '''import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;'''
)
replace_once(
    "src/main/java/org/enthusia/rep/effects/RepEffectManager.java",
    '''    @EventHandler
    public void onPotionConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (warzoneDuelsHook.isDuelExempt(player) || !regionManager.isInSpawnOrWarzone(player.getLocation())) return;
        Material material = event.getItem().getType();
        if (material != Material.POTION && material != Material.SPLASH_POTION && material != Material.LINGERING_POTION) return;
        RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
        if (effects.potionDurationPercent() != 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> applyPotionDurationModifier(player, effects), 5L);
        }
    }

    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof Player player
                    && !warzoneDuelsHook.isDuelExempt(player)
                    && regionManager.isInSpawnOrWarzone(player.getLocation())) {
                RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
                if (effects.potionDurationPercent() != 0) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> applyPotionDurationModifier(player, effects), 5L);
                }
            }
        }
    }

    private void applyPotionDurationModifier(Player player, RepAppliedEffects effects) {
        List<PotionEffect> activeEffects = new ArrayList<>(player.getActivePotionEffects());
        for (PotionEffect effect : activeEffects) {
            if (!isBeneficial(effect.getType())) continue;
            int adjustedDuration = (int) Math.max(1,
                    Math.round(effect.getDuration() * (1.0D + effects.potionDurationPercent() / 100.0D)));
            player.removePotionEffect(effect.getType());
            player.addPotionEffect(new PotionEffect(effect.getType(), adjustedDuration, effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles(), effect.hasIcon()), true);
        }
    }''',
    '''    @EventHandler
    public void onPotionConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (warzoneDuelsHook.isDuelExempt(player) || !regionManager.isInSpawnOrWarzone(player.getLocation())) return;
        if (event.getItem().getType() != Material.POTION) return;
        RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
        Set<PotionEffectType> affectedTypes = potionEffectTypes(event.getItem());
        if (effects.potionDurationPercent() == 0 || affectedTypes.isEmpty()) return;
        Map<PotionEffectType, PotionEffect> before = snapshotPotionEffects(player);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> applyPotionDurationModifier(player, effects, before, affectedTypes), 1L);
    }

    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        Set<PotionEffectType> affectedTypes = new HashSet<>();
        for (PotionEffect effect : event.getPotion().getEffects()) {
            affectedTypes.add(effect.getType());
        }
        if (affectedTypes.isEmpty()) return;
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof Player player
                    && !warzoneDuelsHook.isDuelExempt(player)
                    && regionManager.isInSpawnOrWarzone(player.getLocation())) {
                RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
                if (effects.potionDurationPercent() != 0) {
                    Map<PotionEffectType, PotionEffect> before = snapshotPotionEffects(player);
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> applyPotionDurationModifier(player, effects, before, affectedTypes), 1L);
                }
            }
        }
    }

    private Set<PotionEffectType> potionEffectTypes(ItemStack item) {
        if (!(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return Set.of();
        }
        Set<PotionEffectType> types = new HashSet<>();
        if (potionMeta.getBasePotionType() != null) {
            for (PotionEffect effect : potionMeta.getBasePotionType().getPotionEffects()) {
                types.add(effect.getType());
            }
        }
        for (PotionEffect effect : potionMeta.getCustomEffects()) {
            types.add(effect.getType());
        }
        return Set.copyOf(types);
    }

    private Map<PotionEffectType, PotionEffect> snapshotPotionEffects(Player player) {
        Map<PotionEffectType, PotionEffect> snapshot = new HashMap<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            snapshot.put(effect.getType(), effect);
        }
        return snapshot;
    }

    private void applyPotionDurationModifier(Player player, RepAppliedEffects effects,
                                             Map<PotionEffectType, PotionEffect> before,
                                             Set<PotionEffectType> affectedTypes) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            if (!affectedTypes.contains(effect.getType()) || !isBeneficial(effect.getType())) continue;
            if (!wasPotionEffectAppliedOrRefreshed(before.get(effect.getType()), effect)) continue;
            int adjustedDuration = (int) Math.max(1,
                    Math.round(effect.getDuration() * (1.0D + effects.potionDurationPercent() / 100.0D)));
            player.removePotionEffect(effect.getType());
            player.addPotionEffect(new PotionEffect(effect.getType(), adjustedDuration, effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles(), effect.hasIcon()), true);
        }
    }

    static boolean wasPotionEffectAppliedOrRefreshed(PotionEffect before, PotionEffect after) {
        return after != null && (before == null
                || before.getAmplifier() != after.getAmplifier()
                || after.getDuration() > before.getDuration() + 2);
    }'''
)

# Regression tests.
Path("src/test/java/org/enthusia/rep/storage/OrderedSnapshotWriterTest.java").parent.mkdir(parents=True, exist_ok=True)
Path("src/test/java/org/enthusia/rep/storage/OrderedSnapshotWriterTest.java").write_text('''package org.enthusia.rep.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderedSnapshotWriterTest {
    @Test
    void olderDelayedSnapshotCannotOverwriteNewerSnapshot() {
        FakeStore store = new FakeStore();
        OrderedSnapshotWriter writer = new OrderedSnapshotWriter(store);
        PluginDataSnapshot newer = snapshot(2);
        PluginDataSnapshot older = snapshot(1);

        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(2L, newer));
        assertEquals(OrderedSnapshotWriter.SaveResult.STALE, writer.saveIfNewer(1L, older));
        assertEquals(2, store.lastMarker);
    }

    @Test
    void failedSaveDoesNotAdvanceSequenceAndCanBeRetried() {
        FakeStore store = new FakeStore();
        OrderedSnapshotWriter writer = new OrderedSnapshotWriter(store);
        store.failNext = true;

        assertEquals(OrderedSnapshotWriter.SaveResult.FAILED, writer.saveIfNewer(5L, snapshot(5)));
        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(5L, snapshot(5)));
        assertEquals(5, store.lastMarker);
    }

    private static PluginDataSnapshot snapshot(int marker) {
        return new PluginDataSnapshot(Map.of(new UUID(0L, marker), marker),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static final class FakeStore implements PluginDataStore {
        private boolean failNext;
        private int lastMarker;

        @Override
        public PluginDataSnapshot load() {
            return PluginDataSnapshot.EMPTY;
        }

        @Override
        public boolean save(PluginDataSnapshot snapshot) {
            if (failNext) {
                failNext = false;
                return false;
            }
            lastMarker = snapshot.scores().values().stream().findFirst().orElse(0);
            return true;
        }
    }
}
''', encoding="utf-8")

Path("src/test/java/org/enthusia/rep/effects/RepEffectManagerTest.java").parent.mkdir(parents=True, exist_ok=True)
Path("src/test/java/org/enthusia/rep/effects/RepEffectManagerTest.java").write_text('''package org.enthusia.rep.effects;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepEffectManagerTest {
    @Test
    void onlyNewRefreshedOrAmplifierChangedEffectsAreModified() {
        PotionEffect before = new PotionEffect(PotionEffectType.SPEED, 200, 0);
        assertFalse(RepEffectManager.wasPotionEffectAppliedOrRefreshed(
                before, new PotionEffect(PotionEffectType.SPEED, 199, 0)));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(
                before, new PotionEffect(PotionEffectType.SPEED, 240, 0)));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(
                before, new PotionEffect(PotionEffectType.SPEED, 199, 1)));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(
                null, new PotionEffect(PotionEffectType.SPEED, 200, 0)));
    }
}
''', encoding="utf-8")

rules_test = Path("src/test/java/org/enthusia/rep/rep/RepRulesTest.java")
rules_text = rules_test.read_text(encoding="utf-8")
method = '''
    @Test
    void removalCooldownSurvivesUntilExactExpiry() {
        long removedAt = 10_000L;
        long duration = 5_000L;
        assertTrue(RepRules.isCooldownActive(removedAt, 14_999L, duration));
        assertFalse(RepRules.isCooldownActive(removedAt, 15_000L, duration));
        assertFalse(RepRules.isCooldownActive(removedAt, 9_999L, duration));
    }
'''
if "removalCooldownSurvivesUntilExactExpiry" not in rules_text:
    rules_text = rules_text.rsplit("}", 1)[0] + method + "}\n"
    rules_test.write_text(rules_text, encoding="utf-8")
