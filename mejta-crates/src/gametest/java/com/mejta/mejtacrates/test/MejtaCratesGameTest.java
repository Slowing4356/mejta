package com.mejta.mejtacrates.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacrates.CrateService;
import com.mejta.mejtacrates.KeyItems;
import com.mejta.mejtacrates.MejtaCrates;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.CrateStore;
import com.mejta.mejtacrates.data.PlayerKeys;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.util.ItemJson;

/**
 * Server game test: a headless server with MejtaCore and MejtaCrates opens a crate with a fake player and
 * checks that every key opens once and every reward is handed out once: virtual keys, an opening cut short
 * before its reward, physical keys given while offline (and a copy of one), and keys given to a name that has
 * never joined.
 *
 * <p>Fake players are not in the player list, so to MejtaCrates they are offline; {@code onJoin} plays their join.
 */
public class MejtaCratesGameTest {
	private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000c7a01");
	private static final String NAME = "crate_tester";

	@GameTest(maxTicks = 20)
	public void keysAndRewardsOnce(GameTestHelper helper) {
		try {
			run(helper);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void run(GameTestHelper helper) throws IOException {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CrateService service = MejtaCrates.service();
		CrateStore store = service.store();
		AuditActor actor = AuditActor.system(MejtaCrates.ID);
		Crate crate = new Crate("gametest", "Test Crate", "GOLD", Optional.empty(), Optional.empty(),
				List.of(Reward.ofItem(1, ItemJson.encode(server.registryAccess(), new ItemStack(Items.DIAMOND, 3)))));
		store.save(crate);
		store.savePlayer(PLAYER, PlayerKeys.empty(NAME));
		store.deleteByName(NAME);
		FakePlayer player = FakePlayer.get(level, new GameProfile(PLAYER, NAME));
		Inventory inv = player.getInventory();
		inv.clearContent();

		// Virtual keys: one key, one reward.
		helper.assertTrue(service.giveVirtual(server, actor, PLAYER, NAME, crate, 2), Component.literal("keys given"));
		helper.assertValueEqual(keys(store, crate), 2, Component.literal("two keys"));
		helper.assertValueEqual(service.openVirtual(player, crate, false), CrateService.OpenOutcome.STARTED, Component.literal("opened"));
		helper.assertValueEqual(inv.countItem(Items.DIAMOND), 3, Component.literal("reward handed out"));
		helper.assertValueEqual(keys(store, crate), 1, Component.literal("one key used"));
		helper.assertTrue(store.player(PLAYER, NAME).pending().isEmpty(), Component.literal("nothing pending"));

		// An opening cut short (a crash after the key was taken) is finished once, at the next join.
		PlayerKeys.Opening cut = new PlayerKeys.Opening("0123456789ab", crate.id(), crate.name(), crate.rewards().get(0), 0, "virtual");
		store.savePlayer(PLAYER, store.player(PLAYER, NAME).withOpening(cut));
		service.onJoin(player);
		helper.assertValueEqual(inv.countItem(Items.DIAMOND), 6, Component.literal("pending reward handed out at join"));
		helper.assertTrue(!service.finish(player, cut.id()), Component.literal("a finished opening is not handed out again"));
		service.onJoin(player);
		helper.assertValueEqual(inv.countItem(Items.DIAMOND), 6, Component.literal("still handed out only once"));

		// Physical keys given while offline arrive at the join; a copy opens nothing once the batch is used up.
		helper.assertTrue(service.givePhysical(server, actor, PLAYER, NAME, crate, 1), Component.literal("physical key given"));
		service.onJoin(player);
		ItemStack key = ItemStack.EMPTY;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (KeyItems.isKey(inv.getItem(i))) {
				key = inv.getItem(i).copy();
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		helper.assertValueEqual(key.getCount(), 1, Component.literal("one key item at the join"));
		ItemStack copy = key.copy();
		player.setItemInHand(InteractionHand.MAIN_HAND, key);
		helper.assertValueEqual(service.openPhysical(player, InteractionHand.MAIN_HAND, false), CrateService.OpenOutcome.STARTED,
				Component.literal("physical key opens"));
		helper.assertTrue(player.getMainHandItem().isEmpty(), Component.literal("physical key used"));
		helper.assertValueEqual(inv.countItem(Items.DIAMOND), 9, Component.literal("physical key's reward"));
		player.setItemInHand(InteractionHand.MAIN_HAND, copy);
		helper.assertValueEqual(service.openPhysical(player, InteractionHand.MAIN_HAND, false), CrateService.OpenOutcome.COPIED_KEY,
				Component.literal("a copied key is refused"));
		helper.assertTrue(player.getMainHandItem().isEmpty(), Component.literal("the copy is taken away"));
		helper.assertValueEqual(inv.countItem(Items.DIAMOND), 9, Component.literal("a copied key gives nothing"));

		// Keys given to a name that never joined move to the player at the join, once.
		helper.assertTrue(service.giveByName(actor, NAME, crate, 5, false), Component.literal("keys given by name"));
		CrateStore.NamedKeys byName = store.byName(NAME).orElseThrow();
		service.onJoin(player);
		helper.assertValueEqual(keys(store, crate), 6, Component.literal("name keys moved to the player"));
		store.saveByName(NAME, byName); // as if a crash had kept the file
		service.onJoin(player);
		helper.assertValueEqual(keys(store, crate), 6, Component.literal("name keys are never added twice"));
		helper.assertTrue(store.byName(NAME).isEmpty(), Component.literal("name file removed"));

		store.delete(crate.id());
		helper.succeed();
	}

	private static int keys(CrateStore store, Crate crate) {
		return store.player(PLAYER, NAME).keys(crate.id());
	}
}
