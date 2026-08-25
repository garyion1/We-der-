package dev.autobuilder.gui;

import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;

/** Opened by pressing ' (apostrophe) -- see AutoBuilderClient's keybinding registration. */
public class BuildOptionsScreen extends Screen {

    private final BuilderConfig config;
    private final LitematicFileSchematicSource schematicSource;
    private final BuildExecutor executor;

    private TextFieldWidget fileField;
    private TextFieldWidget originXField;
    private TextFieldWidget originYField;
    private TextFieldWidget originZField;

    public BuildOptionsScreen(BuilderConfig config, LitematicFileSchematicSource schematicSource, BuildExecutor executor) {
        super(Text.translatable("autobuilder.screen.title"));
        this.config = config;
        this.schematicSource = schematicSource;
        this.executor = executor;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 90;
        int rowH = 22;

        fileField = new TextFieldWidget(this.textRenderer, x, y, 200, 20, Text.literal("schematic file"));
        fileField.setMaxLength(256);
        fileField.setText(schematicSource.getFile() != null ? schematicSource.getFile().getFileName().toString() : "");
        fileField.setPlaceholder(Text.literal("filename.litematic (in config/litematica/schematics)"));
        addDrawableChild(fileField);
        y += rowH;

        BlockPos origin = schematicSource.getOrigin();
        originXField = smallField(x, y, String.valueOf(origin.getX()));
        originYField = smallField(x + 68, y, String.valueOf(origin.getY()));
        originZField = smallField(x + 136, y, String.valueOf(origin.getZ()));
        addDrawableChild(originXField);
        addDrawableChild(originYField);
        addDrawableChild(originZField);
        y += rowH;

        addDrawableChild(CyclingButtonWidget.<BuilderConfig.BuildStrategy>builder(s -> Text.literal(s.label))
                .values(BuilderConfig.BuildStrategy.values())
                .initially(config.strategy)
                .build(x, y, 200, 20, Text.translatable("autobuilder.screen.strategy"),
                        (btn, value) -> config.strategy = value));
        y += rowH;

        addDrawableChild(CyclingButtonWidget.<BuilderConfig.Pace>builder(p -> Text.literal(p.name()))
                .values(BuilderConfig.Pace.values())
                .initially(config.pace)
                .build(x, y, 200, 20, Text.translatable("autobuilder.screen.pace"),
                        (btn, value) -> config.pace = value));
        y += rowH;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.usePearlClimbing)
                .build(x, y, 200, 20, Text.translatable("autobuilder.screen.use_pearls"),
                        (btn, value) -> config.usePearlClimbing = value));
        y += rowH;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.autoBuyMaterials)
                .build(x, y, 200, 20, Text.translatable("autobuilder.screen.auto_buy"),
                        (btn, value) -> config.autoBuyMaterials = value));
        y += rowH;

        addDrawableChild(ButtonWidget.builder(Text.translatable("autobuilder.screen.start"), b -> {
            applyFileAndOrigin();
            executor.start(MinecraftClient.getInstance());
            close();
        }).dimensions(x, y, 95, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("autobuilder.screen.pause"), b -> executor.pause())
                .dimensions(x + 105, y, 95, 20).build());
        y += rowH;

        addDrawableChild(ButtonWidget.builder(Text.translatable("autobuilder.screen.stop"), b -> executor.stop())
                .dimensions(x, y, 200, 20).build());
    }

    private TextFieldWidget smallField(int x, int y, String initial) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, 64, 20, Text.literal(""));
        f.setText(initial);
        return f;
    }

    private void applyFileAndOrigin() {
        try {
            Path schematicsDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/litematica/schematics");
            Path file = schematicsDir.resolve(fileField.getText().trim());
            BlockPos origin = new BlockPos(
                    Integer.parseInt(originXField.getText().trim()),
                    Integer.parseInt(originYField.getText().trim()),
                    Integer.parseInt(originZField.getText().trim()));
            schematicSource.load(file, origin);
        } catch (NumberFormatException ignored) {
            // Leave whatever was previously loaded in place rather than crash the screen.
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 110, 0xFFFFFF);
        boolean loaded = schematicSource.isLoaded();
        Text status = loaded ? Text.literal(schematicSource.describe()) : Text.translatable("autobuilder.screen.no_schematic");
        context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, this.height / 2 + 76, loaded ? 0xAAAAAA : 0xFF5555);
    }

    @Override
    public boolean shouldPause() { return false; }
}
