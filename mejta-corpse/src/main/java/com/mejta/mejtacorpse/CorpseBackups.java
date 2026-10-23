package com.mejta.mejtacorpse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonElement;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacorpse.data.BackupStore;
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.data.CorpseStore;
import com.mejta.mejtacorpse.data.DeathBackup;
import com.mejta.mejtacorpse.util.ItemJson;

/**
 * Death backups: a copy of each death's items, kept after the corpse is gone, so staff can give them back.
 * Server thread only.
 *
 * <p><b>Restoring without duplication.</b> A restore creates items, so it is guarded:
 * <ul>
 * <li>only holders of {@link CorpsePermissions#RESTORE}, and for their own deaths only with
 * {@link CorpsePermissions#RESTORE_SELF};</li>
 * <li>only once the corpse is gone (while it exists, its items are still in it);</li>
 * <li>each backup only once: it is marked restored on disk before any item is handed out, and if that
 * write fails nothing is handed out;</li>
 * <li>only after a confirmation that shows what happened to the corpse, with a warning when the owner
 * already took items back from it;</li>
 * <li>every restore is audited, announced to staff and logged.</li>
 * </ul>
 */
public final class CorpseBackups {
	public enum Outcome {
		NOT_FOUND,
		ALREADY_RESTORED,
		CORPSE_EXISTS,
		NOT_ALLOWED,
		OWNER_UNAVAILABLE,
		NO_ITEMS,
		NEEDS_CONFIRM,
		SAVE_FAILED,
		RESTORED
	}

	private static final long DAY = 86_400_000L;

	private final ConfigHandle config;
	private final BackupStore store;
	private final CorpseStore corpses;

	public CorpseBackups(ConfigHandle config, BackupStore store, CorpseStore corpses) {
		this.config = config;
		this.store = store;
		this.corpses = corpses;
	}

	public BackupStore store() {
		return store;
	}

	/** Backs up a corpse that has just become active, then drops the owner's backups beyond the limits. */
	void take(CorpseRecord corpse) {
		int keep = config.get(CorpseSettings.BACKUP_PER_PLAYER);
		if (keep <= 0 || corpse.isEmpty()) {
			return;
		}
		try {
			store.saveNow(DeathBackup.of(corpse));
		} catch (IOException e) {
			// The corpse itself is safe; only the copy is missing.
			MejtaCorpse.log().warn("Could not back up corpse {} of {}", corpse.id(), corpse.ownerName(), e);
			return;
		}
		store.prune(corpse.owner(), keep, config.get(CorpseSettings.BACKUP_KEEP_DAYS) * DAY, System.currentTimeMillis());
	}

	/** At join: drops the player's backups that are too old. */
	void pruneOld(UUID owner) {
		int days = config.get(CorpseSettings.BACKUP_KEEP_DAYS);
		if (days > 0) {
			store.prune(owner, Integer.MAX_VALUE, days * DAY, System.currentTimeMillis());
		}
	}

	/** Adds what happened to a corpse to its backup's history, if it has one. */
	void record(CorpseRecord corpse, DeathBackup.Kind kind, String actor, int count, String detail) {
		store.of(corpse).ifPresent(b -> store.saveLater(b.withEvent(new DeathBackup.Event(System.currentTimeMillis(), kind, actor, count, detail))));
	}

	// ---------------------------------------------------------------- restore

	/**
	 * Gives the items of the owner's backup {@code id} back to them. Without {@code confirm}, only shows what
	 * would be restored and asks for confirmation. Every refusal is explained to {@code source}.
	 */
	public Outcome restore(CommandSourceStack source, ServerPlayer owner, String id, boolean confirm) {
		String name = owner.getGameProfile().name();
		Optional<DeathBackup> found = store.find(owner.getUUID(), id);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal(name + " has no death backup with ID " + id + ". See /corpse backups " + name + "."));
			return Outcome.NOT_FOUND;
		}
		DeathBackup backup = found.get();
		Optional<DeathBackup.Event> done = backup.restored();
		if (done.isPresent()) {
			source.sendFailure(Component.literal("Backup " + id + " was already restored by " + done.get().actor() + ", "
					+ CorpseManager.minutes(System.currentTimeMillis() - done.get().millis()) + " ago. Each backup can be restored once."));
			return Outcome.ALREADY_RESTORED;
		}
		if (corpses.get(backup.id()).isPresent()) {
			source.sendFailure(Component.literal("The corpse of this death still exists, with its items: open it with /corpse open " + id + "."));
			return Outcome.CORPSE_EXISTS;
		}
		ServerPlayer self = source.getPlayer();
		if (self != null && self.getUUID().equals(backup.owner()) && !MejtaCoreAPI.hasPermission(self, CorpsePermissions.RESTORE_SELF)) {
			source.sendFailure(Component.literal("You may not restore your own deaths. Ask another staff member."));
			return Outcome.NOT_ALLOWED;
		}
		if (owner.isRemoved() || owner.isDeadOrDying()) {
			source.sendFailure(Component.literal(name + " must be online and alive to get their items back."));
			return Outcome.OWNER_UNAVAILABLE;
		}
		Map<Integer, ItemStack> stacks = new TreeMap<>();
		int unreadable = 0;
		for (Map.Entry<Integer, JsonElement> e : backup.items().entrySet()) {
			Optional<ItemStack> stack = ItemJson.decode(source.getServer().registryAccess(), e.getValue()).filter(s -> !s.isEmpty());
			if (stack.isPresent()) {
				stacks.put(e.getKey(), stack.get());
			} else {
				unreadable++;
			}
		}
		if (stacks.isEmpty()) {
			source.sendFailure(Component.literal("None of the items in backup " + id + " can be read any more (removed mods?)."));
			return Outcome.NO_ITEMS;
		}
		int count = stacks.values().stream().mapToInt(ItemStack::getCount).sum();
		if (!confirm) {
			askToConfirm(source, backup, name, stacks.size(), count, unreadable);
			return Outcome.NEEDS_CONFIRM;
		}

		// Marked restored on disk first: a crash after this loses the restore instead of allowing a second one.
		DeathBackup marked = backup.withEvent(new DeathBackup.Event(System.currentTimeMillis(), DeathBackup.Kind.RESTORED,
				source.getTextName(), count, ""));
		try {
			store.saveNow(marked);
		} catch (IOException e) {
			MejtaCorpse.log().error("Could not mark backup {} of {} as restored; nothing was given", id, name, e);
			source.sendFailure(Component.literal("The backup could not be saved, so nothing was given. Check the disk and the server log."));
			return Outcome.SAVE_FAILED;
		}
		int dropped = give(owner, stacks);
		try {
			CorpseManager.SAVE_PLAYER.save(owner);
		} catch (RuntimeException e) {
			MejtaCorpse.log().warn("Could not save {} after restoring backup {}; the game saves them later", name, id, e);
		}

		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCorpse.ID, "corpse.restore")
				.actor(MejtaCoreAPI.actor(source))
				.target(backup.owner(), name)
				.outcome(AuditOutcome.SUCCESS)
				.detail("backup", id)
				.detail("stacks", stacks.size())
				.detail("items", count)
				.detail("owner_took_back", backup.takenByOwner())
				.detail("dropped_stacks", dropped)
				.build());
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaCorpse.ID, "mejtacorpse.restore")
				.severity(Severity.INFO)
				.title("Death backup restored")
				.message(source.getTextName() + " restored " + count + " item(s) of " + name + "'s death backup " + id + ".")
				.field("backup", id)
				.field("player", name)
				.field("staff", source.getTextName())
				.inGameAudience(CorpsePermissions.RESTORE)
				.build());
		MejtaCorpse.log().info("{} restored backup {} of {}: {} stack(s), {} item(s)", source.getTextName(), id, name, stacks.size(), count);
		source.sendSuccess(() -> Component.literal("Gave " + name + " back " + stacks.size() + " stack(s) from backup " + id
				+ (dropped > 0 ? "; " + dropped + " did not fit and dropped at their feet" : "") + "."), false);
		if (config.get(CorpseSettings.BACKUP_NOTIFY_OWNER) && self != owner) {
			owner.sendSystemMessage(Component.literal("Staff gave you back the items of your death "
					+ CorpseManager.minutes(System.currentTimeMillis() - backup.createdMillis()) + " ago"
					+ (dropped > 0 ? " (" + dropped + " stack(s) did not fit and lie at your feet)" : "") + ".").withStyle(ChatFormatting.GREEN));
		}
		return Outcome.RESTORED;
	}

	/** Each stack into its old slot if that is empty, else anywhere; what does not fit drops. Returns the stacks dropped. */
	private static int give(ServerPlayer owner, Map<Integer, ItemStack> stacks) {
		Inventory inventory = owner.getInventory();
		int dropped = 0;
		for (Map.Entry<Integer, ItemStack> e : stacks.entrySet()) {
			ItemStack stack = e.getValue();
			if (e.getKey() < inventory.getContainerSize() && inventory.getItem(e.getKey()).isEmpty()) {
				inventory.setItem(e.getKey(), stack);
				continue;
			}
			inventory.add(stack);
			if (!stack.isEmpty()) {
				owner.drop(stack, false);
				dropped++;
			}
		}
		return dropped;
	}

	private void askToConfirm(CommandSourceStack source, DeathBackup backup, String name, int stacks, int count, int unreadable) {
		source.sendSystemMessage(Component.literal("Restore " + name + "'s death backup #" + backup.id() + "?").withStyle(ChatFormatting.GOLD));
		source.sendSystemMessage(Component.literal("Died " + CorpseManager.minutes(System.currentTimeMillis() - backup.createdMillis()) + " ago at "
				+ CorpseManager.where(backup.dimension(), backup.x(), backup.y(), backup.z()) + ": " + stacks + " stack(s), " + count + " item(s).")
				.withStyle(ChatFormatting.GRAY));
		source.sendSystemMessage(Component.literal("Corpse: " + fate(backup)).withStyle(ChatFormatting.GRAY));
		int took = backup.takenByOwner();
		if (took > 0) {
			source.sendSystemMessage(Component.literal("Warning: " + name + " already took " + took
					+ " item(s) back from the corpse; restoring gives them those again.").withStyle(ChatFormatting.RED));
		}
		if (unreadable > 0) {
			source.sendSystemMessage(Component.literal(unreadable + " stack(s) can no longer be read and are left out.").withStyle(ChatFormatting.RED));
		}
		String command = "/corpse restore " + name + " " + backup.id() + " confirm";
		source.sendSystemMessage(Component.literal("[Confirm restore]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command + "\nEach backup can be restored once.")))));
	}

	/** What happened to the corpse of a backup, in a few words. */
	String fate(DeathBackup backup) {
		if (corpses.get(backup.id()).isPresent()) {
			return "still there";
		}
		List<String> parts = new ArrayList<>();
		for (DeathBackup.Event e : backup.history()) {
			switch (e.kind()) {
				case TAKEN_BY_OWNER -> parts.add("owner took " + e.count());
				case TAKEN_BY_OTHER -> parts.add(e.actor() + " took " + e.count());
				case EXPIRED -> parts.add("expired (" + e.detail() + ")");
				case RESTORED -> {
				}
			}
		}
		return parts.isEmpty() ? "gone" : "gone; " + String.join(", ", parts);
	}

	/** One line of {@code /corpse backups}, with a [Restore] button while it can be restored. */
	MutableComponent describe(DeathBackup b, String ownerName) {
		MutableComponent line = Component.literal("#" + b.id() + "  ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(CorpseManager.minutes(System.currentTimeMillis() - b.createdMillis()) + " ago").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("  " + CorpseManager.where(b.dimension(), b.x(), b.y(), b.z()) + "  " + b.items().size() + " stack(s)  corpse "
						+ fate(b)).withStyle(ChatFormatting.GRAY));
		Optional<DeathBackup.Event> restored = b.restored();
		if (restored.isPresent()) {
			line.append(Component.literal("  restored by " + restored.get().actor()).withStyle(ChatFormatting.DARK_GRAY));
		} else if (corpses.get(b.id()).isEmpty()) {
			String command = "/corpse restore " + ownerName + " " + b.id();
			line.append(Component.literal(" ")).append(Component.literal("[Restore]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.RunCommand(command))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(command + "\nShows the items first and asks to confirm.")))));
		}
		return line;
	}
}
