package net.earthcomputer.multiconnect.packets;

import it.unimi.dsi.fastutil.ints.IntList;
import net.earthcomputer.multiconnect.ap.Message;
import net.earthcomputer.multiconnect.ap.Polymorphic;
import net.earthcomputer.multiconnect.ap.Registries;
import net.earthcomputer.multiconnect.ap.Registry;
import net.minecraft.util.Identifier;

import java.util.List;

@Message
public class SPacketSynchronizeTags {
    public List<Group> groups;

    @Polymorphic
    @Message
    public static abstract class Group {
        public Identifier id;
    }

    @Polymorphic(stringValue = "block")
    @Message
    public static class BlockGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            @Registry(Registries.BLOCK)
            public IntList entries;
        }
    }

    @Polymorphic(stringValue = "item")
    @Message
    public static class ItemGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            @Registry(Registries.ITEM)
            public IntList entries;
        }
    }

    @Polymorphic(stringValue = "fluid")
    @Message
    public static class FluidGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            @Registry(Registries.FLUID)
            public IntList entries;
        }
    }

    @Polymorphic(stringValue = "entity_type")
    @Message
    public static class EntityTypeGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            @Registry(Registries.ENTITY_TYPE)
            public IntList entries;
        }
    }

    @Polymorphic(stringValue = "game_event")
    @Message
    public static class GameEventGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            @Registry(Registries.GAME_EVENT)
            public IntList entries;
        }
    }

    @Polymorphic(otherwise = true)
    @Message
    public static class OtherGroup extends Group {
        public List<Tag> tags;

        @Message
        public static class Tag {
            public Identifier name;
            public IntList entries;
        }
    }
}