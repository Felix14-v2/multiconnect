package net.earthcomputer.multiconnect.packets.latest;

import com.mojang.serialization.Dynamic;
import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.GlobalData;
import net.earthcomputer.multiconnect.ap.Introduce;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.ap.PartialHandler;
import net.earthcomputer.multiconnect.ap.Type;
import net.earthcomputer.multiconnect.ap.Types;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.connect.ConnectionMode;
import net.earthcomputer.multiconnect.datafix.MulticonnectDFU;
import net.earthcomputer.multiconnect.impl.ConnectionInfo;
import net.earthcomputer.multiconnect.packets.CommonTypes;
import net.earthcomputer.multiconnect.packets.SPacketPlayerRespawn;
import net.earthcomputer.multiconnect.protocols.generic.DimensionTypeReference;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

import java.util.Optional;
import java.util.function.Consumer;

@MessageVariant(minVersion = Protocols.V1_19)
public class SPacketPlayerRespawn_Latest implements SPacketPlayerRespawn {
    @Introduce(compute = "computeDimension")
    public Identifier dimension;
    public Identifier dimensionId;
    @Type(Types.LONG)
    public long hashedSeed;
    @Type(Types.UNSIGNED_BYTE)
    public int gamemode;
    @Type(Types.UNSIGNED_BYTE)
    public int previousGamemode;
    public boolean isDebug;
    public boolean isFlat;
    public boolean copyMetadata;
    @Introduce(defaultConstruct = true)
    public Optional<CommonTypes.GlobalPos> lastDeathPos;

    public static Identifier computeDimension(
            @Argument("dimension") NbtCompound dimension,
            @GlobalData DynamicRegistryManager registryManager
    ) {
        NbtCompound updatedDimension = (NbtCompound) MulticonnectDFU.FIXER.update(
                MulticonnectDFU.DIMENSION,
                new Dynamic<>(NbtOps.INSTANCE, dimension),
                ConnectionMode.byValue(ConnectionInfo.protocolVersion).getDataVersion(),
                SharedConstants.getGameVersion().getSaveVersion().getId()
        ).getValue();
        updatedDimension = (NbtCompound) DimensionType.CODEC.encodeStart(
                NbtOps.INSTANCE,
                DimensionType.CODEC.decode(
                        new Dynamic<>(NbtOps.INSTANCE, updatedDimension)
                ).result().orElseThrow().getFirst()
        ).result().orElseThrow();
        Registry<DimensionType> dimensionTypeRegistry = registryManager.get(Registry.DIMENSION_TYPE_KEY);
        for (DimensionType dimType : dimensionTypeRegistry) {
            NbtCompound candidate = (NbtCompound) DimensionType.CODEC.encodeStart(NbtOps.INSTANCE, dimType).result().orElseThrow();
            if (candidate.equals(updatedDimension)) {
                return dimensionTypeRegistry.getId(dimType);
            }
        }
        return World.OVERWORLD.getValue();
    }

    @PartialHandler
    public static void saveDimension(
            @Argument("dimension") Identifier dimension,
            @GlobalData Consumer<DimensionTypeReference> dimensionSetter
    ) {
        dimensionSetter.accept(new DimensionTypeReference(dimension));
    }
}
