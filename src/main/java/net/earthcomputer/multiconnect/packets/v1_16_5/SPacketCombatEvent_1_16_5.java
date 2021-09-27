package net.earthcomputer.multiconnect.packets.v1_16_5;

import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.DefaultConstruct;
import net.earthcomputer.multiconnect.ap.Handler;
import net.earthcomputer.multiconnect.ap.Message;
import net.earthcomputer.multiconnect.ap.NetworkEnum;
import net.earthcomputer.multiconnect.ap.Polymorphic;
import net.earthcomputer.multiconnect.ap.Type;
import net.earthcomputer.multiconnect.ap.Types;
import net.earthcomputer.multiconnect.packets.CommonTypes;
import net.earthcomputer.multiconnect.packets.SPacketDeathMessage;
import net.earthcomputer.multiconnect.packets.SPacketEndCombat;
import net.earthcomputer.multiconnect.packets.SPacketEnterCombat;

@Message
@Polymorphic
public abstract class SPacketCombatEvent_1_16_5 {
    public Mode mode;

    @NetworkEnum
    public enum Mode {
        ENTER_COMBAT, END_COMBAT, ENTITY_DIED
    }

    @Polymorphic(stringValue = "ENTER_COMBAT")
    @Message
    public static class EnterCombat extends SPacketCombatEvent_1_16_5 {
        @Handler
        public static SPacketEnterCombat handle(@DefaultConstruct SPacketEnterCombat packet) {
            return packet;
        }
    }

    @Polymorphic(stringValue = "END_COMBAT")
    @Message
    public static class EndCombat extends SPacketCombatEvent_1_16_5 {
        public int duration;
        @Type(Types.INT)
        public int entityId;

        @Handler
        public static SPacketEndCombat handle(
                @Argument("duration") int duration,
                @Argument("entityId") int entityId,
                @DefaultConstruct SPacketEndCombat packet
        ) {
            packet.duration = duration;
            packet.entityId = entityId;
            return packet;
        }
    }

    @Polymorphic(stringValue = "ENTITY_DIED")
    @Message
    public static class EntityDied extends SPacketCombatEvent_1_16_5 {
        public int playerId;
        @Type(Types.INT)
        public int entityId;
        public CommonTypes.Text message;

        @Handler
        public static SPacketDeathMessage handle(
                @Argument("playerId") int playerId,
                @Argument("entityId") int entityId,
                @Argument("message") CommonTypes.Text message,
                @DefaultConstruct SPacketDeathMessage packet
        ) {
            packet.playerId = playerId;
            packet.entityId = entityId;
            packet.message = message;
            return packet;
        }
    }
}
