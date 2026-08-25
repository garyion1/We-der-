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
 * and buy the cheapest listings until the requested quantity is covered.
 *
 * Finding the genuinely cheapest listing takes two passes, because only the page
 * currently on screen can be clicked:
 *   1. SURVEY  -- page through the whole listing recording every price.
 *   2. RETURN  -- re-run the search, page back to whichever page held the
 *                 cheapest, re-read it (listings may have moved) and buy there.
 * Buying "the cheapest seen" while standing on the last page would either pay
 * the last page's price or, worse, click a slot index that belongs to a
 * different page and buy something else entirely.
 *
 * Two price limits, deliberately separate:
 *   - at or under autoBuyLimit: bought unattended.
 *   - above it: stops and waits for explicit approval, so an unattended build
 *     can't quietly drain an account on a mispriced listing.
 *   - hardMaxPrice: never bought, approved or not.
 * A listing whose price can't be parsed is never bought -- unreadable counts as
 * too expensive, not as free.
 *
 * This spends real in-game currency unattended. autoBuyMaterials defaults to
 * off; most servers treat automated buying the same as any other bot use.
 */
public class AuctionHouseBuyer {

    public enum Phase {
        IDLE, WAITING_FOR_GUI, SURVEYING, TURNING_PAGE, RETURNING_TO_PAGE,
        AWAITING_CONFIRMATION, CLICKING, CONFIRMING, SETTLING, DONE, FAILED
    }

    public record Listing(int slotId, double unitPrice, int quantity, int page) {}

    private static final int MAX_PURCHASES = 16;
    private static final int MAX_TRIPS = 8;
    private static final int SETTLE_TICKS = 10;
    private static final int PAGE_LOAD_TICKS = 12;
    private static final int GUI_WAIT_TICKS = 100;

    private final BuilderConfig config;
    private Pattern pricePattern;

    private Phase phase = Phase.IDLE;
    private Phase afterPageTurn = Phase.SURVEYING;
    private Item wanted;
    private int targetQuantity;
    private int purchases;
    private int trips;
    private int stalls;
    private int countAtLastPurchase;
    /** How many we already had, so a partial fill can be told from none at all. */
    private int initialCount;
    private int ticksWaited;
    private int confirmDelay;
    private int settleDelay;
    private int pageDelay;

    /** True while paging to record prices; false while paging back to buy. */
    private boolean surveying = true;
    private int currentPage;
    private int targetPage;
    private int nextPageSlot = -1;
    private final List<Listing> surveyed = new ArrayList<>();
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
            fail("price pattern is not valid regex: " + e.getMessage());
            return;
        }
        this.wanted = item;
        this.targetQuantity = Math.max(1, targetQuantity);
        this.purchases = 0;
        this.trips = 0;
        this.stalls = 0;
        this.countAtLastPurchase = countItem(client.player, item);
        this.initialCount = this.countAtLastPurchase;
        this.lastError = null;
        this.totalSpent = 0;
        this.spendSummary = "";
        beginSurvey(client);
    }

    /** Re-runs the search command and starts recording prices from page one. */
    private void beginSurvey(MinecraftClient client) {
        if (++trips > MAX_TRIPS) {
            finishByInventory(client, "gave up after " + MAX_TRIPS + " trips to the auction house");
            return;
        }
        surveying = true;
        currentPage = 0;
        targetPage = 0;
        nextPageSlot = -1;
        surveyed.clear();
        chosen = null;
        sawOverHardCap = false;
        sawUnreadable = false;
        ticksWaited = 0;
        sendSearch(client);
        phase = Phase.WAITING_FOR_GUI;
    }

    private void sendSearch(MinecraftClient client) {
        String command = String.format(config.auctionCommandTemplate, wanted.getName().getString());
        if (command.startsWith("/")) command = command.substring(1);
        client.player.networkHandler.sendChatCommand(command);
    }

    /** Call once per client tick while active(). */
    public void tick(MinecraftClient client) {
        switch (phase) {
            case WAITING_FOR_GUI -> {
                if (screen(client) != null) {
                    ticksWaited = 0;
                    phase = surveying ? Phase.SURVEYING : Phase.RETURNING_TO_PAGE;
                } else if (++ticksWaited > GUI_WAIT_TICKS) {
                    fail("auction GUI never opened -- does '" + config.auctionCommandTemplate
                            + "' match your server's command?");
                }
            }
            case SURVEYING -> doSurvey(client);
            case RETURNING_TO_PAGE -> doReturnToPage(client);
            case TURNING_PAGE -> {
                if (--pageDelay <= 0) phase = afterPageTurn;
            }
            case AWAITING_CONFIRMATION -> { /* held until confirmPurchase()/declinePurchase() */ }
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

    // ------------------------------------------------------------ survey pass

    private void doSurvey(MinecraftClient client) {
        GenericContainerScreen s = screen(client);
        if (s == null) {
            finishByInventory(client, "auction GUI closed while reading listings");
            return;
        }
        ScreenHandler handler = s.getScreenHandler();
        readPage(handler);

        if (config.scanAllPages && nextPageSlot >= 0 && currentPage + 1 < config.maxAuctionPages) {
            currentPage++;
            click(client, handler.syncId, nextPageSlot);
            pageDelay = PAGE_LOAD_TICKS;
            afterPageTurn = Phase.SURVEYING;
            phase = Phase.TURNING_PAGE;
            return;
        }
        decideTargetPage(client);
    }

    /** Works out which page holds the cheapest listing and heads back to it. */
    private void decideTargetPage(MinecraftClient client) {
        Listing cheapest = surveyed.stream()
                .min((a, b) -> Double.compare(a.unitPrice(), b.unitPrice()))
                .orElse(null);

        if (cheapest == null) {
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

        targetPage = cheapest.page();
        if (targetPage == currentPage) {
            selectOnCurrentPage(client);   // already here, nothing to page back through
            return;
        }
        // Re-run the search to get back to page one, then walk forward to the
        // page holding the cheapest listing.
        surveying = false;
        currentPage = 0;
        ticksWaited = 0;
        sendSearch(client);
        phase = Phase.WAITING_FOR_GUI;
    }

    private void doReturnToPage(MinecraftClient client) {
        GenericContainerScreen s = screen(client);
        if (s == null) {
            finishByInventory(client, "auction GUI closed while paging back to the cheapest listing");
            return;
        }
        if (currentPage >= targetPage) {
            selectOnCurrentPage(client);
            return;
        }
        readPage(s.getScreenHandler());  // refresh nextPageSlot for this page
        if (nextPageSlot < 0) {
            // Fewer pages than before -- listings shifted. Buy the best here.
            selectOnCurrentPage(client);
            return;
        }
        currentPage++;
        click(client, s.getScreenHandler().syncId, nextPageSlot);
        pageDelay = PAGE_LOAD_TICKS;
        afterPageTurn = Phase.RETURNING_TO_PAGE;
        phase = Phase.TURNING_PAGE;
    }

    /**
     * Re-reads the page now on screen and picks the cheapest listing on it. The
     * survey may be stale by now -- someone else may have bought it -- so the
     * decision is always made against what's actually here.
     */
    private void selectOnCurrentPage(MinecraftClient client) {
        GenericContainerScreen s = screen(client);
        if (s == null) {
            finishByInventory(client, "auction GUI closed before the purchase");
            return;
        }
        List<Listing> here = new ArrayList<>();
        readPageInto(s.getScreenHandler(), here, currentPage);

        chosen = here.stream()
                .min((a, b) -> Double.compare(a.unitPrice(), b.unitPrice()))
                .orElse(null);

        if (chosen == null) {
            finishByInventory(client, "nothing buyable on the cheapest page any more");
            return;
        }
        if (chosen.unitPrice() > config.autoBuyLimit) {
            if (!config.confirmExpensivePurchases) {
                finishByInventory(client, "cheapest is " + String.format("%,.0f", chosen.unitPrice())
                        + "/item, over the " + String.format("%,.0f", config.autoBuyLimit) + " limit");
                return;
            }
            phase = Phase.AWAITING_CONFIRMATION;
            return;
        }
        phase = Phase.CLICKING;
    }

    // ------------------------------------------------------------ reading

    private void readPage(ScreenHandler handler) {
        readPageInto(handler, surveyed, currentPage);
    }

    private void readPageInto(ScreenHandler handler, List<Listing> into, int page) {
        if (handler.slots.isEmpty()) return;
        var containerInventory = handler.slots.get(0).inventory;
        nextPageSlot = -1;

        for (Slot slot : handler.slots) {
            // Only container slots are listings. The lower rows are the player's
            // own inventory and must never be clicked as though they were.
            if (slot.inventory != containerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (isNextPageControl(name)) { nextPageSlot = slot.id; continue; }
            if (isOtherControl(name)) continue;

            Double unitPrice = extractUnitPrice(stack);
            if (unitPrice == null) { sawUnreadable = true; continue; }
            if (unitPrice > config.hardMaxPrice) { sawOverHardCap = true; continue; }
            into.add(new Listing(slot.id, unitPrice, stack.getCount(), page));
        }
    }

    private static boolean isNextPageControl(String name) {
        return name.contains("next") || name.contains("»") || name.contains("→");
    }

    private static boolean isOtherControl(String name) {
        return name.contains("previous") || name.contains("back") || name.contains("close")
                || name.contains("«") || name.contains("←") || name.contains("refresh")
                || name.contains("sort") || name.contains("filter") || name.contains("search");
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
            // Nothing arrived: wrong slot, not enough money, or the GUI isn't
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
            spendSummary = String.format("%dx %s for ~%,.0f",
                    now, wanted.getName().getString(), totalSpent);
            phase = Phase.DONE;
            return;
        }
        // Still short: survey again from scratch, since buying changed the listings.
        beginSurvey(client);
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

    /** Ends the trip. A partial fill still counts as success -- the build can carry on. */
    private void finishByInventory(MinecraftClient client, String reason) {
        int now = countItem(client.player, wanted);
        if (now > initialCount && purchases > 0) {
            spendSummary = String.format("%dx %s for ~%,.0f",
                    now, wanted.getName().getString(), totalSpent);
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
        return String.format("%s at %,.0f each (x%d) -- over the %,.0f limit",
                wanted.getName().getString(), chosen.unitPrice(), chosen.quantity(), config.autoBuyLimit);
    }

    public void reset() {
        phase = Phase.IDLE;
        chosen = null;
        surveyed.clear();
    }

    public Phase getPhase() { return phase; }
    public String getLastError() { return lastError; }
    public String getSpendSummary() { return spendSummary; }
    public Listing getChosen() { return chosen; }
}
