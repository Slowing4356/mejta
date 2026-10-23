package com.mejta.mejtasmp.test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;

/**
 * End-to-end check with a real client on a dedicated server: the sidebar arrives and shows the player's
 * stats, a big pile of dropped items shows its size and is picked up exactly once by walking over it,
 * experience is taken at once, and {@code /sb} and {@code /leaderboard} open. Screenshots are saved in the
 * run directory's {@code screenshots/} folder. Every check logs {@code [MejtaSmpTest] PASS/FAIL}.
 */
public final class MejtaSmpClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaSmpTest");
	private static final double X = 5.5, Y = 100, Z = 5.5;

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// These checks click the chest versions of the menus; MejtaCore's own client test covers the screens.
		ctx.runOnClient(mc -> MejtaCore.coreConfig().set(CoreSettings.SERVER_MENU_SCREENS, false));
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			try (TestServerConnection connection = server.connect()) {
				connection.getClientLevel().waitForChunksRender();
				server.runCommand("fill -4 99 -4 30 99 30 stone");
				server.runCommand("fill -4 100 -4 30 104 30 air");
				String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
				UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
				server.runCommand("gamemode survival " + me);
				server.runCommand("tp " + me + " " + X + " " + Y + " " + (Z - 4) + " 0 30");
				sidebar(ctx);
				pile(ctx, server, me, id);
				xp(ctx, server, me, id);
				menus(ctx);
			}
		}
		LOG.info("[MejtaSmpTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaSMP client tests failed: " + String.join("; ", failures));
		}
	}

	private void sidebar(ClientGameTestContext ctx) {
		check("the sidebar arrives with the player's stats", waitUntil(ctx, () -> ctx.computeOnClient(mc -> {
			Objective sidebar = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
			return sidebar != null && sidebar.getName().equals("mejtasmp_sidebar")
					&& mc.level.getScoreboard().listPlayerScores(sidebar).stream().anyMatch(e -> e.display() != null && e.display().getString().contains("Kills"));
		}), 200));
	}

	private void pile(ClientGameTestContext ctx, TestDedicatedServerContext server, String me, UUID id) {
		// 300 cobblestone as five drops: they merge into one labelled pile.
		server.runOnServer(s -> {
			player(s, id).getInventory().clearContent();
			for (int i = 0; i < 5; i++) {
				ItemEntity e = new ItemEntity(s.overworld(), X + (i % 2) * 0.6, Y, Z + (i / 2) * 0.4, new ItemStack(Items.COBBLESTONE, 60));
				e.setDeltaMovement(0, 0, 0);
				e.setPickUpDelay(1000);
				s.overworld().addFreshEntity(e);
			}
		});
		check("the drops merge into one pile labelled 300", waitUntil(ctx, () -> server.computeOnServer(s -> {
			List<ItemEntity> piles = s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(X - 3, Y - 1, Z - 3, X + 3, Y + 2, Z + 3));
			return piles.size() == 1 && piles.get(0).hasCustomName() && piles.get(0).getCustomName().getString().contains("300");
		}), 200));
		check("the client sees the label", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(ItemEntity.class,
				new AABB(X - 3, Y - 1, Z - 3, X + 3, Y + 2, Z + 3)).stream().anyMatch(e -> e.getCustomName() != null
						&& e.getCustomName().getString().contains("300"))), 100));
		ctx.runOnClient(mc -> mc.getToastManager().clear());
		ctx.waitTicks(5);
		ctx.takeScreenshot("smp-1-pile-and-sidebar");

		// Walk over it: every item exactly once, nothing left behind.
		server.runOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(X - 3, Y - 1, Z - 3, X + 3, Y + 2, Z + 3))
				.forEach(e -> e.setPickUpDelay(0)));
		server.runCommand("tp " + me + " " + X + " " + Y + " " + Z);
		check("walking over the pile picks up exactly 300", waitUntil(ctx, () -> server.computeOnServer(s ->
				player(s, id).getInventory().countItem(Items.COBBLESTONE) == 300
						&& s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(X - 3, Y - 1, Z - 3, X + 3, Y + 2, Z + 3)).isEmpty()), 200));
		ctx.waitTicks(10);
		check("never more than 300", server.computeOnServer(s -> player(s, id).getInventory().countItem(Items.COBBLESTONE) == 300));
	}

	private void xp(ClientGameTestContext ctx, TestDedicatedServerContext server, String me, UUID id) {
		server.runCommand("xp set " + me + " 0 points");
		server.runCommand("xp set " + me + " 0 levels");
		server.runOnServer(s -> {
			for (int i = 0; i < 8; i++) {
				s.overworld().addFreshEntity(new ExperienceOrb(s.overworld(), X + 18, Y, Z + 18 + i * 0.1, 5));
			}
		});
		ctx.waitTicks(30);
		check("eight orbs clump into one", server.computeOnServer(s ->
				s.overworld().getEntitiesOfClass(ExperienceOrb.class, new AABB(X + 14, Y - 1, Z + 14, X + 22, Y + 2, Z + 22)).size() == 1));
		server.runCommand("tp " + me + " " + (X + 18) + " " + Y + " " + (Z + 18.3));
		check("all 40 experience is taken", waitUntil(ctx, () -> server.computeOnServer(s -> player(s, id).totalExperience == 40), 100));
	}

	private void menus(ClientGameTestContext ctx) {
		chat(ctx, "/sb");
		check("/sb opens", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?> s
				&& s.getTitle().getString().equals("Sidebar")), 100));
		ctx.getInput().setCursorPos(0, 0);
		ctx.takeScreenshot("smp-2-sidebar-menu");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(5);
		chat(ctx, "/leaderboard");
		check("/leaderboard opens", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?> s
				&& s.getTitle().getString().startsWith("Leaderboard")), 100));
		ctx.takeScreenshot("smp-3-leaderboard");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(5);
	}

	// ---------------------------------------------------------------- helpers

	private static ServerPlayer player(MinecraftServer s, UUID id) {
		return s.getPlayerList().getPlayer(id);
	}

	private static void chat(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(command);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(5);
	}

	private static boolean waitUntil(ClientGameTestContext ctx, BooleanSupplier condition, int ticks) {
		for (int i = 0; i < ticks; i++) {
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
			LOG.info("[MejtaSmpTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaSmpTest] FAIL {}", name);
		}
	}
}
