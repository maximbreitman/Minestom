package net.minestom.server.network.packet.server.play;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2LongRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.palette.PaletteStorage;
import net.minestom.server.instance.palette.Section;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.ServerPacketIdentifier;
import net.minestom.server.utils.BlockPosition;
import net.minestom.server.utils.Utils;
import net.minestom.server.utils.binary.BinaryReader;
import net.minestom.server.utils.binary.BinaryWriter;
import net.minestom.server.utils.cache.CacheablePacket;
import net.minestom.server.utils.cache.TemporaryPacketCache;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.world.biomes.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.jglrxavpok.hephaistos.nbt.NBTException;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ChunkDataPacket implements ServerPacket, CacheablePacket {

    private static final BlockManager BLOCK_MANAGER = MinecraftServer.getBlockManager();
    public static final TemporaryPacketCache CACHE = new TemporaryPacketCache(5, TimeUnit.MINUTES);

    public Biome[] biomes;
    public int chunkX, chunkZ;

    public PaletteStorage paletteStorage = new PaletteStorage(8, 2);
    public Int2ObjectMap<BlockHandler> handlerMap;
    public Int2ObjectMap<NBTCompound> nbtMap;

    private static final byte CHUNK_SECTION_COUNT = 16;
    private static final int MAX_BITS_PER_ENTRY = 16;
    private static final int MAX_BUFFER_SIZE = (Short.BYTES + Byte.BYTES + 5 * Byte.BYTES + (4096 * MAX_BITS_PER_ENTRY / Long.SIZE * Long.BYTES)) * CHUNK_SECTION_COUNT + 256 * Integer.BYTES;

    // Cacheable data
    private final UUID identifier;
    private final long timestamp;

    /**
     * Block entities NBT, as read from raw packet data.
     * Only filled by #read, and unused at the moment.
     */
    public NBTCompound[] blockEntitiesNBT = new NBTCompound[0];
    /**
     * Heightmaps NBT, as read from raw packet data.
     * Only filled by #read, and unused at the moment.
     */
    public NBTCompound heightmapsNBT;

    private ChunkDataPacket() {
        this(new UUID(0, 0), 0);
    }

    public ChunkDataPacket(@Nullable UUID identifier, long timestamp) {
        this.identifier = identifier;
        this.timestamp = timestamp;
    }

    @Override
    public void write(@NotNull BinaryWriter writer) {
        writer.writeInt(chunkX);
        writer.writeInt(chunkZ);

        ByteBuf blocks = Unpooled.buffer(MAX_BUFFER_SIZE);

        Int2LongRBTreeMap maskMap = new Int2LongRBTreeMap();

        for (var entry : paletteStorage.getSectionMap().int2ObjectEntrySet()) {
            final int index = entry.getIntKey();
            final Section section = entry.getValue();

            final int lengthIndex = index % 64;
            final int maskIndex = index / 64;

            long mask = maskMap.get(maskIndex);
            mask |= 1L << lengthIndex;
            maskMap.put(maskIndex, mask);

            Utils.writeSectionBlocks(blocks, section);
        }

        final int maskSize = maskMap.size();
        writer.writeVarInt(maskSize);
        for (int i = 0; i < maskSize; i++) {
            final long value = maskMap.containsKey(i) ? maskMap.get(i) : 0;
            writer.writeLong(value);
        }

        // TODO: don't hardcode heightmaps
        // Heightmap
        int[] motionBlocking = new int[16 * 16];
        int[] worldSurface = new int[16 * 16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                motionBlocking[x + z * 16] = 4;
                worldSurface[x + z * 16] = 5;
            }
        }

        {
            writer.writeNBT("",
                    new NBTCompound()
                            .setLongArray("MOTION_BLOCKING", Utils.encodeBlocks(motionBlocking, 9))
                            .setLongArray("WORLD_SURFACE", Utils.encodeBlocks(worldSurface, 9))
            );
        }

        // Biomes
        if (biomes == null || biomes.length == 0) {
            writer.writeVarInt(0);
        } else {
            writer.writeVarInt(biomes.length);
            for (Biome biome : biomes) {
                writer.writeVarInt(biome.getId());
            }
        }

        // Data
        writer.writeVarInt(blocks.writerIndex());
        writer.write(blocks);
        blocks.release();

        // Block entities
        if (handlerMap == null || handlerMap.isEmpty()) {
            writer.writeVarInt(0);
        } else {
            writer.writeVarInt(handlerMap.size());

            for (var entry : handlerMap.int2ObjectEntrySet()) {
                final int index = entry.getIntKey();
                final BlockHandler handler = entry.getValue();
                final BlockPosition blockPosition = ChunkUtils.getBlockPosition(index, chunkX, chunkZ);

                NBTCompound nbt;
                if (nbtMap != null) {
                    nbt = Objects.requireNonNullElseGet(nbtMap.get(index), NBTCompound::new);
                } else {
                    nbt = new NBTCompound();
                }
                nbt.setString("id", handler.getNamespaceId().asString())
                        .setInt("x", blockPosition.getX())
                        .setInt("y", blockPosition.getY())
                        .setInt("z", blockPosition.getZ());
                writer.writeNBT("", nbt);
            }
        }
    }

    @Override
    public void read(@NotNull BinaryReader reader) {
        chunkX = reader.readInt();
        chunkZ = reader.readInt();

        int maskCount = reader.readVarInt();
        long[] masks = new long[maskCount];
        for (int i = 0; i < maskCount; i++) {
            masks[i] = reader.readLong();
        }
        try {
            // TODO: Use heightmaps
            // unused at the moment
            heightmapsNBT = (NBTCompound) reader.readTag();

            // Biomes
            int[] biomesIds = reader.readVarIntArray();
            this.biomes = new Biome[biomesIds.length];
            for (int i = 0; i < biomesIds.length; i++) {
                this.biomes[i] = MinecraftServer.getBiomeManager().getById(biomesIds[i]);
            }

            // Data
            this.paletteStorage = new PaletteStorage(8, 1);
            int blockArrayLength = reader.readVarInt();
            if (maskCount > 0) {
                final long mask = masks[0]; // TODO support for variable size
                for (int section = 0; section < CHUNK_SECTION_COUNT; section++) {
                    boolean hasSection = (mask & 1 << section) != 0;
                    if (!hasSection)
                        continue;
                    short blockCount = reader.readShort();
                    byte bitsPerEntry = reader.readByte();

                    // Resize palette if necessary
                    if (bitsPerEntry > paletteStorage.getSection(section).getBitsPerEntry()) {
                        paletteStorage.getSection(section).resize(bitsPerEntry);
                    }

                    // Retrieve palette values
                    if (bitsPerEntry < 9) {
                        int paletteSize = reader.readVarInt();
                        for (int i = 0; i < paletteSize; i++) {
                            final int paletteValue = reader.readVarInt();
                            paletteStorage.getSection(section).getPaletteBlockMap().put((short) i, (short) paletteValue);
                            paletteStorage.getSection(section).getBlockPaletteMap().put((short) paletteValue, (short) i);
                        }
                    }

                    // Read blocks
                    int dataLength = reader.readVarInt();
                    long[] data = paletteStorage.getSection(section).getBlocks();
                    for (int i = 0; i < dataLength; i++) {
                        data[i] = reader.readLong();
                    }
                }
            }

            // Block entities
            final int blockEntityCount = reader.readVarInt();
            handlerMap = new Int2ObjectOpenHashMap<>();
            nbtMap = new Int2ObjectOpenHashMap<>();
            for (int i = 0; i < blockEntityCount; i++) {
                NBTCompound tag = (NBTCompound) reader.readTag();
                final String id = tag.getString("id");
                // TODO retrieve handler by namespace
                final int x = tag.getInt("x");
                final int y = tag.getInt("y");
                final int z = tag.getInt("z");
                // TODO add to handlerMap & nbtMap
            }
        } catch (IOException | NBTException e) {
            MinecraftServer.getExceptionManager().handleException(e);
            // TODO: should we throw to avoid an invalid packet?
        }
    }

    @Override
    public int getId() {
        return ServerPacketIdentifier.CHUNK_DATA;
    }

    @Override
    public @NotNull TemporaryPacketCache getCache() {
        return CACHE;
    }

    @Override
    public UUID getIdentifier() {
        return identifier;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}