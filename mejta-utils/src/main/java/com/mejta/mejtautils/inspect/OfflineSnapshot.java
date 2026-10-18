package com.mejta.mejtautils.inspect;

import java.util.Optional;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * A read-only copy of an offline player's saved inventory and ender chest.
 *
 * <p>The save is read through the game's own player-data storage ({@code PlayerList.loadPlayerData}),
 * which only reads {@code playerdata/<uuid>.dat} of the current world and upgrades old data. The data
 * is loaded into a temporary player object that is never added to the world, and copied. Nothing is
 * ever written back: changes to offline players are queued instead (see {@code PendingChanges}).
 */
public final class OfflineSnapshot {
	private final NameAndId profile;
	private final ServerPlayer player;
	private final String dimension;
	private final ItemStack[] inventory = new ItemStack[41];
	private final ItemStack[] enderChest = new ItemStack[27];

	private OfflineSnapshot(NameAndId profile, ServerPlayer player, String dimension) {
		this.profile = profile;
		this.player = player;
		this.dimension = dimension;
		for (int i = 0; i < 41; i++) {
			inventory[i] = player.getInventory().getItem(i).copy();
		}
		for (int i = 0; i < 27; i++) {
			enderChest[i] = player.getEnderChestInventory().getItem(i).copy();
		}
	}

	/** Empty if the player has never joined this world. Call on the server thread. */
	public static Optional<OfflineSnapshot> load(MinecraftServer server, NameAndId profile) {
		Optional<CompoundTag> data = server.getPlayerList().loadPlayerData(profile);
		if (data.isEmpty()) {
			return Optional.empty();
		}
		ServerPlayer temp = new ServerPlayer(server, server.overworld(), new GameProfile(profile.id(), profile.name()),
				ClientInformation.createDefault());
		String dimension;
		try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(LogUtils.getLogger())) {
			ValueInput input = TagValueInput.create(problems, server.registryAccess(), data.get());
			temp.load(input);
			dimension = input.getString("Dimension").orElse("minecraft:overworld");
		}
		return Optional.of(new OfflineSnapshot(profile, temp, dimension));
	}

	public NameAndId profile() {
		return profile;
	}

	/** The temporary player holding the loaded data (health, XP, effects...); never in the world. */
	public ServerPlayer player() {
		return player;
	}

	public String dimension() {
		return dimension;
	}

	/** Copy of inventory slot {@code i} (0-35 items, 36-39 feet to head, 40 offhand). */
	public ItemStack inventory(int i) {
		return inventory[i].copy();
	}

	public ItemStack enderChest(int i) {
		return enderChest[i].copy();
	}
}
