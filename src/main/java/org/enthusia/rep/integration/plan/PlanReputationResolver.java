package org.enthusia.rep.integration.plan;

import com.djrapitops.plan.delivery.web.ResourceService;
import com.djrapitops.plan.delivery.web.resolver.MimeType;
import com.djrapitops.plan.delivery.web.resolver.Resolver;
import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.web.resource.WebResource;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationAnalyticsService;
import org.enthusia.rep.analytics.ReputationChangeRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PlanReputationResolver implements Resolver {

    public static final String BASE_PATH = "/enthusiacommend/reputation";

    private static final String PAGE_FILE = "reputation-dashboard.html";
    private static final String JS_FILE = "reputation-dashboard.js";
    private static final String CSS_FILE = "reputation-dashboard.css";

    private final CommendPlugin plugin;
    private final ResourceService resourceService;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public PlanReputationResolver(CommendPlugin plugin, ResourceService resourceService) {
        this.plugin = plugin;
        this.resourceService = resourceService;
    }

    @Override
    public boolean canAccess(Request request) {
        return request.getUser().isPresent();
    }

    @Override
    public Optional<Response> resolve(Request request) {
        if (!plugin.isEnabled() || !plugin.getRepConfig().isPlanIntegrationEnabled() || !plugin.getRepConfig().isPlanPageEnabled()) {
            return Optional.empty();
        }

        String path = request.getPath().asString();
        if (path.equals(BASE_PATH) || path.equals(BASE_PATH + "/")) {
            return Optional.of(Response.builder()
                    .setMimeType(MimeType.HTML)
                    .setContent(resourceService.getResource(plugin.getName(), PAGE_FILE, () -> WebResource.create(html())))
                    .build());
        }
        if (path.equals(BASE_PATH + "/dashboard.js")) {
            return Optional.of(Response.builder()
                    .setMimeType(MimeType.JS)
                    .setContent(resourceService.getResource(plugin.getName(), JS_FILE, () -> WebResource.create(javascript())))
                    .build());
        }
        if (path.equals(BASE_PATH + "/style.css")) {
            return Optional.of(Response.builder()
                    .setMimeType(MimeType.CSS)
                    .setContent(resourceService.getResource(plugin.getName(), CSS_FILE, () -> WebResource.create(css())))
                    .build());
        }
        if (path.equals(BASE_PATH + "/data")) {
            return Optional.of(Response.builder()
                    .setJSONContent(payload())
                    .build());
        }
        return Optional.empty();
    }

    private Map<String, Object> payload() {
        ReputationAnalyticsService analytics = plugin.getAnalyticsService();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", System.currentTimeMillis());
        payload.put("summary", summary());
        payload.put("recentChanges", analytics.recentChanges(40).stream().map(this::changeMap).toList());
        payload.put("players", analytics.recentPlayerActivity(plugin.getRepService().getScoresSnapshot(), ReputationAnalyticsService.sinceDays(30), 20)
                .stream().map(player -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("player", analytics.nameOf(player.playerId()));
                    map.put("current", player.current());
                    map.put("gained", player.gained());
                    map.put("lost", player.lost());
                    map.put("net", player.net());
                    map.put("changes", player.changeCount());
                    map.put("lastChange", formatTime(player.lastChange()));
                    return map;
                }).toList());
        payload.put("reasons", analytics.topReasons(ReputationAnalyticsService.sinceDays(30), 12).stream().map(reason -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("reason", reason.reason());
            map.put("count", reason.count());
            map.put("net", signed(reason.net()));
            return map;
        }).toList());
        payload.put("staffActions", analytics.staffActions(20).stream().map(this::changeMap).toList());
        return payload;
    }

    private Map<String, Object> summary() {
        ReputationAnalyticsService analytics = plugin.getAnalyticsService();
        ReputationAnalyticsService.WindowTotals day = analytics.windowTotals(ReputationAnalyticsService.sinceHours(24));
        ReputationAnalyticsService.WindowTotals week = analytics.windowTotals(ReputationAnalyticsService.sinceDays(7));
        ReputationAnalyticsService.WindowTotals month = analytics.windowTotals(ReputationAnalyticsService.sinceDays(30));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalChanges", analytics.totalChanges());
        summary.put("dayAdded", day.added());
        summary.put("dayRemoved", day.removed());
        summary.put("dayNet", signed(day.net()));
        summary.put("dayPlayers", day.playersChanged());
        summary.put("weekAdded", week.added());
        summary.put("weekRemoved", week.removed());
        summary.put("weekNet", signed(week.net()));
        summary.put("monthAdded", month.added());
        summary.put("monthRemoved", month.removed());
        summary.put("monthNet", signed(month.net()));
        return summary;
    }

    private Map<String, Object> changeMap(ReputationChangeRecord change) {
        ReputationAnalyticsService analytics = plugin.getAnalyticsService();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", formatTime(change.timestamp()));
        map.put("target", analytics.nameOf(change.targetId()));
        map.put("actor", analytics.actorName(change));
        map.put("amount", signed(change.amount()));
        map.put("reason", change.reason());
        map.put("oldTotal", change.oldTotal());
        map.put("newTotal", change.newTotal());
        map.put("action", readable(change.action().name()));
        map.put("source", readable(change.source().name()));
        map.put("outcome", readable(change.outcome().name()));
        return map;
    }

    private String formatTime(long timestamp) {
        return dateFormatter.format(Instant.ofEpochMilli(timestamp));
    }

    private String signed(int amount) {
        return amount > 0 ? "+" + amount : String.valueOf(amount);
    }

    private String readable(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String html() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Reputation Dashboard</title>
                    <link rel="stylesheet" href="./reputation/style.css">
                </head>
                <body>
                    <main class="rep-dashboard">
                        <header class="rep-header">
                            <div>
                                <p class="rep-kicker">EnthusiaCommend</p>
                                <h1>Reputation Dashboard</h1>
                            </div>
                            <a class="rep-link" href="./reputation/data">JSON</a>
                        </header>
                        <section id="summary" class="summary-grid"></section>
                        <section class="panel">
                            <div class="panel-title">
                                <h2>Recent Reputation Changes</h2>
                            </div>
                            <div id="recentChanges"></div>
                        </section>
                        <section class="panel">
                            <div class="panel-title">
                                <h2>Player Activity</h2>
                                <span>Last 30 days</span>
                            </div>
                            <div id="players"></div>
                        </section>
                        <section class="two-column">
                            <section class="panel">
                                <div class="panel-title">
                                    <h2>Reasons</h2>
                                    <span>Last 30 days</span>
                                </div>
                                <div id="reasons"></div>
                            </section>
                            <section class="panel">
                                <div class="panel-title">
                                    <h2>Staff Audit</h2>
                                </div>
                                <div id="staffActions"></div>
                            </section>
                        </section>
                    </main>
                    <script src="./reputation/dashboard.js"></script>
                </body>
                </html>
                """;
    }

    private String javascript() {
        return """
                const root = '/enthusiacommend/reputation/data';

                function cell(value) {
                    const td = document.createElement('td');
                    td.textContent = value ?? '';
                    return td;
                }

                function renderSummary(summary) {
                    const cards = [
                        ['Total changes', summary.totalChanges],
                        ['Rep added 24h', summary.dayAdded],
                        ['Rep removed 24h', summary.dayRemoved],
                        ['Net 24h', summary.dayNet],
                        ['Players changed 24h', summary.dayPlayers]
                    ];
                    const container = document.getElementById('summary');
                    cards.forEach(([label, value]) => {
                        const card = document.createElement('article');
                        card.className = 'summary-card';
                        const valueEl = document.createElement('strong');
                        valueEl.textContent = value;
                        const labelEl = document.createElement('span');
                        labelEl.textContent = label;
                        card.append(valueEl, labelEl);
                        container.appendChild(card);
                    });
                }

                function renderTable(id, columns, rows, emptyText) {
                    const mount = document.getElementById(id);
                    if (!rows || rows.length === 0) {
                        const empty = document.createElement('p');
                        empty.className = 'empty';
                        empty.textContent = emptyText;
                        mount.appendChild(empty);
                        return;
                    }
                    const wrap = document.createElement('div');
                    wrap.className = 'table-wrap';
                    const table = document.createElement('table');
                    const thead = document.createElement('thead');
                    const headRow = document.createElement('tr');
                    columns.forEach(column => {
                        const th = document.createElement('th');
                        th.textContent = column.label;
                        headRow.appendChild(th);
                    });
                    thead.appendChild(headRow);
                    const tbody = document.createElement('tbody');
                    rows.forEach(row => {
                        const tr = document.createElement('tr');
                        columns.forEach(column => tr.appendChild(cell(row[column.key])));
                        tbody.appendChild(tr);
                    });
                    table.append(thead, tbody);
                    wrap.appendChild(table);
                    mount.appendChild(wrap);
                }

                fetch(root)
                    .then(response => response.json())
                    .then(data => {
                        renderSummary(data.summary);
                        renderTable('recentChanges', [
                            {key: 'time', label: 'Time'},
                            {key: 'target', label: 'Target'},
                            {key: 'actor', label: 'Actor/source'},
                            {key: 'amount', label: 'Change'},
                            {key: 'reason', label: 'Reason'},
                            {key: 'oldTotal', label: 'Old'},
                            {key: 'newTotal', label: 'New'},
                            {key: 'action', label: 'Type'}
                        ], data.recentChanges, 'No retained reputation changes yet.');
                        renderTable('players', [
                            {key: 'player', label: 'Player'},
                            {key: 'current', label: 'Current'},
                            {key: 'gained', label: 'Gained'},
                            {key: 'lost', label: 'Lost'},
                            {key: 'net', label: 'Net'},
                            {key: 'changes', label: 'Changes'},
                            {key: 'lastChange', label: 'Last change'}
                        ], data.players, 'No player reputation activity in the last 30 days.');
                        renderTable('reasons', [
                            {key: 'reason', label: 'Reason'},
                            {key: 'count', label: 'Changes'},
                            {key: 'net', label: 'Net'}
                        ], data.reasons, 'No reason data in the last 30 days.');
                        renderTable('staffActions', [
                            {key: 'time', label: 'Time'},
                            {key: 'target', label: 'Target'},
                            {key: 'actor', label: 'Staff/source'},
                            {key: 'amount', label: 'Change'},
                            {key: 'reason', label: 'Reason'},
                            {key: 'action', label: 'Action'}
                        ], data.staffActions, 'No retained staff reputation actions.');
                    })
                    .catch(error => {
                        document.body.textContent = 'Could not load reputation analytics: ' + error;
                    });
                """;
    }

    private String css() {
        return """
                :root {
                    color-scheme: light dark;
                    --bg: #f7f8fa;
                    --panel: #ffffff;
                    --text: #1f2933;
                    --muted: #667085;
                    --line: #d8dee8;
                    --accent: #b7791f;
                }
                body {
                    margin: 0;
                    font-family: Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                    background: var(--bg);
                    color: var(--text);
                }
                .rep-dashboard {
                    max-width: 1280px;
                    margin: 0 auto;
                    padding: 28px;
                }
                .rep-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    margin-bottom: 22px;
                }
                .rep-kicker {
                    margin: 0 0 4px;
                    color: var(--accent);
                    font-weight: 700;
                    text-transform: uppercase;
                    font-size: 12px;
                }
                h1, h2 {
                    margin: 0;
                    letter-spacing: 0;
                }
                h1 {
                    font-size: 30px;
                    line-height: 1.2;
                }
                h2 {
                    font-size: 18px;
                }
                .rep-link {
                    color: var(--accent);
                    text-decoration: none;
                    font-weight: 700;
                }
                .summary-grid {
                    display: grid;
                    grid-template-columns: repeat(5, minmax(120px, 1fr));
                    gap: 12px;
                    margin-bottom: 18px;
                }
                .summary-card, .panel {
                    background: var(--panel);
                    border: 1px solid var(--line);
                    border-radius: 8px;
                    box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
                }
                .summary-card {
                    padding: 16px;
                }
                .summary-card strong {
                    display: block;
                    font-size: 28px;
                    line-height: 1.1;
                }
                .summary-card span, .panel-title span, .empty {
                    color: var(--muted);
                    font-size: 13px;
                }
                .panel {
                    margin-bottom: 18px;
                    overflow: hidden;
                }
                .panel-title {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 12px;
                    padding: 16px 18px;
                    border-bottom: 1px solid var(--line);
                }
                .two-column {
                    display: grid;
                    grid-template-columns: 0.85fr 1.15fr;
                    gap: 18px;
                }
                .table-wrap {
                    overflow-x: auto;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    font-size: 13px;
                }
                th, td {
                    padding: 10px 12px;
                    border-bottom: 1px solid var(--line);
                    text-align: left;
                    vertical-align: top;
                    white-space: nowrap;
                }
                th {
                    color: var(--muted);
                    font-weight: 700;
                    background: rgba(183, 121, 31, 0.08);
                }
                td:nth-child(5) {
                    white-space: normal;
                    min-width: 180px;
                }
                .empty {
                    margin: 0;
                    padding: 18px;
                }
                @media (max-width: 900px) {
                    .rep-dashboard {
                        padding: 16px;
                    }
                    .summary-grid, .two-column {
                        grid-template-columns: 1fr;
                    }
                    .rep-header {
                        align-items: flex-start;
                        flex-direction: column;
                    }
                }
                @media (prefers-color-scheme: dark) {
                    :root {
                        --bg: #101418;
                        --panel: #161b22;
                        --text: #e5e7eb;
                        --muted: #a0a8b4;
                        --line: #2b3440;
                        --accent: #d39b39;
                    }
                }
                """;
    }
}
