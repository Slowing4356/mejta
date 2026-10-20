package com.mejta.mejtasmp.test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.drops.PileHolder;
import com.mejta.mejtasmp.drops.Piles;
import com.mejta.mejtasmp.mixin.ItemEntityAccessor;
import com.mejta.mejtasmp.stats.Stat;
import com.mejta.mejtasmp.world.Dimensions;
import com.mejta.mejtasmp.world.WorldRules;
import com.mejta.mejtasmp.xp.XpClumps;

/**
 * Server game test: a headless server with MejtaCore and MejtaSMP (so every mixin must apply). Piles of
 * dropped items are checked to hold every item exactly once through merging, pickup, hoppers, saving and
 * destruction; then XP clumps, infinite trades, closed portals, game rules, AFK protection, stats and the
 * player list score.
 */
public class MejtaSmpGameTest {
	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000005a1");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000005a2");

	@GameTest(skyAccess = true, maxTicks = 40)
	public void smp(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ConfigHandle config = MejtaSMP.config();
		Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
		FakePlayer a = FakePlayer.get(level, new GameProfile(A, "smp_a"));
		FakePlayer b = FakePlayer.get(level, new GameProfile(B, "smp_b"));
		a.teleportTo(spot.x, spot.y, spot.z);
		try {
			pilesMergeAndArePickedUpOnce(helper, level, spot, a);
			pilesThroughHoppersSavingAndDestruction(helper, level, spot);
			xpClumpsAndInstantPickup(helper, level, spot, a);
			tradesNeverRunOut(helper, level, spot);
			closedPortalsAndGameRules(helper, level, config, a);
			afkProtection(helper, level, a, b);
			statsAndTabList(helper, level, config, a, b);
		} finally {
			config.set(SmpSettings.NETHER_OPEN, true);
			config.set(SmpSettings.TAB_SCORE, SmpSettings.TabScore.NONE);
			MejtaCoreAPI.status().setAfk(b, false);
		}
		helper.succeed();
	}

	// ---------------------------------------------------------------- piles

	private static void pilesMergeAndArePickedUpOnce(GameTestHelper helper, ServerLevel level, Vec3 spot, FakePlayer player) {
		List<ItemEntity> drops = List.of(drop(level, spot, 64), drop(level, spot.add(0.8, 0, 0), 64), drop(level, spot.add(0, 0, 0.8), 64),
				drop(level, spot.add(0.8, 0, 0.8), 10));
		Piles.merge(drops.get(0));
		List<ItemEntity> piles = cobble(level, spot);
		helper.assertValueEqual(piles.size(), 1, Component.literal("four drops within merge range become one pile"));
		ItemEntity pile = piles.get(0);
		helper.assertValueEqual(Piles.total(pile), 202, Component.literal("the pile holds all 202"));
		helper.assertValueEqual(pile.getItem().getCount(), 64, Component.literal("its visible stack is full"));
		helper.assertValueEqual(((PileHolder) pile).mejtasmp$extra(), 138, Component.literal("the rest is counted"));
		Piles.tick(withTickCount(pile));
		helper.assertTrue(pile.hasCustomName() && pile.getCustomName().getString().contains("202"),
				Component.literal("the pile is labelled with its size: " + pile.getCustomName()));

		// A player with room picks the pile up a stack at a time; each item exactly once.
		player.getInventory().clearContent();
		for (int i = 0; i < 10 && !cobble(level, spot).isEmpty(); i++) {
			cobble(level, spot).get(0).playerTouch(player);
		}
		helper.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE), 202, Component.literal("picked up exactly 202"));
		helper.assertTrue(cobble(level, spot).isEmpty(), Component.literal("nothing left on the ground"));

		// A full inventory takes only what fits; the rest stays on the ground.
		player.getInventory().clearContent();
		for (int slot = 0; slot < 35; slot++) {
			player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
		}
		player.getInventory().setItem(35, new ItemStack(Items.COBBLESTONE, 40));
		ItemEntity big = drop(level, spot, 64);
		((PileHolder) big).mejtasmp$setExtra(100);
		for (int i = 0; i < 5 && !cobble(level, spot).isEmpty(); i++) {
			cobble(level, spot).get(0).playerTouch(player);
		}
		int onGround = cobble(level, spot).stream().mapToInt(Piles::total).sum();
		helper.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE) + onGround, 204, Component.literal("full inventory: nothing lost or made"));
		helper.assertValueEqual(player.getInventory().countItem(Items.COBBLESTONE), 64, Component.literal("only what fitted was taken"));
		clear(level, spot);
		player.getInventory().clearContent();
	}

	private static void pilesThroughHoppersSavingAndDestruction(GameTestHelper helper, ServerLevel level, Vec3 spot) {
		// A hopper takes a stack at a time.
		ItemEntity pile = drop(level, spot, 64);
		((PileHolder) pile).mejtasmp$setExtra(136);
		SimpleContainer hopper = new SimpleContainer(9);
		for (int i = 0; i < 10 && !cobble(level, spot).isEmpty(); i++) {
			HopperBlockEntity.addItem(hopper, cobble(level, spot).get(0));
		}
		helper.assertValueEqual(hopper.countItem(Items.COBBLESTONE), 200, Component.literal("a hopper received exactly 200"));
		helper.assertTrue(cobble(level, spot).isEmpty(), Component.literal("the hopper emptied the pile"));

		// Saving and loading (also how an entity changes dimension) keeps the count.
		ItemEntity saved = drop(level, spot, 64);
		((PileHolder) saved).mejtasmp$setExtra(500);
		ItemEntity copy = new ItemEntity(EntityType.ITEM, level);
		copy.restoreFrom(saved);
		helper.assertValueEqual(Piles.total(copy), 564, Component.literal("a saved and loaded pile keeps all 564"));

		// Destroyed (lava) or despawned: the whole pile goes, nothing continues.
		saved.hurtServer(level, level.damageSources().lava(), 100);
		helper.assertTrue(saved.isRemoved() && cobble(level, spot).isEmpty(), Component.literal("lava destroys the whole pile"));
		ItemEntity old = drop(level, spot, 64);
		((PileHolder) old).mejtasmp$setExtra(50);
		((ItemEntityAccessor) old).mejtasmp$setAge(5999);
		old.tick();
		helper.assertTrue(old.isRemoved() && cobble(level, spot).isEmpty(), Component.literal("a despawning pile goes as a whole"));

		// Never bigger than drops.max_pile.
		ItemEntity x = drop(level, spot, 64);
		((PileHolder) x).mejtasmp$setExtra(MejtaSMP.config().get(SmpSettings.DROPS_MAX_PILE) - 64 - 10);
		drop(level, spot.add(0.3, 0, 0), 64);
		Piles.merge(x);
		helper.assertValueEqual(cobble(level, spot).size(), 2, Component.literal("piles never pass drops.max_pile"));
		clear(level, spot);
	}

	// ---------------------------------------------------------------- XP, trades, portals, rules

	private static void xpClumpsAndInstantPickup(GameTestHelper helper, ServerLevel level, Vec3 spot, FakePlayer player) {
		ExperienceOrb first = orb(level, spot, 3);
		orb(level, spot.add(0.5, 0, 0), 7);
		orb(level, spot.add(0, 0, 0.5), 11);
		XpClumps.clump(first);
		List<ExperienceOrb> orbs = level.getEntitiesOfClass(ExperienceOrb.class, new AABB(spot, spot).inflate(4), o -> !o.isRemoved());
		helper.assertValueEqual(orbs.size(), 1, Component.literal("three orbs of different values clump into one"));
		helper.assertValueEqual(orbs.get(0).getValue(), 21, Component.literal("holding all 21 experience"));
		int before = player.totalExperience;
		player.takeXpDelay = 0;
		orbs.get(0).playerTouch(player);
		helper.assertValueEqual(player.totalExperience - before, 21, Component.literal("taken at once"));
		helper.assertTrue(orbs.get(0).isRemoved(), Component.literal("the orb is gone"));
	}

	private static void tradesNeverRunOut(GameTestHelper helper, ServerLevel level, Vec3 spot) {
		Villager villager = new Villager(EntityType.VILLAGER, level);
		villager.setPos(spot);
		MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 2, 1, 0.05f);
		for (int i = 0; i < 5; i++) {
			villager.notifyTrade(offer);
		}
		helper.assertTrue(offer.getUses() == 0 && !offer.isOutOfStock(), Component.literal("trades never run out"));
	}

	private static void closedPortalsAndGameRules(GameTestHelper helper, ServerLevel level, ConfigHandle config, FakePlayer player) {
		config.set(SmpSettings.NETHER_OPEN, false);
		Villager walker = new Villager(EntityType.VILLAGER, level);
		helper.assertTrue(((NetherPortalBlock) Blocks.NETHER_PORTAL).getPortalDestination(level, walker, BlockPos.ZERO) == null,
				Component.literal("a closed Nether has no portal destination"));
		MinecraftServer server = level.getServer();
		boolean bypass = config.get(SmpSettings.OP_BYPASS);
		server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
		try {
			config.set(SmpSettings.OP_BYPASS, false);
			helper.assertTrue(Dimensions.refuse(player, Level.NETHER), Component.literal("an operator is refused by default"));
			config.set(SmpSettings.OP_BYPASS, true);
			helper.assertTrue(!Dimensions.refuse(player, Level.NETHER), Component.literal("an operator may go with op_bypass.enabled on"));
		} finally {
			config.set(SmpSettings.OP_BYPASS, bypass);
			server.getPlayerList().deop(player.nameAndId());
		}
		config.set(SmpSettings.NETHER_OPEN, true);
		WorldRules.apply(server, config);
		helper.assertValueEqual(server.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE), 0,
				Component.literal("one player sleeping is enough"));
		helper.assertValueEqual(server.getGameRules().get(GameRules.LOCATOR_BAR), false, Component.literal("the locator bar is hidden"));
	}

	// ---------------------------------------------------------------- AFK, stats

	private static void afkProtection(GameTestHelper helper, ServerLevel level, FakePlayer a, FakePlayer b) {
		MejtaCoreAPI.status().setAfk(b, true);
		helper.assertTrue(!ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, level.damageSources().playerAttack(a), 4),
				Component.literal("an AFK player cannot be hurt by a player"));
		helper.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, level.damageSources().fall(), 4),
				Component.literal("falling still hurts"));
		ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(a, level.damageSources().playerAttack(b), 2, 2, false);
		helper.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, level.damageSources().playerAttack(a), 4),
				Component.literal("in combat, being AFK does not protect"));
		ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(a, level.damageSources().generic());
		ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(b, level.damageSources().generic());
		MejtaCoreAPI.status().setAfk(b, false);
	}

	private static void statsAndTabList(GameTestHelper helper, ServerLevel level, ConfigHandle config, FakePlayer a, FakePlayer b) {
		var book = MejtaSMP.scoreboards().book();
		book.playerKilled(a, b);
		book.playerKilled(a, b);
		helper.assertValueEqual(book.get(A).orElseThrow().streak(), 2, Component.literal("two kills in a row: streak 2"));
		helper.assertValueEqual(book.get(B).orElseThrow().streak(), 0, Component.literal("the victim's streak ends"));
		helper.assertValueEqual(book.top(Stat.BEST_STREAK, 3).get(0).id(), A, Component.literal("the killer leads the best streaks"));
		book.died(a);
		helper.assertValueEqual(book.get(A).orElseThrow().bestStreak(), 2, Component.literal("the best streak is kept after death"));

		config.set(SmpSettings.TAB_SCORE, SmpSettings.TabScore.KILLS);
		MejtaSMP.scoreboards().applyTabList(level.getServer());
		var objective = level.getServer().getScoreboard().getDisplayObjective(DisplaySlot.LIST);
		helper.assertTrue(objective != null && objective.getName().equals("mejtasmp_tab"), Component.literal("kills shown in the player list"));
		config.set(SmpSettings.TAB_SCORE, SmpSettings.TabScore.NONE);
		MejtaSMP.scoreboards().applyTabList(level.getServer());
		helper.assertTrue(level.getServer().getScoreboard().getObjective("mejtasmp_tab") == null, Component.literal("and removed again"));
	}

	// ---------------------------------------------------------------- helpers

	private static ItemEntity drop(ServerLevel level, Vec3 at, int count) {
		ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.COBBLESTONE, count));
		e.setDeltaMovement(Vec3.ZERO);
		e.setNoPickUpDelay();
		level.addFreshEntity(e);
		return e;
	}

	private static List<ItemEntity> cobble(ServerLevel level, Vec3 at) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(4), e -> !e.isRemoved() && e.getItem().is(Items.COBBLESTONE)
				|| !e.isRemoved() && ((PileHolder) e).mejtasmp$extra() > 0);
	}

	/** Removes test piles without letting them continue. */
	private static void clear(ServerLevel level, Vec3 at) {
		for (ItemEntity e : cobble(level, at)) {
			((PileHolder) e).mejtasmp$setExtra(0);
			e.discard();
		}
	}

	private static ExperienceOrb orb(ServerLevel level, Vec3 at, int value) {
		ExperienceOrb orb = new ExperienceOrb(level, at.x, at.y, at.z, value);
		level.addFreshEntity(orb);
		return orb;
	}

	/** Piles relabel every 10 ticks of the entity: gives a fresh one such an age. */
	private static ItemEntity withTickCount(ItemEntity entity) {
		entity.tickCount = 10;
		return entity;
	}
}
