package com.mejta.mejtasmp.scoreboard;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Each player's own sidebar, sent as packets only to them: the server's scoreboard is not changed, and
 * players see different sidebars. Only lines that changed are sent again. Server thread only.
 */
public final class Sidebar {
	static final String OBJECTIVE = "mejtasmp_sidebar";

	private record Shown(Component title, List<String> lines) {
	}

	/** Objectives in packets need a scoreboard; this one is never filled. */
	private final Scoreboard packetBoard = new Scoreboard();
	private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();

	/** Shows {@code lines} under {@code title}; an empty list hides the sidebar. */
	public void show(ServerPlayer player, Component title, List<String> lines) {
		if (lines.isEmpty()) {
			hide(player);
			return;
		}
		Shown before = shown.get(player.getUUID());
		Objective objective = objective(title);
		if (before == null) {
			player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
			player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
		} else if (!before.title().equals(title)) {
			player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE));
		}
		List<String> old = before == null ? List.of() : before.lines();
		for (int i = 0; i < lines.size(); i++) {
			// The score orders the lines (highest on top) and is hidden by the blank number format.
			if (i >= old.size() || !old.get(i).equals(lines.get(i)) || old.size() != lines.size()) {
				player.connection.send(new ClientboundSetScorePacket(holder(i), OBJECTIVE, lines.size() - i,
						Optional.of(Component.literal(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
			}
		}
		for (int i = lines.size(); i < old.size(); i++) {
			player.connection.send(new ClientboundResetScorePacket(holder(i), OBJECTIVE));
		}
		shown.put(player.getUUID(), new Shown(title, List.copyOf(lines)));
	}

	/** Removes MejtaSMP's sidebar and gives back the server's own, if it has one. */
	public void hide(ServerPlayer player) {
		Shown before = shown.remove(player.getUUID());
		if (before == null) {
			return;
		}
		player.connection.send(new ClientboundSetObjectivePacket(objective(before.title()), ClientboundSetObjectivePacket.METHOD_REMOVE));
		Objective server = player.level().getServer().getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		if (server != null) {
			player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, server));
		}
	}

	public boolean showing(UUID player) {
		return shown.containsKey(player);
	}

	public void forget(UUID player) {
		shown.remove(player);
	}

	public void clear() {
		shown.clear();
	}

	private Objective objective(Component title) {
		return new Objective(packetBoard, OBJECTIVE, ObjectiveCriteria.DUMMY, title, ObjectiveCriteria.RenderType.INTEGER, false,
				BlankFormat.INSTANCE);
	}

	private static String holder(int line) {
		return "mejtasmp_line_" + line;
	}
}
