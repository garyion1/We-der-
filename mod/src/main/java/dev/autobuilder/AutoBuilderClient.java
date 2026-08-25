package dev.autobuilder;

import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.gui.BuildOptionsScreen;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class AutoBuilderClient implements ClientModInitializer {

    public static final BuilderConfig CONFIG = new BuilderConfig();
    public static final LitematicFileSchematicSource SCHEMATIC = new LitematicFileSchematicSource();
    public static final BuildExecutor EXECUTOR = new BuildExecutor(CONFIG, SCHEMATIC);

    private static KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        // KeyBinding takes a KeyBinding.Category (a record) rather than a String
        // in current versions. Using the built-in MISC category rather than
        // registering a custom one -- registering the same category id twice
        // throws, and MISC needs no registration.
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BuildOptionsScreen(CONFIG, SCHEMATIC, EXECUTOR));
                }
            }
            EXECUTOR.tick(client);
        });
    }
}
