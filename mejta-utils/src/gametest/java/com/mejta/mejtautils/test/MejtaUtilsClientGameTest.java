package com.mejta.mejtautils.test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.presence.PresenceListener;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.audit.AuditTrail;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.inspect.LiveInventoryMenu;
import com.mejta.mejtautils.inspect.OfflineSnapshot;
import com.mejta.mejtautils.offline.PendingChange;
import com.mejta.mejtautils.offline.PendingChange.Container;
import com.mejta.mejtautils.staffmode.StaffSnapshot;
import com.mejta.mejtautils.staffmode.StaffTools;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * End-to-end tests of MejtaUtils with a real client on a dedicated server: unauthorized commands,
 * inspection menus (view mode cannot change anything; edit mode moves items without duplicating them),
 * queued offline changes (applied once, conflicts refused, interrupted applications recovered), vanish
 * leak paths a single client can observe, freeze, and staff mode including recovery after a disconnect
 * in every session state. Every check logs {@code [MejtaUtilsTest] PASS/FAIL}.
 */
public final class MejtaUtilsClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaUtilsTest");
	private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-00000000057a");
	/** Nodes granted to the (non-operator) test player after the unauthorized checks. */
	private static final MejtaPermission[] GRANTED = {UtilsPermissions.INVSEE_VIEW, UtilsPermissions.INVSEE_EDIT, UtilsPermissions.INVSEE_OFFLINE,
			UtilsPermissions.ECSEE_VIEW, UtilsPermissions.STATUS_VIEW, UtilsPermissions.VANISH, UtilsPermissions.VANISH_FAKE,
			UtilsPermissions.FREEZE, UtilsPermissions.STAFFMODE, UtilsPermissions.STAFFMODE_TELEPORT};

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		check("mejtautils addon active", MejtaCoreAPI.getAddon(MejtaUtils.ID).map(a -> a.state() == AddonState.ACTIVE).orElse(false));
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("difficulty easy");
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("fill -2 99 -2 12 99 12 stone");
			TestServerConnection connection = server.connect();
			connection.getClientLevel().waitForChunksRender();
			try {
				unauthorizedChecks(ctx, server);
				grant(server, true);
				inspectionChecks(ctx, server);
				connection = offlineChangeChecks(ctx, server, connection);
				connection = vanishChecks(ctx, server, connection);
				connection = freezeChecks(ctx, server, connection);
				connection = staffModeChecks(ctx, server, connection);
			} finally {
				grant(server, false);
				connection.close();
			}
		}
		LOG.info("[MejtaUtilsTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaUtils tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- unauthorized

	private void unauthorizedChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		String me = name(ctx);
		for (String command : List.of("/invsee " + me, "/ecsee " + me, "/status " + me, "/vanish", "/freeze " + me, "/unfreeze " + me,
				"/staffmode", "/invsee claims")) {
			clearChat(ctx);
			chat(ctx, command);
			check("unauthorized: " + command + " is not available to a non-operator", chatContains(ctx, "Unknown or incomplete command"));
		}
		UUID id = id(ctx);
		check("unauthorized: nothing happened", server.computeOnServer(s -> !MejtaUtils.vanish().isHidden(id)
				&& !MejtaUtils.staffMode().isActive(id) && !MejtaUtils.freeze().isFrozen(id)
				&& s.getPlayerList().getPlayer(id).containerMenu == s.getPlayerList().getPlayer(id).inventoryMenu));
	}

	// ---------------------------------------------------------------- /invsee, /ecsee, /status

	private void inspectionChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID me = id(ctx);
		GameProfile targetProfile = new GameProfile(UUID.fromString("00000000-0000-4000-8000-0000000000f1"), "InvTarget");
		server.runOnServer(s -> {
			FakePlayer target = FakePlayer.get(s.overworld(), targetProfile);
			target.getInventory().clearContent();
			target.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
			player(s, me).getInventory().clearContent();
			LiveInventoryMenu.open(player(s, me), target, false);
		});
		check("invsee: view menu opens as a chest", waitForScreen(ctx, "Viewing InvTarget"));
		clickSlot(ctx, 27, 0);
		clickSlot(ctx, 27, 1);
		ctx.getInput().holdShift();
		clickSlot(ctx, 27, 0);
		ctx.getInput().releaseShift();
		hoverSlot(ctx, 27);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_2);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_Q);
		ctx.waitTicks(5);
		check("invsee view: no click type moves an item", server.computeOnServer(s -> {
			FakePlayer target = FakePlayer.get(s.overworld(), targetProfile);
			ServerPlayer viewer = player(s, me);
			return target.getInventory().countItem(Items.DIAMOND) == 4 && viewer.getInventory().countItem(Items.DIAMOND) == 0
					&& viewer.containerMenu.getCarried().isEmpty() && nearbyItems(viewer).isEmpty();
		}));
		clickSlot(ctx, 53, 0);
		check("invsee: close button closes the menu", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen == null)));

		server.runOnServer(s -> LiveInventoryMenu.open(player(s, me), FakePlayer.get(s.overworld(), targetProfile), true));
		check("invsee: edit menu opens", waitForScreen(ctx, "Editing InvTarget"));
		ctx.getInput().holdShift();
		clickSlot(ctx, 27, 0);
		ctx.getInput().releaseShift();
		check("invsee edit: shift-click moves the stack, exactly once", waitUntil(ctx, () -> server.computeOnServer(s ->
				FakePlayer.get(s.overworld(), targetProfile).getInventory().countItem(Items.DIAMOND) == 0
						&& player(s, me).getInventory().countItem(Items.DIAMOND) == 4)));
		clickSlot(ctx, 41, 0); // crafting display slot: never vanilla-handled
		check("invsee edit: crafting grid stays view-only", server.computeOnServer(s -> player(s, me).containerMenu.getCarried().isEmpty()));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		check("invsee edit: session audited with a per-slot diff", waitUntil(ctx, () -> recentAudit("invsee.edit") != null
				&& recentAudit("invsee.edit").get("details").toString().contains("minecraft:diamond x4 -> empty")));

		clearChat(ctx);
		chat(ctx, "/ecsee " + name(ctx));
		check("ecsee: own ender chest opens", waitForScreen(ctx, "Viewing " + name(ctx) + "'s ender chest"));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		chat(ctx, "/status " + name(ctx));
		check("status: panel opens", waitForScreen(ctx, "Status: " + name(ctx)));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		check("inspection opens are audited", recentAudit("ecsee.open") != null && recentAudit("status.open") != null);
		server.runOnServer(s -> player(s, me).getInventory().clearContent());
	}

	// ---------------------------------------------------------------- queued offline changes

	private TestServerConnection offlineChangeChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, TestServerConnection connection) {
		UUID me = id(ctx);
		String name = name(ctx);
		server.runOnServer(s -> {
			ServerPlayer p = player(s, me);
			p.getInventory().clearContent();
			p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
			p.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 5));
			p.getInventory().setItem(24, new ItemStack(Items.COAL, 1));
			p.getInventory().setItem(23, new ItemStack(Items.REDSTONE, 7));
		});
		ctx.waitTicks(5);

		// 1. Applied exactly once.
		connection.close();
		check("offline: snapshot reads the saved inventory", server.computeOnServer(s -> OfflineSnapshot.load(s, new net.minecraft.server.players.NameAndId(me, name))
				.map(snap -> snap.inventory(0).is(Items.DIAMOND) && snap.inventory(0).getCount() == 3).orElse(false)));
		server.runOnServer(s -> {
			HolderLookup.Provider r = s.registryAccess();
			PendingChange c = new PendingChange(me, name, STAFF, "TestStaff", Instant.now());
			c.remove(Container.INVENTORY, 0, ItemJson.encode(r, new ItemStack(Items.DIAMOND, 3)));
			c.add(Container.INVENTORY, 20, ItemJson.encode(r, new ItemStack(Items.EMERALD, 2)));
			MejtaUtils.pending().save(c);
		});
		connection = reconnect(server);
		check("offline: queued change applied on join", server.computeOnServer(s -> {
			ServerPlayer p = player(s, me);
			return p.getInventory().getItem(0).isEmpty() && p.getInventory().getItem(20).is(Items.EMERALD)
					&& p.getInventory().getItem(20).getCount() == 2 && p.getInventory().countItem(Items.DIAMOND) == 0;
		}));
		check("offline: removed diamonds wait in the staff member's claims, nowhere else", server.computeOnServer(s -> {
			PendingChange c = MejtaUtils.pending().get(me).orElse(null);
			return c != null && c.state() == PendingChange.State.APPLIED && c.claims().size() == 1
					&& ItemJson.matches(s.registryAccess(), new ItemStack(Items.DIAMOND, 3), c.claims().getFirst());
		}));
		connection = reconnect(server);
		check("offline: not applied a second time on the next join", server.computeOnServer(s ->
				player(s, me).getInventory().getItem(20).getCount() == 2 && MejtaUtils.pending().get(me).map(c -> c.claims().size()).orElse(0) == 1));

		// 2. The save changed in between: refused, added items returned.
		connection.close();
		server.runOnServer(s -> {
			MejtaUtils.pending().discard(me, com.mejta.mejtacore.api.audit.AuditActor.system("test"));
			HolderLookup.Provider r = s.registryAccess();
			PendingChange c = new PendingChange(me, name, STAFF, "TestStaff", Instant.now());
			c.remove(Container.INVENTORY, 9, ItemJson.encode(r, new ItemStack(Items.IRON_INGOT, 99)));
			c.add(Container.INVENTORY, 21, ItemJson.encode(r, new ItemStack(Items.GOLD_INGOT, 1)));
			MejtaUtils.pending().save(c);
		});
		connection = reconnect(server);
		check("offline conflict: nothing changed on the player", server.computeOnServer(s -> {
			ServerPlayer p = player(s, me);
			return p.getInventory().getItem(9).getCount() == 5 && p.getInventory().getItem(21).isEmpty();
		}));
		check("offline conflict: cancelled, added item returned to claims", server.computeOnServer(s ->
				MejtaUtils.pending().get(me).map(c -> c.state() == PendingChange.State.CANCELLED && c.claims().size() == 1
						&& ItemJson.matches(s.registryAccess(), new ItemStack(Items.GOLD_INGOT, 1), c.claims().getFirst())).orElse(false)));

		// 3. Interrupted while applying, and the save already shows the result.
		recoveryCheck(ctx, server, connection = reconnectWithChange(server, connection, me, name, 22, new ItemStack(Items.LAPIS_LAZULI, 4)),
				me, 22, PendingChange.State.APPLIED, true, "already applied");
		// 4. Interrupted while applying, the save still shows the state before: applied now.
		recoveryCheck(ctx, server, connection = reconnectWithChange(server, connection, me, name, 23, new ItemStack(Items.REDSTONE, 7)),
				me, 23, PendingChange.State.APPLIED, true, "not yet applied");
		// 5. Interrupted, and the save shows neither: nothing is changed.
		recoveryCheck(ctx, server, connection = reconnectWithChange(server, connection, me, name, 24, new ItemStack(Items.COAL, 5)),
				me, 24, PendingChange.State.APPLYING, false, "unknown");
		server.runOnServer(s -> MejtaUtils.pending().discard(me, com.mejta.mejtacore.api.audit.AuditActor.system("test")));
		return connection;
	}

	/** Disconnects, stores an APPLYING change removing {@code item} from {@code slot}, reconnects. */
	private TestServerConnection reconnectWithChange(TestDedicatedServerContext server, TestServerConnection connection, UUID me, String name,
			int slot, ItemStack item) {
		connection.close();
		server.runOnServer(s -> {
			MejtaUtils.pending().discard(me, com.mejta.mejtacore.api.audit.AuditActor.system("test"));
			PendingChange c = new PendingChange(me, name, STAFF, "TestStaff", Instant.now());
			c.remove(Container.INVENTORY, slot, ItemJson.encode(s.registryAccess(), item));
			c.markApplying();
			MejtaUtils.pending().save(c);
		});
		return reconnect(server);
	}

	private void recoveryCheck(ClientGameTestContext ctx, TestDedicatedServerContext server, TestServerConnection connection, UUID me, int slot,
			PendingChange.State expected, boolean slotEmpty, String what) {
		check("offline recovery (" + what + "): state " + expected, server.computeOnServer(s ->
				MejtaUtils.pending().get(me).map(c -> c.state() == expected).orElse(false)));
		check("offline recovery (" + what + "): slot " + (slotEmpty ? "empty" : "unchanged"), server.computeOnServer(s ->
				player(s, me).getInventory().getItem(slot).isEmpty() == slotEmpty));
	}

	// ---------------------------------------------------------------- vanish

	private TestServerConnection vanishChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, TestServerConnection connection) {
		UUID me = id(ctx);
		String name = name(ctx);
		List<String> presence = new CopyOnWriteArrayList<>();
		server.runOnServer(s -> {
			MejtaCoreAPI.presence().addListener((player, n, kind, real) -> {
				if (player.equals(me)) {
					presence.add(kind + ":" + real);
				}
			});
			player(s, me).getInventory().clearContent();
		});
		server.runCommand("tp " + name + " 5 100 5");
		ctx.waitTicks(10);
		chat(ctx, "/vanish");
		check("vanish: on", waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.vanish().isHidden(me))));
		check("vanish: hidden in MejtaCore presence", server.computeOnServer(s -> MejtaCoreAPI.presence().isHidden(me)
				&& MejtaCoreAPI.presence().publiclyOnline(s).isEmpty()));
		check("vanish: server list count excludes vanished staff", server.computeOnServer(s -> s.getPlayerCount() == 0));
		check("vanish: entity tracker hides the player from non-staff", server.computeOnServer(s ->
				!player(s, me).broadcastToPlayer(FakePlayer.get(s.overworld()))));
		check("vanish: selectors and /list hide the player from non-staff", server.computeOnServer(s ->
				VanishHooks.visibleTo(FakePlayer.get(s.overworld()), s.getPlayerList().getPlayers()).isEmpty()));

		server.runCommand("summon husk 9 100 5");
		ctx.waitTicks(60);
		check("vanish: mobs do not target", server.computeOnServer(s -> monsters(s).stream().noneMatch(m -> m.getTarget() == player(s, me))));

		server.runCommand("summon item 5 100 5 {Item:{id:\"minecraft:diamond\",count:1}}");
		ctx.waitTicks(40);
		check("vanish: items are not picked up", server.computeOnServer(s -> player(s, me).getInventory().countItem(Items.DIAMOND) == 0
				&& !nearbyItems(player(s, me)).isEmpty()));

		server.runCommand("setblock 5 100 7 stone_pressure_plate");
		server.runCommand("tp " + name + " 5.5 100 7.5");
		ctx.waitTicks(20);
		check("vanish: pressure plates are not pressed", server.computeOnServer(s ->
				!s.overworld().getBlockState(new BlockPos(5, 100, 7)).getValue(BlockStateProperties.POWERED)));
		server.runCommand("setblock 3 99 3 farmland");
		server.runCommand("tp " + name + " 3.5 103 3.5");
		ctx.waitTicks(40);
		check("vanish: farmland is not trampled", server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(3, 99, 3)).is(Blocks.FARMLAND)));

		connection.close();
		check("vanish: a vanished player's leave is not public", presence.stream().noneMatch(e -> e.startsWith("LEAVE")));
		clearChat(ctx);
		connection = reconnect(server);
		check("vanish: still vanished after rejoining", server.computeOnServer(s -> MejtaUtils.vanish().isHidden(me)));
		check("vanish: no join message and no public join", !chatHas(ctx, "joined the game") && presence.stream().noneMatch(e -> e.startsWith("JOIN")));

		setBool(UtilsSettings.VANISH_FAKE_PRESENCE, true);
		resendCommands(server);
		chat(ctx, "/vanish fakejoin");
		check("vanish: fake join makes the player visible", waitUntil(ctx, () -> server.computeOnServer(s -> !MejtaUtils.vanish().isHidden(me))));
		check("vanish: fake join announced to presence listeners as not real", presence.contains("JOIN:false"));
		check("vanish: entity tracker shows the player again", server.computeOnServer(s -> player(s, me).broadcastToPlayer(FakePlayer.get(s.overworld()))));
		ctx.waitTicks(100);
		check("vanish: mobs can target the player again (test sanity)", server.computeOnServer(s ->
				monsters(s).stream().anyMatch(m -> m.getTarget() == player(s, me))));
		setBool(UtilsSettings.VANISH_FAKE_PRESENCE, false);
		server.runCommand("kill @e[type=husk]");
		server.runCommand("kill @e[type=item]");
		return connection;
	}

	// ---------------------------------------------------------------- freeze

	private TestServerConnection freezeChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, TestServerConnection connection) {
		UUID me = id(ctx);
		String name = name(ctx);
		server.runCommand("tp " + name + " 5 100 5");
		server.runOnServer(s -> player(s, me).getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2)));
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		ctx.waitTicks(10);
		server.runCommand("freeze " + name + " test freeze");
		check("freeze: frozen by console", waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.freeze().isFrozen(me))));
		double[] anchor = server.computeOnServer(s -> new double[] {player(s, me).getX(), player(s, me).getZ()});
		ctx.getInput().holdKeyFor(options -> options.keyUp, 30);
		ctx.waitTicks(10);
		check("freeze: walking does not move the player", server.computeOnServer(s -> Math.hypot(player(s, me).getX() - anchor[0],
				player(s, me).getZ() - anchor[1]) < 0.5));
		ctx.getInput().pressKey(options -> options.keyDrop);
		ctx.waitTicks(10);
		check("freeze: the drop key drops nothing", server.computeOnServer(s -> player(s, me).getInventory().countItem(Items.DIAMOND) == 2
				&& nearbyItems(player(s, me)).isEmpty()));
		clearChat(ctx);
		chat(ctx, "/vanish");
		check("freeze: commands outside the allowed list are refused", chatContains(ctx, "You cannot use that command while frozen")
				&& server.computeOnServer(s -> !MejtaUtils.vanish().isHidden(me)));
		clearChat(ctx);
		chat(ctx, "/msg " + name + " still here");
		check("freeze: allowed commands still work", chatContains(ctx, "still here"));

		connection.close();
		check("freeze: logout reported to staff (not punished)", waitUntil(ctx, () -> !notifications(r ->
				r.get("channel").getAsString().equals("mejtautils.freeze") && r.get("title").getAsString().contains("logged out while frozen")
						&& r.get("message").getAsString().contains("not proof")).isEmpty()));
		connection = reconnect(server);
		check("freeze: still frozen after rejoining", server.computeOnServer(s -> MejtaUtils.freeze().isFrozen(me)));
		server.runCommand("unfreeze " + name);
		check("freeze: unfrozen", waitUntil(ctx, () -> server.computeOnServer(s -> !MejtaUtils.freeze().isFrozen(me))));
		check("freeze: flight given for hovering is taken back", server.computeOnServer(s -> !player(s, me).getAbilities().mayfly));
		check("freeze: start and stop audited", recentAudit("freeze.start") != null && recentAudit("freeze.stop") != null);
		return connection;
	}

	// ---------------------------------------------------------------- staff mode

	private TestServerConnection staffModeChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, TestServerConnection connection) {
		UUID me = id(ctx);
		givePlayerKit(server, me);
		List<ItemStack> before = server.computeOnServer(s -> inventoryCopy(player(s, me)));

		chat(ctx, "/staffmode");
		check("staff mode: on", waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me))));
		check("staff mode: only staff tools in the inventory", server.computeOnServer(s -> onlyTools(player(s, me))
				&& countTools(player(s, me)) == 3));
		check("staff mode: vanished, adventure mode", server.computeOnServer(s -> MejtaUtils.vanish().isHidden(me)
				&& player(s, me).gameMode.getGameModeForPlayer() == GameType.ADVENTURE));
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		ctx.waitTicks(3);
		ctx.getInput().pressKey(options -> options.keyDrop);
		ctx.waitTicks(10);
		check("staff mode: tools cannot be dropped", server.computeOnServer(s -> countTools(player(s, me)) == 3 && nearbyItems(player(s, me)).isEmpty()));
		chat(ctx, "/staffmode off");
		check("staff mode: off", waitUntil(ctx, () -> server.computeOnServer(s -> !MejtaUtils.staffMode().isActive(me))));
		check("staff mode: survival inventory restored exactly", server.computeOnServer(s -> sameInventory(player(s, me), before)));
		check("staff mode: visible and in survival again", server.computeOnServer(s -> !MejtaUtils.vanish().isHidden(me)
				&& player(s, me).gameMode.getGameModeForPlayer() == GameType.SURVIVAL));

		// Disconnect while ACTIVE: staff mode continues, then restores.
		chat(ctx, "/staffmode");
		waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me)));
		connection.close();
		connection = reconnect(server);
		check("staff recovery (active): resumed with tools", server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me) && onlyTools(player(s, me))));
		chat(ctx, "/staffmode off");
		check("staff recovery (active): restored afterwards", waitUntil(ctx, () -> server.computeOnServer(s ->
				!MejtaUtils.staffMode().isActive(me) && sameInventory(player(s, me), before))));

		// Interrupted while RESTORING: the replacement is repeated, never doubled.
		chat(ctx, "/staffmode");
		waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me)));
		setSessionState(server, me, "RESTORING");
		connection.close();
		connection = reconnect(server);
		check("staff recovery (restoring): restored on join", server.computeOnServer(s -> !MejtaUtils.staffMode().isActive(me)
				&& sameInventory(player(s, me), before)));

		// Interrupted while ENTERING, before anything changed: the session is dropped, nothing lost.
		server.runOnServer(s -> {
			ServerPlayer p = player(s, me);
			JsonObject snapshot = StaffSnapshot.capture(p);
			JsonObject session = new JsonObject();
			session.addProperty("format", 1);
			session.addProperty("session", UUID.randomUUID().toString());
			session.addProperty("state", "ENTERING");
			session.addProperty("uuid", me.toString());
			session.addProperty("name", p.getGameProfile().name());
			session.addProperty("started", Instant.now().toString());
			session.addProperty("vanished_by_staffmode", false);
			session.add("snapshot", snapshot);
			session.addProperty("hash", StaffSnapshot.hash(snapshot));
			MejtaCoreAPI.dataStore(MejtaUtils.ID).writeNow(DataStore.playerKey("staffmode", me), session);
			MejtaUtils.staffMode().reload(me);
		});
		connection.close();
		connection = reconnect(server);
		check("staff recovery (entering): session dropped, inventory untouched", server.computeOnServer(s ->
				!MejtaUtils.staffMode().isActive(me) && sameInventory(player(s, me), before)));

		// Ambiguous: active session but a foreign item in the inventory. Nothing changes until confirmed.
		chat(ctx, "/staffmode");
		waitUntil(ctx, () -> server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me)));
		server.runOnServer(s -> player(s, me).getInventory().setItem(30, new ItemStack(Items.DIRT, 1)));
		connection.close();
		connection = reconnect(server);
		check("staff recovery (ambiguous): asks instead of guessing", server.computeOnServer(s -> MejtaUtils.staffMode().isActive(me)
				&& MejtaUtils.staffMode().confirmationNeeded(me).isPresent() && player(s, me).getInventory().getItem(30).is(Items.DIRT)));
		chat(ctx, "/staffmode restore confirm");
		check("staff recovery (ambiguous): restored after confirmation", waitUntil(ctx, () -> server.computeOnServer(s ->
				!MejtaUtils.staffMode().isActive(me) && sameInventory(player(s, me), before))));

		// A tool outside staff mode is removed on join.
		server.runOnServer(s -> player(s, me).getInventory().setItem(35, StaffTools.create(StaffTools.Kind.COMPASS)));
		connection.close();
		connection = reconnect(server);
		check("staff tools: removed outside staff mode", server.computeOnServer(s -> countTools(player(s, me)) == 0));
		check("staff mode: enter, exit and recoveries audited", recentAudit("staffmode.enter") != null && recentAudit("staffmode.exit") != null
				&& recentAudit("staffmode.recover") != null);
		return connection;
	}

	private static void givePlayerKit(TestDedicatedServerContext server, UUID me) {
		server.runOnServer(s -> {
			ServerPlayer p = player(s, me);
			p.getInventory().clearContent();
			p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			p.getInventory().setItem(5, new ItemStack(Items.BREAD, 12));
			p.getInventory().setItem(39, new ItemStack(Items.IRON_HELMET));
			p.getInventory().setItem(40, new ItemStack(Items.SHIELD));
		});
	}

	private static void setSessionState(TestDedicatedServerContext server, UUID me, String state) {
		server.runOnServer(s -> {
			DataStore store = MejtaCoreAPI.dataStore(MejtaUtils.ID);
			JsonObject session = store.read(DataStore.playerKey("staffmode", me)).orElseThrow();
			session.addProperty("state", state);
			store.writeNow(DataStore.playerKey("staffmode", me), session);
			MejtaUtils.staffMode().reload(me);
		});
	}

	// ---------------------------------------------------------------- helpers

	private static TestServerConnection reconnect(TestDedicatedServerContext server) {
		TestServerConnection connection = server.connect();
		connection.getClientLevel().waitForChunksRender();
		return connection;
	}

	private static ServerPlayer player(MinecraftServer s, UUID id) {
		return s.getPlayerList().getPlayer(id);
	}

	private static List<ItemEntity> nearbyItems(ServerPlayer p) {
		return p.level().getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(6));
	}

	private static List<Monster> monsters(MinecraftServer s) {
		return s.overworld().getEntitiesOfClass(Monster.class, new AABB(-20, 80, -20, 30, 120, 30));
	}

	private static List<ItemStack> inventoryCopy(ServerPlayer p) {
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			out.add(p.getInventory().getItem(i).copy());
		}
		return out;
	}

	private static boolean sameInventory(ServerPlayer p, List<ItemStack> expected) {
		for (int i = 0; i < expected.size(); i++) {
			if (!ItemStack.matches(p.getInventory().getItem(i), expected.get(i))) {
				LOG.error("[MejtaUtilsTest] slot {} is {}, expected {}", i, p.getInventory().getItem(i), expected.get(i));
				return false;
			}
		}
		return true;
	}

	private static boolean onlyTools(ServerPlayer p) {
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack stack = p.getInventory().getItem(i);
			if (!stack.isEmpty() && !StaffTools.isTool(stack)) {
				return false;
			}
		}
		return true;
	}

	private static int countTools(ServerPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (StaffTools.isTool(p.getInventory().getItem(i))) {
				n++;
			}
		}
		return n;
	}

	private static JsonObject recentAudit(String action) {
		List<JsonObject> recent = AuditTrail.get().recent(200).join();
		return recent == null ? null : recent.stream().filter(r -> r.get("action").getAsString().equals(action)).findFirst().orElse(null);
	}

	private static List<JsonObject> notifications(java.util.function.Predicate<JsonObject> filter) {
		List<JsonObject> found = MejtaCoreAPI.recordLog("mejtacore", "notifications", com.mejta.mejtacore.api.storage.RecordLogOptions.DEFAULT)
				.readRecent(200, filter).join();
		return found == null ? List.of() : found;
	}

	/** Overrides (or restores) the required level of the nodes the test player needs. */
	@SuppressWarnings("unchecked")
	private static void grant(TestDedicatedServerContext server, boolean on) {
		ConfigHandle perms = MejtaCore.configs().find("mejtacore.permissions").orElseThrow();
		for (MejtaPermission permission : GRANTED) {
			Setting<PermissionLevel> setting = (Setting<PermissionLevel>) perms.spec().setting(permission.node()).orElseThrow();
			if (on) {
				perms.set(setting, PermissionLevel.ALL);
			} else {
				perms.reset(setting);
			}
		}
		perms.save();
		resendCommands(server);
	}

	private static void setBool(Setting<Boolean> setting, boolean value) {
		MejtaUtils.config().set(setting, value);
		MejtaUtils.config().save();
	}

	private static void resendCommands(TestDedicatedServerContext server) {
		server.runOnServer(s -> {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				s.getCommands().sendCommands(p);
			}
		});
	}

	private static UUID id(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.getUUID());
	}

	private static String name(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
	}

	private static boolean waitForScreen(ClientGameTestContext ctx, String title) {
		return waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?> s
				&& s.getTitle().getString().equals(title)));
	}

	private static void clickSlot(ClientGameTestContext ctx, int slot, int button) {
		hoverSlot(ctx, slot);
		ctx.getInput().pressMouse(button);
		ctx.waitTicks(3);
	}

	private static void hoverSlot(ClientGameTestContext ctx, int slot) {
		double[] pos = ctx.computeOnClient(mc -> {
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
			var s = screen.getMenu().getSlot(slot);
			double factor = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {(intField(screen, "leftPos") + s.x + 8) * factor, (intField(screen, "topPos") + s.y + 8) * factor};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTicks(1);
	}

	private static int intField(Object target, String name) {
		try {
			Field field = AbstractContainerScreen.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.getInt(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read " + name, e);
		}
	}

	private static void chat(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(command);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(5);
	}

	private static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
	}

	private static boolean chatHas(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text)));
	}

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = waitUntil(ctx, () -> chatHas(ctx, text));
		if (!found) {
			LOG.error("[MejtaUtilsTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaUtilsClientGameTest::chatLines));
		}
		return found;
	}

	@SuppressWarnings("unchecked")
	private static List<String> chatLines(Minecraft mc) {
		try {
			Field field = ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			List<GuiMessage> messages = (List<GuiMessage>) field.get(mc.gui.getChat());
			return messages.stream().map(m -> m.content().getString()).toList();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read chat", e);
		}
	}

	private static boolean waitUntil(ClientGameTestContext ctx, BooleanSupplier condition) {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			ctx.waitTick();
		}
		return condition.getAsBoolean();
	}

	private void check(String name, boolean ok) {
		if (ok) {
			passed++;
			LOG.info("[MejtaUtilsTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaUtilsTest] FAIL {}", name);
		}
	}
}
