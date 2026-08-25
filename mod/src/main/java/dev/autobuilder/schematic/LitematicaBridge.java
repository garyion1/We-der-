package dev.autobuilder.schematic;

import dev.autobuilder.AutoBuilderMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the schematic you currently have placed in Litematica, so the builder
 * follows whatever you positioned there instead of asking for a filename and
 * coordinates.
 *
 * Deliberately reflective rather than a compile-time dependency:
 *
 *  - Litematica publishes no stable API for other mods, and its internals move
 *    between versions. A hard dependency that fails to resolve would break the
 *    build outright; reflection degrades to a clear message instead.
 *  - Only two things are actually needed -- the .litematic file and where it was
 *    placed. The block data is then read by this mod's own parser, which is
 *    already known-good. That keeps the guessed surface down to a handful of
 *    calls rather than reimplementing Litematica's whole container model.
 *
 * Several plausible method names are tried for each step, since that is the part
 * most likely to have been renamed.
 */
public final class LitematicaBridge {

    /** What the builder needs: which file, placed where, turned which way. */
    public record Placement(String name, Path file, BlockPos origin,
                            BlockRotation rotation, BlockMirror mirror) {}

    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";

    private LitematicaBridge() {}

    public static boolean isLitematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** The selected placement, or null if Litematica is absent or nothing is placed. */
    public static Placement getActivePlacement() {
        if (!isLitematicaLoaded()) return null;
        try {
            Class<?> dataManager = Class.forName(DATA_MANAGER);
            Object manager = invokeAny(dataManager, null,
                    "getSchematicPlacementManager");
            if (manager == null) return null;

            Object placement = invokeAny(manager.getClass(), manager,
                    "getSelectedSchematicPlacement", "getSelectedPlacement", "getCurrentPlacement");
            if (placement == null) placement = firstEnabledPlacement(manager);
            if (placement == null) return null;

            return readPlacement(placement);
        } catch (Throwable t) {
            // Any surprise here means the internals moved. Report it once rather
            // than letting it surface as a confusing failure later on.
            AutoBuilderMod.LOG.warn("Couldn't read Litematica's placement: {}", t.toString());
            return null;
        }
    }

    private static Object firstEnabledPlacement(Object manager) {
        Object all = invokeAny(manager.getClass(), manager,
                "getAllSchematicPlacements", "getAllPlacements", "getPlacements");
        if (!(all instanceof List<?> list) || list.isEmpty()) return null;
        for (Object placement : list) {
            Object enabled = invokeAny(placement.getClass(), placement, "isEnabled");
            if (!(enabled instanceof Boolean b) || b) return placement;
        }
        return null;
    }

    private static Placement readPlacement(Object placement) {
        Object file = invokeAny(placement.getClass(), placement,
                "getSchematicFile", "getFile");
        Path path = toPath(file);
        if (path == null) {
            // In-memory schematics (created in-game, never saved) have no file
            // to re-read, so they can't be built this way.
            AutoBuilderMod.LOG.warn("Litematica placement has no file on disk -- save the schematic first.");
            return null;
        }

        Object origin = invokeAny(placement.getClass(), placement, "getOrigin", "getPosition");
        if (!(origin instanceof BlockPos pos)) return null;

        Object nameObj = invokeAny(placement.getClass(), placement, "getName");
        String name = nameObj instanceof String s ? s : path.getFileName().toString();

        Object rotationObj = invokeAny(placement.getClass(), placement, "getRotation");
        Object mirrorObj = invokeAny(placement.getClass(), placement, "getMirror");
        BlockRotation rotation = rotationObj instanceof BlockRotation r ? r : BlockRotation.NONE;
        BlockMirror mirror = mirrorObj instanceof BlockMirror m ? m : BlockMirror.NONE;

        return new Placement(name, path, pos, rotation, mirror);
    }

    private static Path toPath(Object file) {
        if (file instanceof Path p) return p;
        if (file instanceof java.io.File f) return f.toPath();
        return null;
    }

    /** Calls the first of these no-arg methods that exists, or returns null. */
    private static Object invokeAny(Class<?> type, Object instance, String... names) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method.invoke(instance);
            } catch (NoSuchMethodException ignored) {
                // try the next candidate name
            } catch (Throwable t) {
                AutoBuilderMod.LOG.warn("Litematica {}() failed: {}", name, t.toString());
                return null;
            }
        }
        return null;
    }
}
