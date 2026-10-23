package com.mejta.mejtacore.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.ConfigNames;
import com.mejta.mejtacore.config.SettingGroups;
import com.mejta.mejtacore.config.SettingSteps;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.network.RemoteConfigs;

/**
 * The settings editor: every config of this computer and, on a server with MejtaCore, the server's configs in
 * a sidebar; the selected config's settings in a scrolling list, grouped by category, with a widget that fits
 * each type (toggle, slider and number field, option button, text field), a reset button per setting, search,
 * a "changed only" filter, Reset All and Undo.
 *
 * <p>Changes apply as they are made, like everywhere else in MejtaCore: a server's settings are sent to the
 * server at once (which checks and saves them), this computer's are saved when the screen closes. Undo puts
 * back every setting changed in this screen since it opened. Typed values are checked as they are typed and
 * taken once they are valid and typing pauses, or on Enter.
 */
public class SettingsScreen extends Screen {
	/** Parts of a setting row, for {@link #positionOf}. */
	public enum Part {
		/** The toggle, option button or slider; the text field where a setting has nothing else. */
		CONTROL,
		/** The number or text field. */
		FIELD,
		RESET
	}

	private static final int MARGIN = 8;
	private static final int TOP = 30;
	private static final int FOOTER_HEIGHT = 30;
	private static final int SIDEBAR_ITEM = 20;
	private static final int SIDEBAR_LABEL = 14;
	private static final int CONTENT_HEADER = 26;
	private static final int ROW_HEIGHT = 28;
	private static final int GROUP_HEIGHT = 18;
	private static final int RESET_WIDTH = 20;
	private static final int FIELD_WIDTH = 46;
	private static final int SCROLL_STEP = 24;
	private static final long COMMIT_DELAY_MILLIS = 500;
	private static final long CONFIRM_MILLIS = 4_000;
	/** Sliders are only offered where one pixel is not a huge jump. */
	private static final long MAX_SLIDER_STEPS = 100_000;
	/** Longest text a server's setting can carry (ConfigPayloads.MAX_TEXT). */
	private static final int MAX_TEXT = 4096;

	private static final int PANEL = 0x70000000;
	private static final int ROW_HOVER = 0x18FFFFFF;
	private static final int CHANGED_MARK = 0xFFFFCC33;
	private static final int ERROR_MARK = 0xFFFF5555;
	private static final int DIVIDER = 0x40FFFFFF;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int DARK_GRAY = 0xFF707070;
	private static final int GOLD = 0xFFFFAA00;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int RED = 0xFFFF5555;
	private static final int AQUA = 0xFF55FFFF;
	private static final int PURPLE = 0xFFFF55FF;
	private static final int FIELD_TEXT = 0xFFE0E0E0;

	private final @Nullable Screen parent;
	private final List<Source> sources = new ArrayList<>();
	private final List<SidebarItem> sidebar = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();
	/** Value of each setting before this screen first changed it, by source and key, for Undo. */
	private final Map<String, Map<String, Object>> originals = new LinkedHashMap<>();

	private @Nullable Source selected;
	private String wantedId;
	private boolean wantServer;
	private String query = "";
	private boolean changedOnly;
	/** The server's copy of the wanted config may still arrive: the local copy is shown meanwhile. */
	private boolean awaitingServer;
	private int sidebarScroll;
	private int contentScroll;
	private @Nullable Object shownSpec;
	private @Nullable ConfigHandle shownHandle;
	private String sourcesSignature = "";
	private long resetConfirmUntil;

	private EditBox search;
	private Button changedFilter;
	private Button resetAllButton;
	private Button undoButton;
	private Button reloadButton;
	private Button doneButton;

	// Layout, recomputed by init()
	private int sidebarLeft;
	private int sidebarWidth;
	private int contentLeft;
	private int contentRight;
	private int listTop;
	private int listBottom;
	private int controlWidth;

	/**
	 * @param configId the config to show first, or {@code null} for MejtaCore's
	 * @param server   whether to prefer the server's copy of that config while playing on a server
	 */
	public SettingsScreen(@Nullable Screen parent, @Nullable String configId, boolean server) {
		super(Component.literal("Mejta Settings"));
		this.parent = parent;
		this.wantedId = configId == null ? MejtaCore.MOD_ID : configId;
		this.wantServer = server;
		RemoteConfigs.request();
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void init() {
		sidebarLeft = MARGIN;
		sidebarWidth = Math.clamp(width / 4, 96, 150);
		contentLeft = sidebarLeft + sidebarWidth + MARGIN;
		contentRight = width - MARGIN;
		listTop = TOP + CONTENT_HEADER;
		listBottom = height - FOOTER_HEIGHT - 4;
		controlWidth = Math.clamp((contentRight - contentLeft) * 9 / 20, 90, 170);

		String text = search != null ? search.getValue() : query;
		int filterWidth = Math.min(90, Math.max(60, (contentRight - contentLeft) / 4));
		search = new EditBox(font, contentLeft + 1, 7, contentRight - contentLeft - filterWidth - 6, 18, Component.literal("Search"));
		search.setMaxLength(64);
		search.setHint(Component.literal("Search all settings...").withStyle(ChatFormatting.DARK_GRAY));
		search.setValue(text);
		search.setResponder(this::onSearch);
		addRenderableWidget(search);
		changedFilter = addRenderableWidget(Button.builder(Component.empty(), b -> {
			changedOnly = !changedOnly;
			contentScroll = 0;
			rebuildSidebar();
			rebuildRows();
		}).bounds(contentRight - filterWidth, 6, filterWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Show only settings that differ from their default")))
				.build());

		int buttonWidth = Math.min(100, (width - 2 * MARGIN - 3 * 4) / 4);
		int y = height - FOOTER_HEIGHT + 5;
		int x = width - MARGIN - 4 * buttonWidth - 3 * 4;
		reloadButton = addRenderableWidget(Button.builder(Component.literal("Reload Files"), b -> reloadFiles())
				.bounds(x, y, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Re-read this computer's files in config/mejta, discarding unsaved changes."
						+ " The server's files are reloaded with /mejtaadmin config reload.")))
				.build());
		resetAllButton = addRenderableWidget(Button.builder(Component.literal("Reset All"), b -> resetAll())
				.bounds(x + buttonWidth + 4, y, buttonWidth, 20).build());
		undoButton = addRenderableWidget(Button.builder(Component.literal("Undo Changes"), b -> undoChanges())
				.bounds(x + 2 * (buttonWidth + 4), y, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal("Put back every setting changed since this screen opened")))
				.build());
		doneButton = addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(x + 3 * (buttonWidth + 4), y, buttonWidth, 20).build());

		query = text;
		rebuildSidebar();
		rebuildRows();
		updateButtons();
	}

	private void onSearch(String text) {
		if (text.equals(query)) {
			return;
		}
		query = text;
		contentScroll = 0;
		rebuildSidebar();
		rebuildRows();
	}

	private int contentWidth() {
		return contentRight - contentLeft;
	}

	// ---------------------------------------------------------------- sources and sidebar

	/** One config in the sidebar: a file of this computer or a server's config. */
	private record Source(String id, String name, boolean server) {
		String key() {
			return (server ? "server:" : "local:") + id;
		}

		Optional<ConfigHandle> handle() {
			return server ? RemoteConfigs.find(id) : MejtaCore.configs().find(id);
		}
	}

	private sealed interface SidebarItem permits SidebarLabel, SidebarButton {
		int height();
	}

	private record SidebarLabel(Component text, @Nullable Component note) implements SidebarItem {
		@Override
		public int height() {
			return SIDEBAR_LABEL;
		}
	}

	private record SidebarButton(Source source, Button button) implements SidebarItem {
		@Override
		public int height() {
			return SIDEBAR_ITEM;
		}
	}

	private void collectSources() {
		sources.clear();
		if (RemoteConfigs.supported()) {
			for (ConfigHandle config : RemoteConfigs.configs()) {
				sources.add(new Source(config.id(), RemoteConfigs.name(config), true));
			}
		}
		for (ConfigHandle config : MejtaCore.configs().all()) {
			sources.add(new Source(config.id(), ConfigNames.displayName(config), false));
		}
		StringBuilder signature = new StringBuilder(remoteState().getString());
		for (Source source : sources) {
			signature.append('|').append(source.key());
		}
		sourcesSignature = signature.toString();
	}

	/** Why the server's configs are not listed, or empty if they are (or there is no server). */
	private static Component remoteState() {
		if (!RemoteConfigs.supported()) {
			return Component.empty();
		}
		if (RemoteConfigs.incompatibleProtocol() != 0) {
			return Component.literal("Different MejtaCore version").withStyle(ChatFormatting.RED);
		}
		if (!RemoteConfigs.received()) {
			return Component.literal("Loading...").withStyle(ChatFormatting.GRAY);
		}
		if (!RemoteConfigs.permitted()) {
			return Component.literal("No permission").withStyle(ChatFormatting.RED);
		}
		return RemoteConfigs.configs().isEmpty() ? Component.literal("Nothing to edit").withStyle(ChatFormatting.GRAY) : Component.empty();
	}

	private void rebuildSidebar() {
		for (SidebarItem item : sidebar) {
			if (item instanceof SidebarButton b) {
				removeWidget(b.button());
			}
		}
		sidebar.clear();
		collectSources();
		chooseSelection();

		boolean remote = RemoteConfigs.supported();
		boolean labelledServer = false;
		boolean labelledLocal = false;
		if (remote) {
			Component state = remoteState();
			if (!state.getString().isEmpty()) {
				sidebar.add(new SidebarLabel(Component.literal("Server").withStyle(ChatFormatting.GOLD), state));
				labelledServer = true;
			}
		}
		for (Source source : sources) {
			if (remote && source.server() && !labelledServer) {
				sidebar.add(new SidebarLabel(Component.literal("Server").withStyle(ChatFormatting.GOLD), null));
				labelledServer = true;
			}
			if (remote && !source.server() && !labelledLocal) {
				sidebar.add(new SidebarLabel(Component.literal("This Computer").withStyle(ChatFormatting.GOLD), null));
				labelledLocal = true;
			}
			Button button = Button.builder(sidebarLabel(source), b -> select(source))
					.bounds(sidebarLeft + 2, 0, sidebarWidth - 4, SIDEBAR_ITEM - 2)
					.tooltip(Tooltip.create(sidebarTooltip(source)))
					.build();
			addRenderableWidget(button);
			sidebar.add(new SidebarButton(source, button));
		}
		layoutSidebar();
	}

	private Component sidebarLabel(Source source) {
		boolean current = selected != null && selected.key().equals(source.key());
		MutableComponent label = Component.literal(trimTo(source.name(), sidebarWidth - 30));
		if (!query.isBlank() || changedOnly) {
			ConfigHandle handle = source.handle().orElse(null);
			int matches = handle == null ? 0 : visibleSettings(handle).size();
			label.append(Component.literal(" " + matches).withStyle(matches > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
		}
		return current ? Component.literal("> ").withStyle(ChatFormatting.YELLOW).append(label.withStyle(ChatFormatting.YELLOW)) : label;
	}

	private static Component sidebarTooltip(Source source) {
		return source.server()
				? Component.literal(source.name() + ": the server's config/mejta/" + source.id() + ".json. Changes are checked and saved by the server.")
				: Component.literal(source.name() + ": config/mejta/" + source.id() + ".json on this computer."
						+ (RemoteConfigs.supported() ? " Changing it does not change the server." : ""));
	}

	/** Keeps the selection if it still exists, else takes the wanted config (the server's copy first if asked). */
	private void chooseSelection() {
		if (selected != null && !awaitingServer) {
			for (Source source : sources) {
				if (source.key().equals(selected.key())) {
					selected = source;
					return;
				}
			}
			// The server's copy went away (permission revoked): fall back to the same config here, if any.
			wantedId = selected.id();
		}
		Source local = null;
		Source server = null;
		for (Source source : sources) {
			if (source.id().equals(wantedId)) {
				if (source.server()) {
					server = source;
				} else {
					local = source;
				}
			}
		}
		Source chosen = wantServer && server != null ? server : local != null ? local : server;
		if (chosen == null && !sources.isEmpty()) {
			chosen = sources.getFirst();
		}
		// While the server's catalog is loading, keep looking for its copy rather than settle on the local one.
		awaitingServer = wantServer && (chosen == null || !chosen.server()) && RemoteConfigs.supported()
				&& !RemoteConfigs.received() && RemoteConfigs.incompatibleProtocol() == 0;
		selected = chosen;
	}

	private void select(Source source) {
		if (selected != null && selected.key().equals(source.key())) {
			return;
		}
		selected = source;
		wantedId = source.id();
		wantServer = source.server();
		awaitingServer = false;
		contentScroll = 0;
		rebuildSidebar();
		rebuildRows();
		if (source.server()) {
			RemoteConfigs.request();
		}
	}

	private void layoutSidebar() {
		int total = 0;
		for (SidebarItem item : sidebar) {
			total += item.height();
		}
		int visibleHeight = listBottom - TOP;
		sidebarScroll = Math.clamp(sidebarScroll, 0, Math.max(0, total - visibleHeight));
		int y = TOP - sidebarScroll;
		for (SidebarItem item : sidebar) {
			if (item instanceof SidebarButton b) {
				b.button().setY(y + 1);
				b.button().visible = y >= TOP && y + item.height() <= listBottom;
				b.button().setMessage(sidebarLabel(b.source()));
			}
			y += item.height();
		}
	}

	// ---------------------------------------------------------------- rows

	private abstract static class Row {
		int top;

		abstract int height();

		List<AbstractWidget> widgets() {
			return List.of();
		}
	}

	private static final class GroupRow extends Row {
		final String name;
		final int count;

		GroupRow(String name, int count) {
			this.name = name;
			this.count = count;
		}

		@Override
		int height() {
			return GROUP_HEIGHT;
		}
	}

	private static final class MessageRow extends Row {
		final List<Component> lines;

		MessageRow(Component... lines) {
			this.lines = List.of(lines);
		}

		@Override
		int height() {
			return 12 * lines.size() + 8;
		}
	}

	private List<Setting<?>> visibleSettings(ConfigHandle handle) {
		List<Setting<?>> out = new ArrayList<>();
		String needle = query.trim().toLowerCase(Locale.ROOT);
		for (Setting<?> setting : handle.spec().settings()) {
			if (changedOnly && !isChanged(handle, setting)) {
				continue;
			}
			if (!needle.isEmpty() && !matches(setting, needle)) {
				continue;
			}
			out.add(setting);
		}
		return out;
	}

	private static boolean matches(Setting<?> setting, String needle) {
		return setting.displayName().toLowerCase(Locale.ROOT).contains(needle)
				|| setting.key().toLowerCase(Locale.ROOT).contains(needle)
				|| setting.description().toLowerCase(Locale.ROOT).contains(needle)
				|| SettingGroups.displayName(SettingGroups.groupOf(setting.key())).toLowerCase(Locale.ROOT).contains(needle);
	}

	private static boolean isChanged(ConfigHandle handle, Setting<?> setting) {
		return !Objects.equals(handle.get(setting), setting.defaultValue());
	}

	private void rebuildRows() {
		for (Row row : rows) {
			for (AbstractWidget widget : row.widgets()) {
				removeWidget(widget);
			}
		}
		rows.clear();
		ConfigHandle handle = selected == null ? null : selected.handle().orElse(null);
		shownHandle = handle;
		shownSpec = handle == null ? null : handle.spec();
		if (handle == null) {
			rows.add(emptyState());
		} else {
			List<Setting<?>> settings = visibleSettings(handle);
			Map<String, List<Setting<?>>> groups = SettingGroups.of(settings);
			boolean headers = groups.size() > 1 || groups.size() == 1 && !groups.containsKey(SettingGroups.GENERAL)
					&& handle.spec().settings().size() > 6;
			for (Map.Entry<String, List<Setting<?>>> group : groups.entrySet()) {
				if (headers) {
					rows.add(new GroupRow(SettingGroups.displayName(group.getKey()), group.getValue().size()));
				}
				for (Setting<?> setting : group.getValue()) {
					SettingRow row = new SettingRow(handle, setting);
					rows.add(row);
					for (AbstractWidget widget : row.widgets()) {
						addRenderableWidget(widget);
					}
				}
			}
			if (settings.isEmpty()) {
				rows.add(handle.spec().settings().isEmpty()
						? new MessageRow(Component.literal("This config has no settings").withStyle(ChatFormatting.GRAY))
						: new MessageRow(Component.literal(changedOnly && query.isBlank()
								? "Every setting here has its default value"
								: "No settings match \"" + query.trim() + "\"").withStyle(ChatFormatting.GRAY),
								Component.literal("The numbers in the list on the left show matches in each config").withStyle(ChatFormatting.DARK_GRAY)));
			}
		}
		layoutRows();
		if (resetAllButton != null) {
			resetAllButton.setTooltip(Tooltip.create(Component.literal("Restore every setting of "
					+ (selected == null ? "this config" : selected.name()) + " to its default (secret settings are kept). Click twice to confirm.")));
		}
		updateButtons();
	}

	private MessageRow emptyState() {
		if (sources.isEmpty() && RemoteConfigs.supported() && !RemoteConfigs.received()) {
			return new MessageRow(Component.literal("Loading the server's settings...").withStyle(ChatFormatting.GRAY));
		}
		if (selected != null && selected.server()) {
			return new MessageRow(Component.literal("The server no longer offers this config to you").withStyle(ChatFormatting.RED));
		}
		return new MessageRow(Component.literal("No configuration files").withStyle(ChatFormatting.GRAY));
	}

	private int contentHeight() {
		int total = 0;
		for (Row row : rows) {
			total += row.height();
		}
		return total;
	}

	private int maxContentScroll() {
		return Math.max(0, contentHeight() - (listBottom - listTop));
	}

	private void layoutRows() {
		contentScroll = Math.clamp(contentScroll, 0, maxContentScroll());
		int y = listTop - contentScroll;
		for (Row row : rows) {
			row.top = y;
			boolean visible = y >= listTop && y + row.height() <= listBottom;
			if (row instanceof SettingRow setting) {
				setting.place(visible);
			}
			y += row.height();
		}
	}

	private void scrollBy(int pixels) {
		contentScroll = Math.clamp(contentScroll + pixels, 0, maxContentScroll());
		layoutRows();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0 && x < contentLeft - MARGIN / 2) {
			sidebarScroll -= (int) Math.signum(scrollY) * SIDEBAR_ITEM;
			layoutSidebar();
			return true;
		}
		if (scrollY != 0 && y >= listTop && y <= listBottom) {
			scrollBy(-(int) Math.signum(scrollY) * SCROLL_STEP);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	/** One setting: its name and description on the left, its widgets on the right. */
	private final class SettingRow extends Row {
		final ConfigHandle handle;
		final Setting<?> setting;
		final boolean sensitive;
		final boolean remote;
		@Nullable Button button;
		@Nullable ValueSlider slider;
		@Nullable EditBox field;
		final Button reset;
		@Nullable String error;
		/** Text typed and not taken yet, and when it was last changed. */
		@Nullable String pendingText;
		long pendingSince;
		/** A slider value not sent yet (dragging sends only once the slider rests). */
		@Nullable Object pendingValue;
		boolean fieldWasFocused;
		boolean syncing;

		SettingRow(ConfigHandle handle, Setting<?> setting) {
			this.handle = handle;
			this.setting = setting;
			this.sensitive = SettingText.isSensitive(setting);
			this.remote = RemoteConfigs.isRemote(handle);
			int right = contentRight - 6;
			int controlLeft = right - RESET_WIDTH - 4 - controlWidth;
			switch (sensitive ? SettingType.STRING : setting.type()) {
				case BOOLEAN, ENUM -> button = Button.builder(Component.empty(), b -> pressed())
						.bounds(controlLeft, 0, controlWidth, 20).build();
				case INTEGER, DECIMAL -> {
					if (sliderSteps(setting) > 0 && controlWidth >= FIELD_WIDTH + 50) {
						int sliderWidth = controlWidth - FIELD_WIDTH - 4;
						slider = new ValueSlider(controlLeft, sliderWidth, this);
						field = newField(controlLeft + sliderWidth + 4, FIELD_WIDTH);
					} else {
						field = newField(controlLeft, controlWidth);
					}
				}
				case STRING -> {
					if (sensitive) {
						button = Button.builder(Component.empty(), b -> editSecret()).bounds(controlLeft, 0, controlWidth, 20).build();
					} else {
						field = newField(controlLeft, controlWidth);
					}
				}
			}
			reset = Button.builder(Component.literal("\u21BA"), b -> resetToDefault())
					.bounds(right - RESET_WIDTH, 0, RESET_WIDTH, 20)
					.tooltip(Tooltip.create(Component.literal(sensitive
							? "Clear the value" : "Reset to the default: " + format(setting.defaultValue()))))
					.build();
			sync(true);
		}

		private EditBox newField(int x, int fieldWidth) {
			EditBox box = new EditBox(font, x + 1, 0, fieldWidth - 2, 18, Component.literal(setting.displayName()));
			box.setMaxLength(setting.type() == SettingType.STRING ? MAX_TEXT : 32);
			box.setValue(text(handle.get(setting)));
			box.setResponder(this::typed);
			return box;
		}

		@Override
		int height() {
			return ROW_HEIGHT;
		}

		@Override
		List<AbstractWidget> widgets() {
			List<AbstractWidget> out = new ArrayList<>(4);
			if (button != null) {
				out.add(button);
			}
			if (slider != null) {
				out.add(slider);
			}
			if (field != null) {
				out.add(field);
			}
			out.add(reset);
			return out;
		}

		void place(boolean visible) {
			int y = top + (ROW_HEIGHT - 20) / 2;
			for (AbstractWidget widget : widgets()) {
				widget.setY(widget instanceof EditBox ? y + 1 : y);
				widget.visible = visible;
			}
		}

		Object current() {
			return handle.get(setting);
		}

		String text(Object value) {
			return sensitive ? "" : format(value);
		}

		/** Brings the widgets in line with the handle's value (changed by the server, a reset, Undo...). */
		void sync(boolean force) {
			Object value = current();
			syncing = true;
			try {
				if (button != null) {
					button.setMessage(sensitive
							? Component.literal(SettingText.display(setting, value) + "  Change...").withStyle(ChatFormatting.AQUA)
							: valueText(setting, value));
				}
				boolean editing = field != null && field.isFocused() || pendingText != null || pendingValue != null;
				if (slider != null && (force || !editing && !slider.isFocused())) {
					slider.show(value);
				}
				if (field != null && (force || !editing) && !field.getValue().equals(text(value))) {
					field.setValue(text(value));
					error = null;
					field.setTextColor(FIELD_TEXT);
				}
			} finally {
				syncing = false;
			}
			reset.active = isChanged(handle, setting);
		}

		void tick() {
			long now = System.currentTimeMillis();
			if (pendingText != null && now - pendingSince >= COMMIT_DELAY_MILLIS) {
				commitText();
			}
			if (pendingValue != null && now - pendingSince >= COMMIT_DELAY_MILLIS / 2) {
				Object value = pendingValue;
				pendingValue = null;
				commit(value);
			}
			boolean focused = field != null && field.isFocused();
			if (fieldWasFocused && !focused) {
				// Leaving the field takes a valid value at once and drops an invalid one.
				if (pendingText != null) {
					commitText();
				}
				if (error != null) {
					error = null;
					sync(true);
				}
			}
			fieldWasFocused = focused;
			sync(false);
		}

		void pressed() {
			Object value = current();
			Object next = switch (setting.type()) {
				case BOOLEAN -> !Boolean.TRUE.equals(value);
				case ENUM -> SettingSteps.cycle(setting, value, shiftDown() ? -1 : 1);
				default -> value;
			};
			if (!SettingSteps.changes(value, next)) {
				MenuStatus.show(Component.literal(SettingSteps.unchangedReason(setting, value, shiftDown())).withStyle(ChatFormatting.YELLOW));
				return;
			}
			commit(next);
		}

		void typed(String text) {
			if (syncing) {
				return;
			}
			Object value = SettingText.parse(setting, text);
			error = problem(value, text);
			if (field != null) {
				field.setTextColor(error == null ? FIELD_TEXT : RED);
			}
			if (error == null && slider != null) {
				slider.show(value);
			}
			pendingText = error == null && !Objects.equals(value, current()) ? text : null;
			pendingSince = System.currentTimeMillis();
		}

		void slid(Object value) {
			if (syncing) {
				return;
			}
			if (field != null) {
				syncing = true;
				field.setValue(text(value));
				field.setTextColor(FIELD_TEXT);
				syncing = false;
			}
			error = null;
			pendingText = null;
			pendingValue = Objects.equals(value, current()) ? null : value;
			pendingSince = System.currentTimeMillis();
		}

		/** Why {@code text} cannot be taken, or {@code null} if it can. */
		@Nullable String problem(@Nullable Object value, String text) {
			if (value == null) {
				return switch (setting.type()) {
					case INTEGER -> text.isBlank() ? "Enter a whole number" : "\"" + text.trim() + "\" is not a whole number";
					case DECIMAL -> text.isBlank() ? "Enter a number" : "\"" + text.trim() + "\" is not a number";
					default -> "Not a valid value";
				};
			}
			if (setting.isValid(value)) {
				return null;
			}
			if ((setting.type() == SettingType.INTEGER || setting.type() == SettingType.DECIMAL) && SettingSteps.hasRealRange(setting)) {
				double d = ((Number) value).doubleValue();
				if (d < setting.min().doubleValue() || d > setting.max().doubleValue()) {
					return "Must be from " + format(setting.min()) + " to " + format(setting.max());
				}
			}
			return setting.type() == SettingType.STRING && text.isBlank() ? "Cannot be empty" : "This value is not allowed";
		}

		void commitText() {
			String text = pendingText;
			pendingText = null;
			if (text == null) {
				return;
			}
			Object value = SettingText.parse(setting, text);
			if (problem(value, text) == null) {
				commit(value);
			}
		}

		void commit(Object value) {
			Object before = current();
			if (Objects.equals(before, value)) {
				return;
			}
			remember(this, before);
			if (!trySet(handle, setting, value)) {
				MenuStatus.show(Component.literal((sensitive ? "The value" : format(value)) + " is not allowed for "
						+ setting.displayName()).withStyle(ChatFormatting.RED));
				sync(true);
				return;
			}
			if (!remote) {
				MenuStatus.show(Component.literal(setting.displayName() + " set to " + (sensitive ? "(set)" : format(value)))
						.withStyle(ChatFormatting.GREEN));
			}
			sync(true);
			if (changedOnly) {
				rebuildSidebar();
			}
		}

		void resetToDefault() {
			pendingText = null;
			pendingValue = null;
			error = null;
			if (isChanged(handle, setting)) {
				remember(this, current());
				handle.reset(setting);
				if (!remote) {
					MenuStatus.show(Component.literal(setting.displayName() + " reset to its default").withStyle(ChatFormatting.GREEN));
				}
			}
			sync(true);
		}

		void editSecret() {
			if (minecraft == null) {
				return;
			}
			Component hint = Component.literal("Secret: type a new value; the current one is not shown");
			minecraft.setScreen(new TextInputScreen(SettingsScreen.this, Component.literal("Edit " + setting.displayName()), "", hint,
					text -> setting.isValid(text), text -> commit(text)));
		}

		void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			int textRight = contentRight - 6 - RESET_WIDTH - 4 - controlWidth - 6;
			int x = contentLeft + 8;
			boolean changed = isChanged(handle, setting);
			MutableComponent name = Component.literal(trimTo(setting.displayName(), textRight - x - (setting.isExperimental() ? 24 : 0)))
					.withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
			graphics.text(font, name, x, top + 5, WHITE, true);
			if (setting.isExperimental()) {
				graphics.text(font, Component.literal("EXP").withStyle(ChatFormatting.LIGHT_PURPLE), x + font.width(name) + 4, top + 5, PURPLE, true);
			}
			Component second = error != null
					? Component.literal(trimTo(error, textRight - x)).withStyle(ChatFormatting.RED)
					: Component.literal(trimTo(secondLine(), textRight - x)).withStyle(ChatFormatting.GRAY);
			graphics.text(font, second, x, top + 16, GRAY, false);
			if (mouseX >= contentLeft && mouseX < textRight && mouseY >= top && mouseY < top + ROW_HEIGHT) {
				graphics.setComponentTooltipForNextFrame(font, tooltip(), mouseX, mouseY);
			}
		}

		private String secondLine() {
			if (!setting.description().isEmpty()) {
				return setting.description();
			}
			return sensitive ? "Secret" : "Default: " + format(setting.defaultValue());
		}

		List<Component> tooltip() {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(setting.displayName()).withStyle(ChatFormatting.WHITE));
			if (setting.isExperimental()) {
				lines.add(Component.literal("Experimental: may still change").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			for (String line : wrap(setting.description(), 260)) {
				lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
			}
			if (sensitive) {
				lines.add(Component.literal("Secret: the value is never shown").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				lines.add(Component.literal("Default: ").withStyle(ChatFormatting.DARK_GRAY).append(valueText(setting, setting.defaultValue())));
			}
			if ((setting.type() == SettingType.INTEGER || setting.type() == SettingType.DECIMAL) && SettingSteps.hasRealRange(setting)) {
				lines.add(Component.literal("Range: " + format(setting.min()) + " to " + format(setting.max())).withStyle(ChatFormatting.DARK_GRAY));
			}
			if (setting.type() == SettingType.ENUM && !sensitive) {
				lines.add(Component.literal("Options: " + String.join(", ", setting.options())).withStyle(ChatFormatting.DARK_GRAY));
				lines.add(Component.literal("Click: next option, Shift-click: previous").withStyle(ChatFormatting.DARK_GRAY));
			}
			lines.add(Component.literal("Key: " + setting.key()).withStyle(ChatFormatting.DARK_GRAY));
			return lines;
		}
	}

	/** A slider over a number setting's range, in its steps (whole numbers, or the decimal step). */
	private final class ValueSlider extends AbstractSliderButton {
		private final SettingRow row;
		private final Setting<?> setting;
		private boolean showing;

		ValueSlider(int x, int sliderWidth, SettingRow row) {
			this.row = row;
			this.setting = row.setting;
			super(x, 0, sliderWidth, 20, Component.empty(), 0.0);
		}

		/** Moves the knob to {@code value} without sending anything. */
		void show(@Nullable Object value) {
			if (!(value instanceof Number number)) {
				return;
			}
			double min = setting.min().doubleValue();
			double span = setting.max().doubleValue() - min;
			showing = true;
			this.value = span <= 0 ? 0.0 : Math.clamp((number.doubleValue() - min) / span, 0.0, 1.0);
			updateMessage();
			showing = false;
		}

		Object valueAt(double fraction) {
			if (setting.type() == SettingType.INTEGER) {
				long min = setting.min().longValue();
				long max = setting.max().longValue();
				return (int) Math.clamp(min + Math.round(fraction * (max - min)), min, max);
			}
			double min = setting.min().doubleValue();
			double max = setting.max().doubleValue();
			BigDecimal step = BigDecimal.valueOf(SettingSteps.decimalStep(setting));
			BigDecimal raw = BigDecimal.valueOf(min + fraction * (max - min));
			double snapped = raw.divide(step, 0, RoundingMode.HALF_UP).multiply(step).doubleValue();
			return Math.clamp(snapped, min, max);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(format(valueAt(value))));
		}

		@Override
		protected void applyValue() {
			if (!showing) {
				row.slid(valueAt(value));
			}
		}
	}

	private static long sliderSteps(Setting<?> setting) {
		if (!SettingSteps.hasRealRange(setting)) {
			return 0;
		}
		double span = setting.max().doubleValue() - setting.min().doubleValue();
		double steps = setting.type() == SettingType.INTEGER ? span : span / SettingSteps.decimalStep(setting);
		return Double.isFinite(steps) && steps >= 1 && steps <= MAX_SLIDER_STEPS ? (long) steps : 0;
	}

	@SuppressWarnings("unchecked")
	private static <T> boolean trySet(ConfigHandle handle, Setting<?> setting, Object value) {
		return handle.trySet((Setting<T>) setting, (T) value);
	}

	// ---------------------------------------------------------------- footer actions

	private void remember(SettingRow row, Object before) {
		if (selected == null) {
			return;
		}
		originals.computeIfAbsent(selected.key(), k -> new LinkedHashMap<>()).putIfAbsent(row.setting.key(), before);
	}

	private void undoChanges() {
		int restored = 0;
		for (Map.Entry<String, Map<String, Object>> entry : originals.entrySet()) {
			Source source = sourceByKey(entry.getKey());
			ConfigHandle handle = source == null ? null : source.handle().orElse(null);
			if (handle == null) {
				continue;
			}
			for (Map.Entry<String, Object> original : entry.getValue().entrySet()) {
				Setting<?> setting = handle.spec().setting(original.getKey()).orElse(null);
				if (setting != null && !Objects.equals(handle.get(setting), original.getValue())
						&& trySet(handle, setting, original.getValue())) {
					restored++;
				}
			}
		}
		originals.clear();
		MenuStatus.show(Component.literal(restored == 0 ? "Nothing to undo"
				: "Put back " + restored + " setting" + (restored == 1 ? "" : "s")).withStyle(ChatFormatting.GREEN));
		resyncRows();
	}

	private @Nullable Source sourceByKey(String key) {
		for (Source source : sources) {
			if (source.key().equals(key)) {
				return source;
			}
		}
		return null;
	}

	private void resetAll() {
		ConfigHandle handle = shownHandle;
		if (handle == null || selected == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now > resetConfirmUntil) {
			resetConfirmUntil = now + CONFIRM_MILLIS;
			updateButtons();
			return;
		}
		resetConfirmUntil = 0;
		Map<String, Object> remembered = originals.computeIfAbsent(selected.key(), k -> new LinkedHashMap<>());
		for (Setting<?> setting : handle.spec().settings()) {
			if (!SettingText.isSensitive(setting)) {
				remembered.putIfAbsent(setting.key(), handle.get(setting));
			}
		}
		if (RemoteConfigs.isRemote(handle)) {
			// One RESET_ALL edit: the server's rate limit would refuse setting-by-setting resets of a large config.
			handle.resetAll();
		} else {
			// Secrets are kept, as with a server's Reset All.
			ConfigEditing.Result result = ConfigEditing.apply(handle, ConfigEditing.Op.RESET_ALL, null, null, false);
			MenuStatus.show(!result.ok() ? Component.literal(result.message()).withStyle(ChatFormatting.RED)
					: Component.literal(result.message().contains("kept")
							? "Settings reset to defaults (secret settings kept)" : "Settings reset to defaults")
							.withStyle(ChatFormatting.GREEN));
		}
		resyncRows();
	}

	private void reloadFiles() {
		int count = MejtaCore.configs().reloadAll();
		MenuStatus.show(Component.literal("Reloaded " + count + " configuration file(s)"
				+ (RemoteConfigs.supported() ? " on this computer" : "")).withStyle(ChatFormatting.GREEN));
		resyncRows();
	}

	private void resyncRows() {
		for (Row row : rows) {
			if (row instanceof SettingRow setting) {
				setting.pendingText = null;
				setting.pendingValue = null;
				setting.error = null;
				setting.sync(true);
			}
		}
		if (changedOnly) {
			rebuildRows();
		}
		rebuildSidebar();
		updateButtons();
	}

	private boolean hasUndo() {
		for (Map.Entry<String, Map<String, Object>> entry : originals.entrySet()) {
			Source source = sourceByKey(entry.getKey());
			ConfigHandle handle = source == null ? null : source.handle().orElse(null);
			if (handle == null) {
				continue;
			}
			for (Map.Entry<String, Object> original : entry.getValue().entrySet()) {
				Optional<Setting<?>> setting = handle.spec().setting(original.getKey());
				if (setting.isPresent() && !Objects.equals(handle.get(setting.get()), original.getValue())) {
					return true;
				}
			}
		}
		return false;
	}

	private void updateButtons() {
		if (doneButton == null) {
			return;
		}
		changedFilter.setMessage(Component.literal(changedOnly ? "Show: Changed" : "Show: All").withStyle(changedOnly ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
		boolean confirming = System.currentTimeMillis() < resetConfirmUntil;
		resetAllButton.setMessage(confirming ? Component.literal("Confirm Reset").withStyle(ChatFormatting.RED) : Component.literal("Reset All"));
		ConfigHandle handle = shownHandle;
		boolean anyChanged = false;
		if (handle != null) {
			for (Setting<?> setting : handle.spec().settings()) {
				if (!SettingText.isSensitive(setting) && isChanged(handle, setting)) {
					anyChanged = true;
					break;
				}
			}
		}
		resetAllButton.active = anyChanged || confirming;
		undoButton.active = hasUndo();
	}

	// ---------------------------------------------------------------- ticking and drawing

	@Override
	public void tick() {
		super.tick();
		collectSourcesIfChanged();
		ConfigHandle handle = selected == null ? null : selected.handle().orElse(null);
		if (handle != shownHandle || handle != null && handle.spec() != shownSpec) {
			// The server sent a new catalog (another handle, or settings added or removed): rebuild, keep the place.
			int scroll = contentScroll;
			rebuildRows();
			contentScroll = scroll;
			layoutRows();
		}
		for (Row row : rows) {
			if (row instanceof SettingRow setting) {
				setting.tick();
			}
		}
		updateButtons();
	}

	private void collectSourcesIfChanged() {
		String before = sourcesSignature;
		Source was = selected;
		collectSources();
		if (!before.equals(sourcesSignature)) {
			rebuildSidebar();
			if (was != selected) {
				rebuildRows();
			}
		} else {
			layoutSidebar();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		graphics.fill(sidebarLeft, TOP - 2, sidebarLeft + sidebarWidth, listBottom + 2, PANEL);
		graphics.fill(contentLeft, TOP - 2, contentRight, listBottom + 2, PANEL);
		graphics.fill(contentLeft, listTop - 2, contentRight, listTop - 1, DIVIDER);
		for (Row row : rows) {
			if (row.top < listTop || row.top + row.height() > listBottom) {
				continue;
			}
			if (row instanceof SettingRow setting) {
				if (mouseX >= contentLeft && mouseX < contentRight && mouseY >= row.top && mouseY < row.top + row.height()) {
					graphics.fill(contentLeft + 1, row.top, contentRight - 1, row.top + row.height(), ROW_HOVER);
				}
				if (setting.error != null) {
					graphics.fill(contentLeft + 2, row.top + 3, contentLeft + 4, row.top + row.height() - 3, ERROR_MARK);
				} else if (isChanged(setting.handle, setting.setting)) {
					graphics.fill(contentLeft + 2, row.top + 3, contentLeft + 4, row.top + row.height() - 3, CHANGED_MARK);
				}
			} else if (row instanceof GroupRow) {
				graphics.fill(contentLeft + 6, row.top + row.height() - 3, contentRight - 6, row.top + row.height() - 2, DIVIDER);
			}
		}
		int maxScroll = maxContentScroll();
		if (maxScroll > 0) {
			int track = listBottom - listTop;
			int thumb = Math.max(16, track * track / (track + maxScroll));
			int thumbTop = listTop + (track - thumb) * contentScroll / maxScroll;
			graphics.fill(contentRight - 4, listTop, contentRight - 2, listBottom, 0x30FFFFFF);
			graphics.fill(contentRight - 4, thumbTop, contentRight - 2, thumbTop + thumb, 0xC0FFFFFF);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.text(font, getTitle(), sidebarLeft + 2, 12, GOLD, true);

		int y = TOP - sidebarScroll;
		for (SidebarItem item : sidebar) {
			if (item instanceof SidebarLabel label && y >= TOP && y + item.height() <= listBottom) {
				graphics.text(font, label.text(), sidebarLeft + 4, y + 3, GOLD, true);
				if (label.note() != null) {
					graphics.text(font, label.note(), sidebarLeft + 8 + font.width(label.text()), y + 3, GRAY, false);
				}
			}
			y += item.height();
		}

		drawContentHeader(graphics);
		for (Row row : rows) {
			if (row.top < listTop || row.top + row.height() > listBottom) {
				continue;
			}
			switch (row) {
				case SettingRow setting -> setting.draw(graphics, mouseX, mouseY);
				case GroupRow group -> graphics.text(font, Component.literal(group.name).withStyle(ChatFormatting.BOLD)
						.append(Component.literal("  " + group.count).withStyle(ChatFormatting.DARK_GRAY)), contentLeft + 6, row.top + 5, GOLD, true);
				case MessageRow message -> {
					int lineY = row.top + 6;
					for (Component line : message.lines) {
						graphics.centeredText(font, line, (contentLeft + contentRight) / 2, lineY, GRAY);
						lineY += 12;
					}
				}
				default -> {
				}
			}
		}

		Component status = MenuStatus.current();
		if (status != null) {
			int statusRight = reloadButton.getX() - 6;
			graphics.text(font, Component.literal(trimTo(status.getString(), statusRight - MARGIN)).setStyle(status.getStyle()),
					MARGIN, height - FOOTER_HEIGHT + 11, WHITE, true);
		}
	}

	private void drawContentHeader(GuiGraphicsExtractor graphics) {
		int x = contentLeft + 6;
		if (selected == null) {
			graphics.text(font, Component.literal("Settings").withStyle(ChatFormatting.BOLD), x, TOP + 2, WHITE, true);
			return;
		}
		graphics.text(font, Component.literal(selected.name()).withStyle(ChatFormatting.BOLD), x, TOP + 2, WHITE, true);
		Component where = selected.server()
				? Component.literal("Server: changes are checked and saved by the server at once").withStyle(ChatFormatting.AQUA)
				: RemoteConfigs.supported()
						? Component.literal("This computer only: does not change the server").withStyle(ChatFormatting.YELLOW)
						: Component.literal("config/mejta/" + selected.id() + ".json, saved when you close this screen").withStyle(ChatFormatting.GRAY);
		graphics.text(font, Component.literal(trimTo(where.getString(), contentWidth() - 12)).setStyle(where.getStyle()), x, TOP + 13,
				selected.server() ? AQUA : YELLOW, false);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			for (Row row : rows) {
				if (row instanceof SettingRow setting && setting.field != null && setting.field.isFocused()) {
					setting.commitText();
					return true;
				}
			}
		}
		return super.keyPressed(event);
	}

	private boolean shiftDown() {
		return minecraft != null && (InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LSHIFT)
				|| InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RSHIFT));
	}

	@Override
	public void onClose() {
		for (Row row : rows) {
			if (row instanceof SettingRow setting) {
				setting.commitText();
				if (setting.pendingValue != null) {
					Object value = setting.pendingValue;
					setting.pendingValue = null;
					setting.commit(value);
				}
			}
		}
		if (minecraft != null) {
			minecraft.setScreen(parent);
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

	// ---------------------------------------------------------------- text helpers

	static String format(Object value) {
		if (value instanceof Double d) {
			BigDecimal decimal = BigDecimal.valueOf(d).stripTrailingZeros();
			return decimal.scale() <= 0 ? decimal.setScale(1).toPlainString() : decimal.toPlainString();
		}
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		return String.valueOf(value);
	}

	private static MutableComponent valueText(Setting<?> setting, Object value) {
		return switch (setting.type()) {
			case BOOLEAN -> Boolean.TRUE.equals(value)
					? Component.literal("ON").withStyle(ChatFormatting.GREEN)
					: Component.literal("OFF").withStyle(ChatFormatting.RED);
			case STRING -> Component.literal("\"" + value + "\"").withStyle(ChatFormatting.AQUA);
			default -> Component.literal(prettyOption(format(value))).withStyle(ChatFormatting.AQUA);
		};
	}

	/** {@code DRY_RUN} reads "Dry Run"; options that are not in capitals are shown as they are. */
	private static String prettyOption(String option) {
		if (!option.equals(option.toUpperCase(Locale.ROOT)) || option.chars().noneMatch(Character::isLetter)) {
			return option;
		}
		StringBuilder out = new StringBuilder();
		for (String word : option.toLowerCase(Locale.ROOT).split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	private String trimTo(String text, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && font.width(candidate) > maxWidth) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	// ---------------------------------------------------------------- for tests and tooling

	/** ID of the config shown, or {@code null}. */
	public @Nullable String selectedConfigId() {
		return selected == null ? null : selected.id();
	}

	/** Whether the config shown is the server's. */
	public boolean showsServerConfig() {
		return selected != null && selected.server();
	}

	/** Shows {@code configId} (the server's copy if {@code server}); {@code false} if it is not listed. */
	public boolean selectConfig(String configId, boolean server) {
		for (Source source : sources) {
			if (source.id().equals(configId) && source.server() == server) {
				select(source);
				return true;
			}
		}
		return false;
	}

	/** Why the server's configs are not listed ("Loading...", "No permission"...), or "" if they are or there is no server. */
	public String serverState() {
		return remoteState().getString();
	}

	/** Whether the sidebar lists {@code configId} (the server's copy if {@code server}). */
	public boolean listsConfig(String configId, boolean server) {
		return sources.stream().anyMatch(s -> s.id().equals(configId) && s.server() == server);
	}

	/** Keys of the settings listed, after search and filter. */
	public List<String> listedKeys() {
		List<String> keys = new ArrayList<>();
		for (Row row : rows) {
			if (row instanceof SettingRow setting) {
				keys.add(setting.setting.key());
			}
		}
		return keys;
	}

	/** Names of the category headers listed. */
	public List<String> listedGroups() {
		List<String> names = new ArrayList<>();
		for (Row row : rows) {
			if (row instanceof GroupRow group) {
				names.add(group.name);
			}
		}
		return names;
	}

	/**
	 * Scrolls {@code key}'s row into view and returns the centre of one of its widgets in GUI coordinates, or
	 * {@code null} if the setting is not listed or has no such widget.
	 */
	public int @Nullable [] positionOf(String key, Part part) {
		SettingRow row = rowFor(key);
		if (row == null) {
			return null;
		}
		if (row.top < listTop || row.top + ROW_HEIGHT > listBottom) {
			contentScroll += row.top - listTop;
			layoutRows();
		}
		AbstractWidget widget = switch (part) {
			case CONTROL -> row.button != null ? row.button : row.slider != null ? row.slider : row.field;
			case FIELD -> row.field;
			case RESET -> row.reset;
		};
		return widget == null ? null : new int[] {widget.getX() + widget.getWidth() / 2, widget.getY() + widget.getHeight() / 2};
	}

	/** Text the field of {@code key} shows, or the button's label. */
	public @Nullable String shownValue(String key) {
		SettingRow row = rowFor(key);
		if (row == null) {
			return null;
		}
		return row.field != null ? row.field.getValue() : row.button != null ? row.button.getMessage().getString() : null;
	}

	/** The validation message shown for {@code key}, or {@code null}. */
	public @Nullable String errorOf(String key) {
		SettingRow row = rowFor(key);
		return row == null ? null : row.error;
	}

	/** Centre of the search box in GUI coordinates. */
	public int[] searchPosition() {
		return new int[] {search.getX() + search.getWidth() / 2, search.getY() + search.getHeight() / 2};
	}

	/** Centre of a footer button ({@code reload}, {@code reset_all}, {@code undo}, {@code done}, {@code changed}). */
	public int @Nullable [] buttonPosition(String id) {
		Button button = switch (id) {
			case "reload" -> reloadButton;
			case "reset_all" -> resetAllButton;
			case "undo" -> undoButton;
			case "done" -> doneButton;
			case "changed" -> changedFilter;
			default -> null;
		};
		return button == null ? null : new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
	}

	/** Centre of a config's sidebar button, scrolled into view, or {@code null}. */
	public int @Nullable [] sidebarPosition(String configId, boolean server) {
		for (SidebarItem item : sidebar) {
			if (item instanceof SidebarButton b && b.source().id().equals(configId) && b.source().server() == server) {
				if (!b.button().visible) {
					int y = 0;
					for (SidebarItem other : sidebar) {
						if (other == item) {
							break;
						}
						y += other.height();
					}
					sidebarScroll = y;
					layoutSidebar();
				}
				return new int[] {b.button().getX() + b.button().getWidth() / 2, b.button().getY() + b.button().getHeight() / 2};
			}
		}
		return null;
	}

	private @Nullable SettingRow rowFor(String key) {
		for (Row row : rows) {
			if (row instanceof SettingRow setting && setting.setting.key().equals(key)) {
				return setting;
			}
		}
		return null;
	}

	/** Opens the screen on {@code configId}; while playing on a server, on the server's copy if it has one. */
	public static SettingsScreen forConfig(@Nullable Screen parent, @Nullable String configId) {
		return new SettingsScreen(parent, configId, true);
	}
}
