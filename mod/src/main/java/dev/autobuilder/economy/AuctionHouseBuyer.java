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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a server's auction-house GUI: send the search command, wait for the
 * container to open, read a price out of each listing's name/lore, and buy the
 * cheapest ones until the requested quantity is covered.
 *
 * This spends real in-game currency without a human at the controls. Most
 * servers' rules treat automated buying the same as any other bot or macro use,
 * so BuilderConfig.autoBuyMaterials defaults to off -- check your server's rules
 * before turning it on.
 *
 * Every server's auction GUI differs: what a price looks like in the lore text,
 * whether buying takes one click or two. That's what auctionPriceRegex,
 * auctionRequiresConfirmClick and auctionConfirmDelayTicks are for -- watch what
 * your server actually does and tune them.
 */
public class AuctionHouseBuyer {

    public enum Phase { IDLE, WAITING_FOR_GUI, CLICKING, CONFIRMING, SETTLING, DONE, FAILED }

    public record Listing(int slotId, double unitPrice, int quantity) {}

    /** Hard cap on purchases per shopping trip, so a misparsed GUI can't buy in a loop. */
    private static final int MAX_PURCHASES = 16;
    /** Ticks to wait after a click before judging whether the item actually arrived. */
    private static final int SETTLE_TICKS = 10;

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
    private Listing bestListing;
    private String lastError;

    public AuctionHouseBuyer(BuilderConfig config) {
        this.config = config;
        this.pricePattern = Pattern.compile(config.auctionPriceRegex);
    }

    public void startShopping(MinecraftClient client, Item item, int targetQuantity) {
        try {
            this.pricePattern = Pattern.compile(config.auctionPriceRegex); // pick up GUI edits
        } catch (Exception e) {
            phase = Phase.FAILED;
            lastError = "price pattern is not a valid regex: " + e.getMessage();
            return;
        }
        this.wanted = item;
        this.targetQuantity = Math.max(1, targetQuantity);
        this.purchases = 0;
        this.stalls = 0;
        this.countAtLastPurchase = countItem(client.player, item);
        this.bestListing = null;
        this.lastError = null;
        this.ticksWaited = 0;

        String command = String.format(config.auctionCommandTemplate, item.getName().getString());
        if (command.startsWith("/")) command = command.substring(1);
        client.player.networkHandler.sendChatCommand(command);
        phase = Phase.WAITING_FOR_GUI;
    }

    /** Call once per client tick while active(). */
    public void tick(MinecraftClient client) {
        switch (phase) {
            case WAITING_FOR_GUI -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    selectNextListing(client, screen.getScreenHandler());
                } else if (++ticksWaited > 100) {
                    phase = Phase.FAILED;
                    lastError = "auction GUI never opened -- does '" + config.auctionCommandTemplate
                            + "' match your server's command?";
                }
            }
            case CLICKING -> {
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    click(client, screen.getScreenHandler().syncId, bestListing.slotId());
                    purchases++;
                    if (config.auctionRequiresConfirmClick) {
                        confirmDelay = config.auctionConfirmDelayTicks;
                        phase = Phase.CONFIRMING;
                    } else {
                        settleDelay = SETTLE_TICKS;
                        phase = Phase.SETTLING;
                    }
                } else {
                    finishByInventory(client, "auction GUI closed mid-purchase");
                }
            }
            case CONFIRMING -> {
                if (--confirmDelay <= 0) {
                    if (client.currentScreen instanceof GenericContainerScreen screen) {
                        click(client, screen.getScreenHandler().syncId, bestListing.slotId());
                    }
                    settleDelay = SETTLE_TICKS;
                    phase = Phase.SETTLING;
                }
            }
            case SETTLING -> {
                if (--settleDelay > 0) return;
                int now = countItem(client.player, wanted);
                if (now <= countAtLastPurchase) {
                    // The click didn't yield anything -- wrong slot, too expensive,
                    // or the GUI isn't shaped the way the price pattern assumes.
                    if (++stalls >= 2) {
                        finishByInventory(client, "purchases are not arriving -- check the price pattern"
                                + " and whether buying needs a confirm click");
                        return;
                    }
                } else {
                    stalls = 0;
                    countAtLastPurchase = now;
                }

                if (now >= targetQuantity || purchases >= MAX_PURCHASES) {
                    phase = Phase.DONE;
                    return;
                }
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    selectNextListing(client, screen.getScreenHandler());
                } else {
                    finishByInventory(client, "auction GUI closed before the order was filled");
                }
            }
            default -> { /* IDLE / DONE / FAILED: nothing until startShopping() runs again */ }
        }
    }

    private void selectNextListing(MinecraftClient client, ScreenHandler handler) {
        bestListing = scanForCheapest(handler);
        if (bestListing != null) {
            phase = Phase.CLICKING;
        } else if (sawTooExpensive) {
            finishByInventory(client, "every listing is over the "
                    + String.format("%,.0f", config.maxUnitPrice) + "/item cap");
        } else {
            finishByInventory(client, "no listing's price could be read -- check the price pattern");
        }
    }

    /**
     * Ends the trip. Counts as success if anything at all was bought -- a partial
     * fill still lets the build continue, just not as far.
     */
    private void finishByInventory(MinecraftClient client, String reason) {
        if (countItem(client.player, wanted) > 0 && purchases > 0) {
            phase = Phase.DONE;
        } else {
            phase = Phase.FAILED;
            lastError = reason;
        }
    }

    private void click(MinecraftClient client, int syncId, int slotId) {
        client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, client.player);
    }

    private boolean sawTooExpensive;

    private Listing scanForCheapest(ScreenHandler handler) {
        Listing best = null;
        sawTooExpensive = false;
        for (Slot slot : handler.slots) {
            // Only the container's own slots are listings; the lower rows are the
            // player's own inventory and must never be clicked as if they were.
            if (slot.inventory == handler.slots.get(0).inventory) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;
                Double unitPrice = extractUnitPrice(stack);
                // A listing whose price can't be read is never bought: an
                // unparseable price is treated as too expensive, not as free.
                if (unitPrice == null) continue;
                if (unitPrice > config.maxUnitPrice) {
                    sawTooExpensive = true;
                    continue;
                }
                if (best == null || unitPrice < best.unitPrice()) {
                    best = new Listing(slot.id, unitPrice, stack.getCount());
                }
            }
        }
        return best;
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
        return phase == Phase.WAITING_FOR_GUI || phase == Phase.CLICKING
                || phase == Phase.CONFIRMING || phase == Phase.SETTLING;
    }

    public void reset() {
        phase = Phase.IDLE;
        bestListing = null;
    }

    public Phase getPhase() { return phase; }
    public String getLastError() { return lastError; }
    public Listing getBestListing() { return bestListing; }
}
