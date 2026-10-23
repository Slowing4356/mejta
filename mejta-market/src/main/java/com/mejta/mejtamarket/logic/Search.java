package com.mejta.mejtamarket.logic;

import java.util.Locale;

/**
 * Matches a search text against an item: every word must appear in the item's name, its ID (with
 * {@code _} read as a space) or the seller's name, ignoring case. {@code "iron pick"} finds an
 * {@code Iron Pickaxe}; an empty search matches everything.
 */
public final class Search {
	private Search() {
	}

	public static boolean matches(String query, String itemId, String itemName, String sellerName) {
		if (query == null || query.isBlank()) {
			return true;
		}
		String id = itemId.toLowerCase(Locale.ROOT);
		int colon = id.indexOf(':');
		String haystack = String.join(" ", itemName.toLowerCase(Locale.ROOT), id, (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' '),
				sellerName == null ? "" : sellerName.toLowerCase(Locale.ROOT));
		for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
			if (!haystack.contains(word)) {
				return false;
			}
		}
		return true;
	}

	/** Cleans up typed search text: at most 48 characters, no control characters. */
	public static String clean(String query) {
		StringBuilder b = new StringBuilder();
		for (char c : query.trim().toCharArray()) {
			if (b.length() >= 48) {
				break;
			}
			if (!Character.isISOControl(c)) {
				b.append(c);
			}
		}
		return b.toString();
	}
}
