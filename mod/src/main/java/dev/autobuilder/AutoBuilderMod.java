package dev.autobuilder;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoBuilderMod implements ModInitializer {
    public static final String MOD_ID = "autobuilder";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("Auto Litematica Builder loaded (client-only logic lives in AutoBuilderClient).");
    }
}
