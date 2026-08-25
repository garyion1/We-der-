package dev.autobuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Points at one .litematic file, a world-space origin, and its rotation/mirror,
 * and re-reads on demand. Normally driven by LitematicaSync from whatever's
 * currently placed in Litematica, rather than set by hand.
 */
public class LitematicFileSchematicSource implements SchematicSource {
    private final RawLitematicReader reader = new RawLitematicReader();

    private String displayName;
    private Path file;
    private BlockPos origin = BlockPos.ORIGIN;
    private BlockRotation rotation = BlockRotation.NONE;
    private BlockMirror mirror = BlockMirror.NONE;
    private Map<BlockPos, BlockState> cached = Collections.emptyMap();
    private String error;

    public void load(Path file, BlockPos origin) {
        load(file.getFileName().toString(), file, origin, BlockRotation.NONE, BlockMirror.NONE);
    }

    public void load(String displayName, Path file, BlockPos origin, BlockRotation rotation, BlockMirror mirror) {
        this.displayName = displayName;
        this.file = file;
        this.origin = origin;
        this.rotation = rotation;
        this.mirror = mirror;
        reload();
    }

    public void clear() {
        this.file = null;
        this.cached = Collections.emptyMap();
        this.error = null;
    }

    public void reload() {
        if (file == null) return;
        try {
            cached = reader.readAsWorldPositions(file, origin, rotation, mirror);
            error = null;
        } catch (IOException e) {
            cached = Collections.emptyMap();
            error = e.getMessage();
        }
    }

    /** True if this describes the same source (file, spot, orientation) as before -- used to skip needless re-parsing. */
    public boolean matches(Path file, BlockPos origin, BlockRotation rotation, BlockMirror mirror) {
        return Objects.equals(this.file, file) && Objects.equals(this.origin, origin)
                && this.rotation == rotation && this.mirror == mirror;
    }

    @Override
    public Map<BlockPos, BlockState> getTargetBlocks() {
        return cached;
    }

    @Override
    public boolean isLoaded() {
        return file != null && error == null && !cached.isEmpty();
    }

    @Override
    public String describe() {
        if (file == null) return "no schematic selected";
        if (error != null) return "failed to read " + fileName() + ": " + error;
        return (displayName != null ? displayName : fileName())
                + " (" + cached.size() + " blocks) @ " + origin.toShortString();
    }

    private String fileName() {
        return file.getFileName().toString();
    }

    public Path getFile() { return file; }
    public BlockPos getOrigin() { return origin; }
}
