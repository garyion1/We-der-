package dev.autobuilder.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses .litematic files directly instead of depending on Litematica's
 * internal (non-public, version-unstable) API -- the same choice Baritone's
 * own litematica integration made. The NBT schema and bit-packing algorithm
 * below match the format documented/reimplemented independently by both
 * Baritone (src/main/java/baritone/utils/schematic/litematica/) and the
 * litemapy Python library (github.com/SmylerMC/litemapy), cross-checked
 * against each other while building this class. Not yet handled: Entities
 * and TileEntities in a region (so chests/signs/etc placed via schematic
 * won't get their contents/text) -- block shapes only.
 */
public class RawLitematicReader {

    public Map<BlockPos, BlockState> readAsWorldPositions(Path file, BlockPos worldOrigin) throws IOException {
        NbtCompound root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }

        NbtCompound regions = root.getCompound("Regions")
                .orElseThrow(() -> new IOException("Not a .litematic file: no 'Regions' tag"));
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (String regionName : regions.getKeys()) {
            Optional<NbtCompound> region = regions.getCompound(regionName);
            if (region.isEmpty()) continue;
            for (Map.Entry<BlockPos, BlockState> e : parseRegion(region.get()).entrySet()) {
                world.put(worldOrigin.add(e.getKey()), e.getValue());
            }
        }
        return world;
    }

    /**
     * Returns region-local (not yet offset by the region's own Position) block positions -> states.
     *
     * NBT getters return Optional in current Minecraft versions, so this uses
     * plain Optional methods (orElse/orElseThrow) rather than the version-specific
     * getXOr convenience overloads.
     */
    private Map<BlockPos, BlockState> parseRegion(NbtCompound region) {
        NbtCompound posTag = region.getCompound("Position").orElse(new NbtCompound());
        NbtCompound sizeTag = region.getCompound("Size").orElse(new NbtCompound());
        BlockPos regionOffset = new BlockPos(
                posTag.getInt("x").orElse(0),
                posTag.getInt("y").orElse(0),
                posTag.getInt("z").orElse(0));
        int sizeX = sizeTag.getInt("x").orElse(0);
        int sizeY = sizeTag.getInt("y").orElse(0);
        int sizeZ = sizeTag.getInt("z").orElse(0);
        int absX = Math.abs(sizeX), absY = Math.abs(sizeY), absZ = Math.abs(sizeZ);

        // Iterate elements rather than using an index-based getCompound(int),
        // whose return type also varies by version.
        List<BlockState> palette = new ArrayList<>();
        for (NbtElement element : region.getList("BlockStatePalette").orElse(new NbtList())) {
            if (element instanceof NbtCompound entry) {
                palette.add(paletteEntryToState(entry));
            }
        }

        long[] longArray = region.getLongArray("BlockStates").orElse(new long[0]);
        int volume = absX * absY * absZ;
        int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        LitematicaBitArray bits = new LitematicaBitArray(bitsPerEntry, longArray);

        Map<BlockPos, BlockState> blocks = new HashMap<>(volume);
        // Iteration order is y-major, then z, then x -- matches how the index
        // was written, per the litemapy/Baritone-reimplemented format.
        for (int y = 0; y < absY; y++) {
            for (int z = 0; z < absZ; z++) {
                for (int x = 0; x < absX; x++) {
                    int index = (y * absZ + z) * absX + x;
                    int paletteIndex = bits.getAt(index);
                    if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
                    BlockState state = palette.get(paletteIndex);
                    if (state.isAir()) continue;

                    // Negative Size components mean the region extends in the
                    // opposite direction from Position -- mirror the local coord.
                    int rx = sizeX < 0 ? -x : x;
                    int ry = sizeY < 0 ? -y : y;
                    int rz = sizeZ < 0 ? -z : z;
                    blocks.put(regionOffset.add(rx, ry, rz), state);
                }
            }
        }
        return blocks;
    }

    private BlockState paletteEntryToState(NbtCompound entry) {
        Identifier id = Identifier.tryParse(entry.getString("Name").orElse(""));
        Block block = id != null ? Registries.BLOCK.get(id) : Blocks.AIR;
        BlockState state = block.getDefaultState();

        Optional<NbtCompound> maybeProps = entry.getCompound("Properties");
        if (maybeProps.isPresent()) {
            NbtCompound props = maybeProps.get();
            StateManager<Block, BlockState> stateManager = block.getStateManager();
            for (String key : props.getKeys()) {
                Property<?> property = stateManager.getProperty(key);
                if (property != null) {
                    state = applyProperty(state, property, props.getString(key).orElse(""));
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.map(v -> state.with(property, v)).orElse(state);
    }

    /**
     * Bit-packed index array as used by .litematic's BlockStates long array:
     * fixed-width entries, LSB-first, allowed to span two adjacent longs
     * (unlike vanilla's post-1.16 chunk section packing, which does not span).
     */
    private static final class LitematicaBitArray {
        private final long[] longArray;
        private final long maxEntryValue;
        private final int bitsPerEntry;

        LitematicaBitArray(int bitsPerEntry, long[] longArray) {
            this.bitsPerEntry = bitsPerEntry;
            this.maxEntryValue = (1L << bitsPerEntry) - 1L;
            this.longArray = longArray;
        }

        int getAt(int index) {
            long startOffset = (long) index * bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((long) (index + 1) * bitsPerEntry - 1) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);

            if (startArrIndex < 0 || startArrIndex >= longArray.length) return 0;
            if (startArrIndex == endArrIndex) {
                return (int) ((longArray[startArrIndex] >>> startBitOffset) & maxEntryValue);
            }
            int endOffset = 64 - startBitOffset;
            long lo = longArray[startArrIndex] >>> startBitOffset;
            long hi = endArrIndex < longArray.length ? longArray[endArrIndex] << endOffset : 0L;
            return (int) ((lo | hi) & maxEntryValue);
        }
    }
}
