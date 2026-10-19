package com.mejta.mejtacore.network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.PendingEdits;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.network.ConfigPayloads.EditConfig;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteConfig;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteSetting;
import com.mejta.mejtacore.ui.ChestMenuScreen;

/**
 * A server config as seen by a client, usable everywhere a {@link ConfigHandle} is (in particular the
 * standard settings pages). Settings are rebuilt from the server's description, so the addon does
 * not need to be installed on the client. Changes are sent to the server, which validates and saves
 * them; the value shown changes immediately and is replaced by the server's answer right after (see
 * {@link PendingEdits} for catalogs that cross an edit on the network).
 */
final class RemoteConfigHandle implements ConfigHandle {
	private final String id;
	private String name;
	private String schema = "";
	private ConfigSpec spec = ConfigSpec.builder(1).build();
	private final Map<String, Object> values = new HashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	RemoteConfigHandle(String id) {
		this.id = id;
	}

	String name() {
		return name;
	}

	/** Applies the server's latest description and values. */
	synchronized void update(RemoteConfig remote) {
		name = remote.name();
		String newSchema = schemaOf(remote);
		if (!newSchema.equals(schema)) {
			schema = newSchema;
			ConfigSpec.Builder builder = ConfigSpec.builder(1);
			for (RemoteSetting s : remote.settings()) {
				Setting<?> setting = toSetting(s);
				if (setting != null) {
					builder.add(setting);
				}
			}
			spec = builder.build();
		}
		values.clear();
		Map<String, String> pending = RemoteConfigs.PENDING.overrides(id);
		for (RemoteSetting s : remote.settings()) {
			spec.setting(s.key()).ifPresent(setting -> {
				// While this client's edits are on their way, a catalog may predate them: keep what was sent.
				Object value = SettingText.parse(setting, pending.getOrDefault(s.key(), s.value()));
				if (value != null) {
					values.put(s.key(), value);
				}
			});
		}
		fireChanged();
	}

	private static String schemaOf(RemoteConfig remote) {
		StringBuilder out = new StringBuilder();
		for (RemoteSetting s : remote.settings()) {
			out.append(s.key()).append('|').append(s.type()).append('|').append(s.displayName()).append('|')
					.append(s.description()).append('|').append(s.defaultValue()).append('|').append(s.min()).append('|')
					.append(s.max()).append('|').append(s.options()).append('\n');
		}
		return out.toString();
	}

	private static Setting<?> toSetting(RemoteSetting s) {
		try {
			Setting<?> setting = switch (SettingType.valueOf(s.type())) {
				case BOOLEAN -> Setting.bool(s.key(), Boolean.parseBoolean(s.defaultValue()));
				case INTEGER -> s.min().isEmpty()
						? Setting.integer(s.key(), Integer.parseInt(s.defaultValue()))
						: Setting.integer(s.key(), Integer.parseInt(s.defaultValue()), Integer.parseInt(s.min()), Integer.parseInt(s.max()));
				case DECIMAL -> s.min().isEmpty()
						? Setting.decimal(s.key(), Double.parseDouble(s.defaultValue()))
						: Setting.decimal(s.key(), Double.parseDouble(s.defaultValue()), Double.parseDouble(s.min()), Double.parseDouble(s.max()));
				case STRING -> Setting.string(s.key(), s.defaultValue());
				case ENUM -> Setting.choice(s.key(), s.defaultValue(), new ArrayList<>(s.options()));
			};
			return setting.named(s.displayName()).describedAs(s.description());
		} catch (RuntimeException e) {
			MejtaLog.CORE.warn("Ignoring server setting '{}' that this client cannot show: {}", s.key(), e.toString());
			return null;
		}
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public synchronized ConfigSpec spec() {
		return spec;
	}

	@Override
	public Path file() {
		return Path.of("server", id + ".json");
	}

	@Override
	public synchronized <T> T get(Setting<T> setting) {
		Object value = values.get(setting.key());
		return setting.valueClass().isInstance(value) ? setting.valueClass().cast(value) : setting.defaultValue();
	}

	@Override
	public <T> void set(Setting<T> setting, T value) {
		if (!trySet(setting, value)) {
			throw new IllegalArgumentException("Invalid value " + value + " for setting '" + setting.key() + "'");
		}
	}

	@Override
	public <T> boolean trySet(Setting<T> setting, T value) {
		if (!setting.isValid(value)) {
			return false;
		}
		String text = SettingText.format(setting, value);
		if (!send(ConfigEditing.Op.SET, setting.key(), text, Map.of(setting.key(), text))) {
			return false;
		}
		synchronized (this) {
			values.put(setting.key(), value);
		}
		fireChanged();
		return true;
	}

	@Override
	public <T> void reset(Setting<T> setting) {
		if (!send(ConfigEditing.Op.RESET, setting.key(), "", Map.of(setting.key(), SettingText.format(setting, setting.defaultValue())))) {
			return;
		}
		synchronized (this) {
			values.put(setting.key(), setting.defaultValue());
		}
		fireChanged();
	}

	@Override
	public void resetAll() {
		Map<String, String> defaults = new HashMap<>();
		synchronized (this) {
			for (Setting<?> setting : spec.settings()) {
				defaults.put(setting.key(), SettingText.format(setting, setting.defaultValue()));
			}
		}
		if (!send(ConfigEditing.Op.RESET_ALL, "", "", defaults)) {
			return;
		}
		synchronized (this) {
			for (Setting<?> setting : spec.settings()) {
				values.put(setting.key(), setting.defaultValue());
			}
		}
		fireChanged();
	}

	@Override
	public boolean isDirty() {
		return false;
	}

	/** The server saves every accepted change itself. */
	@Override
	public void save() {
	}

	/** Asks the server for its current values. */
	@Override
	public void reload() {
		RemoteConfigs.request();
	}

	@Override
	public void addChangeListener(Runnable listener) {
		listeners.add(Objects.requireNonNull(listener));
	}

	/**
	 * Sends one edit and remembers the values it sets until the server answers. Returns {@code false}, and says
	 * so in the menu, if the server no longer accepts edits (before 1.4.0 the edit was dropped silently while
	 * the menu showed the new value).
	 */
	private boolean send(ConfigEditing.Op op, String key, String value, Map<String, String> optimistic) {
		boolean canSend;
		try {
			canSend = ClientPlayNetworking.canSend(EditConfig.TYPE);
		} catch (IllegalStateException e) {
			canSend = false; // not connected
		}
		if (!canSend) {
			ChestMenuScreen.showStatus(Component.literal("Not sent: you are not connected to a server that accepts setting changes")
					.withStyle(ChatFormatting.RED));
			return false;
		}
		RemoteConfigs.PENDING.sent(id, optimistic);
		ClientPlayNetworking.send(new EditConfig(id, op.name(), key, value));
		return true;
	}

	private void fireChanged() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				MejtaLog.CORE.error("Remote config listener for '{}' failed", id, e);
			}
		}
	}
}
