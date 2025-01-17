package net.earthcomputer.multiconnect.packets.v1_15_2;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.Datafix;
import net.earthcomputer.multiconnect.ap.DatafixTypes;
import net.earthcomputer.multiconnect.ap.Introduce;
import net.earthcomputer.multiconnect.ap.Length;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.ap.OnlyIf;
import net.earthcomputer.multiconnect.ap.Type;
import net.earthcomputer.multiconnect.ap.Types;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.ChunkData;
import net.earthcomputer.multiconnect.packets.SPacketChunkData;
import net.earthcomputer.multiconnect.packets.v1_14_4.ChunkData_1_14_4;
import net.earthcomputer.multiconnect.protocols.v1_16_5.Protocol_1_16_5;
import net.minecraft.nbt.NbtCompound;

import java.util.List;

@MessageVariant(minVersion = Protocols.V1_15, maxVersion = Protocols.V1_15_2)
public class SPacketChunkData_1_15_2 implements SPacketChunkData {
    @Type(Types.INT)
    public int x;
    @Type(Types.INT)
    public int z;
    public boolean fullChunk;
    public int verticalStripBitmask;
    public NbtCompound heightmaps;
    @Length(constant = Protocol_1_16_5.BIOME_ARRAY_LENGTH)
    @Type(Types.INT)
    @OnlyIf("hasFullChunk")
    @Introduce(compute = "computeBiomes")
    public IntList biomes;
    @Length(raw = true)
    public ChunkData data;
    @Datafix(DatafixTypes.BLOCK_ENTITY)
    public List<NbtCompound> blockEntities;

    public static boolean hasFullChunk(@Argument("fullChunk") boolean fullChunk) {
        return fullChunk;
    }

    public static IntList computeBiomes(@Argument("data") ChunkData data_) {
        ChunkData_1_14_4 data = (ChunkData_1_14_4) data_;
        if (data.biomes.length == 0) {
            return new IntArrayList();
        }

        // from: z * 16 | x
        // to: y * 16 | z * 4 | x
        int[] result = new int[1024];

        for(int z = 0; z < 4; ++z) {
            for(int x = 0; x < 4; ++x) {
                int midX = (x << 2) + 2;
                int midZ = (z << 2) + 2;
                int oldIndex = midZ << 4 | midX;
                result[z << 2 | x] = data.biomes[oldIndex];
            }
        }

        for(int i = 1; i < 64; ++i) {
            System.arraycopy(result, 0, result, i * 16, 16);
        }

        return new IntArrayList(result);
    }
}
