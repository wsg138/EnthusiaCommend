from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    "    private final Map<UUID, Integer> scoreByPlayer = new ConcurrentHashMap<>();",
    "    private final Map<UUID, Integer> scoreByPlayer = new ConcurrentHashMap<>();\n    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();"
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    public List<Commendation> recentCommendations(int limit) {
        List<Commendation> commendations = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                commendations.addAll(entries);
            }
        }
        return commendations.stream()
                .sorted(Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }''',
    '''    public List<Commendation> recentCommendations(int limit) {
        List<Commendation> commendations = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                commendations.addAll(entries);
            }
        }
        return commendations.stream()
                .sorted(Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Immutable copies safe for asynchronous integrations such as Plan. */
    public List<Commendation> getCommendationSnapshotsAbout(UUID targetId) {
        List<Commendation> entries = commendationsByTarget.get(targetId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return entries.stream().map(this::cloneCommendation).toList();
        }
    }

    /** Immutable copies safe for asynchronous integrations such as Plan. */
    public List<Commendation> recentCommendationSnapshots(int limit) {
        List<Commendation> snapshots = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                entries.stream().map(this::cloneCommendation).forEach(snapshots::add);
            }
        }
        return snapshots.stream()
                .sorted(Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    public String nameOf(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() != null ? player.getName() : playerId.toString().substring(0, 8);
    }''',
    '''    public String nameOf(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        rememberName(playerId, player.getName());
        return cachedNameOf(playerId);
    }

    public void rememberName(UUID playerId, String playerName) {
        if (playerId != null && playerName != null && !playerName.isBlank()) {
            knownNames.put(playerId, playerName);
        }
    }

    /** Does not access Bukkit and is safe for asynchronous integrations. */
    public String cachedNameOf(UUID playerId) {
        if (playerId == null) {
            return "unknown";
        }
        return knownNames.getOrDefault(playerId, playerId.toString().substring(0, 8));
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/stalk/StalkManager.java",
    "import org.bukkit.event.Listener;",
    "import org.bukkit.event.EventHandler;\nimport org.bukkit.event.EventPriority;\nimport org.bukkit.event.Listener;"
)
# Remove duplicate import introduced because EventHandler already existed before Listener.
stalk_path = Path("src/main/java/org/enthusia/rep/stalk/StalkManager.java")
stalk = stalk_path.read_text(encoding="utf-8")
stalk = stalk.replace("import org.bukkit.event.EventHandler;\nimport org.bukkit.event.EventHandler;", "import org.bukkit.event.EventHandler;")
stalk = stalk.replace("import org.bukkit.event.player.PlayerMoveEvent;", "import org.bukkit.event.player.PlayerJoinEvent;\nimport org.bukkit.event.player.PlayerMoveEvent;")
anchor = '''    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {'''
join_handler = '''    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        repService.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {'''
if anchor not in stalk:
    raise SystemExit("StalkManager move handler anchor not found")
stalk_path.write_text(stalk.replace(anchor, join_handler, 1), encoding="utf-8")

replace_once(
    "src/main/java/org/enthusia/rep/CommendPlugin.java",
    '''        this.repService = new RepService(
                this,
                repConfig,
                snapshot,
                this::markDirty,
                this::handleScoreChanged,
                analyticsService,
                this::handleAuditRecord
        );
        this.stalkManager''',
    '''        this.repService = new RepService(
                this,
                repConfig,
                snapshot,
                this::markDirty,
                this::handleScoreChanged,
                analyticsService,
                this::handleAuditRecord
        );
        for (var player : Bukkit.getOnlinePlayers()) {
            repService.rememberName(player.getUniqueId(), player.getName());
        }
        this.stalkManager'''
)

plan_path = Path("src/main/java/org/enthusia/rep/integration/plan/PlanReputationDataExtension.java")
plan = plan_path.read_text(encoding="utf-8")
plan = plan.replace("plugin.getRepService().getCommendationsAbout(playerId).stream()",
                    "plugin.getRepService().getCommendationSnapshotsAbout(playerId).stream()")
plan = plan.replace("plugin.getAnalyticsService().nameOf(commendation.getGiver())",
                    "plugin.getRepService().cachedNameOf(commendation.getGiver())")
plan = plan.replace("plugin.getRepService().recentCommendations(10)",
                    "plugin.getRepService().recentCommendationSnapshots(10)")
plan = plan.replace("plugin.getAnalyticsService().nameOf(commendation.getTarget())",
                    "plugin.getRepService().cachedNameOf(commendation.getTarget())")
if "getAnalyticsService().nameOf" in plan:
    raise SystemExit("Unsafe Plan name lookup remains")
plan_path.write_text(plan, encoding="utf-8")
