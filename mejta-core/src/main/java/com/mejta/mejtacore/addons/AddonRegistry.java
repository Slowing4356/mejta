package com.mejta.mejtacore.addons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;

import com.mejta.mejtacore.api.addon.AddonContext;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.config.ConfigManager;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;

/**
 * Holds addon registrations. Lifecycle:
 * <ol>
 * <li>Open: {@link #register} accepts definitions (during Fabric's {@code main} phase).</li>
 * <li>{@link #finalizeRegistrations}: closes registration, resolves dependencies in a stable
 * topological order, loads configs, runs init callbacks.</li>
 * </ol>
 */
public final class AddonRegistry {
	private static final AddonRegistry INSTANCE = new AddonRegistry();

	private final Map<String, RegisteredAddon> addons = new TreeMap<>();
	private final List<String> initOrder = new ArrayList<>();
	private volatile boolean finalized;

	private AddonRegistry() {
	}

	public static AddonRegistry get() {
		return INSTANCE;
	}

	public synchronized boolean register(AddonDefinition definition, String source) {
		if (definition == null) {
			Diagnostics.error("registry", "Null addon definition registered from " + source);
			return false;
		}
		if (finalized) {
			Diagnostics.error(definition.id(), "Registration rejected: registration is closed. Register from your "
					+ "ModInitializer or a \"mejtacore\" entrypoint, not later (source: " + source + ")");
			return false;
		}
		RegisteredAddon existing = addons.get(definition.id());
		if (existing != null) {
			Diagnostics.error(definition.id(), "Duplicate addon id '" + definition.id() + "': already registered by "
					+ existing.source() + ", rejected registration from " + source);
			return false;
		}
		addons.put(definition.id(), new RegisteredAddon(definition, source));
		MejtaLog.debug("Registered addon '{}' {} from {}", definition.id(), definition.version(), source);
		return true;
	}

	public synchronized void finalizeRegistrations(ConfigManager configs) {
		if (finalized) {
			return;
		}
		finalized = true;

		for (String id : resolveOrder()) {
			RegisteredAddon addon = addons.get(id);
			if (addon.state() == AddonState.FAILED) {
				continue;
			}
			Optional<String> failedDependency = addon.definition().dependencies().stream()
					.filter(dep -> !addons.get(dep).isActive())
					.findFirst();
			if (failedDependency.isPresent()) {
				fail(addon, "required addon '" + failedDependency.get() + "' failed to initialize");
				continue;
			}
			initialize(addon, configs);
		}

		long active = addons.values().stream().filter(AddonInfo::isActive).count();
		MejtaLog.CORE.info("{} addon(s) registered, {} active, {} failed", addons.size(), active, addons.size() - active);
	}

	private void initialize(RegisteredAddon addon, ConfigManager configs) {
		try {
			addon.definition().config().ifPresent(spec -> addon.setConfig(configs.open(addon.id(), spec)));
			Logger logger = MejtaLog.forAddon(addon.id());
			AddonContext context = new AddonContext() {
				@Override
				public AddonInfo info() {
					return addon;
				}

				@Override
				public Optional<ConfigHandle> config() {
					return addon.config();
				}

				@Override
				public Logger logger() {
					return logger;
				}
			};
			if (addon.definition().initializer().isPresent()) {
				addon.definition().initializer().get().onInitialize(context);
			}
			addon.markActive();
			initOrder.add(addon.id());
			MejtaLog.CORE.info("Addon '{}' {} initialized", addon.id(), addon.version());
		} catch (Throwable t) {
			addon.markFailed("exception during initialization: " + t);
			Diagnostics.error(addon.id(), "Initialization failed", t);
		}
	}

	/**
	 * Kahn's algorithm over accepted addons, visiting IDs alphabetically for a stable order.
	 * Addons with missing required dependencies, or inside a cycle, are marked failed.
	 */
	private List<String> resolveOrder() {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (RegisteredAddon addon : addons.values()) {
				if (addon.state() == AddonState.FAILED) {
					continue;
				}
				for (String dep : addon.definition().dependencies()) {
					RegisteredAddon target = addons.get(dep);
					if (target == null) {
						fail(addon, "missing required addon '" + dep + "'");
						changed = true;
						break;
					}
					if (target.state() == AddonState.FAILED) {
						fail(addon, "required addon '" + dep + "' is unavailable");
						changed = true;
						break;
					}
				}
			}
		}

		Map<String, Integer> remainingDeps = new HashMap<>();
		Map<String, List<String>> dependents = new HashMap<>();
		for (RegisteredAddon addon : addons.values()) {
			if (addon.state() == AddonState.FAILED) {
				continue;
			}
			int count = 0;
			List<String> before = new ArrayList<>(addon.definition().dependencies());
			for (String opt : addon.definition().optionalDependencies()) {
				RegisteredAddon target = addons.get(opt);
				if (target != null && target.state() != AddonState.FAILED) {
					before.add(opt);
				}
			}
			for (String dep : before) {
				dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(addon.id());
				count++;
			}
			remainingDeps.put(addon.id(), count);
		}

		List<String> order = new ArrayList<>();
		TreeMap<String, Boolean> ready = new TreeMap<>();
		remainingDeps.forEach((id, count) -> {
			if (count == 0) {
				ready.put(id, true);
			}
		});
		while (!ready.isEmpty()) {
			String id = ready.pollFirstEntry().getKey();
			order.add(id);
			for (String dependent : dependents.getOrDefault(id, List.of())) {
				if (remainingDeps.merge(dependent, -1, Integer::sum) == 0) {
					ready.put(dependent, true);
				}
			}
		}

		for (Map.Entry<String, Integer> entry : remainingDeps.entrySet()) {
			if (entry.getValue() > 0) {
				fail(addons.get(entry.getKey()), "dependency cycle detected");
			}
		}
		return order;
	}

	private static void fail(RegisteredAddon addon, String reason) {
		addon.markFailed(reason);
		Diagnostics.error(addon.id(), "Addon disabled: " + reason);
	}

	public synchronized Optional<AddonInfo> find(String id) {
		return Optional.ofNullable(addons.get(id));
	}

	public synchronized Collection<AddonInfo> all() {
		return List.copyOf(addons.values());
	}

	/** Active addon IDs in the order they were initialized. */
	public synchronized List<String> initializationOrder() {
		return List.copyOf(initOrder);
	}

	public boolean isFinalized() {
		return finalized;
	}
}
