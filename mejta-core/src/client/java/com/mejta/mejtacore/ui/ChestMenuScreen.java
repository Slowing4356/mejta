package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.client.MenuContext;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;

/**
 * Client-side screen drawn like a vanilla chest. It has no server-side container, so it never
 * touches the player's inventory and works on any server.
 *
 * <p>The last row is a navigation bar (Back, page controls, Close); the other rows show entries.
 */
public class ChestMenuScreen extends Screen {
	private static final Identifier CHEST_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final Identifier SLOT_HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
	private static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
	private static final int IMAGE_WIDTH = 176;
	private static final int COLUMNS = 9;
	private static final int TITLE_COLOR = 0xFF404040;

	private static final int NAV_BACK = 0;
	private static final int NAV_PREVIOUS = 3;
	private static final int NAV_PAGE = 4;
	private static final int NAV_NEXT = 5;
	private static final int NAV_CLOSE = 8;

	private final Supplier<List<MenuEntry>> source;
	private final @Nullable Screen parent;

	private List<MenuEntry> allEntries = List.of();
	private MenuEntry[] slotEntries = new MenuEntry[0];
	private ItemStack[] slotIcons = new ItemStack[0];
	private int page;
	private int rows;
	private int left;
	private int top;

	public ChestMenuScreen(Component title, Supplier<List<MenuEntry>> entries, @Nullable Screen parent) {
		super(title);
		this.source = entries;
		this.parent = parent;
	}

	@Override
	protected void init() {
		refresh();
	}

	/** Re-reads entries from the supplier and rebuilds the visible page. */
	public void refresh() {
		rows = Math.clamp(MejtaCore.coreConfig().get(CoreSettings.MENU_ROWS), 3, 6);
		left = (width - IMAGE_WIDTH) / 2;
		top = (height - imageHeight()) / 2;

		List<MenuEntry> loaded;
		try {
			loaded = new ArrayList<>(source.get());
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Menu '" + getTitle().getString() + "' failed to build its entries", e);
			loaded = new ArrayList<>(List.of(errorEntry()));
		}
		allEntries = loaded;
		page = Math.clamp(page, 0, pageCount() - 1);

		int total = rows * COLUMNS;
		slotEntries = new MenuEntry[total];
		slotIcons = new ItemStack[total];
		Arrays.fill(slotIcons, ItemStack.EMPTY);

		int perPage = contentSlots();
		int start = page * perPage;
		for (int i = 0; i < perPage && start + i < allEntries.size(); i++) {
			setSlot(i, allEntries.get(start + i));
		}

		int nav = (rows - 1) * COLUMNS;
		for (int i = 0; i < COLUMNS; i++) {
			setSlot(nav + i, MenuItems.filler());
		}
		setSlot(nav + NAV_BACK, parent != null
				? MenuItems.simple("nav_back", Items.ARROW, Component.literal("Back").withStyle(ChatFormatting.YELLOW), ctx -> ctx.back())
				: MenuItems.simple("nav_close_main", Items.ARROW, Component.literal("Close").withStyle(ChatFormatting.YELLOW), ctx -> ctx.close()));
		if (pageCount() > 1) {
			if (page > 0) {
				setSlot(nav + NAV_PREVIOUS, MenuItems.simple("nav_prev", Items.SPECTRAL_ARROW,
						Component.literal("Previous Page").withStyle(ChatFormatting.YELLOW), ctx -> changePage(-1)));
			}
			setSlot(nav + NAV_PAGE, MenuEntry.builder("nav_page")
					.icon(() -> new ItemStack(Items.PAPER))
					.title(Component.literal("Page " + (page + 1) + " / " + pageCount()).withStyle(ChatFormatting.WHITE))
					.description(Component.literal("Scroll the mouse wheel to change pages").withStyle(ChatFormatting.GRAY))
					.build());
			if (page < pageCount() - 1) {
				setSlot(nav + NAV_NEXT, MenuItems.simple("nav_next", Items.SPECTRAL_ARROW,
						Component.literal("Next Page").withStyle(ChatFormatting.YELLOW), ctx -> changePage(1)));
			}
		}
		setSlot(nav + NAV_CLOSE, MenuItems.simple("nav_close", Items.BARRIER,
				Component.literal("Close Menu").withStyle(ChatFormatting.RED), ctx -> ctx.close()));
	}

	private void setSlot(int slot, @Nullable MenuEntry entry) {
		slotEntries[slot] = entry;
		if (entry == null) {
			slotIcons[slot] = ItemStack.EMPTY;
			return;
		}
		ItemStack icon;
		try {
			icon = entry.createIcon();
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Icon of menu entry '" + entry.id() + "' failed", e);
			icon = null;
		}
		slotIcons[slot] = icon == null || icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon;
	}

	private void changePage(int delta) {
		int target = Math.clamp(page + delta, 0, pageCount() - 1);
		if (target != page) {
			page = target;
			refresh();
		}
	}

	private int contentSlots() {
		return (rows - 1) * COLUMNS;
	}

	private int pageCount() {
		return Math.max(1, (allEntries.size() + contentSlots() - 1) / contentSlots());
	}

	private int imageHeight() {
		return rows * 18 + 24;
	}

	/** Left edge of a slot in GUI coordinates (used by tests and tooling). */
	public int slotX(int slot) {
		return left + 8 + (slot % COLUMNS) * 18;
	}

	/** Top edge of a slot in GUI coordinates (used by tests and tooling). */
	public int slotY(int slot) {
		return top + 18 + (slot / COLUMNS) * 18;
	}

	/** The entry shown in a slot on the current page, or {@code null}. */
	public @Nullable MenuEntry entryAt(int slot) {
		return slot >= 0 && slot < slotEntries.length ? slotEntries[slot] : null;
	}

	/** Index of the first slot of the navigation row. */
	public int navigationRowStart() {
		return (rows - 1) * COLUMNS;
	}

	private int slotAt(double mouseX, double mouseY) {
		for (int slot = 0; slot < slotEntries.length; slot++) {
			int x = slotX(slot);
			int y = slotY(slot);
			if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
				return slot;
			}
		}
		return -1;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int bodyHeight = rows * 18 + 17;
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE, left, top, 0.0F, 0.0F, IMAGE_WIDTH, bodyHeight, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST_TEXTURE, left, top + bodyHeight, 0.0F, 215.0F, IMAGE_WIDTH, 7, 256, 256);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.text(font, getTitle(), left + 8, top + 6, TITLE_COLOR, false);

		int hovered = slotAt(mouseX, mouseY);
		MenuEntry hoveredEntry = hovered >= 0 ? slotEntries[hovered] : null;
		boolean highlight = hoveredEntry != null && hoveredEntry != MenuItems.filler();
		if (highlight) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, slotX(hovered) - 4, slotY(hovered) - 4, 24, 24);
		}
		for (int slot = 0; slot < slotIcons.length; slot++) {
			if (!slotIcons[slot].isEmpty()) {
				graphics.item(slotIcons[slot], slotX(slot), slotY(slot));
			}
		}
		if (highlight) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, slotX(hovered) - 4, slotY(hovered) - 4, 24, 24);
			List<Component> lines = tooltipFor(hoveredEntry);
			if (!lines.isEmpty()) {
				graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
			}
		}
	}

	private static List<Component> tooltipFor(MenuEntry entry) {
		List<Component> lines = new ArrayList<>();
		try {
			Component title = entry.title();
			if (!title.getString().isEmpty()) {
				lines.add(title);
			}
			lines.addAll(entry.description());
		} catch (RuntimeException e) {
			lines.add(Component.literal("Error: " + e.getMessage()).withStyle(ChatFormatting.RED));
		}
		return lines;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int slot = slotAt(event.x(), event.y());
		if (slot < 0) {
			return super.mouseClicked(event, doubleClick);
		}
		MenuEntry entry = slotEntries[slot];
		if (entry == null || entry == MenuItems.filler()) {
			return true;
		}
		playClickSound();
		try {
			entry.action().onClick(new Context(event));
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Menu entry '" + entry.id() + "' failed on click", e);
			LocalPlayer player = minecraft.player;
			if (player != null) {
				player.sendOverlayMessage(Component.literal("That action failed, see the log").withStyle(ChatFormatting.RED));
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0 && pageCount() > 1) {
			changePage(scrollY < 0 ? 1 : -1);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private void playClickSound() {
		float volume = switch (MejtaCore.coreConfig().get(CoreSettings.MENU_SOUND)) {
			case OFF -> 0.0F;
			case QUIET -> 0.3F;
			case NORMAL -> 1.0F;
		};
		if (volume > 0) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, volume));
		}
	}

	@Override
	public void removed() {
		MejtaCore.configs().saveAll();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	private static MenuEntry errorEntry() {
		return MenuEntry.builder("error")
				.icon(() -> new ItemStack(Items.BARRIER))
				.title(Component.literal("This menu could not be loaded").withStyle(ChatFormatting.RED))
				.description(Component.literal("See the game log for details").withStyle(ChatFormatting.GRAY))
				.build();
	}

	private final class Context implements MenuContext {
		private final MouseButtonEvent event;

		private Context(MouseButtonEvent event) {
			this.event = event;
		}

		@Override
		public int button() {
			return event.button();
		}

		@Override
		public boolean shiftDown() {
			return event.hasShiftDown() || keyHeld(InputConstants.KEY_LSHIFT) || keyHeld(InputConstants.KEY_RSHIFT);
		}

		@Override
		public boolean controlDown() {
			return event.hasControlDown() || keyHeld(InputConstants.KEY_LCONTROL) || keyHeld(InputConstants.KEY_RCONTROL);
		}

		// Event modifiers come from GLFW; the live key state is checked too so synthetic input
		// (automation, client game tests) that does not set modifier bits behaves the same.
		private boolean keyHeld(int key) {
			return InputConstants.isKeyDown(minecraft.getWindow(), key);
		}

		@Override
		public LocalPlayer player() {
			return minecraft.player;
		}

		@Override
		public Screen screen() {
			return ChestMenuScreen.this;
		}

		@Override
		public void openPage(Component title, Supplier<List<MenuEntry>> entries) {
			minecraft.setScreen(new ChestMenuScreen(title, entries, ChestMenuScreen.this));
		}

		@Override
		public void openScreen(Screen screen) {
			minecraft.setScreen(screen);
		}

		@Override
		public void refresh() {
			ChestMenuScreen.this.refresh();
		}

		@Override
		public void back() {
			minecraft.setScreen(parent);
		}

		@Override
		public void close() {
			onClose();
		}
	}
}
