package dev.autobuilder.gui;

import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The build options menu, opened with [.
 *
 * Options are split across tabs because there are more of them than fit on one
 * screen. Switching tabs re-opens the screen with the tab index carried over,
 * rather than relying on a re-init method whose name varies between versions.
 *
 * Everything here is built from ButtonWidget and TextFieldWidget only -- the
 * fancier widgets (CyclingButtonWidget, scroll panes) have version-sensitive
 * signatures and are not worth the breakage.
 */
public class BuildOptionsScreen extends Screen {

    private static final String[] TABS = {"Build", "Movement", "Timing", "Materials", "Safety"};

    private final BuilderConfig config;
    private final LitematicFileSchematicSource schematicSource;
    private final BuildExecutor executor;
    private final int tab;

    /** Text fields whose contents are pushed into config when leaving the screen/tab. */
    private final List<Runnable> pendingApplies = new ArrayList<>();

    public BuildOptionsScreen(BuilderConfig config, LitematicFileSchematicSource source, BuildExecutor executor) {
        this(config, source, executor, 0);
    }

    public BuildOptionsScreen(BuilderConfig config, LitematicFileSchematicSource source,
                              BuildExecutor executor, int tab) {
        super(Text.literal("Auto Builder"));
        this.config = config;
        this.schematicSource = source;
        this.executor = executor;
        this.tab = tab;
    }

    @Override
    protected void init() {
        pendingApplies.clear();

        int panelW = 260;
        int left = this.width / 2 - panelW / 2;
        int top = 40;
        int rowH = 22;

        // --- tab bar -------------------------------------------------------
        int tabW = panelW / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int target = i;
            String label = (i == tab ? "▸ " : "") + TABS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                applyPending();
                MinecraftClient.getInstance().setScreen(
                        new BuildOptionsScreen(config, schematicSource, executor, target));
            }).dimensions(left + i * tabW, top, tabW - 2, 20).build());
        }

        int y = top + 28;
        switch (tab) {
            case 0 -> y = buildTab(left, y, panelW, rowH);
            case 1 -> y = movementTab(left, y, panelW, rowH);
            case 2 -> y = timingTab(left, y, panelW, rowH);
            case 3 -> y = materialsTab(left, y, panelW, rowH);
            case 4 -> y = safetyTab(left, y, panelW, rowH);
        }

        // --- controls, pinned to the bottom --------------------------------
        int controlY = this.height - 56;
        addDrawableChild(ButtonWidget.builder(Text.literal("Start"), b -> {
            applyPending();
            executor.start(MinecraftClient.getInstance());
            close();
        }).dimensions(left, controlY, 84, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Pause / Resume"), b -> executor.pause())
                .dimensions(left + 88, controlY, 84, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> executor.stop())
                .dimensions(left + 176, controlY, 84, 20).build());
    }

    // ---------------------------------------------------------------- tabs

    private int buildTab(int x, int y, int w, int rowH) {
        addDrawableChild(textField(x, y, w, "Schematic file",
                schematicSource.getFile() != null ? schematicSource.getFile().getFileName().toString() : "",
                this::applySchematicFile));
        y += rowH;

        BlockPos origin = schematicSource.getOrigin();
        addDrawableChild(textField(x, y, w, "Build origin (x y z)",
                origin.getX() + " " + origin.getY() + " " + origin.getZ(),
                this::applyOrigin));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Order", BuilderConfig.BuildStrategy.values(),
                () -> config.strategy, v -> config.strategy = v, v -> v.label));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Direction", BuilderConfig.LayerDirection.values(),
                () -> config.layerDirection, v -> config.layerDirection = v, v -> v.label));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Scaffold block", BuilderConfig.ScaffoldBlock.values(),
                () -> config.scaffoldBlock, v -> config.scaffoldBlock = v, v -> v.label));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Break wrong blocks",
                () -> config.breakWrongBlocks, v -> config.breakWrongBlocks = v));
        return y + rowH;
    }

    private int movementTab(int x, int y, int w, int rowH) {
        addDrawableChild(toggle(x, y, w, "Pearl climbing",
                () -> config.usePearlClimbing, v -> config.usePearlClimbing = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Keep pearls in reserve", new Integer[]{0, 2, 4, 8, 16},
                () -> config.pearlReserve, v -> config.pearlReserve = v, String::valueOf));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Reach", new Double[]{3.0, 3.5, 4.0, 4.5},
                () -> config.maxReach, v -> config.maxReach = v, v -> v + " blocks"));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Sprint between blocks",
                () -> config.allowSprint, v -> config.allowSprint = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Pathfinding effort", new Integer[]{2000, 4000, 8000, 16000},
                () -> config.maxPathNodes, v -> config.maxPathNodes = v, String::valueOf));
        return y + rowH;
    }

    private int timingTab(int x, int y, int w, int rowH) {
        addDrawableChild(cycler(x, y, w, "Pace", BuilderConfig.Pace.values(),
                () -> config.pace, v -> config.pace = v, Enum::name));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Take breaks",
                () -> config.takeBreaks, v -> config.takeBreaks = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Break every", new Integer[]{32, 64, 128, 256, 512},
                () -> config.breakEveryBlocks, v -> config.breakEveryBlocks = v, v -> v + " blocks"));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Break length", new Integer[]{5, 15, 30, 60, 120},
                () -> config.breakSeconds, v -> config.breakSeconds = v, v -> v + "s"));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Look around on breaks",
                () -> config.lookAroundOnBreak, v -> config.lookAroundOnBreak = v));
        return y + rowH;
    }

    private int materialsTab(int x, int y, int w, int rowH) {
        addDrawableChild(toggle(x, y, w, "Buy from auction house",
                () -> config.autoBuyMaterials, v -> config.autoBuyMaterials = v));
        y += rowH;

        addDrawableChild(textField(x, y, w, "Auction command", config.auctionCommandTemplate,
                s -> { if (!s.isBlank()) config.auctionCommandTemplate = s; }));
        y += rowH;

        addDrawableChild(textField(x, y, w, "Price pattern (regex)", config.auctionPriceRegex,
                s -> { if (!s.isBlank()) config.auctionPriceRegex = s; }));
        y += rowH;

        addDrawableChild(textField(x, y, w, "Max price each", String.valueOf(config.maxUnitPrice),
                s -> {
                    try { config.maxUnitPrice = Double.parseDouble(s.trim().replace(",", "")); }
                    catch (NumberFormatException ignored) { /* keep previous value */ }
                }));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Needs confirm click",
                () -> config.auctionRequiresConfirmClick, v -> config.auctionRequiresConfirmClick = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Buy extra", new Integer[]{0, 10, 25, 50},
                () -> config.buyExtraPercent, v -> config.buyExtraPercent = v, v -> v + "%"));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "When out of materials",
                BuilderConfig.OutOfMaterialsPolicy.values(),
                () -> config.outOfMaterials, v -> config.outOfMaterials = v, v -> v.label));
        return y + rowH;
    }

    private int safetyTab(int x, int y, int w, int rowH) {
        addDrawableChild(toggle(x, y, w, "Stop on low health",
                () -> config.stopOnLowHealth, v -> config.stopOnLowHealth = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Health floor", new Integer[]{4, 6, 10, 14},
                () -> config.lowHealthThreshold, v -> config.lowHealthThreshold = v,
                v -> (v / 2.0) + " hearts"));
        y += rowH;

        addDrawableChild(toggle(x, y, w, "Stop if player nearby",
                () -> config.stopOnPlayerNearby, v -> config.stopOnPlayerNearby = v));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Player radius", new Integer[]{16, 32, 64, 128},
                () -> config.stopOnPlayerRadius, v -> config.stopOnPlayerRadius = v, v -> v + " blocks"));
        y += rowH;

        addDrawableChild(cycler(x, y, w, "Time limit", new Integer[]{0, 15, 30, 60, 120},
                () -> config.maxBuildMinutes, v -> config.maxBuildMinutes = v,
                v -> v == 0 ? "none" : v + " min"));
        return y + rowH;
    }

    // ---------------------------------------------------------------- widgets

    /** A button reading "Label: value" that advances to the next value on click. */
    private <T> ButtonWidget cycler(int x, int y, int w, String label, T[] values,
                                    Supplier<T> getter, Consumer<T> setter, Function<T, String> render) {
        return ButtonWidget.builder(Text.literal(label + ": " + render.apply(getter.get())), btn -> {
            T current = getter.get();
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(current)) { index = i; break; }
            }
            T next = values[(index + 1) % values.length];
            setter.accept(next);
            btn.setMessage(Text.literal(label + ": " + render.apply(next)));
        }).dimensions(x, y, w, 20).build();
    }

    private ButtonWidget toggle(int x, int y, int w, String label,
                                Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return cycler(x, y, w, label, new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                getter, setter, v -> v ? "ON" : "OFF");
    }

    /**
     * Text fields don't push their value on every keystroke -- the apply runs when
     * the tab changes, the build starts, or the screen closes, so a half-typed
     * value never lands in the config.
     */
    private TextFieldWidget textField(int x, int y, int w, String placeholder, String initial,
                                      Consumer<String> apply) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, w, 20, Text.literal(placeholder));
        field.setMaxLength(256);
        field.setText(initial);
        field.setPlaceholder(Text.literal(placeholder));
        pendingApplies.add(() -> apply.accept(field.getText()));
        return field;
    }

    private void applyPending() {
        for (Runnable r : pendingApplies) r.run();
    }

    private void applySchematicFile(String name) {
        if (name.isBlank()) return;
        Path dir = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("config").resolve("litematica").resolve("schematics");
        schematicSource.load(dir.resolve(name.trim()), schematicSource.getOrigin());
    }

    private void applyOrigin(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) return;
        try {
            BlockPos origin = new BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (schematicSource.getFile() != null) {
                schematicSource.load(schematicSource.getFile(), origin);
            }
        } catch (NumberFormatException ignored) {
            // Leave the previous origin in place rather than reset it to something wrong.
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void close() {
        applyPending();
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);

        boolean loaded = schematicSource.isLoaded();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(loaded ? schematicSource.describe() : "No schematic loaded"),
                this.width / 2, this.height - 32, loaded ? 0xAAAAAA : 0xFF5555);

        String status = executor.getState() + " -- " + executor.getStatusMessage();
        if (executor.getTotalCount() > 0) {
            status += "  (" + executor.getPlacedCount() + "/" + executor.getTotalCount() + ")";
        }
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                this.width / 2, this.height - 20, 0x88CC88);
    }

    @Override
    public boolean shouldPause() { return false; }
}
