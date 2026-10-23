package com.mejta.mejtaqol.warp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.PagedMenu;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Names;

/** {@code /warp}, {@code /warps}, {@code /setwarp} and {@code /delwarp}. The warps live in the data store under {@code warps}. */
public final class WarpCommands {
	private static final String KEY = "warps";

	private static volatile WarpBook book = new WarpBook();

	private WarpCommands() {
	}

	/** Reads the warps of the world that is starting. */
	public static void load(DataStore store) {
		book = store.read(KEY).map(WarpBook::fromJson).orElseGet(WarpBook::new);
	}

	public static WarpBook book() {
		return book;
	}

	private static void save() {
		MejtaQoL.store().write(KEY, book.toJson());
	}

	public static void register() {
		String id = MejtaQoL.ID;
		MejtaCoreAPI.registerRootCommand(id, "warp", (root, ctx) -> root
				.requires(Cmds.available(Features.WARP))
				.executes(c -> list(c.getSource().getPlayerOrException()))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(WarpCommands::suggest)
						.executes(c -> warp(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
		MejtaCoreAPI.registerRootCommand(id, "warps", (root, ctx) -> root
				.requires(Cmds.available(Features.WARP))
				.executes(c -> list(c.getSource().getPlayerOrException())));
		MejtaCoreAPI.registerRootCommand(id, "setwarp", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.WARPS_ENABLED, QolPermissions.WARPS_MANAGE))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(WarpCommands::suggest)
						.executes(c -> set(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
		MejtaCoreAPI.registerRootCommand(id, "delwarp", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.WARPS_ENABLED, QolPermissions.WARPS_MANAGE))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(WarpCommands::suggest)
						.executes(c -> delete(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
	}

	public static int warp(ServerPlayer player, String name) {
		Optional<WarpBook.Warp> warp = book.get(name);
		if (warp.isEmpty()) {
			player.sendSystemMessage(Msg.error("There is no warp called " + name + "."));
			return 0;
		}
		String key = warp.get().name();
		return MejtaQoL.teleports().request(player, Features.WARP, Component.literal("warp " + key), t ->
				book.get(key).map(w -> Teleports.Result.to(w.location()))
						.orElseGet(() -> Teleports.Result.fail("The warp " + key + " was deleted."))) ? 1 : 0;
	}

	/**
	 * Creates or moves a warp at the player's position. The item in their main hand (if any) becomes its
	 * icon in the {@code /warps} menu.
	 */
	public static int set(ServerPlayer player, String rawName) {
		Optional<String> name = Names.normalize(rawName);
		if (name.isEmpty()) {
			player.sendSystemMessage(Msg.error("Warp names use letters, digits, _ and - (at most 16)."));
			return 0;
		}
		ItemStack held = player.getMainHandItem();
		String icon = held.isEmpty() ? WarpBook.DEFAULT_ICON : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		boolean existed = book.get(name.get()).isPresent();
		Location here = Locations.of(player);
		book.set(name.get(), here, icon, player.getGameProfile().name());
		save();
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaQoL.ID, existed ? "warps.move" : "warps.create")
				.actor(MejtaCoreAPI.actor(player))
				.target(name.get())
				.outcome(AuditOutcome.SUCCESS)
				.detail("location", here.describe())
				.build());
		player.sendSystemMessage(Msg.success((existed ? "Moved warp " : "Created warp ") + name.get() + " at " + here.describe() + "."));
		return 1;
	}

	public static int delete(ServerPlayer player, String name) {
		Optional<WarpBook.Warp> warp = book.get(name);
		if (warp.isEmpty() || !book.delete(name)) {
			player.sendSystemMessage(Msg.error("There is no warp called " + name + "."));
			return 0;
		}
		save();
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaQoL.ID, "warps.delete")
				.actor(MejtaCoreAPI.actor(player))
				.target(warp.get().name())
				.outcome(AuditOutcome.SUCCESS)
				.detail("location", warp.get().location().describe())
				.build());
		player.sendSystemMessage(Msg.success("Deleted warp " + warp.get().name() + "."));
		return 1;
	}

	private static int list(ServerPlayer player) {
		List<WarpBook.Warp> warps = new ArrayList<>(book.all().values());
		if (warps.isEmpty()) {
			player.sendSystemMessage(Msg.info("There are no warps yet."));
			return 0;
		}
		if (MejtaQoL.config().get(QolSettings.WARPS_MENU)) {
			openMenu(player, warps);
		} else {
			MutableComponent line = Msg.highlight("Warps:");
			for (WarpBook.Warp w : warps) {
				line.append(Component.literal(" ")).append(Msg.button(w.name(), "/warp " + w.name(), ChatFormatting.AQUA));
			}
			player.sendSystemMessage(line);
		}
		return warps.size();
	}

	private static void openMenu(ServerPlayer player, List<WarpBook.Warp> warps) {
		PagedMenu.<WarpBook.Warp>builder(Component.literal("Warps (" + warps.size() + ")"), () -> new ArrayList<>(book.all().values()))
				.rows(Math.max(2, Math.min(6, (warps.size() + 8) / 9 + 1)))
				.entry(w -> MenuIcons.of(icon(w.icon()), Component.literal(w.name()).withStyle(ChatFormatting.AQUA),
						MenuIcons.line(w.location().describe()),
						Component.empty(),
						Component.literal("Click: teleport").withStyle(ChatFormatting.YELLOW)),
						(w, click) -> {
							click.menu().close();
							warp(click.player(), w.name());
						})
				.empty(() -> MenuIcons.of(Items.ENDER_PEARL, Component.literal("No warps").withStyle(ChatFormatting.GRAY)))
				.info(() -> MenuIcons.of(Items.BOOK, Component.literal("Warps").withStyle(ChatFormatting.GOLD),
						MenuIcons.line("Click a warp to go there"), MenuIcons.line("Or use /warp <name>")))
				.validWhile(p -> Cmds.available(Features.WARP).test(p.createCommandSourceStack()))
				.open(player);
	}

	static Item icon(String id) {
		Identifier key = Identifier.tryParse(id);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == null || item == Items.AIR ? Items.ENDER_PEARL : item;
	}

	private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		String typed = b.getRemaining().toLowerCase(Locale.ROOT);
		for (String name : book.all().keySet()) {
			if (name.startsWith(typed)) {
				b.suggest(name);
			}
		}
		return b.buildFuture();
	}
}
