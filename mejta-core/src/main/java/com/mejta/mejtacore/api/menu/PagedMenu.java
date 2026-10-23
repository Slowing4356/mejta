package com.mejta.mejtacore.api.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * A {@link ServerMenu} listing any number of entries over pages, with the bar every MejtaCore list shares along
 * the bottom row:
 *
 * <pre>
 *  [Back] [ .. ] [ .. ] [&lt; Prev] [Info] [Next &gt;] [ .. ] [ .. ] [Close]
 * </pre>
 *
 * <p>Pages turn in place (the chest stays open, so the cursor does not jump). The entries are read again from
 * their supplier every time the icons are drawn, so a refresh shows added or removed entries; a click acts on the
 * entry the player saw. Unused slots are filled with {@link MenuIcons#filler()} panes.
 *
 * <pre>{@code
 * PagedMenu.builder(Component.literal("Homes"), () -> homes.of(player))
 *     .entry(home -> MenuIcons.of(Items.RED_BED, Component.literal(home.name())),
 *            (home, click) -> teleport(click.player(), home))
 *     .empty(() -> MenuIcons.of(Items.PAPER, Component.literal("No homes yet")))
 *     .open(player);
 * }</pre>
 *
 * @since API version 7
 */
public final class PagedMenu {
	/** Bar columns that {@link Builder#barButton} may use; the others are Back, page buttons, Info and Close. */
	private static final int[] FREE_BAR_COLUMNS = {1, 2, 6, 7};

	private PagedMenu() {
	}

	/** @param entries read again on every draw; called on the server thread */
	public static <T> Builder<T> builder(Component title, Supplier<? extends List<? extends T>> entries) {
		return new Builder<>(title, entries);
	}

	public static final class Builder<T> {
		private final Component title;
		private final Supplier<? extends List<? extends T>> entries;
		private int rows = 6;
		private Function<? super T, ItemStack> entryIcon = e -> MenuIcons.of(Items.PAPER, Component.literal(String.valueOf(e)));
		private BiConsumer<? super T, MenuClick> entryClick;
		private Supplier<ItemStack> empty = () -> MenuIcons.of(Items.PAPER, Component.literal("Nothing here yet").withStyle(ChatFormatting.GRAY));
		private Supplier<ItemStack> info;
		private String backTo;
		private Consumer<ServerPlayer> back;
		private final List<BarButton> barButtons = new ArrayList<>();
		private int refreshTicks;
		private Predicate<ServerPlayer> validWhile = p -> true;
		private Consumer<ServerPlayer> onClose = p -> {
		};

		private record BarButton(int column, Supplier<ItemStack> icon, ServerMenu.ClickHandler onClick) {
		}

		private Builder(Component title, Supplier<? extends List<? extends T>> entries) {
			this.title = Objects.requireNonNull(title, "title");
			this.entries = Objects.requireNonNull(entries, "entries");
		}

		/** 2 to 6; the last row is the bar, the others list entries (9 per row). Default 6. */
		public Builder<T> rows(int rows) {
			if (rows < 2 || rows > 6) {
				throw new IllegalArgumentException("rows must be 2 to 6");
			}
			this.rows = rows;
			return this;
		}

		/**
		 * How each entry looks, and what clicking it does ({@code null}: nothing).
		 */
		public Builder<T> entry(Function<? super T, ItemStack> icon, BiConsumer<? super T, MenuClick> onClick) {
			this.entryIcon = Objects.requireNonNull(icon, "icon");
			this.entryClick = onClick;
			return this;
		}

		/** Shown in the middle of the list when there are no entries. */
		public Builder<T> empty(Supplier<ItemStack> icon) {
			this.empty = Objects.requireNonNull(icon, "icon");
			return this;
		}

		/**
		 * The icon in the middle of the bar, e.g. explaining the clicks. The page number is added to its lore when
		 * there is more than one page. Without one, the bar shows the entry count and page.
		 */
		public Builder<T> info(Supplier<ItemStack> icon) {
			this.info = Objects.requireNonNull(icon, "icon");
			return this;
		}

		/**
		 * A "Back" button at the left of the bar.
		 *
		 * @param to what it leads back to, shown as "Back to ..."; {@code null} for just "Back"
		 */
		public Builder<T> back(String to, Consumer<ServerPlayer> back) {
			this.backTo = to;
			this.back = Objects.requireNonNull(back, "back");
			return this;
		}

		/** An extra button in the bar. {@code column} is 1, 2, 6 or 7 (0 is Back, 3 to 5 the pages, 8 Close). */
		public Builder<T> barButton(int column, Supplier<ItemStack> icon, ServerMenu.ClickHandler onClick) {
			boolean free = false;
			for (int c : FREE_BAR_COLUMNS) {
				free |= c == column;
			}
			if (!free) {
				throw new IllegalArgumentException("bar column " + column + " is not free; use 1, 2, 6 or 7");
			}
			barButtons.removeIf(b -> b.column == column);
			barButtons.add(new BarButton(column, Objects.requireNonNull(icon, "icon"), Objects.requireNonNull(onClick, "onClick")));
			return this;
		}

		/** @see ServerMenu.Builder#refreshEvery */
		public Builder<T> refreshEvery(int ticks) {
			if (ticks < 0) {
				throw new IllegalArgumentException("ticks must be >= 0");
			}
			this.refreshTicks = ticks;
			return this;
		}

		/** @see ServerMenu.Builder#validWhile */
		public Builder<T> validWhile(Predicate<ServerPlayer> condition) {
			this.validWhile = Objects.requireNonNull(condition, "condition");
			return this;
		}

		/** Called once when the menu closes, for any reason (not when a page turns). */
		public Builder<T> onClose(Consumer<ServerPlayer> onClose) {
			this.onClose = Objects.requireNonNull(onClose, "onClose");
			return this;
		}

		/** Opens on the first page. */
		public boolean open(ServerPlayer player) {
			return open(player, 0);
		}

		/** Opens on {@code page} (0-based, clamped to the pages there are), e.g. to come back where the player was. */
		public boolean open(ServerPlayer player, int page) {
			return build(page).open(player);
		}

		/** The menu, opening on {@code page} (0-based). */
		public ServerMenu build(int page) {
			State<T> state = new State<>(entries, (rows - 1) * 9, page);
			int bar = (rows - 1) * 9;
			ServerMenu.Builder menu = ServerMenu.builder(title, rows)
					.refreshEvery(refreshTicks)
					.validWhile(validWhile)
					.onClose(onClose);
			for (int i = 0; i < state.perPage; i++) {
				int index = i;
				menu.button(i, () -> {
					if (index == 0) {
						state.reload(); // slot 0 is drawn first, both as a chest and as a screen
					}
					T entry = state.entry(index);
					if (entry != null) {
						return entryIcon.apply(entry);
					}
					return state.shown.isEmpty() && index == emptySlot(state.perPage) ? empty.get() : ItemStack.EMPTY;
				}, click -> {
					T entry = state.entry(index);
					if (entry != null && entryClick != null) {
						entryClick.accept(entry, click);
					}
				});
			}
			if (back != null) {
				menu.backButton(bar, backTo, back);
			}
			menu.button(bar + 3, () -> state.page > 0 ? MenuIcons.page(false, state.page, state.pages()) : MenuIcons.filler(), click -> {
				if (state.page > 0) {
					state.page--;
					click.menu().refresh();
				}
			});
			menu.icon(bar + 4, () -> infoIcon(state));
			menu.button(bar + 5, () -> state.page + 1 < state.pages() ? MenuIcons.page(true, state.page + 2, state.pages()) : MenuIcons.filler(),
					click -> {
						if (state.page + 1 < state.pages()) {
							state.page++;
							click.menu().refresh();
						}
					});
			for (BarButton button : barButtons) {
				menu.button(bar + button.column, button.icon, button.onClick);
			}
			menu.closeButton(bar + 8);
			return menu.fillEmpty().build();
		}

		private ItemStack infoIcon(State<T> state) {
			String page = "Page " + (state.page + 1) + " of " + state.pages();
			if (info == null) {
				int count = state.shown.size();
				return MenuIcons.of(Items.BOOK, Component.literal(count + (count == 1 ? " entry" : " entries")).withStyle(ChatFormatting.AQUA),
						MenuIcons.line(page));
			}
			ItemStack icon = info.get().copy();
			if (state.pages() > 1) {
				List<Component> lore = new ArrayList<>(icon.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
				lore.add(MenuIcons.line(page).copy().withStyle(style -> style.withItalic(false)));
				icon.set(DataComponents.LORE, new ItemLore(lore));
			}
			return icon;
		}

		/** The middle of the list area, where the "empty" icon goes. */
		private static int emptySlot(int perPage) {
			int rows = perPage / 9;
			return (rows - 1) / 2 * 9 + 4;
		}
	}

	/** What one open menu shows. */
	private static final class State<T> {
		final Supplier<? extends List<? extends T>> source;
		final int perPage;
		int page;
		List<? extends T> shown = List.of();

		State(Supplier<? extends List<? extends T>> source, int perPage, int page) {
			this.source = source;
			this.perPage = perPage;
			this.page = Math.max(0, page);
			reload();
		}

		void reload() {
			List<? extends T> fresh = source.get();
			shown = fresh == null ? List.of() : List.copyOf(fresh);
			page = Math.min(page, pages() - 1);
		}

		int pages() {
			return Math.max(1, (shown.size() + perPage - 1) / perPage);
		}

		/** The entry at {@code index} of the current page, or {@code null}. */
		T entry(int index) {
			int at = page * perPage + index;
			return at < shown.size() ? shown.get(at) : null;
		}
	}
}
