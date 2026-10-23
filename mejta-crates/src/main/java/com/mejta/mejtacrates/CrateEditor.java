package com.mejta.mejtacrates;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.Reward;

/** Changes to crates, from {@code /crates admin} and the preview menu. Each one is saved and audited ({@code crate.edit}). */
public final class CrateEditor {
	private CrateEditor() {
	}

	/** Saves {@code changed} in place of its crate and records {@code what}. */
	public static void save(AuditActor actor, Consumer<Component> reply, Crate changed, String what) {
		MejtaCrates.service().store().save(changed);
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCrates.ID, "crate.edit").actor(actor).target("crate " + changed.id())
				.outcome(AuditOutcome.SUCCESS).detail("change", what).build());
		reply.accept(Component.literal(changed.name() + ": " + what + ".").withStyle(ChatFormatting.GREEN));
	}

	public static boolean create(AuditActor actor, Consumer<Component> reply, String id, String name) {
		if (!Crate.isValidId(id)) {
			reply.accept(Component.literal("Crate IDs use lowercase letters, digits, _ and -, at most 32 characters.").withStyle(ChatFormatting.RED));
			return false;
		}
		if (MejtaCrates.service().store().crate(id).isPresent()) {
			reply.accept(Component.literal("A crate with the ID " + id + " exists already.").withStyle(ChatFormatting.RED));
			return false;
		}
		save(actor, reply, Crate.create(id, name), "created");
		return true;
	}

	public static void delete(AuditActor actor, Consumer<Component> reply, Crate crate) {
		MejtaCrates.service().store().delete(crate.id());
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCrates.ID, "crate.delete").actor(actor).target("crate " + crate.id())
				.outcome(AuditOutcome.SUCCESS).detail("rewards", crate.rewards().size()).build(), CratePermissions.ALERTS);
		reply.accept(Component.literal("Deleted " + crate.name() + ". Players' keys of it stay, and work again if you create a crate with the same ID.")
				.withStyle(ChatFormatting.GREEN));
	}

	public static void addReward(AuditActor actor, Consumer<Component> reply, Crate crate, Reward reward, String what) {
		if (crate.rewards().size() >= Crate.MAX_REWARDS) {
			reply.accept(Component.literal("A crate holds at most " + Crate.MAX_REWARDS + " rewards.").withStyle(ChatFormatting.RED));
			return;
		}
		save(actor, reply, crate.withReward(reward), "added reward #" + (crate.rewards().size() + 1) + " (" + what + ", weight " + reward.weight() + ")");
	}

	/** @param index 0-based */
	public static Optional<Reward> reward(Consumer<Component> reply, Crate crate, int index) {
		if (index < 0 || index >= crate.rewards().size()) {
			reply.accept(Component.literal(crate.name() + " has no reward #" + (index + 1) + "; see /crates admin info " + crate.id() + ".")
					.withStyle(ChatFormatting.RED));
			return Optional.empty();
		}
		return Optional.of(crate.rewards().get(index));
	}

	/** @param index 0-based */
	public static void updateReward(AuditActor actor, Consumer<Component> reply, Crate crate, int index, UnaryOperator<Reward> change, String what) {
		reward(reply, crate, index).ifPresent(r -> save(actor, reply, crate.withReward(index, change.apply(r)), "reward #" + (index + 1) + ": " + what));
	}

	/** @param index 0-based */
	public static void removeReward(AuditActor actor, Consumer<Component> reply, Crate crate, int index) {
		reward(reply, crate, index).ifPresent(r -> save(actor, reply, crate.withoutReward(index), "removed reward #" + (index + 1)));
	}
}
