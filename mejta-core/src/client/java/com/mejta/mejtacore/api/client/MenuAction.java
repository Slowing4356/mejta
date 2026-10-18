package com.mejta.mejtacore.api.client;

/** What happens when a {@link MenuEntry} is clicked. */
@FunctionalInterface
public interface MenuAction {
	MenuAction NONE = context -> {
	};

	void onClick(MenuContext context);
}
