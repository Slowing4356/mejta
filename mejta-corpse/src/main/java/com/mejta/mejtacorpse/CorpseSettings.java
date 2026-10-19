package com.mejta.mejtacorpse;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** MejtaCorpse's settings, in {@code config/mejta/mejtacorpse.json}. Keys are stable. */
public final class CorpseSettings {
	public enum ExpireAction {
		/** The items fall to the ground where the corpse lay. */
		DROP_ITEMS,
		/** The items are deleted. */
		DELETE_ITEMS
	}

	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
			.describedAs("Players leave a corpse holding their items when they die. Off: items drop as in vanilla (existing corpses stay)");
	public static final Setting<Integer> PUBLIC_AFTER_MINUTES = Setting.integer("access.public_after_minutes", -1, -1, 10_080)
			.describedAs("Minutes after which anyone may loot a corpse. -1 = only the owner (and staff), 0 = anyone at once");
	public static final Setting<Boolean> OWNER_REMOTE_OPEN = Setting.bool("access.owner_remote_open", false)
			.describedAs("Owners may open their corpses from anywhere with /corpse. Corpses in the void can always be opened this way");
	public static final Setting<Integer> MAX_DISTANCE = Setting.integer("access.max_distance", 6, 2, 16)
			.describedAs("How close (in blocks) a player must stay to keep a corpse's menu open");
	public static final Setting<Integer> EXPIRE_MINUTES = Setting.integer("expire.minutes", 0, 0, 525_600)
			.describedAs("Minutes until a corpse that still holds items expires. 0 = never");
	public static final Setting<ExpireAction> EXPIRE_ACTION = Setting.enumeration("expire.action", ExpireAction.DROP_ITEMS)
			.describedAs("What happens to the items of an expired corpse");
	public static final Setting<Boolean> SHOW_SKIN = Setting.bool("display.player_skin", true)
			.describedAs("The corpse wears the dead player's skin (off: the default skin)");
	public static final Setting<Boolean> SHOW_NAME = Setting.bool("display.show_name", true)
			.describedAs("Show \"<player>'s corpse\" above the corpse");
	public static final Setting<Boolean> DEATH_MESSAGE = Setting.bool("notify.death_message", true)
			.describedAs("Tell a player where their corpse is when they die");
	public static final Setting<Boolean> AUDIT_LOOTING = Setting.bool("audit.looting", true)
			.describedAs("Record in MejtaCore's audit log when someone other than the owner takes items from a corpse");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ENABLED, PUBLIC_AFTER_MINUTES, OWNER_REMOTE_OPEN, MAX_DISTANCE, EXPIRE_MINUTES, EXPIRE_ACTION, SHOW_SKIN, SHOW_NAME,
					DEATH_MESSAGE, AUDIT_LOOTING)
			.build();

	private CorpseSettings() {
	}
}
