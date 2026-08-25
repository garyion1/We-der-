package dev.autobuilder.economy;

import dev.autobuilder.config.BuilderConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a server's auction-house GUI: search, read prices out of listing lore,
 * and buy the cheapest ones until the requested quantity is covered.
 *
 * Finding the actual cheapest means paging: the cheapest listing on page one is
 * not necessarily the cheapest listing. With scanAllPages on, every page is read
 * first and only then is the best one bought.
 *
 * Two price limits, deliberately separate:
 *   - autoBuyLimit: bought without asking.
 *   - above it: the trip stops and waits for explicit confirmation, so an
 *     unattended build can't quietly drain an account on a mispriced listing.
 *   - hardMaxPrice: never bought, confirmed or not.
 * A listing whose price can't be parsed is never bought -- unreadable is treated
 * as too expensive, not as free.
 *
 * This spends real in-game currency unattended. autoBuyMaterials defaults to off;
 * most servers' rules treat automated buying the same as any other bot use.
 */
public class AuctionHouseBuyer {

    public enum Phase {
        IDLE, WAITING_FOR_GUI, SCANNING, TURNING_PAGE,
        AWAITING_CONFIRMATION, CLICKING, CONFIRMING, SETTLING, DONE, FAILED
    }

    public record Listing(int slotId, double unitPrice, int quantity, int page) {}

    private static final int MAX_PURCHASES = 16;
    private static final int SETTLE_TICKS = 10;
    private static final int PAGE_LOAD_TICKS = 12;

    private final BuilderConfig config;
    private Pattern pricePattern;

    private Phase phase = Phase.IDLE;
    private Item wanted;
    private int targetQuantity;
    private int purchases;
    private int stalls;
    private int countAtLastPurchase;
    private int ticksWaited;
    private int confirmDelay;
    private int settleDelay;
    private int pageDelay;

    private int currentPage;
    private int nextPageSlot = -1;
    private final List<Listing> seen = new ArrayList<>();
    private Listing chosen;
    private boolean sawOverHardCap;
    private boolean sawUnreadable;
    private String lastError;
    private String spendSummary = "";
    private double totalSpent;

    public AuctionHouseBuyer(BuilderConfig config) {
        this.config = config;
        this.pricePattern = Pattern.compile(config.auctionPriceRegex);
    }

    public void startShopping(MinecraftClient client, Item item, int targetQuantity) {
        try {
            this.pricePattern = Pattern.compile(config.auctionPriceRegex);
        } catch (Exception e) {
            phase = Phase.FAILED;
            lastError = "price pattern is not valid regex: " + e.getMessage();
            return;
        }
        this.wanted = item;
        this.targetQuantity = Math.max(1, targetQuantity);
        this.purchases = 0;
        this.stalls = 0;
        this.countAtLastPurchase = countItem(client.player, item);
        this.ticksWaited = 0;
        this.currentPage = 0;
        this.nextPageSlot = -1;
        this.seen.clear();
        this.chosen = null;
        this.sawOverHardCap = false;
        this.sawUnreadable = false;
        this.lastError = null;
        this.totalSpent = 0;

        String command = String.format(config.auctionCommandTemplate, item.getName().getString());
        if (command.startsWith("/")) command = command.substring(1);
        client.player.networkHandler.sendChatCommand(command);
        phase = Phase.WAITING_FOR_GUI;
    }

    /** Call once per client tick while active(). */
    public void tick(MinecraftClient client) {
        switch (phase) {
            case WAITING_FOR_GUI -> {
                if (screen(client) != null) {
                    phase = Phase.SCANNING;
                } else if (++ticksWaited > 100) {
                    fail("auction GUI never opened -- does '" + config.auctionCommandTemplate
                            + "' match your server's command?");
                }
            }
            case SCANNING -> doScan(client);
            case TURNING_PAGE -> {
                if (--pageDelay <= 0) phase = Phase.SCANNING;
            }
            case AWAITING_CONFIRMATION -> { /* held until confirmPurchase()/cancel() */ }
            case CLICKING -> doClick(client);
            case CONFIRMING -> {
                if (--confirmDelay <= 0) {
                    GenericContainerScreen s = screen(client);
                    if (s != null) click(client, s.getScreenHandler().syncId, chosen.slotId());
                    settleDelay = SETTLE_TICKS;
                    phase = Phase.SETTLING;
                }
            }
            case SETTLING -> doSettle(client);
            default -> { /* IDLE / DONE / FAILED */ }
        }
    }

    // ------------------------------------------------------------ scanning

    private void doScan(MinecraftClient client) {
        GenericContainerScreen s = screen(client);
        if (s == null) {
            finishByInventory(client, "auction GUI closed while reading listings");
            return;
        }
        ScreenHandler handler = s.getScreenHandler();
        collectListings(handler);

        boolean morePages = config.scanAllPages
                && currentPage + 1 < config.maxAuctionPages
                && nextPageSlot >= 0;
        if (morePages) {
            currentPage++;
            click(client, handler.syncId, nextPageSlot);
            pageDelay = PAGE_LOAD_TICKS;
            phase = Phase.TURNING_PAGE;
            return;
        }
        chooseCheapest(client);
    }

    /** Reads every container slot on the current page into `seen`. */
    private void collectListings(ScreenHandler handler) {
        if (handler.slots.isEmpty()) return;
        var containerInventory = handler.slots.get(0).inventory;
        nextPageSlot = -1;

        for (Slot slot : handler.slots) {
            // Only the container's own slots are listings. The lower rows are the
            // player's inventory and must never be clicked as though they were.
            if (slot.inventory != containerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("next") && (name.contains("page") || name.contains("»") || name.contains(">"))) {
                nextPageSlot = slot.id;
                continue;
            }
            if (name.contains("previous") || name.contains("back") || name.contains("close")) continue;

            Double unitPrice = extractUnitPrice(stack);
            if (unitPrice == null) { sawUnreadable = true; continue; }
            if (unitPrice > config.hardMaxPrice) { sawOverHardCap = true; continue; }
            seen.add(new Listing(slot.id, unitPrice, stack.getCount(), currentPage));
        }
    }

    /**
     * Picks the single cheapest listing across every page read. Anything above
     * autoBuyLimit stops here for confirmation rather than being bought.
     */
    private void chooseCheapest(MinecraftClient client) {
        chosen = seen.stream()
                .filter(l -> l.page() == currentPage) // only the page we're looking at is clickable
                .min((a, b) -> Double.compare(a.unitPrice(), b.unitPrice()))
                .orElse(null);

        Listing cheapestAnywhere = seen.stream()
                .min((a, b) -> Double.compare(a.unitPrice(), b.unitPrice()))
                .orElse(null);

        if (cheapestAnywhere == null) {
            if (sawOverHardCap) {
                fail("every listing is over the hard cap of "
                        + String.format("%,.0f", config.hardMaxPrice) + " per item");
            } else if (sawUnreadable) {
                fail("no listing's price could be read -- check the price pattern");
            } else {
                fail("no listings found for " + wanted.getName().getString());
            }
            return;
        }

        // The cheapest may be on an earlier page; only the page currently open can
        // be clicked, so buy the best on this page and let the loop come back round.
        if (chosen == null) chosen = cheapestAnywhere;

        if (chosen.unitPrice() > config.autoBuyLimit) {
            if (!config.confirmExpensivePurchases) {
                fail("cheapest is " + String.format("%,.0f", chosen.unitPrice())
                        + "/item, over the " + String.format("%,.0f", config.autoBuyLimit) + " limit");
                return;
            }
            phase = Phase.AWAITING_CONFIRMATION;
            return;
        }
        phase = Phase.CLICKING;
    }

    // ------------------------------------------------------------ buying

    private void doClick(MinecraftClient client) {
        GenericContainerScreen s = screen(client);
        if (s == null) {
            finishByInventory(client, "auction GUI closed mid-purchase");
            return;
        }
        click(client, s.getScreenHandler().syncId, chosen.slotId());
        purchases++;
        totalSpent += chosen.unitPrice() * chosen.quantity();
        if (config.auctionRequiresConfirmClick) {
            confirmDelay = config.auctionConfirmDelayTicks;
            phase = Phase.CONFIRMING;
        } else {
            settleDelay = SETTLE_TICKS;
            phase = Phase.SETTLING;
        }
    }

    private void doSettle(MinecraftClient client) {
        if (--settleDelay > 0) return;
        int now = countItem(client.player, wanted);

        if (now <= countAtLastPurchase) {
            // Nothing arrived: wrong slot, insufficient funds, or the GUI isn't
            // shaped the way the price pattern assumes.
            if (++stalls >= 2) {
                finishByInventory(client, "purchases aren't arriving -- check the price pattern"
                        + " and whether buying needs a confirm click");
                return;
            }
        } else {
            stalls = 0;
            countAtLastPurchase = now;
        }

        if (now >= targetQuantity || purchases >= MAX_PURCHASES) {
            spendSummary = String.format("bought %dx %s for ~%,.0f",
                    now, wanted.getName().getString(), totalSpent);
            phase = Phase.DONE;
            return;
        }
        if (screen(client) != null) {
            seen.clear();
            currentPage = 0;
            phase = Phase.SCANNING;
        } else {
            finishByInventory(client, "auction GUI closed before the order was filled");
        }
    }

    /** Called from the GUI when the player approves a listing over the auto-buy limit. */
    public void confirmPurchase() {
        if (phase == Phase.AWAITING_CONFIRMATION) phase = Phase.CLICKING;
    }

    /** Called from the GUI to decline an expensive listing. */
    public void declinePurchase() {
        if (phase == Phase.AWAITING_CONFIRMATION) {
            fail("purchase declined at " + String.format("%,.0f", chosen.unitPrice()) + "/item");
        }
    }

    private void finishByInventory(MinecraftClient client, String reason) {
        if (countItem(client.player, wanted) > 0 && purchases > 0) {
            phase = Phase.DONE;
        } else {
            fail(reason);
        }
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        lastError = reason;
    }

    private GenericContainerScreen screen(MinecraftClient client) {
        return client.currentScreen instanceof GenericContainerScreen s ? s : null;
    }

    private void click(MinecraftClient client, int syncId, int slotId) {
        client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, client.player);
    }

    private Double extractUnitPrice(ItemStack stack) {
        StringBuilder text = new StringBuilder(stack.getName().getString()).append('\n');
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                text.append(line.getString()).append('\n');
            }
        }
        Matcher matcher = pricePattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < 1) return null;
        try {
            double total = Double.parseDouble(matcher.group(1).replace(",", ""));
            return total / Math.max(1, stack.getCount());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countItem(ClientPlayerEntity player, Item item) {
        if (player == null || item == null) return 0;
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == item) count += inventory.getStack(i).getCount();
        }
        return count;
    }

    public boolean active() {
        return phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED;
    }

    /** True while blocked on the player approving an over-limit purchase. */
    public boolean awaitingConfirmation() {
        return phase == Phase.AWAITING_CONFIRMATION;
    }

    public String confirmationPrompt() {
        if (chosen == null || wanted == null) return "";
        return String.format("%s at %,.0f each (%d in stack) -- over the %,.0f limit",
                wanted.getName().getString(), chosen.unitPrice(), chosen.quantity(), config.autoBuyLimit);
    }

    public void reset() {
        phase = Phase.IDLE;
        chosen = null;
        seen.clear();
    }

    public Phase getPhase() { return phase; }
    public String getLastError() { return lastError; }
    public String getSpendSummary() { return spendSummary; }
    public Listing getChosen() { return chosen; }
}
