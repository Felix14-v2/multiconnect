package net.earthcomputer.multiconnect.packets.v1_18_2;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.GlobalData;
import net.earthcomputer.multiconnect.ap.Handler;
import net.earthcomputer.multiconnect.ap.Introduce;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.ap.ReturnType;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.CommonTypes;
import net.earthcomputer.multiconnect.packets.SPacketChatMessage;
import net.earthcomputer.multiconnect.packets.SPacketGameMessage;
import net.earthcomputer.multiconnect.packets.latest.SPacketGameMessage_Latest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.message.MessageType;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.Util;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@MessageVariant(minVersion = Protocols.V1_16, maxVersion = Protocols.V1_18_2)
public class SPacketGameMessage_1_18_2 implements SPacketGameMessage {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MyText.Arg.class, new MyText.Arg.Serializer())
            .registerTypeAdapter(MyText.Contents.class, new MyText.Contents.Serializer())
            .create();

    public CommonTypes.Text text;
    public byte position;
    @Introduce(compute = "computeSender")
    public UUID sender;

    public static UUID computeSender(@Argument("text") CommonTypes.Text text) {
        MyText myText;
        try {
            myText = JsonHelper.deserialize(GSON, text.json, MyText.class, false);
        } catch (JsonParseException e) {
            return Util.NIL_UUID;
        }
        if (myText == null) {
            return Util.NIL_UUID;
        }
        Integer senderIndex = getSenderIndex(myText);
        if (senderIndex == null || senderIndex >= myText.with.length) {
            return Util.NIL_UUID;
        }
        MyText.Arg senderArg = myText.with[senderIndex];
        if (senderArg.hoverEvent == null) {
            return Util.NIL_UUID;
        }
        if (!"show_entity".equals(senderArg.hoverEvent.action)) {
            return Util.NIL_UUID;
        }
        if (senderArg.hoverEvent.contents != null) {
            try {
                return UUID.fromString(senderArg.hoverEvent.contents.id);
            } catch (IllegalArgumentException ignore) {
            }
        }
        if (senderArg.hoverEvent.value != null) {
            NbtCompound nbt;
            try {
                nbt = StringNbtReader.parse(senderArg.hoverEvent.value);
            } catch (CommandSyntaxException e) {
                nbt = null;
            }
            if (nbt != null && nbt.contains("id", NbtElement.STRING_TYPE)) {
                try {
                    return UUID.fromString(nbt.getString("id"));
                } catch (IllegalArgumentException ignore) {
                }
            }
        }

        return Util.NIL_UUID;
    }

    @Nullable
    private static Integer getSenderIndex(MyText myText) {
        return switch (myText.translate) {
            case "chat.type.text", "chat.type.announcement", "chat.message.display.incoming", "chat.type.emote" -> 0;
            case "chat.type.team.text" -> 1;
            default -> null;
        };
    }

    @ReturnType(SPacketGameMessage_Latest.class)
    @ReturnType(SPacketChatMessage.class)
    @Handler
    public static List<Object> handle(
            @Argument("text") CommonTypes.Text text,
            @Argument("position") byte position,
            @Argument("sender") UUID sender,
            @GlobalData DynamicRegistryManager registryManager
    ) {
        if (registryManager == null) {
            // Some servers apparently send chat messages before the game join packet. We can't handle these anymore
            return new ArrayList<>(0);
        }

        Registry<MessageType> messageTypeRegistry = registryManager.get(Registry.MESSAGE_TYPE_KEY);
        int systemId = messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.SYSTEM));
        int gameInfoId = messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.GAME_INFO));
        int chatId = messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.CHAT));
        int messageType = switch (position) {
            case 1 -> systemId;
            case 2 -> gameInfoId;
            default -> chatId;
        };

        List<Object> packets = new ArrayList<>(1);
        var basicPacket = new SPacketGameMessage_Latest();
        basicPacket.messageType = messageType;
        basicPacket.text = text;
        packets.add(basicPacket);

        MyText myText;
        try {
            myText = JsonHelper.deserialize(GSON, text.json, MyText.class, false);
        } catch (JsonParseException e) {
            return packets;
        }
        if (myText == null) {
            return packets;
        }

        int teamId = messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.TEAM_MSG_COMMAND));
        messageType = switch (myText.translate) {
            case "chat.type.announcement" -> messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.SAY_COMMAND));
            case "chat.message.display.incoming" -> messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.MSG_COMMAND));
            case "chat.type.emote" -> messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.EMOTE_COMMAND));
            case "chat.type.team.text" -> teamId;
            default -> messageType;
        };

        if (messageType == systemId || messageType == gameInfoId) {
            return packets;
        }

        if (messageType == chatId && !"chat.type.text".equals(myText.translate)) {
            messageType = messageTypeRegistry.getRawId(messageTypeRegistry.get(MessageType.TELLRAW_COMMAND));
        }

        Integer contentIndex = switch (myText.translate) {
            case "chat.type.text", "chat.type.announcement", "chat.message.display.incoming", "chat.type.emote" -> 1;
            case "chat.type.team.text" -> 2;
            default -> null;
        };

        var packet = new SPacketChatMessage();
        packet.signedContent = contentIndex == null || contentIndex >= myText.with.length
                ? text
                : new CommonTypes.Text(GSON.toJson(myText.with[contentIndex]));
        packet.unsignedContent = Optional.empty();
        packet.messageType = messageType;
        packet.sender = sender;
        Integer senderIndex = getSenderIndex(myText);
        packet.displayName = senderIndex == null || senderIndex >= myText.with.length
                ? new CommonTypes.Text("\"\"")
                : new CommonTypes.Text(GSON.toJson(myText.with[senderIndex]));
        packet.teamDisplayName = messageType != teamId || myText.with.length == 0
                ? Optional.empty()
                : Optional.of(new CommonTypes.Text(GSON.toJson(myText.with[0])));
        packet.timestamp = Instant.now().toEpochMilli();
        packet.salt = 0;
        packet.signature = new byte[0];
        packets.clear();
        packets.add(packet);
        return packets;
    }

    private static class MyText {
        String translate = "";
        Arg[] with = new Arg[0];

        private static class Arg {
            HoverEvent hoverEvent;
            JsonElement remainder;

            private static class Serializer implements JsonSerializer<Arg>, JsonDeserializer<Arg> {
                @Override
                public Arg deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    Arg result = new Arg();
                    if (json.isJsonObject()) {
                        JsonObject jsonObj = json.getAsJsonObject();
                        if (jsonObj.has("hoverEvent")) {
                            result.hoverEvent = context.deserialize(jsonObj.get("hoverEvent"), HoverEvent.class);
                        }
                    }
                    result.remainder = json;
                    return result;
                }

                @Override
                public JsonElement serialize(Arg src, Type typeOfSrc, JsonSerializationContext context) {
                    if (src.remainder.isJsonObject()) {
                        JsonObject obj = src.remainder.getAsJsonObject();
                        if (src.hoverEvent != null) {
                            obj.add("hoverEvent", context.serialize(src.hoverEvent));
                        }
                    }
                    return src.remainder;
                }
            }
        }

        private static class HoverEvent {
            String action = "";
            String value;
            Contents contents;
        }

        private static class Contents {
            String id = "";
            JsonElement remainder;

            private static class Serializer implements JsonSerializer<Contents>, JsonDeserializer<Contents> {
                @Override
                public Contents deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    Contents result = new Contents();
                    if (json.isJsonObject()) {
                        JsonObject jsonObj = json.getAsJsonObject();
                        if (jsonObj.has("id")) {
                            result.id = context.deserialize(jsonObj.get("id"), String.class);
                        }
                    }
                    result.remainder = json;
                    return result;
                }

                @Override
                public JsonElement serialize(Contents src, Type typeOfSrc, JsonSerializationContext context) {
                    if (src.remainder.isJsonObject()) {
                        JsonObject obj = src.remainder.getAsJsonObject();
                        if (src.id != null) {
                            obj.add("id", context.serialize(src.id));
                        }
                    }
                    return src.remainder;
                }
            }
        }
    }
}
