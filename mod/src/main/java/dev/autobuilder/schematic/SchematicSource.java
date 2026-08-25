package dev.autobuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/** A loaded build target: world-space positions mapped to the block state that should end up there. */
public interface SchematicSource {
    Map<BlockPos, BlockState> getTargetBlocks();

    boolean isLoaded();

    String describe();
}
