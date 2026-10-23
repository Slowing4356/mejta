package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.client.MenuAction;
import com.mejta.mejtacore.api.client.MenuContext;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;

/**
 * Base of MejtaCore's menu screens, in the style of the settings editor: a title, an optional sidebar, a few
 * header lines, a scrolling grid of cards and a footer with buttons and the status line. A card shows an icon, a
 * name and the first line of its description; the whole description is its tooltip. Cards that do something
 * light up under the mouse.
 *
 * <p>Nothing here touches the player's inventory: the icons are only drawn.
 */
public abstract class CardScreen extends Screen {
	/** Something in the card grid. */
	public sealed interface Item permits Card, Heading {
	}

	/** A label across the whole grid that starts a new group of cards. */
	public record Heading(Component text) implements Item {
	}

	/** How a card was clicked. {@code button}: 0 = left, 1 = right, 2 = middle. */
	public record Click(int button, boolean shift, boolean control) {
	}

	@FunctionalInterface
	public interface CardAction {
		void run(Click click);
	}

	/**
	 * One card.
	 *
	 * @param action      what a click does, or {@code null} for a card that only informs
	 * @param highlighted drawn with a green edge (the selected option, for example)
	 */
	public record Card(String id, ItemStack icon, Component title, List<Component> lines, @Nullable CardAction action,
			boolean highlighted) implements Item {
		public boolean clickable() {
			return action != null;
		}
	}

	/** An entry of the sidebar. */
	protected record SidebarEntry(String id, Component label, @Nullable Component tooltip) {
	}

	/** A footer button; {@code id} is what {@link #footerPosition} takes. */
	protected record FooterButton(String id, Component label, @Nullable Component tooltip, Runnable action) {
	}

	protected static final int MARGIN = 8;
	protected static final int TOP = 30;
	protected static final int FOOTER_HEIGHT = 30;
	private static final int SIDEBAR_ITEM = 20;
	private static final int MAX_CONTENT_WIDTH = 460;
	private static final int PAD = 5;
	private static final int SCROLLBAR = 6;
	private static final int GAP = 4;
	private static final int CARD_HEIGHT = 30;
	private static final int HEADING_HEIGHT = 18;
	private static final int MIN_CARD_WIDTH = 140;
	private static final int HEADER_LINE = 11;

	protected static final int PANEL = 0x70000000;
	private static final int CARD = 0x1EFFFFFF;
	private static final int CARD_INFO = 0x0FFFFFFF;
	private static final int CARD_HOVER = 0x40FFFFFF;
	private static final int SELECTED_MARK = 0xFF55FF55;
	private static final int DIVIDER = 0x40FFFFFF;
	protected static final int WHITE = 0xFFFFFFFF;
	protected static final int GRAY = 0xFFA0A0A0;
	protected static final int GOLD = 0xFFFFAA00;

	protected final @Nullable Screen parent;
	private List<Item> items = List.of();
	private final List<Placed> placed = new ArrayList<>();
	private List<Component> header = List.of();
	private final List<SidebarButton> sidebarButtons = new ArrayList<>();
	private final Map<String, Button> footer = new LinkedHashMap<>();
	private int scroll;
	private int sidebarScroll;
	private int contentHeight;

	// Layout, recomputed by init() and refresh()
	private boolean hasSidebar;
	private int sidebarLeft;
	private int sidebarWidth;
	private int contentLeft;
	private int contentRight;
	private int listTop;
	private int listBottom;
	private int footerLeft;

	private record Placed(Item item, int x, int y, int width, int height) {
	}

	private record SidebarButton(String id, Button button) {
	}

	protected CardScreen(Component title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	// ---------------------------------------------------------------- what subclasses provide

	/** The cards (and headings) to show; called on opening and on every {@link #refresh()}. */
	protected abstract List<Item> buildItems();

	/** Lines above the cards. */
	protected List<Component> buildHeader() {
		return List.of();
	}

	/** Sidebar entries; a screen without any has no sidebar and centres its cards. */
	protected List<SidebarEntry> sidebarEntries() {
		return List.of();
	}

	/** ID of the sidebar entry shown as selected. */
	protected @Nullable String selectedSidebar() {
		return null;
	}

	protected void sidebarClicked(String id) {
	}

	/** Footer buttons, right-aligned; by default Back (with a parent) and Close. */
	protected List<FooterButton> footerButtons() {
		List<FooterButton> buttons = new ArrayList<>();
		if (parent != null) {
			buttons.add(new FooterButton("back", Component.literal("Back"), null, this::onClose));
		}
		buttons.add(new FooterButton("close", Component.literal(parent != null ? "Close" : "Done"), null, this::closeAll));
		return buttons;
	}

	/** Most cards side by side. */
	protected int maxColumns() {
		return 3;
	}

	/** Text shown when there are no cards. */
	protected Component emptyText() {
		return Component.literal("Nothing here").withStyle(ChatFormatting.GRAY);
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void init() {
		List<SidebarEntry> entries = sidebarEntries();
		hasSidebar = !entries.isEmpty();
		if (hasSidebar) {
			sidebarLeft = MARGIN;
			sidebarWidth = Math.clamp(width / 4, 96, 150);
			contentLeft = sidebarLeft + sidebarWidth + MARGIN;
			contentRight = width - MARGIN;
		} else {
			int contentWidth = Math.min(width - 2 * MARGIN, MAX_CONTENT_WIDTH);
			contentLeft = (width - contentWidth) / 2;
			contentRight = contentLeft + contentWidth;
		}
		listBottom = height - FOOTER_HEIGHT - 4;

		sidebarButtons.clear();
		for (SidebarEntry entry : entries) {
			Button.Builder builder = Button.builder(entry.label(), b -> sidebarClicked(entry.id()))
					.bounds(sidebarLeft + 2, 0, sidebarWidth - 4, SIDEBAR_ITEM - 2);
			if (entry.tooltip() != null) {
				builder.tooltip(Tooltip.create(entry.tooltip()));
			}
			sidebarButtons.add(new SidebarButton(entry.id(), addRenderableWidget(builder.build())));
		}

		footer.clear();
		List<FooterButton> buttons = footerButtons();
		int buttonWidth = Math.min(100, (width - 2 * MARGIN - (buttons.size() - 1) * 4) / Math.max(1, buttons.size()));
		int x = (hasSidebar ? width - MARGIN : contentRight) - buttons.size() * buttonWidth - (buttons.size() - 1) * 4;
		footerLeft = x;
		for (FooterButton spec : buttons) {
			Button.Builder builder = Button.builder(spec.label(), b -> spec.action().run())
					.bounds(x, height - FOOTER_HEIGHT + 5, buttonWidth, 20);
			if (spec.tooltip() != null) {
				builder.tooltip(Tooltip.create(spec.tooltip()));
			}
			footer.put(spec.id(), addRenderableWidget(builder.build()));
			x += buttonWidth + 4;
		}
		refresh();
	}

	/** Builds the cards again (e.g. after a value changed), keeping the scroll position. */
	public void refresh() {
		if (minecraft == null) {
			return; // not shown yet: init() refreshes
		}
		try {
			items = List.copyOf(buildItems());
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Menu '" + getTitle().getString() + "' failed to build its entries", e);
			items = List.of(new Card("error", new ItemStack(Items.BARRIER),
					Component.literal("This menu could not be loaded").withStyle(ChatFormatting.RED),
					List.of(Component.literal("See the game log for details").withStyle(ChatFormatting.GRAY)), null, false));
		}
		try {
			header = List.copyOf(buildHeader());
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Header of menu '" + getTitle().getString() + "' failed", e);
			header = List.of();
		}
		String selected = selectedSidebar();
		for (SidebarButton b : sidebarButtons) {
			for (SidebarEntry entry : sidebarEntries()) {
				if (entry.id().equals(b.id())) {
					Component label = Component.literal(trimTo(entry.label().getString(), sidebarWidth - 24)).setStyle(entry.label().getStyle());
					b.button().setMessage(entry.id().equals(selected)
							? Component.literal("> ").withStyle(ChatFormatting.YELLOW).append(label.copy().withStyle(ChatFormatting.YELLOW))
							: label);
				}
			}
		}
		layout();
	}

	private void layout() {
		listTop = TOP + (header.isEmpty() ? 0 : header.size() * HEADER_LINE + 8);
		int inner = contentRight - contentLeft - 2 * PAD - SCROLLBAR;
		int columns = Math.clamp(inner / MIN_CARD_WIDTH, 1, Math.max(1, maxColumns()));
		int cardWidth = (inner - (columns - 1) * GAP) / columns;
		placed.clear();
		int y = 0;
		int column = 0;
		for (Item item : items) {
			if (item instanceof Heading) {
				if (column > 0) {
					y += CARD_HEIGHT + GAP;
					column = 0;
				}
				placed.add(new Placed(item, contentLeft + PAD, y, inner, HEADING_HEIGHT));
				y += HEADING_HEIGHT;
				continue;
			}
			placed.add(new Placed(item, contentLeft + PAD + column * (cardWidth + GAP), y, cardWidth, CARD_HEIGHT));
			if (++column == columns) {
				column = 0;
				y += CARD_HEIGHT + GAP;
			}
		}
		if (column > 0) {
			y += CARD_HEIGHT + GAP;
		}
		contentHeight = y;
		scroll = Math.clamp(scroll, 0, maxScroll());
		layoutSidebar();
	}

	private void layoutSidebar() {
		int total = sidebarButtons.size() * SIDEBAR_ITEM;
		sidebarScroll = Math.clamp(sidebarScroll, 0, Math.max(0, total - (listBottom - TOP)));
		int y = TOP - sidebarScroll;
		for (SidebarButton b : sidebarButtons) {
			b.button().setY(y + 1);
			b.button().visible = y >= TOP && y + SIDEBAR_ITEM <= listBottom + 2;
			y += SIDEBAR_ITEM;
		}
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (listBottom - listTop - 4));
	}

	/** Top of a placed item on screen. */
	private int screenY(Placed p) {
		return listTop + 2 + p.y() - scroll;
	}

	private boolean visible(Placed p) {
		int top = screenY(p);
		return top >= listTop && top + p.height() <= listBottom;
	}

	private @Nullable Placed cardAt(double mouseX, double mouseY) {
		if (mouseY < listTop || mouseY > listBottom) {
			return null;
		}
		for (Placed p : placed) {
			if (p.item() instanceof Card && visible(p)) {
				int top = screenY(p);
				if (mouseX >= p.x() && mouseX < p.x() + p.width() && mouseY >= top && mouseY < top + p.height()) {
					return p;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		if (hasSidebar) {
			graphics.fill(sidebarLeft, TOP - 2, sidebarLeft + sidebarWidth, listBottom + 2, PANEL);
		}
		graphics.fill(contentLeft, TOP - 2, contentRight, listBottom + 2, PANEL);
		if (!header.isEmpty()) {
			graphics.fill(contentLeft, listTop - 3, contentRight, listTop - 2, DIVIDER);
		}
		Placed hovered = cardAt(mouseX, mouseY);
		for (Placed p : placed) {
			if (!visible(p)) {
				continue;
			}
			int top = screenY(p);
			if (p.item() instanceof Card card) {
				int colour = !card.clickable() ? CARD_INFO : p == hovered ? CARD_HOVER : CARD;
				graphics.fill(p.x(), top, p.x() + p.width(), top + p.height(), colour);
				if (card.highlighted()) {
					graphics.fill(p.x(), top, p.x() + 2, top + p.height(), SELECTED_MARK);
				}
			} else {
				graphics.fill(p.x() + 1, top + p.height() - 3, p.x() + p.width() - 1, top + p.height() - 2, DIVIDER);
			}
		}
		int maxScroll = maxScroll();
		if (maxScroll > 0) {
			int track = listBottom - listTop;
			int thumb = Math.max(16, track * track / (track + maxScroll));
			int thumbTop = listTop + (track - thumb) * scroll / maxScroll;
			graphics.fill(contentRight - 4, listTop, contentRight - 2, listBottom, 0x30FFFFFF);
			graphics.fill(contentRight - 4, thumbTop, contentRight - 2, thumbTop + thumb, 0xC0FFFFFF);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Component title = Component.literal(trimTo(getTitle().getString(), (hasSidebar ? width : contentRight - contentLeft) - 2 * MARGIN))
				.setStyle(getTitle().getStyle());
		if (hasSidebar) {
			graphics.text(font, title, sidebarLeft + 2, 12, GOLD, true);
		} else {
			graphics.centeredText(font, title, width / 2, 12, GOLD);
		}

		int lineY = TOP + 3;
		for (Component line : header) {
			graphics.text(font, Component.literal(trimTo(line.getString(), contentRight - contentLeft - 2 * PAD)).setStyle(line.getStyle()),
					contentLeft + PAD + 1, lineY, WHITE, true);
			lineY += HEADER_LINE;
		}

		Placed hovered = cardAt(mouseX, mouseY);
		boolean anyCard = false;
		for (Placed p : placed) {
			if (p.item() instanceof Card) {
				anyCard = true;
			}
			if (!visible(p)) {
				continue;
			}
			int top = screenY(p);
			switch (p.item()) {
				case Card card -> drawCard(graphics, card, p.x(), top, p.width());
				case Heading heading -> graphics.text(font, heading.text().copy().withStyle(ChatFormatting.BOLD), p.x() + 2, top + 5, GOLD, true);
			}
		}
		if (!anyCard) {
			graphics.centeredText(font, emptyText(), (contentLeft + contentRight) / 2, listTop + 20, GRAY);
		}
		if (hovered != null && hovered.item() instanceof Card card) {
			List<Component> tooltip = new ArrayList<>();
			tooltip.add(card.title());
			tooltip.addAll(card.lines());
			if (tooltip.size() > 1 || font.width(card.title()) > hovered.width() - 34) {
				graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
			}
		}

		Component status = MenuStatus.current();
		if (status != null) {
			graphics.text(font, Component.literal(trimTo(status.getString(), footerLeft - 2 * MARGIN)).setStyle(status.getStyle()),
					MARGIN, height - FOOTER_HEIGHT + 11, WHITE, true);
		}
	}

	private void drawCard(GuiGraphicsExtractor graphics, Card card, int x, int top, int width) {
		int textLeft = x + 26;
		int textWidth = width - 30;
		if (!card.icon().isEmpty()) {
			graphics.item(card.icon(), x + 5, top + (CARD_HEIGHT - 16) / 2);
			if (card.icon().getCount() > 1) {
				String count = Integer.toString(card.icon().getCount());
				graphics.text(font, Component.literal(count), x + 22 - font.width(count), top + CARD_HEIGHT / 2 + 1, WHITE, true);
			}
		}
		graphics.text(font, Component.literal(trimTo(card.title().getString(), textWidth)).setStyle(card.title().getStyle()),
				textLeft, top + 5, WHITE, true);
		if (!card.lines().isEmpty()) {
			Component first = card.lines().getFirst();
			graphics.text(font, Component.literal(trimTo(first.getString(), textWidth)).setStyle(first.getStyle()),
					textLeft, top + 17, GRAY, false);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		Placed p = cardAt(event.x(), event.y());
		if (p == null || !(p.item() instanceof Card card) || !card.clickable()) {
			return false;
		}
		playClickSound();
		Click click = new Click(event.button(), event.hasShiftDown() || keyHeld(InputConstants.KEY_LSHIFT) || keyHeld(InputConstants.KEY_RSHIFT),
				event.hasControlDown() || keyHeld(InputConstants.KEY_LCONTROL) || keyHeld(InputConstants.KEY_RCONTROL));
		try {
			card.action().run(click);
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Menu entry '" + card.id() + "' failed on click", e);
			MenuStatus.show(Component.literal("That action failed, see the log").withStyle(ChatFormatting.RED));
		}
		return true;
	}

	// Event modifiers come from GLFW; the live key state is checked too so synthetic input
	// (automation, client game tests) that does not set modifier bits behaves the same.
	private boolean keyHeld(int key) {
		return minecraft != null && InputConstants.isKeyDown(minecraft.getWindow(), key);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0 && hasSidebar && x < contentLeft - MARGIN / 2) {
			sidebarScroll -= (int) Math.signum(scrollY) * SIDEBAR_ITEM;
			layoutSidebar();
			return true;
		}
		if (scrollY != 0 && y >= listTop && y <= listBottom) {
			scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * (CARD_HEIGHT + GAP), 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	protected void playClickSound() {
		if (minecraft == null) {
			return;
		}
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
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	/** Closes every menu, back to the game. */
	protected void closeAll() {
		if (minecraft != null) {
			minecraft.setScreen(null);
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

	// ---------------------------------------------------------------- helpers for subclasses

	/** A card for an addon's {@link MenuEntry}; a click runs its action with this screen as the context. */
	protected Card entryCard(MenuEntry entry) {
		ItemStack icon;
		try {
			icon = entry.createIcon();
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Icon of menu entry '" + entry.id() + "' failed", e);
			icon = null;
		}
		Component title;
		List<Component> lines;
		try {
			title = entry.title();
			lines = entry.description();
		} catch (RuntimeException e) {
			title = Component.literal(entry.id());
			lines = List.of(Component.literal("Error: " + e.getMessage()).withStyle(ChatFormatting.RED));
		}
		CardAction action = entry.action() == MenuAction.NONE ? null : click -> entry.action().onClick(new Context(click));
		return new Card(entry.id(), icon == null || icon.isEmpty() ? new ItemStack(Items.BARRIER) : icon, title, lines, action, false);
	}

	protected static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	protected static MutableComponent dark(String text) {
		return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
	}

	protected String trimTo(String text, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	/** The {@link MenuContext} of a click on an addon's entry. */
	private final class Context implements MenuContext {
		private final Click click;

		private Context(Click click) {
			this.click = click;
		}

		@Override
		public int button() {
			return click.button();
		}

		@Override
		public boolean shiftDown() {
			return click.shift();
		}

		@Override
		public boolean controlDown() {
			return click.control();
		}

		@Override
		public LocalPlayer player() {
			return minecraft.player;
		}

		@Override
		public Screen screen() {
			return CardScreen.this;
		}

		@Override
		public void openPage(Component title, Supplier<List<MenuEntry>> entries) {
			minecraft.setScreen(new EntryPageScreen(title, entries, CardScreen.this));
		}

		@Override
		public void openScreen(Screen screen) {
			minecraft.setScreen(screen);
		}

		@Override
		public void refresh() {
			CardScreen.this.refresh();
		}

		@Override
		public void back() {
			minecraft.setScreen(parent);
		}

		@Override
		public void close() {
			closeAll();
		}
	}

	// ---------------------------------------------------------------- for tests and tooling

	/** IDs of the cards, in order. */
	public List<String> cardIds() {
		List<String> ids = new ArrayList<>();
		for (Item item : items) {
			if (item instanceof Card card) {
				ids.add(card.id());
			}
		}
		return ids;
	}

	/** The card with this ID, or {@code null}. */
	public @Nullable Card card(String id) {
		for (Item item : items) {
			if (item instanceof Card card && card.id().equals(id)) {
				return card;
			}
		}
		return null;
	}

	/** The header lines, as text. */
	public List<String> headerLines() {
		List<String> out = new ArrayList<>();
		for (Component line : header) {
			out.add(line.getString());
		}
		return out;
	}

	/** Headings, in order. */
	public List<String> headings() {
		List<String> out = new ArrayList<>();
		for (Item item : items) {
			if (item instanceof Heading heading) {
				out.add(heading.text().getString());
			}
		}
		return out;
	}

	/** Scrolls the card into view and returns its centre in GUI coordinates, or {@code null}. */
	public int @Nullable [] cardPosition(String id) {
		for (Placed p : placed) {
			if (p.item() instanceof Card card && card.id().equals(id)) {
				if (!visible(p)) {
					scroll = Math.clamp(p.y(), 0, maxScroll());
				}
				return new int[] {p.x() + p.width() / 2, screenY(p) + p.height() / 2};
			}
		}
		return null;
	}

	/** Centre of a footer button ({@code back}, {@code close}...), or {@code null}. */
	public int @Nullable [] footerPosition(String id) {
		Button button = footer.get(id);
		return button == null ? null : new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
	}

	/** Centre of a sidebar entry, scrolled into view, or {@code null}. */
	public int @Nullable [] sidebarPosition(String id) {
		for (int i = 0; i < sidebarButtons.size(); i++) {
			SidebarButton b = sidebarButtons.get(i);
			if (b.id().equals(id)) {
				if (!b.button().visible) {
					sidebarScroll = i * SIDEBAR_ITEM;
					layoutSidebar();
				}
				return new int[] {b.button().getX() + b.button().getWidth() / 2, b.button().getY() + b.button().getHeight() / 2};
			}
		}
		return null;
	}
}
