package com.mejta.mejtaqol.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.home.HomeCommands;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.mixin.MsgCommandInvoker;
import com.mejta.mejtaqol.rtp.RandomTeleport;
import com.mejta.mejtaqol.spawn.SpawnCommands;
import com.mejta.mejtaqol.station.Stations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.tpa.TpaRequests;
import com.mejta.mejtaqol.tpa.TpaService;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.warp.WarpCommands;

/**
 * Server game test: a headless dedicated server loads MejtaCore and MejtaQoL (so both mixins must
 * apply) and drives the features with Fabric's fake players. Fake players cannot open menus and are not
 * in the player list, so menus and timed waits are covered by the unit tests and by hand (see the
 * README); everything else runs here against the real game.
 */
public class MejtaQolGameTest {
	private static final UUID A_ID = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");
	private static final UUID B_ID = UUID.fromString("00000000-0000-4000-8000-00000000b0b0");

	@GameTest(skyAccess = true, maxTicks = 40)
	public void features(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		ConfigHandle config = MejtaQoL.config();
		int warmup = config.get(QolSettings.WARMUP_SECONDS);
		config.set(QolSettings.WARMUP_SECONDS, 0);
		FakePlayer a = FakePlayer.get(level, new GameProfile(A_ID, "qol_a"));
		FakePlayer b = FakePlayer.get(level, new GameProfile(B_ID, "qol_b"));
		try {
			commandsAreRegistered(helper, server);
			homesAndBack(helper, a);
			warps(helper, a, b);
			teleportRequests(helper, a, b);
			afkTag(helper, a);
			combatLock(helper, level, a, b);
			replies(helper, a, b);
			randomTeleportScan(helper, level);
			portableCraftingReturnsItems(helper, level, a);
			spawn(helper, server, a);
			featureSwitch(helper, config, a);
		} finally {
			config.set(QolSettings.WARMUP_SECONDS, warmup);
			config.set(QolSettings.HOMES_ENABLED, true);
			for (UUID id : List.of(A_ID, B_ID)) {
				List.copyOf(MejtaQoL.homes().book(id).all().keySet()).forEach(name -> MejtaQoL.homes().book(id).delete(name));
				MejtaQoL.homes().save(id);
				MejtaQoL.homes().forget(id);
				MejtaQoL.prefs().setAcceptsTeleportRequests(id, true);
			}
		}
		helper.succeed();
	}

	private static void commandsAreRegistered(GameTestHelper helper, MinecraftServer server) {
		for (String name : List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tptoggle", "sethome", "home", "delhome", "homes",
				"back", "spawn", "setspawn", "warp", "warps", "setwarp", "delwarp", "rtp", "enderchest", "ec", "workbench", "craft", "anvil",
				"stonecutter", "grindstone", "smithing", "loom", "cartography", "trash", "afk", "r", "reply", "qol")) {
			helper.assertTrue(server.getCommands().getDispatcher().getRoot().getChild(name) != null, Component.literal("/" + name + " is registered"));
		}
	}

	private static void homesAndBack(GameTestHelper helper, ServerPlayer a) {
		Vec3 home = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
		Vec3 away = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 6)));
		a.teleportTo(home.x, home.y, home.z);
		helper.assertValueEqual(HomeCommands.setHome(a, "Base"), 1, Component.literal("/sethome Base"));
		helper.assertTrue(MejtaQoL.homes().book(A_ID).get("base").isPresent(), Component.literal("home stored lowercase"));
		helper.assertValueEqual(HomeCommands.setHome(a, "bad name"), 0, Component.literal("/sethome with a space is refused"));

		a.teleportTo(away.x, away.y, away.z);
		helper.assertValueEqual(HomeCommands.home(a, Optional.empty()), 1, Component.literal("/home goes to the only home"));
		assertAt(helper, a, home, "after /home");
		helper.assertValueEqual(HomeCommands.home(a, Optional.of("base")), 0, Component.literal("/home again is refused by the cooldown"));

		Location back = MejtaQoL.teleports().backLocation(A_ID).orElseThrow();
		helper.assertTrue(Math.abs(back.x() - away.x) < 0.01 && Math.abs(back.z() - away.z) < 0.01, Component.literal("/back points before /home"));
		helper.assertValueEqual(SpawnCommands.back(a), 1, Component.literal("/back"));
		assertAt(helper, a, away, "after /back");

		helper.assertValueEqual(HomeCommands.deleteOwn(a, "BASE"), 1, Component.literal("/delhome"));
		helper.assertValueEqual(HomeCommands.home(a, Optional.of("base")), 0, Component.literal("/home of a deleted home"));
	}

	private static void warps(GameTestHelper helper, ServerPlayer a, ServerPlayer b) {
		Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
		a.teleportTo(spot.x, spot.y, spot.z);
		helper.assertValueEqual(WarpCommands.set(a, "qoltest"), 1, Component.literal("/setwarp"));
		helper.assertValueEqual(WarpCommands.warp(b, "QOLTEST"), 1, Component.literal("/warp"));
		assertAt(helper, b, spot, "after /warp");
		helper.assertValueEqual(WarpCommands.delete(a, "qoltest"), 1, Component.literal("/delwarp"));
		helper.assertTrue(WarpCommands.book().get("qoltest").isEmpty(), Component.literal("warp deleted"));
	}

	private static void teleportRequests(GameTestHelper helper, ServerPlayer a, ServerPlayer b) {
		Map<UUID, ServerPlayer> online = Map.of(A_ID, a, B_ID, b);
		TpaService tpa = new TpaService(MejtaQoL.config(), MejtaQoL.teleports(), MejtaQoL.prefs(), online::get);
		Vec3 aPos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(0, 1, 6)));
		Vec3 bPos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(6, 1, 0)));
		a.teleportTo(aPos.x, aPos.y, aPos.z);
		b.teleportTo(bPos.x, bPos.y, bPos.z);

		helper.assertTrue(!tpa.send(a, a, TpaRequests.Direction.TO_TARGET), Component.literal("no request to oneself"));
		helper.assertTrue(tpa.send(a, b, TpaRequests.Direction.TO_TARGET), Component.literal("/tpa sent"));
		helper.assertTrue(tpa.accept(b, Optional.of("QOL_A")), Component.literal("/tpaccept by name"));
		assertAt(helper, a, bPos, "after /tpa was accepted");
		helper.assertValueEqual(tpa.requests().size(), 0, Component.literal("request used up"));

		helper.assertTrue(!tpa.toggle(a), Component.literal("/tptoggle turns requests off"));
		helper.assertTrue(!tpa.send(b, a, TpaRequests.Direction.TO_TARGET), Component.literal("a player who refuses gets no request"));
		helper.assertTrue(tpa.toggle(a), Component.literal("/tptoggle turns requests on again"));
		helper.assertTrue(tpa.send(b, a, TpaRequests.Direction.TO_SENDER), Component.literal("/tpahere sent"));
		helper.assertTrue(tpa.deny(a, Optional.empty()), Component.literal("/tpdeny"));
		helper.assertTrue(!tpa.accept(a, Optional.empty()), Component.literal("nothing left to accept"));
		helper.assertTrue(!tpa.send(a, b, TpaRequests.Direction.TO_TARGET), Component.literal("request cooldown"));
	}

	private static void afkTag(GameTestHelper helper, ServerPlayer a) {
		helper.assertTrue(MejtaQoL.afk().toggle(a), Component.literal("/afk on"));
		Component name = a.getTabListDisplayName();
		helper.assertTrue(name != null && name.getString().endsWith("[AFK]"), Component.literal("player list shows [AFK]: " + name));
		helper.assertTrue(!MejtaQoL.afk().toggle(a), Component.literal("/afk off"));
		Component after = a.getTabListDisplayName();
		helper.assertTrue(after == null || !after.getString().contains("[AFK]"), Component.literal("tag removed: " + after));
	}

	/** A PvP hit puts both players in combat (MejtaCore): teleports and /afk are refused until it ends. */
	private static void combatLock(GameTestHelper helper, ServerLevel level, ServerPlayer a, ServerPlayer b) {
		ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(b, level.damageSources().playerAttack(a), 2, 2, false);
		helper.assertTrue(MejtaCoreAPI.status().inCombat(A_ID) && MejtaCoreAPI.status().inCombat(B_ID),
				Component.literal("a PvP hit puts both players in combat"));
		helper.assertTrue(MejtaQoL.teleports().refusal(a, Features.HOME).map(m -> m.getString().contains("combat")).orElse(false),
				Component.literal("teleports are refused in combat"));
		helper.assertTrue(!MejtaQoL.afk().toggle(b) && !MejtaCoreAPI.status().isAfk(B_ID), Component.literal("/afk is refused in combat"));
		ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(a, level.damageSources().generic());
		ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(b, level.damageSources().generic());
		helper.assertTrue(!MejtaCoreAPI.status().inCombat(A_ID) && !MejtaCoreAPI.status().inCombat(B_ID)
				&& MejtaQoL.teleports().refusal(a, Features.HOME).map(m -> !m.getString().contains("combat")).orElse(true),
				Component.literal("death ends combat"));
	}

	private static void replies(GameTestHelper helper, ServerPlayer a, ServerPlayer b) {
		MsgCommandInvoker.mejtaqol$sendMessage(a.createCommandSourceStack(), List.of(b), PlayerChatMessage.system("hello"));
		helper.assertTrue(MejtaQoL.replies().partner(B_ID).map(p -> p.partner().equals(A_ID) && p.incoming()).orElse(false),
				Component.literal("/msg is remembered for /r"));
	}

	private static void randomTeleportScan(GameTestHelper helper, ServerLevel level) {
		BlockPos stone = helper.absolutePos(new BlockPos(2, 1, 5));
		helper.setBlock(new BlockPos(2, 1, 5), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 2, 5), Blocks.AIR);
		helper.setBlock(new BlockPos(2, 3, 5), Blocks.AIR);
		helper.assertValueEqual(RandomTeleport.safeY(level, stone.getX(), stone.getZ()).orElse(Integer.MIN_VALUE), stone.getY() + 1,
				Component.literal("safe spot on top of stone"));
		BlockPos lava = helper.absolutePos(new BlockPos(5, 1, 2));
		helper.setBlock(new BlockPos(5, 1, 2), Blocks.LAVA);
		helper.assertTrue(RandomTeleport.safeY(level, lava.getX(), lava.getZ()).isEmpty(), Component.literal("no spot on a lava surface"));
	}

	private static void portableCraftingReturnsItems(GameTestHelper helper, ServerLevel level, ServerPlayer a) {
		a.getInventory().clearContent();
		Stations.PortableCrafting menu = new Stations.PortableCrafting(1, a.getInventory(), ContainerLevelAccess.create(level, a.blockPosition()));
		helper.assertTrue(menu.stillValid(a), Component.literal("portable crafting stays open"));
		menu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS, 3));
		menu.removed(a);
		helper.assertValueEqual(a.getInventory().countItem(Items.OAK_PLANKS), 3, Component.literal("items in the grid come back on close"));
		a.getInventory().clearContent();
	}

	private static void spawn(GameTestHelper helper, MinecraftServer server, ServerPlayer a) {
		DataStore store = MejtaQoL.store();
		Optional<Location> custom = SpawnCommands.customSpawn(store);
		helper.assertTrue(!SpawnCommands.spawnLocation(server, a).dimension().isEmpty(), Component.literal("world spawn found"));
		Location set = new Location("minecraft:overworld", 12.5, 80, -3.5, 90, 0);
		store.write("spawn", set.toJson());
		helper.assertValueEqual(SpawnCommands.spawnLocation(server, a), set, Component.literal("/setspawn point wins"));
		if (custom.isPresent()) {
			store.write("spawn", custom.get().toJson());
		} else {
			store.delete("spawn");
		}
	}

	private static void featureSwitch(GameTestHelper helper, ConfigHandle config, ServerPlayer a) {
		helper.assertTrue(Cmds.available(Features.HOME).test(a.createCommandSourceStack()), Component.literal("/home available"));
		config.set(QolSettings.HOMES_ENABLED, false);
		helper.assertTrue(!Cmds.available(Features.HOME).test(a.createCommandSourceStack()), Component.literal("/home gone when switched off"));
		config.set(QolSettings.HOMES_ENABLED, true);
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer p, Vec3 expected, String when) {
		helper.assertTrue(p.position().distanceTo(expected) < 0.01, Component.literal(p.getGameProfile().name() + " " + when + ": at "
				+ p.position() + ", expected " + expected));
	}
}
