package com.mejta.mejtamarket.menu;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import com.mejta.mejtamarket.MejtaMarket;

/**
 * Text typed on a sign, for players without mods: the server shows the player a sign that exists only
 * on their screen (a block change sent to them alone, near their head), opens the sign editor, and
 * reads back the first line when they press Done. The real block is then shown again. The world is
 * never changed, so nothing can be placed, broken or duplicated this way.
 *
 * <p>The editor's answer is caught by {@code SignUpdateMixin} before the game handles it.
 */
public final class SignInput {
	private record Pending(BlockPos pos, Consumer<String> onDone) {
	}

	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	private SignInput() {
	}

	/**
	 * Opens a sign editor whose first line is empty and whose other three lines show {@code hints}.
	 * {@code onDone} gets the first line, trimmed (possibly empty), on the server thread.
	 *
	 * @return where the sign is shown
	 */
	public static BlockPos open(ServerPlayer player, List<String> hints, Consumer<String> onDone) {
		player.closeContainer();
		// Above the player's head: within reach (the client closes editors of signs too far away).
		BlockPos pos = player.blockPosition().above(2);
		if (player.level().isOutsideBuildHeight(pos)) {
			pos = player.blockPosition().below(2);
		}
		BlockState state = Blocks.OAK_SIGN.defaultBlockState();
		SignText text = new SignText();
		for (int i = 0; i < 3 && i < hints.size(); i++) {
			text = text.setMessage(i + 1, Component.literal(hints.get(i)));
		}
		CompoundTag data = new CompoundTag();
		RegistryOps<Tag> ops = player.level().registryAccess().createSerializationContext(NbtOps.INSTANCE);
		data.put("front_text", SignText.DIRECT_CODEC.encodeStart(ops, text).getOrThrow());
		data.put("back_text", SignText.DIRECT_CODEC.encodeStart(ops, new SignText()).getOrThrow());
		data.putBoolean("is_waxed", false);
		// The sign entity only carries the position and type into the packet. It is never given text with
		// setText: that marks the real block as changed, and the server would then send the real block
		// (air) to the client next tick, which removes the sign and closes the editor at once.
		SignBlockEntity sign = new SignBlockEntity(pos, state);
		sign.setLevel(player.level());
		PENDING.put(player.getUUID(), new Pending(pos, onDone));
		player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
		player.connection.send(ClientboundBlockEntityDataPacket.create(sign, (be, registries) -> data));
		player.connection.send(new ClientboundOpenSignEditorPacket(pos, true));
		return pos;
	}

	/**
	 * Called with every sign edit a player sends. @return whether it answered an open input (the game
	 * must then ignore it)
	 */
	public static boolean capture(ServerPlayer player, BlockPos pos, String[] lines) {
		Pending p = PENDING.get(player.getUUID());
		if (p == null || !p.pos().equals(pos)) {
			return false;
		}
		PENDING.remove(player.getUUID(), p);
		String typed = lines.length > 0 && lines[0] != null ? lines[0].trim() : "";
		MinecraftServer server = player.level().getServer();
		server.execute(() -> {
			// Show the real block again (and its block entity, if it has one).
			player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
			var be = player.level().getBlockEntity(pos);
			if (be != null) {
				var update = be.getUpdatePacket();
				if (update != null) {
					player.connection.send(update);
				}
			}
			if (player.isRemoved()) {
				return;
			}
			try {
				p.onDone().accept(typed);
			} catch (RuntimeException e) {
				MejtaMarket.log().error("Sign input of {} failed", player.getGameProfile().name(), e);
			}
		});
		return true;
	}

	public static void forget(UUID player) {
		PENDING.remove(player);
	}
}
