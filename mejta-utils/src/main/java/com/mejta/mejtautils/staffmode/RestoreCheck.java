package com.mejta.mejtautils.staffmode;

import java.util.ArrayList;
import java.util.List;

/** Reasons a restoration cannot be done without a person confirming it. */
public final class RestoreCheck {
	private RestoreCheck() {
	}

	/**
	 * @param snapshotIntact    hash matches and every item decodes
	 * @param dimensionExists   the saved dimension is loaded on this server
	 * @param sameDataVersion   the snapshot was written by this game version
	 * @return empty if restoring is safe; otherwise the reasons, shown to the confirming staff member
	 */
	public static List<String> problems(boolean snapshotIntact, boolean dimensionExists, boolean sameDataVersion) {
		List<String> out = new ArrayList<>();
		if (!snapshotIntact) {
			out.add("the saved state is damaged (hash mismatch or unreadable items)");
		}
		if (!dimensionExists) {
			out.add("the saved dimension no longer exists; you would be sent to world spawn");
		}
		if (!sameDataVersion) {
			out.add("the saved state was written by a different game version");
		}
		return out;
	}
}
