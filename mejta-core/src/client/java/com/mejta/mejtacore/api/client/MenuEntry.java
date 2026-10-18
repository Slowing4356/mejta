package com.mejta.mejtacore.api.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A clickable icon in a MejtaCore chest-style menu.
 *
 * <pre>{@code
 * MenuEntry entry = MenuEntry.builder("status")
 *     .icon(() -> new ItemStack(Items.COMPASS))
 *     .title(Component.literal("Status"))
 *     .description(Component.literal("Shows the current status"))
 *     .onClick(ctx -> ctx.player().displayClientMessage(Component.literal("OK"), false))
 *     .build();
 * }</pre>
 *
 * The icon supplier is called each time a menu is opened (item stacks cannot be created before a
 * world is loaded), so it may return a stack reflecting current state.
 */
public final class MenuEntry {
	private final String id;
	private final Supplier<ItemStack> icon;
	private final Supplier<Component> title;
	private final Supplier<List<Component>> description;
	private final MenuAction action;

	private MenuEntry(Builder b) {
		this.id = b.id;
		this.icon = b.icon;
		this.title = b.title;
		this.description = b.description;
		this.action = b.action;
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public String id() {
		return id;
	}

	public ItemStack createIcon() {
		return icon.get();
	}

	public Component title() {
		return title.get();
	}

	public List<Component> description() {
		return description.get();
	}

	public MenuAction action() {
		return action;
	}

	public static final class Builder {
		private final String id;
		private Supplier<ItemStack> icon;
		private Supplier<Component> title;
		private Supplier<List<Component>> description = List::of;
		private MenuAction action = MenuAction.NONE;

		private Builder(String id) {
			this.id = Objects.requireNonNull(id, "id");
		}

		public Builder icon(Supplier<ItemStack> icon) {
			this.icon = Objects.requireNonNull(icon, "icon");
			return this;
		}

		public Builder title(Component title) {
			Objects.requireNonNull(title, "title");
			this.title = () -> title;
			return this;
		}

		/** Title computed every time the menu is drawn, for entries that show live values. */
		public Builder title(Supplier<Component> title) {
			this.title = Objects.requireNonNull(title, "title");
			return this;
		}

		public Builder description(Component... lines) {
			List<Component> copy = List.of(lines);
			this.description = () -> copy;
			return this;
		}

		public Builder description(List<Component> lines) {
			List<Component> copy = List.copyOf(lines);
			this.description = () -> copy;
			return this;
		}

		/** Description computed every time the tooltip is shown. */
		public Builder dynamicDescription(Supplier<List<Component>> lines) {
			this.description = Objects.requireNonNull(lines, "lines");
			return this;
		}

		public Builder onClick(MenuAction action) {
			this.action = Objects.requireNonNull(action, "action");
			return this;
		}

		public MenuEntry build() {
			if (icon == null) {
				throw new IllegalStateException("Menu entry '" + id + "' needs an icon");
			}
			if (title == null) {
				throw new IllegalStateException("Menu entry '" + id + "' needs a title");
			}
			return new MenuEntry(this);
		}
	}

	/** Convenience: copies a list of description lines, appending extra lines. */
	public static List<Component> lines(List<Component> base, Component... extra) {
		List<Component> out = new ArrayList<>(base);
		out.addAll(List.of(extra));
		return out;
	}
}
