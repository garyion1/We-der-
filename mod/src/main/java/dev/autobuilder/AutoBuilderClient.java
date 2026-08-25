package dev.autobuilder;

import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.config.ConfigStore;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.gui.BuildOptionsScreen;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import dev.autobuilder.schematic.LitematicaSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class AutoBuilderClient implements ClientModInitializer {

    public static final BuilderConfig CONFIG = new BuilderConfig();
    public static final LitematicFileSchematicSource SCHEMATIC = new LitematicFileSchematicSource();
    public static final BuildExecutor EXECUTOR = new BuildExecutor(CONFIG, SCHEMATIC);
    public static final LitematicaSync LITEMATICA_SYNC = new LitematicaSync(SCHEMATIC, EXECUTOR::isActive);

    private static ConfigStore configStore;
    private static KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        configStore = new ConfigStore(FabricLoader.getInstance().getConfigDir());
        configStore.load(CONFIG);

        // KeyBinding takes a KeyBinding.Category record rather than a String in
        // current versions. MISC needs no registration, unlike a custom category
        // (registering the same id twice throws).
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LITEMATICA_SYNC.tick();
            while (openMenuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    LITEMATICA_SYNC.checkNow(); // fresh read the moment the menu opens
                    client.setScreen(new BuildOptionsScreen(CONFIG, SCHEMATIC, EXECUTOR));
                }
            }
            EXECUTOR.tick(client);
        });
    }

    /** Called by the options screen when settings change, so they survive a restart. */
    public static void saveConfig() {
        if (configStore != null) configStore.save(CONFIG);
    }

    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }
}
