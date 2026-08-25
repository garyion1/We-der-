package dev.autobuilder.economy;

import dev.autobuilder.config.BuilderConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a server's /ah-style GUI: send the search command, wait for the
 * container screen, scan every slot's name + lore for a price, buy whichever
 * listing is cheapest per item (and under maxUnitPrice).
 *
 * This automates a real purchase against a live server economy. Most servers'
 * rules treat any form of automated buying/selling the same as macro or bot
 * use, whether or not it's "cheating" in the traditional sense -- it's still
 * playing without a human at the controls. BuilderConfig.autoBuyMaterials
 * defaults to false; only turn it on somewhere you've checked this is
 * actually allowed (singleplayer, a server you administer, or one whose
 * rules explicitly permit automation).
 *
 * The GUI layout (does a listing need one click or two, what "price" even
 * looks like in the lore text) is entirely server-specific -- tune
 * BuilderConfig.auctionPriceRegex / auctionRequiresConfirmClick /
 * auctionConfirmDelayTicks by watching what your server's /ah actually does.
 */
public class AuctionHouseBuyer {

    public enum Phase { IDLE, WAITING_FOR_GUI, CLICKING, CONFIRMING, DONE, FAILED }

    public record Listing(int slotId, double unitPrice, int quantity) {}

    private final BuilderConfig config;
    private Pattern pricePattern;

    private Phase phase = Phase.IDLE;
    private int ticksWaited;
    private int confirmDelay;
    private Listing bestListing;
    private String lastError;

    public AuctionHouseBuyer(BuilderConfig config) {
        this.config = config;
        this.pricePattern = Pattern.compile(config.auctionPriceRegex);
    }

    public void startShopping(MinecraftClient client, String itemSearchTerm) {
        this.pricePattern = Pattern.compile(config.auctionPriceRegex); // pick up live edits from the GUI
        String cmd = String.format(config.auctionCommandTemplate, itemSearchTerm);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        client.player.networkHandler.sendChatCommand(cmd);
        phase = Phase.WAITING_FOR_GUI;
        ticksWaited = 0;
        bestListing = null;
        lastError = null;
    }

    /** Call once per client tick while active() is true. */
    public void tick(MinecraftClient client) {
        switch (phase) {
            case WAITING_FOR_GUI -> {
                ticksWaited++;
                if (client.currentScreen instanceof GenericContainerScreen gcs) {
                    bestListing = scanForCheapest(gcs.getScreenHandler());
                    if (bestListing != null) {
                        phase = Phase.CLICKING;
                    } else {
                        phase = Phase.FAILED;
                        lastError = "no listing under max price / matching the price pattern was found";
                    }
                } else if (ticksWaited > 100) {
                    phase = Phase.FAILED;
                    lastError = "auction GUI never opened -- check auctionCommandTemplate matches your server's command";
                }
            }
            case CLICKING -> {
                if (client.currentScreen instanceof GenericContainerScreen gcs) {
                    click(client, gcs.getScreenHandler().syncId, bestListing.slotId());
                    if (config.auctionRequiresConfirmClick) {
                        confirmDelay = config.auctionConfirmDelayTicks;
                        phase = Phase.CONFIRMING;
                    } else {
                        phase = Phase.DONE;
                    }
                } else {
                    phase = Phase.FAILED;
                    lastError = "GUI closed before the purchase click landed";
                }
            }
            case CONFIRMING -> {
                if (confirmDelay-- <= 0) {
                    if (client.currentScreen instanceof GenericContainerScreen gcs) {
                        click(client, gcs.getScreenHandler().syncId, bestListing.slotId());
                    }
                    phase = Phase.DONE;
                }
            }
            default -> { /* IDLE / DONE / FAILED: nothing to do until startShopping() is called again */ }
        }
    }

    private void click(MinecraftClient client, int syncId, int slotId) {
        client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, client.player);
    }

    private Listing scanForCheapest(ScreenHandler handler) {
        Listing best = null;
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            Double unitPrice = extractUnitPrice(stack);
            if (unitPrice == null || unitPrice > config.maxUnitPrice) continue;
            if (best == null || unitPrice < best.unitPrice()) {
                best = new Listing(slot.id, unitPrice, stack.getCount());
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
        Matcher m = pricePattern.matcher(text);
        if (!m.find()) return null;
        try {
            double total = Double.parseDouble(m.group(1).replace(",", ""));
            return total / Math.max(1, stack.getCount());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean active() {
        return phase == Phase.WAITING_FOR_GUI || phase == Phase.CLICKING || phase == Phase.CONFIRMING;
    }

    public Phase getPhase() { return phase; }
    public String getLastError() { return lastError; }
    public Listing getBestListing() { return bestListing; }
}
