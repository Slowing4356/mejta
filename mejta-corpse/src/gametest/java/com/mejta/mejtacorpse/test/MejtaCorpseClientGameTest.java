package com.mejta.mejtacorpse.test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacorpse.MejtaCorpse;
import com.mejta.mejtacorpse.data.CorpseRecord;

/**
 * End-to-end check with a real client on a dedicated server: a real player dies, respawns, walks back,
 * right-clicks the corpse, takes items with real clicks and uses Take all. Screenshots of the corpse and
 * its menu are saved in the run directory's {@code screenshots/} folder for a look by eye. Every check
 * logs {@code [MejtaCorpseTest] PASS/FAIL}.
 */
public final class MejtaCorpseClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaCorpseTest");
	/** Where the player dies: the corpse lies here, on the stone floor at y 99. */
	private static final double X = 5.5, Y = 100, Z = 5.5;

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			try (TestServerConnection connection = server.connect()) {
				connection.getClientLevel().waitForChunksRender();
				server.runCommand("fill -4 99 -4 16 99 16 stone");
				server.runCommand("fill -4 100 -4 16 104 16 air");
				deathAndCorpse(ctx, server);
				openAndTake(ctx, server);
			}
		}
		LOG.info("[MejtaCorpseTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaCorpse client tests failed: " + String.join("; ", failures));
		}
	}

	private void deathAndCorpse(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		String me = name(ctx);
		UUID id = id(ctx);
		server.runCommand("gamemode survival " + me);
		server.runCommand("tp " + me + " " + X + " " + Y + " " + Z + " 0 0");
		server.runOnServer(s -> {
			Inventory inv = player(s, id).getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			inv.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
			inv.setItem(5, new ItemStack(Items.BREAD, 16));
			inv.setItem(9, new ItemStack(Items.OAK_LOG, 32));
			inv.setItem(20, new ItemStack(Items.IRON_INGOT, 12));
			inv.setItem(35, new ItemStack(Items.TORCH, 40));
			inv.setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
			inv.setItem(37, new ItemStack(Items.DIAMOND_LEGGINGS));
			inv.setItem(38, new ItemStack(Items.DIAMOND_CHESTPLATE));
			inv.setItem(39, new ItemStack(Items.DIAMOND_HELMET));
			inv.setItem(40, new ItemStack(Items.SHIELD));
		});
		ctx.waitTicks(10);
		server.runCommand("kill " + me);
		ctx.waitForScreen(DeathScreen.class);
		ctx.takeScreenshot("corpse-1-death-screen");
		check("death: chat names the corpse's place", chatContains(ctx, "Your items are in your corpse at"));
		check("death: one active corpse with all 11 stacks", server.computeOnServer(s -> corpse(id)
				.filter(r -> r.state() == CorpseRecord.State.ACTIVE && r.items().size() == 11).isPresent()));
		ctx.runOnClient(mc -> {
			mc.player.respawn();
			mc.setScreen(null);
		});
		check("respawn: the inventory is empty", waitUntil(ctx, () -> server.computeOnServer(s ->
				player(s, id).isAlive() && player(s, id).getInventory().isEmpty())));
		check("death: no items were dropped", server.computeOnServer(s ->
				player(s, id).level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
						new net.minecraft.world.phys.AABB(-10, 90, -10, 20, 110, 20)).isEmpty()));

		// Back to the corpse, looking at it from the front and the side.
		lookAtCorpse(ctx, server, me, X, Z + 3);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-2-front");
		lookAtCorpse(ctx, server, me, X + 3, Z + 1);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-3-side");
		lookAtCorpse(ctx, server, me, X - 4, Z - 4);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-4-far");
	}

	private void openAndTake(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		String me = name(ctx);
		UUID id = id(ctx);
		lookAtCorpse(ctx, server, me, X, Z + 3);
		ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTicks(5);
		check("attack: the corpse is unharmed", server.computeOnServer(s -> corpse(id).isPresent()));

		ctx.getInput().pressKey(options -> options.keyUse);
		check("right-click: the corpse's menu opens", waitForScreen(ctx, me + "'s corpse"));
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(2);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-5-menu");
		hoverSlot(ctx, 36);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-6-helmet-tooltip");
		hoverSlot(ctx, 49);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-7-take-all-tooltip");

		// Shift-click the oak logs (inventory slot 9 = menu slot 0) into the player's inventory.
		shiftClickSlot(ctx, 0);
		check("shift-click: the logs move once", waitUntil(ctx, () -> server.computeOnServer(s ->
				player(s, id).getInventory().countItem(Items.OAK_LOG) == 32
						&& corpse(id).map(r -> !r.items().containsKey(9)).orElse(false))));
		// Pick up the torches and try to put them back: they must stay with the player.
		clickSlot(ctx, 26, 0);
		clickSlot(ctx, 26, 0);
		clickSlot(ctx, 30, 0);
		check("pick-up: the torches cannot go back in", server.computeOnServer(s -> {
			ServerPlayer p = player(s, id);
			int torches = p.getInventory().countItem(Items.TORCH) + (p.containerMenu.getCarried().is(Items.TORCH) ? p.containerMenu.getCarried().getCount() : 0);
			return torches == 40 && corpse(id).map(r -> !r.items().containsKey(35)).orElse(false);
		}));
		// Put any carried stack down in the player's own inventory before Take all.
		clickSlot(ctx, 54 + 26, 0);

		clickSlot(ctx, 49, 0);
		check("take all: armour is worn again and the shield is in the offhand", waitUntil(ctx, () -> server.computeOnServer(s -> {
			Inventory inv = player(s, id).getInventory();
			return inv.getItem(39).is(Items.DIAMOND_HELMET) && inv.getItem(38).is(Items.DIAMOND_CHESTPLATE)
					&& inv.getItem(37).is(Items.DIAMOND_LEGGINGS) && inv.getItem(36).is(Items.DIAMOND_BOOTS) && inv.getItem(40).is(Items.SHIELD);
		})));
		check("take all: every item exactly once", server.computeOnServer(s -> {
			Inventory inv = player(s, id).getInventory();
			return inv.getItem(0).is(Items.DIAMOND_SWORD) && inv.getItem(1).getCount() == 64 && inv.countItem(Items.BREAD) == 16
					&& inv.countItem(Items.OAK_LOG) == 32 && inv.countItem(Items.IRON_INGOT) == 12 && inv.countItem(Items.TORCH) == 40
					&& inv.countItem(Items.SHIELD) == 1 && inv.countItem(Items.DIAMOND_SWORD) == 1;
		}));
		check("take all: the emptied menu closes by itself", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen == null)));
		check("close: the empty corpse is removed", waitUntil(ctx, () -> server.computeOnServer(s -> corpse(id).isEmpty()
				&& player(s, id).level().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
						new net.minecraft.world.phys.AABB(X - 2, Y - 1, Z - 2, X + 2, Y + 2, Z + 2), e -> !(e instanceof ServerPlayer)).isEmpty())));
		ctx.waitTicks(10);
		clearToasts(ctx);
		ctx.takeScreenshot("corpse-8-gone-items-back");
	}

	// ---------------------------------------------------------------- helpers

	/** Teleports the player to (x, z) and turns it towards the corpse's middle. */
	private static void lookAtCorpse(ClientGameTestContext ctx, TestDedicatedServerContext server, String me, double x, double z) {
		double dx = X - x, dz = Z - z, dy = (Y + 0.3) - (Y + 1.62);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		server.runCommand("tp " + me + " " + x + " " + Y + " " + z + " " + yaw + " " + pitch);
		ctx.waitTicks(20);
	}

	/** Advancement and chat-verification toasts would cover the screenshots. */
	private static void clearToasts(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.getToastManager().clear());
		ctx.waitTicks(2);
	}

	private static Optional<CorpseRecord> corpse(UUID owner) {
		return MejtaCorpse.manager().store().ofOwner(owner).stream().findFirst();
	}

	private static ServerPlayer player(MinecraftServer s, UUID id) {
		return s.getPlayerList().getPlayer(id);
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

	/** A real shift-click: container screens read shift from the click event's modifiers. */
	private static void shiftClickSlot(ClientGameTestContext ctx, int slot) {
		hoverSlot(ctx, slot);
		ctx.getInput().holdShift();
		ctx.runOnClient(mc -> {
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
			var s = screen.getMenu().getSlot(slot);
			MouseButtonEvent event = new MouseButtonEvent(intField(screen, "leftPos") + s.x + 8, intField(screen, "topPos") + s.y + 8,
					new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT));
			screen.mouseClicked(event, false);
			screen.mouseReleased(event);
		});
		ctx.getInput().releaseShift();
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

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = waitUntil(ctx, () -> ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text))));
		if (!found) {
			LOG.error("[MejtaCorpseTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaCorpseClientGameTest::chatLines));
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
			LOG.info("[MejtaCorpseTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaCorpseTest] FAIL {}", name);
		}
	}
}
