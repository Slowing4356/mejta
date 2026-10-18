package com.mejta.mejtautils.staffmode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.inspect.Inspect;
import com.mejta.mejtautils.inspect.StatusMenu;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * The staff-mode tools. Each carries the same custom-data marker, so they are recognised exactly by
 * component equality; anything with the marker outside an active staff mode is deleted. Every use
 * re-checks staff mode and the tool's permission.
 */
public final class StaffTools {
	public enum Kind {
		COMPASS(Items.COMPASS, "Random player", "Right-click: teleport to a random player who is not staff"),
		WAND(Items.BLAZE_ROD, "Inspection wand", "Right-click a player: inventory. Shift-right-click: status panel"),
		FREEZE_ROD(Items.PACKED_ICE, "Freeze rod", "Right-click a player twice within 3 s: freeze or unfreeze");

		final Item item;
		final String title;
		final String help;

		Kind(Item item, String title, String help) {
			this.item = item;
			this.title = title;
			this.help = help;
		}
	}

	private static final CustomData MARKER;

	static {
		CompoundTag tag = new CompoundTag();
		tag.putString("mejtautils_tool", "staffmode");
		MARKER = CustomData.of(tag);
	}

	private record PendingFreeze(UUID target, long until) {
	}

	private static final Map<UUID, PendingFreeze> FREEZE_CLICKS = new ConcurrentHashMap<>();

	private StaffTools() {
	}

	public static ItemStack create(Kind kind) {
		ItemStack stack = MenuIcons.of(kind.item, Component.literal(kind.title).withStyle(ChatFormatting.AQUA),
				List.of(MenuIcons.line(kind.help), Component.literal("Staff mode tool").withStyle(ChatFormatting.DARK_GRAY)));
		stack.set(DataComponents.CUSTOM_DATA, MARKER);
		return stack;
	}

	public static boolean isTool(ItemStack stack) {
		return !stack.isEmpty() && Objects.equals(stack.get(DataComponents.CUSTOM_DATA), MARKER);
	}

	private static Kind kindOf(ItemStack stack) {
		if (!isTool(stack)) {
			return null;
		}
		for (Kind kind : Kind.values()) {
			if (stack.is(kind.item)) {
				return kind;
			}
		}
		return null;
	}

	/** The tools staff mode gives, according to the settings. */
	public static List<ItemStack> kit() {
		List<ItemStack> out = new ArrayList<>();
		if (MejtaUtils.config().get(UtilsSettings.STAFF_COMPASS)) {
			out.add(create(Kind.COMPASS));
		}
		if (MejtaUtils.config().get(UtilsSettings.STAFF_WAND)) {
			out.add(create(Kind.WAND));
		}
		if (MejtaUtils.config().get(UtilsSettings.STAFF_FREEZE_ROD)) {
			out.add(create(Kind.FREEZE_ROD));
		}
		return out;
	}

	/** Removes tools from a container (outside staff mode); returns how many stacks were removed. */
	public static int sweep(Container container) {
		int removed = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (isTool(container.getItem(i))) {
				container.setItem(i, ItemStack.EMPTY);
				removed++;
			}
		}
		return removed;
	}

	/** Item entities carrying a tool are deleted as soon as they appear (a tool can never lie in the world). */
	public static boolean discardIfTool(Entity entity) {
		if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && isTool(item.getItem())) {
			item.discard();
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- using the tools

	public static InteractionResult useInAir(ServerPlayer player, InteractionHand hand) {
		Kind kind = kindOf(player.getItemInHand(hand));
		if (kind == null) {
			return InteractionResult.PASS;
		}
		if (!usable(player)) {
			return InteractionResult.FAIL;
		}
		if (kind == Kind.COMPASS) {
			teleportToRandomPlayer(player);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public static InteractionResult useOnEntity(ServerPlayer player, InteractionHand hand, Entity entity) {
		Kind kind = kindOf(player.getItemInHand(hand));
		if (kind == null || hand != InteractionHand.MAIN_HAND) {
			return kind == null ? InteractionResult.PASS : InteractionResult.FAIL;
		}
		if (!usable(player) || !(entity instanceof ServerPlayer target)) {
			return InteractionResult.FAIL;
		}
		switch (kind) {
			case WAND -> {
				if (player.isShiftKeyDown()) {
					StatusMenu.openFor(player, target);
				} else {
					Inspect.openInventory(player, target, false);
				}
			}
			case FREEZE_ROD -> freezeClick(player, target);
			default -> {
				return InteractionResult.PASS;
			}
		}
		return InteractionResult.SUCCESS;
	}

	private static boolean usable(ServerPlayer player) {
		if (!MejtaUtils.staffMode().isActive(player.getUUID()) || !MejtaCoreAPI.hasPermission(player, UtilsPermissions.STAFFMODE)) {
			player.sendSystemMessage(Component.literal("Staff tools only work in staff mode.").withStyle(ChatFormatting.RED));
			return false;
		}
		return true;
	}

	private static void teleportToRandomPlayer(ServerPlayer staff) {
		if (!MejtaCoreAPI.hasPermission(staff, UtilsPermissions.STAFFMODE_TELEPORT)) {
			staff.sendSystemMessage(Component.literal("You need " + UtilsPermissions.STAFFMODE_TELEPORT.node() + ".").withStyle(ChatFormatting.RED));
			return;
		}
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer p : MejtaCoreAPI.presence().publiclyOnline(staff.level().getServer())) {
			if (p != staff && !MejtaCoreAPI.hasPermission(p, UtilsPermissions.STAFFMODE) && !p.isSpectator()) {
				candidates.add(p);
			}
		}
		if (candidates.isEmpty()) {
			staff.sendSystemMessage(Component.literal("No other players online.").withStyle(ChatFormatting.YELLOW));
			return;
		}
		ServerPlayer target = candidates.get(staff.getRandom().nextInt(candidates.size()));
		staff.teleport(new TeleportTransition(target.level(), target.position(), Vec3.ZERO, target.getYRot(), target.getXRot(),
				TeleportTransition.DO_NOTHING));
		staff.sendSystemMessage(Component.literal("Teleported to " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.AQUA));
		UtilsAudit.record("staffmode.teleport", staff, target, AuditOutcome.SUCCESS, Map.of("dimension",
				target.level().dimension().identifier().toString()));
	}

	private static void freezeClick(ServerPlayer staff, ServerPlayer target) {
		if (!MejtaCoreAPI.hasPermission(staff, UtilsPermissions.FREEZE)) {
			staff.sendSystemMessage(Component.literal("You need " + UtilsPermissions.FREEZE.node() + ".").withStyle(ChatFormatting.RED));
			return;
		}
		long now = System.currentTimeMillis();
		PendingFreeze pending = FREEZE_CLICKS.get(staff.getUUID());
		if (pending == null || !pending.target().equals(target.getUUID()) || now > pending.until()) {
			FREEZE_CLICKS.put(staff.getUUID(), new PendingFreeze(target.getUUID(), now + 3000));
			boolean frozen = MejtaUtils.freeze().isFrozen(target.getUUID());
			staff.sendSystemMessage(Component.literal("Click " + target.getGameProfile().name() + " again within 3 s to "
					+ (frozen ? "unfreeze" : "freeze") + " them.").withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		FREEZE_CLICKS.remove(staff.getUUID());
		if (MejtaUtils.freeze().isFrozen(target.getUUID())) {
			MejtaUtils.freeze().unfreeze(target.getUUID(), MejtaCoreAPI.actor(staff), staff.level().getServer());
			staff.sendSystemMessage(Component.literal("Unfroze " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.AQUA));
		} else if (MejtaCoreAPI.hasPermission(target, UtilsPermissions.FREEZE_EXEMPT) || !Targets.mayActOn(staff, target)) {
			staff.sendSystemMessage(Component.literal(target.getGameProfile().name() + " cannot be frozen.").withStyle(ChatFormatting.RED));
			UtilsAudit.record("freeze.start", staff, target, AuditOutcome.DENIED, Map.of("reason", "exempt or protected", "via", "freeze rod"));
		} else {
			MejtaUtils.freeze().freeze(target.getUUID(), target.getGameProfile().name(), target, MejtaCoreAPI.actor(staff), "staff-mode freeze rod");
			staff.sendSystemMessage(Component.literal("Froze " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.AQUA));
		}
	}
}
