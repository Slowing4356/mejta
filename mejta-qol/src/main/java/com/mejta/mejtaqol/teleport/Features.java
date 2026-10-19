package com.mejta.mejtaqol.teleport;

import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;

/** MejtaQoL's teleporting features. The ID is also the cooldown key and the command named in messages. */
public final class Features {
	/** Both {@code /tpa} and {@code /tpahere}: whoever travels has accepted or sent the request. */
	public static final Teleports.Feature TPA = new Teleports.Feature("tpa", QolSettings.TPA_ENABLED, QolPermissions.TPA, null);
	public static final Teleports.Feature HOME = new Teleports.Feature("home", QolSettings.HOMES_ENABLED, QolPermissions.HOME,
			QolSettings.HOMES_COOLDOWN);
	public static final Teleports.Feature HOME_OTHERS = new Teleports.Feature("homes", QolSettings.HOMES_ENABLED, QolPermissions.HOMES_OTHERS,
			null);
	public static final Teleports.Feature BACK = new Teleports.Feature("back", QolSettings.BACK_ENABLED, QolPermissions.BACK,
			QolSettings.BACK_COOLDOWN);
	public static final Teleports.Feature SPAWN = new Teleports.Feature("spawn", QolSettings.SPAWN_ENABLED, QolPermissions.SPAWN,
			QolSettings.SPAWN_COOLDOWN);
	public static final Teleports.Feature WARP = new Teleports.Feature("warp", QolSettings.WARPS_ENABLED, QolPermissions.WARP,
			QolSettings.WARPS_COOLDOWN);
	public static final Teleports.Feature RTP = new Teleports.Feature("rtp", QolSettings.RTP_ENABLED, QolPermissions.RTP,
			QolSettings.RTP_COOLDOWN);

	private Features() {
	}
}
