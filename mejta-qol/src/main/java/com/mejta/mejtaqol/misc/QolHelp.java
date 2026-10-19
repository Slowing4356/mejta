package com.mejta.mejtaqol.misc;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;

/** {@code /qol}: the MejtaQoL commands the player can use right now, each clickable. */
public final class QolHelp {
	private record Entry(String usage, String suggestion, String description, Predicate<CommandSourceStack> available) {
	}

	private static final List<Entry> ENTRIES = List.of(
			new Entry("/tpa <player>", "/tpa ", "ask to teleport to a player", Cmds.available(Features.TPA)),
			new Entry("/tpahere <player>", "/tpahere ", "ask a player to come to you", s -> Cmds.available(Features.TPA).test(s)
					&& Cmds.available(QolSettings.TPAHERE_ENABLED, QolPermissions.TPAHERE).test(s)),
			new Entry("/tpaccept, /tpdeny", "/tpaccept", "answer a teleport request", Cmds.available(Features.TPA)),
			new Entry("/tptoggle", "/tptoggle", "refuse or accept teleport requests", Cmds.available(Features.TPA)),
			new Entry("/sethome [name]", "/sethome ", "save a home here", Cmds.available(Features.HOME)),
			new Entry("/home [name]", "/home ", "go to a home", Cmds.available(Features.HOME)),
			new Entry("/homes", "/homes", "list your homes", Cmds.available(Features.HOME)),
			new Entry("/delhome <name>", "/delhome ", "delete a home", Cmds.available(Features.HOME)),
			new Entry("/back", "/back", "return to where you were before teleporting or dying", Cmds.available(Features.BACK)),
			new Entry("/spawn", "/spawn", "go to spawn", Cmds.available(Features.SPAWN)),
			new Entry("/warps", "/warps", "list the server's warps", Cmds.available(Features.WARP)),
			new Entry("/warp <name>", "/warp ", "go to a warp", Cmds.available(Features.WARP)),
			new Entry("/rtp", "/rtp", "teleport to a random place", Cmds.available(Features.RTP)),
			new Entry("/ec", "/ec", "open your ender chest", Cmds.available(QolSettings.ENDERCHEST_ENABLED, QolPermissions.ENDERCHEST)),
			new Entry("/craft", "/craft", "open a crafting table", Cmds.available(QolSettings.WORKBENCH_ENABLED, QolPermissions.WORKBENCH)),
			new Entry("/anvil", "/anvil", "open an anvil", Cmds.available(QolSettings.ANVIL_ENABLED, QolPermissions.ANVIL)),
			new Entry("/stonecutter", "/stonecutter", "open a stonecutter", Cmds.available(QolSettings.STONECUTTER_ENABLED, QolPermissions.STONECUTTER)),
			new Entry("/grindstone", "/grindstone", "open a grindstone", Cmds.available(QolSettings.GRINDSTONE_ENABLED, QolPermissions.GRINDSTONE)),
			new Entry("/smithing", "/smithing", "open a smithing table", Cmds.available(QolSettings.SMITHING_ENABLED, QolPermissions.SMITHING)),
			new Entry("/loom", "/loom", "open a loom", Cmds.available(QolSettings.LOOM_ENABLED, QolPermissions.LOOM)),
			new Entry("/cartography", "/cartography", "open a cartography table", Cmds.available(QolSettings.CARTOGRAPHY_ENABLED, QolPermissions.CARTOGRAPHY)),
			new Entry("/trash", "/trash", "throw items away", Cmds.available(QolSettings.TRASH_ENABLED, QolPermissions.TRASH)),
			new Entry("/afk", "/afk", "mark yourself as away", Cmds.available(QolSettings.AFK_ENABLED, QolPermissions.AFK)),
			new Entry("/r <message>", "/r ", "reply to your last private message", Cmds.available(QolSettings.REPLY_ENABLED, QolPermissions.REPLY)),
			new Entry("/setspawn", "/setspawn", "set the /spawn point here", Cmds.available(QolSettings.SPAWN_ENABLED, QolPermissions.SETSPAWN)),
			new Entry("/setwarp, /delwarp <name>", "/setwarp ", "create or delete a warp", Cmds.available(QolSettings.WARPS_ENABLED, QolPermissions.WARPS_MANAGE)),
			new Entry("/homes <player>", "/homes ", "see another player's homes", s -> Cmds.available(Features.HOME).test(s)
					&& MejtaCoreAPI.hasPermission(s, QolPermissions.HOMES_OTHERS)));

	private QolHelp() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaQoL.ID, "qol", (root, ctx) -> root.executes(c -> show(c.getSource())));
	}

	private static int show(CommandSourceStack source) {
		source.sendSystemMessage(Msg.highlight("Commands you can use (click one to type it):"));
		int shown = 0;
		for (Entry e : ENTRIES) {
			if (!e.available().test(source)) {
				continue;
			}
			source.sendSystemMessage(Component.literal(" ").append(Msg.suggest(e.usage(), e.suggestion(), ChatFormatting.AQUA))
					.append(Msg.info(" " + e.description())));
			shown++;
		}
		if (shown == 0) {
			source.sendSystemMessage(Msg.info(" None right now."));
		}
		return shown;
	}
}
