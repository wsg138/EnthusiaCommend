package org.enthusia.rep.integration.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.Tab;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationAnalyticsService;
import org.enthusia.rep.analytics.ReputationChangeRecord;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@PluginInfo(
        name = "EnthusiaCommend",
        iconName = "star-half-stroke",
        iconFamily = Family.SOLID,
        color = Color.AMBER
)
public final class PlanReputationDataExtension implements DataExtension {

    private final CommendPlugin plugin;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public PlanReputationDataExtension(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_PERIODICAL};
    }

    @NumberProvider(
            text = "Current reputation",
            description = "Current EnthusiaCommend reputation score.",
            iconName = "star-half-stroke",
            iconColor = Color.AMBER,
            priority = 100,
            showInPlayerTable = true
    )
    public long currentReputation(UUID playerId) {
        return plugin.getRepService().getScore(playerId);
    }

    @NumberProvider(
            text = "Net reputation 7d",
            description = "Net reputation change in the last 7 days.",
            iconName = "arrow-trend-up",
            iconColor = Color.GREEN,
            priority = 90
    )
    public long netReputationSevenDays(UUID playerId) {
        return plugin.getAnalyticsService().playerStats(
                playerId,
                plugin.getRepService().getScore(playerId),
                ReputationAnalyticsService.sinceDays(7)
        ).windowNet();
    }

    @NumberProvider(
            text = "Reputation gained",
            description = "Total reputation gained in retained history.",
            iconName = "plus",
            iconColor = Color.GREEN,
            priority = 80
    )
    public long reputationGained(UUID playerId) {
        return plugin.getAnalyticsService().playerStats(
                playerId,
                plugin.getRepService().getScore(playerId),
                ReputationAnalyticsService.sinceDays(7)
        ).gained();
    }

    @NumberProvider(
            text = "Reputation lost",
            description = "Total reputation lost in retained history.",
            iconName = "minus",
            iconColor = Color.RED,
            priority = 70
    )
    public long reputationLost(UUID playerId) {
        return plugin.getAnalyticsService().playerStats(
                playerId,
                plugin.getRepService().getScore(playerId),
                ReputationAnalyticsService.sinceDays(7)
        ).lost();
    }

    @NumberProvider(
            text = "Total rep changes",
            description = "Total retained reputation change records.",
            iconName = "list",
            iconColor = Color.LIGHT_BLUE,
            priority = 100
    )
    public long totalReputationChanges() {
        return plugin.getAnalyticsService().totalChanges();
    }

    @NumberProvider(
            text = "Rep added 24h",
            description = "Reputation added in the last 24 hours.",
            iconName = "plus",
            iconColor = Color.GREEN,
            priority = 90
    )
    public long reputationAddedDay() {
        return plugin.getAnalyticsService().windowTotals(ReputationAnalyticsService.sinceHours(24)).added();
    }

    @NumberProvider(
            text = "Rep removed 24h",
            description = "Reputation removed in the last 24 hours.",
            iconName = "minus",
            iconColor = Color.RED,
            priority = 80
    )
    public long reputationRemovedDay() {
        return plugin.getAnalyticsService().windowTotals(ReputationAnalyticsService.sinceHours(24)).removed();
    }

    @Tab("Reputation")
    @TableProvider(tableColor = Color.AMBER)
    public Table recentPlayerHistory(UUID playerId) {
        Table.Factory table = Table.builder()
                .columnOne("Time", new Icon(Family.SOLID, "clock", Color.GREY))
                .columnTwo("Actor", new Icon(Family.SOLID, "user", Color.LIGHT_BLUE))
                .columnThree("Change", new Icon(Family.SOLID, "plus-minus", Color.AMBER))
                .columnFour("Reason", new Icon(Family.SOLID, "message", Color.GREY));

        for (ReputationChangeRecord change : plugin.getAnalyticsService().playerHistory(playerId, 10)) {
            table.addRow(
                    dateFormatter.format(Instant.ofEpochMilli(change.timestamp())),
                    plugin.getAnalyticsService().actorName(change),
                    signed(change.amount()) + " (" + change.oldTotal() + " -> " + change.newTotal() + ")",
                    change.reason()
            );
        }
        return table.build();
    }

    @Tab("Reputation")
    @TableProvider(tableColor = Color.AMBER)
    public Table recentServerChanges() {
        Table.Factory table = Table.builder()
                .columnOne("Time", new Icon(Family.SOLID, "clock", Color.GREY))
                .columnTwo("Target", new Icon(Family.SOLID, "user", Color.LIGHT_BLUE))
                .columnThree("Actor", new Icon(Family.SOLID, "user-pen", Color.LIGHT_BLUE))
                .columnFour("Change", new Icon(Family.SOLID, "plus-minus", Color.AMBER))
                .columnFive("Reason", new Icon(Family.SOLID, "message", Color.GREY));

        for (ReputationChangeRecord change : plugin.getAnalyticsService().recentChanges(10)) {
            table.addRow(
                    dateFormatter.format(Instant.ofEpochMilli(change.timestamp())),
                    plugin.getAnalyticsService().nameOf(change.targetId()),
                    plugin.getAnalyticsService().actorName(change),
                    signed(change.amount()) + " (" + change.oldTotal() + " -> " + change.newTotal() + ")",
                    change.reason()
            );
        }
        return table.build();
    }

    private String signed(int amount) {
        return amount > 0 ? "+" + amount : String.valueOf(amount);
    }
}
