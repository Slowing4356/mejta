package com.mejta.mejtacrates;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/**
 * MejtaCrates' settings, in {@code config/mejta/mejtacrates.json} and MejtaCore's settings editor. Keys are
 * stable. The crates themselves are not settings: they are edited with {@code /crates admin} (see the README).
 */
public final class CrateSettings {
	public static final Setting<Boolean> ANIMATION = Setting.bool("open.animation", true)
			.describedAs("Opening a crate spins its rewards in a menu before showing the prize. Off: the prize is given at once");
	public static final Setting<Integer> ANIMATION_TICKS = Setting.integer("open.animation_ticks", 80, 20, 200)
			.describedAs("How long the spin lasts, in ticks (20 = one second)");
	public static final Setting<Boolean> SOUNDS = Setting.bool("open.sounds", true)
			.describedAs("Play sounds while the rewards spin and when the prize is shown");
	public static final Setting<Boolean> OPEN_FROM_MENU = Setting.bool("open.from_menu", true)
			.describedAs("Players may open crates with their virtual keys from /crates. Off: only at a crate block");
	public static final Setting<Boolean> PHYSICAL_OPEN_ANYWHERE = Setting.bool("keys.physical_open_anywhere", true)
			.describedAs("Right-clicking a physical key opens its crate anywhere. Off: only at one of the crate's blocks");
	public static final Setting<Boolean> NOTIFY_KEYS = Setting.bool("keys.notify", true)
			.describedAs("Tell players when they receive keys");
	public static final Setting<Double> BROADCAST_CHANCE = Setting.decimal("broadcast.chance_percent", 5.0, 0.0, 100.0)
			.describedAs("Announce to everyone when a player wins a reward with this chance (in percent) or less. 0 = only rewards marked to broadcast");
	public static final Setting<Boolean> AUDIT_OPENINGS = Setting.bool("audit.openings", true)
			.describedAs("Record every opening and its prize in MejtaCore's audit log (giving and taking keys is always recorded)");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ANIMATION, ANIMATION_TICKS, SOUNDS, OPEN_FROM_MENU, PHYSICAL_OPEN_ANYWHERE, NOTIFY_KEYS, BROADCAST_CHANCE, AUDIT_OPENINGS)
			.build();

	private CrateSettings() {
	}
}
