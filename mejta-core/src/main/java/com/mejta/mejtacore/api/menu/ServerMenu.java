package com.mejta.mejtacore.api.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.menu.ButtonMenu;

/**
 * A chest-style menu built and run entirely on the server, so players without any mod can use it
 * (status panels, confirmations, settings pages).
 *
 * <pre>{@code
 * ServerMenu.builder(Component.literal("Vanish settings"), 3)
 *     .button(11, () -> MenuIcons.of(Items.HOPPER, Component.literal("Item pickup: " + (on ? "on" : "off"))),
 *             click -> { toggle(); click.menu().refresh(); })
 *     .refreshEvery(20)
 *     .validWhile(p -> MejtaCoreAPI.hasPermission(p, MY_PERMISSION))
 *     .open(player);
 * }</pre>
 *
 * <p><b>No item can ever be taken out of, or put into, a server menu.</b> Every click, on the menu or
 * on the player's own inventory below it, is refused on the server and the client is re-synced; only
 * the button handler runs. Icons are created fresh from their suppliers, so they are copies.
 *
 * @since API version 4
 */
public final class ServerMenu {
	/** Operations on an open menu. */
	public interface Handle {
		/** Re-creates every icon now (they are also refreshed every {@code refreshEvery} ticks). */
		void refresh();

		/** Closes the menu. */
		void close();
	}

	@FunctionalInterface
	public interface ClickHandler {
		void onClick(MenuClick click);
	}

	public record Button(Supplier<ItemStack> icon, ClickHandler handler) {
	}

	private final Component title;
	private final int rows;
	private final Map<Integer, Button> buttons;
	private final int refreshTicks;
	private final Predicate<ServerPlayer> validWhile;
	private final Consumer<ServerPlayer> onClose;

	private ServerMenu(Builder b) {
		this.title = b.title;
		this.rows = b.rows;
		this.buttons = Map.copyOf(b.buttons);
		this.refreshTicks = b.refreshTicks;
		this.validWhile = b.validWhile;
		this.onClose = b.onClose;
	}

	/** @param rows 1 to 6 */
	public static Builder builder(Component title, int rows) {
		return new Builder(title, rows);
	}

	public Component title() {
		return title;
	}

	public int rows() {
		return rows;
	}

	public Map<Integer, Button> buttons() {
		return buttons;
	}

	/** Ticks between automatic icon refreshes; {@code 0} = only on {@link Handle#refresh()}. */
	public int refreshTicks() {
		return refreshTicks;
	}

	public Predicate<ServerPlayer> validWhile() {
		return validWhile;
	}

	public Consumer<ServerPlayer> onClose() {
		return onClose;
	}

	/**
	 * Whether {@code menu} is an open server menu (made with this class). Lets addons tell them apart
	 * from real containers, e.g. to block item transfers in containers but not clicks on server menus.
	 */
	public static boolean isServerMenu(AbstractContainerMenu menu) {
		return menu instanceof ButtonMenu;
	}

	/** Opens the menu for {@code player} (closing whatever they had open). Call on the server thread. */
	public boolean open(ServerPlayer player) {
		return ButtonMenu.open(player, this);
	}

	public static final class Builder {
		private final Component title;
		private final int rows;
		private final Map<Integer, Button> buttons = new HashMap<>();
		private int refreshTicks;
		private Predicate<ServerPlayer> validWhile = p -> true;
		private Consumer<ServerPlayer> onClose = p -> {
		};

		private Builder(Component title, int rows) {
			this.title = Objects.requireNonNull(title, "title");
			if (rows < 1 || rows > 6) {
				throw new IllegalArgumentException("rows must be 1 to 6");
			}
			this.rows = rows;
		}

		/** A clickable icon. Replaces any earlier button in the same slot. */
		public Builder button(int slot, Supplier<ItemStack> icon, ClickHandler onClick) {
			if (slot < 0 || slot >= rows * 9) {
				throw new IllegalArgumentException("slot " + slot + " is outside a " + rows + "-row menu");
			}
			buttons.put(slot, new Button(Objects.requireNonNull(icon, "icon"), Objects.requireNonNull(onClick, "onClick")));
			return this;
		}

		/** An icon that does nothing when clicked. */
		public Builder icon(int slot, Supplier<ItemStack> icon) {
			return button(slot, icon, click -> {
			});
		}

		/** Refresh every icon every {@code ticks} ticks (e.g. 10 for live values). */
		public Builder refreshEvery(int ticks) {
			if (ticks < 0) {
				throw new IllegalArgumentException("ticks must be >= 0");
			}
			this.refreshTicks = ticks;
			return this;
		}

		/** Checked when opening, on every refresh and before every click; the menu closes once it fails. */
		public Builder validWhile(Predicate<ServerPlayer> condition) {
			this.validWhile = Objects.requireNonNull(condition, "condition");
			return this;
		}

		/** Called once when the menu closes, for any reason. */
		public Builder onClose(Consumer<ServerPlayer> onClose) {
			this.onClose = Objects.requireNonNull(onClose, "onClose");
			return this;
		}

		public ServerMenu build() {
			return new ServerMenu(this);
		}

		/** {@code build().open(player)}. */
		public boolean open(ServerPlayer player) {
			return build().open(player);
		}
	}
}
