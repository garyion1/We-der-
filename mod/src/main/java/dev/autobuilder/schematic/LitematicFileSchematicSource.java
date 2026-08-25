package dev.autobuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/** Points at one .litematic file + a world-space origin to build it at; re-reads on demand via reload(). */
public class LitematicFileSchematicSource implements SchematicSource {
    private final RawLitematicReader reader = new RawLitematicReader();
    private Path file;
    private BlockPos origin = BlockPos.ORIGIN;
    private Map<BlockPos, BlockState> cached = Collections.emptyMap();
    private String error;

    public void load(Path file, BlockPos origin) {
        this.file = file;
        this.origin = origin;
        reload();
    }

    public void reload() {
        if (file == null) return;
        try {
            cached = reader.readAsWorldPositions(file, origin);
            error = null;
        } catch (IOException e) {
            cached = Collections.emptyMap();
            error = e.getMessage();
        }
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
        if (error != null) return "failed to read " + file.getFileName() + ": " + error;
        return file.getFileName() + " (" + cached.size() + " blocks) @ " + origin.toShortString();
    }

    public Path getFile() { return file; }
    public BlockPos getOrigin() { return origin; }
}
