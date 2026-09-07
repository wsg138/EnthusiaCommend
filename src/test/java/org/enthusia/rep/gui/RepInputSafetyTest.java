package org.enthusia.rep.gui;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepInputSafetyTest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final List<Runnable> tasks = new ArrayList<>();
    private final Map<Integer, ItemStack> anvilContents = new HashMap<>();
    private final Map<Integer, ItemStack> playerContents = new HashMap<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> constructedItems;
    private RepGuiManager manager;
    private Player player;
    private AnvilInventory anvil;
    private AnvilView view;
    private Inventory confirmation;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        constructedItems = mockConstruction(ItemStack.class);
        CommendPlugin plugin = mock(CommendPlugin.class);
        when(plugin.getName()).thenReturn("EnthusiaCommend");
        when(plugin.namespace()).thenReturn("enthusiacommend");
        RepConfig config = mock(RepConfig.class);
        when(plugin.getRepConfig()).thenReturn(config);
        when(config.getMaxReasonLength()).thenReturn(200);
        when(config.getInputTimeoutMillis()).thenReturn(30_000L);
        manager = new RepGuiManager(plugin, null, null);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("*Tester");
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        backInventory(inventory, playerContents, 41);
        anvil = mock(AnvilInventory.class);
        backInventory(anvil, anvilContents, 3);
        view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(anvil);
        when(view.getBottomInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.openAnvil(isNull(), eq(true))).thenReturn(view);
        confirmation = mock(Inventory.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), anyString()))
                .thenReturn(confirmation);
    }

    @AfterEach
    void tearDown() {
        constructedItems.close();
        bukkit.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingAnvilRemovesGuiItemsBeforeVanillaReturnsOrDropsThem(boolean full) throws Exception {
        seedAnvil();
        if (full) fillPlayerInventory();
        Map<Integer, ItemStack> before = new HashMap<>(playerContents);
        manager.onInventoryClose(new InventoryCloseEvent(view));
        assertNull(anvilContents.get(0), "Input must be gone before vanilla handles overflow");
        assertNull(anvilContents.get(2));
        assertEquals(before, playerContents, "Real player items must be preserved");
        assertFalse(anvilInputActive());
        assertTrue(tasks.isEmpty(), "Cleanup must not depend on a later tick");
    }

    @Test
    void submittingWithFullInventoryDefersCloseAndLeavesNothingToDrop() throws Exception {
        seedAnvil();
        fillPlayerInventory();
        when(view.getRenameText()).thenReturn("Helpful player");
        doAnswer(call -> {
            assertNull(anvilContents.get(0), "Input must be removed before closeInventory");
            assertNull(anvilContents.get(2));
            manager.onInventoryClose(new InventoryCloseEvent(view));
            return null;
        }).when(player).closeInventory();
        InventoryClickEvent click = click(2, ClickType.LEFT);
        manager.onInventoryClick(click);
        manager.onInventoryClick(click); // repeated click before the next tick
        assertTrue(click.isCancelled());
        verify(player, never()).closeInventory();
        runTasks();
        verify(player, times(1)).closeInventory();
        verify(player, times(1)).openInventory(confirmation);
        assertFalse(anvilInputActive());
    }

    @Test
    void cancellingBeforeScheduledSubmitDoesNotReopenConfirmation() throws Exception {
        seedAnvil();
        when(view.getRenameText()).thenReturn("Helpful player");
        manager.onInventoryClick(click(2, ClickType.LEFT));
        manager.onInventoryClose(new InventoryCloseEvent(view));
        runTasks();
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    void quitAndShutdownClearAnvilBeforeDiscardingSession() throws Exception {
        seedAnvil();
        manager.onQuit(new PlayerQuitEvent(player, Component.empty()));
        assertNull(anvilContents.get(0));
        assertNull(anvilContents.get(2));
        seedAnvil();
        manager.shutdown();
        assertNull(anvilContents.get(0));
        assertNull(anvilContents.get(2));
        assertFalse(anvilInputActive());
    }

    @Test
    void cleanupPreservesUntaggedItemsAndDoesNotClaimAnotherAnvil() throws Exception {
        seedAnvil();
        ItemStack realItem = item(false);
        anvilContents.put(1, realItem);
        InventoryView otherView = mock(InventoryView.class);
        when(otherView.getPlayer()).thenReturn(player);
        Inventory otherAnvil = mock(Inventory.class);
        when(otherView.getTopInventory()).thenReturn(otherAnvil);
        manager.onInventoryClose(new InventoryCloseEvent(otherView));
        assertTrue(anvilInputActive());
        manager.onInventoryClose(new InventoryCloseEvent(view));
        assertSame(realItem, anvilContents.get(1));
        verify(otherAnvil, never()).clear(anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIFT_LEFT", "NUMBER_KEY", "SWAP_OFFHAND", "DROP", "CONTROL_DROP", "DOUBLE_CLICK", "MIDDLE"})
    void anvilRejectsItemTransferClicks(String type) throws Exception {
        seedAnvil();
        InventoryClickEvent click = click(2, ClickType.valueOf(type));
        manager.onInventoryClick(click);
        assertTrue(click.isCancelled());
        verify(player, never()).closeInventory();
        verify(player, never()).openInventory(confirmation);
    }

    @Test
    void legacyThenModernChatIsPrivateAndCreatesOnlyOneDraft() throws Exception {
        seedChat();
        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, player, "Private reason", new HashSet<>(List.of(player)));
        manager.onChat(legacy);
        assertTrue(legacy.isCancelled());
        assertTrue(legacy.getRecipients().isEmpty());
        assertTrue(chatInputActive(), "Legacy suppression must not consume the modern input");
        legacy.setCancelled(false);
        legacy.getRecipients().add(player);
        manager.protectLegacyChat(legacy);
        assertTrue(legacy.isCancelled());
        assertTrue(legacy.getRecipients().isEmpty());
        AsyncChatEvent modern = chat("Private reason");
        modern.setCancelled(true); // Paper propagates the legacy cancellation
        manager.onPaperChat(modern);
        assertTrue(modern.isCancelled());
        assertTrue(modern.viewers().isEmpty());
        modern.setCancelled(false);
        modern.viewers().add(player);
        manager.protectPaperChat(modern);
        assertTrue(modern.isCancelled());
        assertTrue(modern.viewers().isEmpty());
        assertEquals(1, tasks.size());
        runTasks();
        verify(player, times(1)).openInventory(confirmation);
        AsyncChatEvent normal = chat("Ordinary chat afterwards");
        manager.onPaperChat(normal);
        manager.protectPaperChat(normal);
        assertFalse(normal.isCancelled());
        assertEquals(1, normal.viewers().size());
    }

    @Test
    void blankInputRemainsPrivateAndCanBeRetried() throws Exception {
        seedChat();
        AsyncChatEvent blank = chat("   ");
        manager.onPaperChat(blank);
        assertTrue(blank.isCancelled());
        runTasks();
        assertTrue(chatInputActive());
        verify(player, never()).openInventory(confirmation);
        manager.onPaperChat(chat("A useful reason"));
        runTasks();
        verify(player, times(1)).openInventory(confirmation);
    }

    @Test
    void ordinaryLegacyChatIsUntouched() {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Hello", new HashSet<>(List.of(player)));
        manager.onChat(event);
        manager.protectLegacyChat(event);
        assertFalse(event.isCancelled());
        assertEquals(1, event.getRecipients().size());
    }

    @Test
    void chatCaptureRunsBeforeChatPluginsAndProtectionRunsAtHighest() throws Exception {
        assertPriority("onChat", AsyncPlayerChatEvent.class, EventPriority.LOWEST);
        assertPriority("onPaperChat", AsyncChatEvent.class, EventPriority.LOWEST);
        assertPriority("protectLegacyChat", AsyncPlayerChatEvent.class, EventPriority.HIGHEST);
        assertPriority("protectPaperChat", AsyncChatEvent.class, EventPriority.HIGHEST);
    }

    private void assertPriority(String name, Class<?> event, EventPriority priority) throws Exception {
        EventHandler handler = RepGuiManager.class.getMethod(name, event).getAnnotation(EventHandler.class);
        assertEquals(priority, handler.priority());
        assertFalse(handler.ignoreCancelled());
    }

    private AsyncChatEvent chat(String message) {
        return new AsyncChatEvent(true, player, new HashSet<Audience>(List.of(player)),
                ChatRenderer.defaultRenderer(), Component.text(message), Component.text(message),
                mock(net.kyori.adventure.chat.SignedMessage.class));
    }

    private void seedChat() {
        manager.beginChatInput(player, targetId, RepCategory.HELPED_ME, 0);
    }

    private void seedAnvil() {
        manager.openAnvilInput(player, targetId, RepCategory.HELPED_ME, 0);
        anvilContents.put(0, item(true));
        anvilContents.put(2, item(true));
    }

    private InventoryClickEvent click(int slot, ClickType type) {
        return new InventoryClickEvent(view, InventoryType.SlotType.RESULT, slot, type, InventoryAction.PICKUP_ALL);
    }

    private ItemStack item(boolean tagged) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.LIME_DYE);
        when(item.hasItemMeta()).thenReturn(true);
        ItemMeta meta = mock(ItemMeta.class);
        when(item.getItemMeta()).thenReturn(meta);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(tagged);
        return item;
    }

    private void backInventory(Inventory inventory, Map<Integer, ItemStack> items, int size) {
        when(inventory.getSize()).thenReturn(size);
        when(inventory.getItem(anyInt())).thenAnswer(call -> items.get(call.getArgument(0, Integer.class)));
        doAnswer(call -> items.remove(call.getArgument(0, Integer.class)))
                .when(inventory).clear(anyInt());
    }

    private void runTasks() {
        List<Runnable> queued = new ArrayList<>(tasks);
        tasks.clear();
        queued.forEach(Runnable::run);
    }

    private boolean chatInputActive() {
        PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(player, "/rep", new HashSet<>());
        manager.onCommandPreprocess(command);
        return command.isCancelled();
    }

    private boolean anvilInputActive() {
        InventoryClickEvent event = click(0, ClickType.LEFT);
        manager.onInventoryClick(event);
        return event.isCancelled();
    }

    private void fillPlayerInventory() {
        ItemStack realItem = item(false);
        for (int slot = 0; slot < 41; slot++) {
            playerContents.put(slot, realItem);
        }
    }
}
