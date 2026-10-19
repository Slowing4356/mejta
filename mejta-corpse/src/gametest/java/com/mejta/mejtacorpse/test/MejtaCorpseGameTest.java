package com.mejta.mejtacorpse.test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacorpse.CorpseManager;
import com.mejta.mejtacorpse.MejtaCorpse;
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.data.CorpseStore;
import com.mejta.mejtacorpse.data.SlotLayout;
import com.mejta.mejtacorpse.menu.CorpseMenu;
import com.mejta.mejtacorpse.menu.LiveCorpse;
import com.mejta.mejtacorpse.util.ItemJson;

/**
 * Server game test: a headless server with MejtaCore and MejtaCorpse (so the death hook must apply)
 * drives corpses with Fabric's fake players and the real menu code, and checks after every step that each
 * item exists exactly once: in the corpse on disk, or with the player.
 *
 * <p>Fake players are not in the player list, so the game cannot save them: the corpse is created with a
 * no-op save here, and a real death of a fake player must fall back to vanilla drops.
 */
public class MejtaCorpseGameTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000c0a01");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000c0a02");
	private static final CorpseManager.PlayerSaver NO_SAVE = p -> {
	};

	@GameTest(skyAccess = true, maxTicks = 60)
	public void corpses(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CorpseManager manager = MejtaCorpse.manager();
		FakePlayer owner = FakePlayer.get(level, new GameProfile(OWNER, "corpse_owner"));
		FakePlayer other = FakePlayer.get(level, new GameProfile(OTHER, "corpse_other"));
		Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
		owner.teleportTo(spot.x, spot.y, spot.z);
		other.teleportTo(spot.x, spot.y, spot.z);

		// Death: items move into an active corpse with two entities.
		Inventory inv = owner.getInventory();
		inv.clearContent();
		inv.setItem(0, new ItemStack(Items.DIAMOND, 5));
		inv.setItem(9, new ItemStack(Items.COBBLESTONE, 64));
		inv.setItem(39, new ItemStack(Items.IRON_HELMET));
		inv.setItem(40, new ItemStack(Items.SHIELD));
		helper.assertTrue(manager.capture(owner, NO_SAVE), Component.literal("corpse created"));
		helper.assertTrue(inv.isEmpty(), Component.literal("inventory emptied"));
		List<CorpseRecord> corpses = manager.store().ofOwner(OWNER);
		helper.assertValueEqual(corpses.size(), 1, Component.literal("one corpse"));
		CorpseRecord corpse = corpses.get(0);
		helper.assertValueEqual(corpse.state(), CorpseRecord.State.ACTIVE, Component.literal("corpse is active"));
		helper.assertValueEqual(corpse.items().size(), 4, Component.literal("four stacks stored"));
		helper.assertValueEqual(onDisk(corpse.id()).size(), 4, Component.literal("four stacks on disk"));
		Entity body = level.getEntity(corpse.body());
		helper.assertTrue(body instanceof Mannequin, Component.literal("body is a mannequin: " + body));
		helper.assertValueEqual(body.getPose(), Pose.SLEEPING, Component.literal("body lies down"));
		helper.assertTrue(body.isInvulnerable(), Component.literal("body is invulnerable"));
		helper.assertTrue(level.getEntity(corpse.hitbox()) instanceof Interaction, Component.literal("hitbox is an interaction entity"));
		helper.assertTrue(!manager.open(other, corpse, false), Component.literal("another player may not open it"));

		// Something else removes the body: the corpse's entities come back, the items are untouched.
		UUID oldBody = corpse.body();
		body.discard();
		helper.runAtTickTime(5, () -> {
			CorpseRecord repaired = manager.store().get(corpse.id()).orElseThrow();
			helper.assertTrue(repaired.body() != null && !repaired.body().equals(oldBody), Component.literal("new body"));
			helper.assertTrue(level.getEntity(repaired.body()) instanceof Mannequin, Component.literal("new body exists"));
			helper.assertValueEqual(repaired.items().size(), 4, Component.literal("items untouched by the repair"));

			menuMovesEachItemOnce(helper, level, manager, owner, repaired);
			failedWriteMovesNothing(helper, level, owner);
			unfinishedDeathIsResolvedAtJoin(helper, level, manager, owner);
			fakePlayerDeathFallsBackToDrops(helper, level, manager, other);
			helper.succeed();
		});
	}

	private static void menuMovesEachItemOnce(GameTestHelper helper, ServerLevel level, CorpseManager manager, ServerPlayer owner,
			CorpseRecord corpse) {
		HolderLookup.Provider registries = level.getServer().registryAccess();
		Inventory inv = owner.getInventory();
		LiveCorpse live = new LiveCorpse(manager.store(), registries, corpse);
		live.addViewer();
		CorpseMenu menu = new CorpseMenu(1, inv, live, false);
		int diamonds = SlotLayout.toDisplay(0).getAsInt();
		int cobble = SlotLayout.toDisplay(9).getAsInt();

		// Shift-click: into the inventory, out of the corpse on disk. A second click moves nothing more.
		menu.clicked(diamonds, 0, ContainerInput.QUICK_MOVE, owner);
		helper.assertValueEqual(count(inv, Items.DIAMOND), 5, Component.literal("diamonds moved"));
		helper.assertTrue(!onDisk(corpse.id()).containsKey(0), Component.literal("diamonds gone from disk"));
		menu.clicked(diamonds, 0, ContainerInput.QUICK_MOVE, owner);
		menu.clicked(diamonds, 0, ContainerInput.PICKUP_ALL, owner);
		helper.assertValueEqual(count(inv, Items.DIAMOND) + count(menu.getCarried(), Items.DIAMOND), 5, Component.literal("still 5 diamonds"));

		// Nothing can be put in.
		menu.setCarried(new ItemStack(Items.DIRT, 3));
		menu.clicked(diamonds, 0, ContainerInput.PICKUP, owner);
		menu.clicked(diamonds, 1, ContainerInput.PICKUP, owner);
		helper.assertTrue(live.getItem(diamonds).isEmpty(), Component.literal("nothing placed into the corpse"));
		helper.assertValueEqual(menu.getCarried().getCount(), 3, Component.literal("dirt still carried"));
		menu.setCarried(ItemStack.EMPTY);

		// Number key: into an empty hotbar slot only.
		inv.setItem(5, ItemStack.EMPTY);
		menu.clicked(cobble, 5, ContainerInput.SWAP, owner);
		helper.assertValueEqual(inv.getItem(5).getCount(), 64, Component.literal("cobblestone swapped to the hotbar"));
		helper.assertTrue(live.getItem(cobble).isEmpty() && !onDisk(corpse.id()).containsKey(9), Component.literal("cobblestone gone"));

		// Buttons cannot be taken.
		menu.clicked(46, 0, ContainerInput.PICKUP, owner);
		menu.clicked(46, 0, ContainerInput.QUICK_MOVE, owner);
		helper.assertTrue(menu.getCarried().isEmpty(), Component.literal("a button is never picked up"));

		// Take all: the owner wears the helmet and holds the shield again.
		menu.takeAll(owner, true);
		helper.assertTrue(inv.getItem(39).is(Items.IRON_HELMET), Component.literal("helmet back on the head"));
		helper.assertTrue(inv.getItem(40).is(Items.SHIELD), Component.literal("shield back in the offhand"));
		helper.assertTrue(live.holdsNothing() && onDisk(corpse.id()).isEmpty(), Component.literal("corpse empty"));

		// Closing the empty corpse removes it.
		menu.removed(owner);
		helper.assertTrue(manager.store().get(corpse.id()).isEmpty(), Component.literal("record removed"));
		Entity body = level.getEntity(corpse.body());
		helper.assertTrue(body == null || body.isRemoved(), Component.literal("body removed"));
		helper.assertValueEqual(count(inv, Items.DIAMOND), 5, Component.literal("exactly 5 diamonds"));
		helper.assertValueEqual(count(inv, Items.COBBLESTONE), 64, Component.literal("exactly 64 cobblestone"));
		helper.assertValueEqual(count(inv, Items.IRON_HELMET), 1, Component.literal("exactly 1 helmet"));
		helper.assertValueEqual(count(inv, Items.SHIELD), 1, Component.literal("exactly 1 shield"));
		inv.clearContent();
	}

	/** A corpse whose disk refuses writes hands out nothing. */
	private static void failedWriteMovesNothing(GameTestHelper helper, ServerLevel level, ServerPlayer owner) {
		HolderLookup.Provider registries = level.getServer().registryAccess();
		FlakyStore disk = new FlakyStore();
		CorpseStore store = new CorpseStore(disk);
		Map<Integer, JsonElement> items = new TreeMap<>();
		items.put(0, ItemJson.encode(registries, new ItemStack(Items.EMERALD, 7)));
		CorpseRecord corpse = CorpseRecord.pending("f00d0000", OWNER, "corpse_owner", 0, "minecraft:overworld", 0, 0, 0, 0, false, items, null)
				.withState(CorpseRecord.State.ACTIVE);
		try {
			store.saveNow(corpse);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		LiveCorpse live = new LiveCorpse(store, registries, corpse);
		live.addViewer();
		CorpseMenu menu = new CorpseMenu(2, owner.getInventory(), live, true);
		disk.fail = true;
		int slot = SlotLayout.toDisplay(0).getAsInt();
		menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, owner);
		menu.clicked(slot, 0, ContainerInput.PICKUP, owner);
		menu.clicked(slot, 0, ContainerInput.THROW, owner);
		menu.takeAll(owner, true);
		helper.assertValueEqual(count(owner.getInventory(), Items.EMERALD) + count(menu.getCarried(), Items.EMERALD), 0,
				Component.literal("nothing handed out while the disk fails"));
		helper.assertValueEqual(live.getItem(slot).getCount(), 7, Component.literal("the corpse keeps all 7"));
		disk.fail = false;
		menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, owner);
		helper.assertValueEqual(count(owner.getInventory(), Items.EMERALD), 7, Component.literal("taken once the disk works"));
		helper.assertTrue(disk.read(CorpseStore.key("f00d0000")).orElseThrow().getAsJsonObject("items").isEmpty(),
				Component.literal("and gone from that disk"));
		owner.getInventory().clearContent();
	}

	/**
	 * A stop between writing the corpse and finishing it leaves it pending. At the owner's next join it is
	 * deleted if they still hold the items (their death was not saved), and activated if they do not.
	 */
	private static void unfinishedDeathIsResolvedAtJoin(GameTestHelper helper, ServerLevel level, CorpseManager manager, ServerPlayer owner) {
		HolderLookup.Provider registries = level.getServer().registryAccess();
		Inventory inv = owner.getInventory();
		inv.clearContent();
		inv.setItem(3, new ItemStack(Items.GOLD_INGOT, 9));
		CorpseRecord stillHeld = pending("aa000001", owner, 3, ItemJson.encode(registries, new ItemStack(Items.GOLD_INGOT, 9)));
		CorpseRecord moved = pending("aa000002", owner, 4, ItemJson.encode(registries, new ItemStack(Items.REDSTONE, 2)));
		try {
			manager.store().saveNow(stillHeld);
			manager.store().saveNow(moved);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		manager.onJoin(owner);
		helper.assertTrue(manager.store().get("aa000001").isEmpty(), Component.literal("corpse of an unsaved death discarded"));
		CorpseRecord activated = manager.store().get("aa000002").orElseThrow();
		helper.assertValueEqual(activated.state(), CorpseRecord.State.ACTIVE, Component.literal("corpse of a saved death activated"));
		helper.assertValueEqual(count(inv, Items.GOLD_INGOT), 9, Component.literal("the held gold stays"));
		manager.store().delete("aa000002");
		for (UUID e : new UUID[] {activated.body(), activated.hitbox()}) {
			Entity entity = e == null ? null : level.getEntity(e);
			if (entity != null) {
				entity.discard();
			}
		}
		inv.clearContent();
	}

	private static CorpseRecord pending(String id, ServerPlayer owner, int slot, JsonElement item) {
		return CorpseRecord.pending(id, owner.getUUID(), owner.getGameProfile().name(), System.currentTimeMillis(), "minecraft:overworld",
				owner.getX(), owner.getY(), owner.getZ(), 0, false, Map.of(slot, item), null);
	}

	/**
	 * A real death runs the death hook. A fake player cannot be saved, so no corpse may be created and the
	 * items must drop as in vanilla: neither kept twice nor lost.
	 */
	private static void fakePlayerDeathFallsBackToDrops(GameTestHelper helper, ServerLevel level, CorpseManager manager, ServerPlayer other) {
		other.getInventory().clearContent();
		other.getInventory().setItem(2, new ItemStack(Items.APPLE, 11));
		other.die(level.damageSources().generic());
		helper.assertTrue(manager.store().ofOwner(OTHER).isEmpty(), Component.literal("no corpse for a player that cannot be saved"));
		helper.assertTrue(other.getInventory().isEmpty(), Component.literal("inventory dropped"));
		int apples = 0;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(other.blockPosition()).inflate(4))) {
			if (item.getItem().is(Items.APPLE)) {
				apples += item.getItem().getCount();
				item.discard();
			}
		}
		helper.assertValueEqual(apples, 11, Component.literal("all 11 apples dropped, once"));
	}

	private static Map<Integer, JsonElement> onDisk(String id) {
		Map<Integer, JsonElement> out = new TreeMap<>();
		Optional<JsonObject> doc = MejtaCorpse.store().read(CorpseStore.key(id));
		doc.ifPresent(d -> d.getAsJsonObject("items").entrySet().forEach(e -> out.put(Integer.parseInt(e.getKey()), e.getValue())));
		return out;
	}

	private static int count(Inventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			n += count(inv.getItem(i), item);
		}
		return n;
	}

	private static int count(ItemStack stack, Item item) {
		return stack.is(item) ? stack.getCount() : 0;
	}

	/** An in-memory store whose immediate writes can be made to fail, like a full disk. */
	static final class FlakyStore implements DataStore {
		final Map<String, JsonObject> docs = new TreeMap<>();
		boolean fail;

		@Override
		public Optional<JsonObject> read(String key) {
			return Optional.ofNullable(docs.get(key)).map(JsonObject::deepCopy);
		}

		@Override
		public void write(String key, JsonObject value) {
			docs.put(key, value.deepCopy());
		}

		@Override
		public CompletableFuture<Void> writeDurable(String key, JsonObject value) {
			write(key, value);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void writeNow(String key, JsonObject value) throws IOException {
			if (fail) {
				throw new IOException("disk full");
			}
			write(key, value);
		}

		@Override
		public void delete(String key) {
			docs.remove(key);
		}

		@Override
		public List<String> keys(String folder) {
			return docs.keySet().stream().filter(k -> k.startsWith(folder + "/")).toList();
		}

		@Override
		public CompletableFuture<Void> flush() {
			return CompletableFuture.completedFuture(null);
		}
	}
}
