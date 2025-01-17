package net.earthcomputer.multiconnect.packets.v1_16_5;

import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.Length;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.ChunkData;

import java.util.List;

@MessageVariant(minVersion = Protocols.V1_15, maxVersion = Protocols.V1_16_5)
public class ChunkData_1_16_5 implements ChunkData {
    @Length(compute = "computeSectionsLength")
    public List<ChunkData.Section> sections;

    public static int computeSectionsLength(
            @Argument("outer.verticalStripBitmask") int verticalStripBitmask
    ) {
        return Integer.bitCount(verticalStripBitmask & 0xffff);
    }
}
