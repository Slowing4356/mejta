package com.mejta.mejtacore.api.addon;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * Immutable description of an addon. Create one with {@link #builder(String)}.
 *
 * <pre>{@code
 * AddonDefinition def = AddonDefinition.builder("example")
 *     .name("Example Addon")
 *     .version("1.0.0")
 *     .description("Shows how to use MejtaCore.")
 *     .icon("minecraft:diamond")
 *     .config(MyConfig.SPEC)
 *     .dependsOn("otheraddon")
 *     .onInitialize(ctx -> ctx.logger().info("Hello!"))
 *     .build();
 * }</pre>
 */
public final class AddonDefinition {
	/** Addon IDs: lowercase letter first, then lowercase letters, digits, {@code _} or {@code -}; max 64 chars. */
	public static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

	/** IDs that would collide with MejtaCore's own command and file names. */
	public static final Set<String> RESERVED_IDS = Set.of("mejtacore", "mejta", "core", "gui", "help", "addons", "config", "about");

	private final String id;
	private final String name;
	private final String version;
	private final String description;
	private final String iconItem;
	private final ConfigSpec config;
	private final Set<String> dependencies;
	private final Set<String> optionalDependencies;
	private final AddonInitializer initializer;
	private final List<MejtaPermission> permissions;

	private AddonDefinition(Builder b) {
		this.id = b.id;
		this.name = b.name == null ? b.id : b.name;
		this.version = b.version;
		this.description = b.description;
		this.iconItem = b.iconItem;
		this.config = b.config;
		this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(b.dependencies));
		this.optionalDependencies = Collections.unmodifiableSet(new LinkedHashSet<>(b.optionalDependencies));
		this.initializer = b.initializer;
		this.permissions = List.copyOf(b.permissions);
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String version() {
		return version;
	}

	public String description() {
		return description;
	}

	/** Item ID used as the addon's icon, e.g. {@code minecraft:book}. */
	public String iconItem() {
		return iconItem;
	}

	public Optional<ConfigSpec> config() {
		return Optional.ofNullable(config);
	}

	/** Addon IDs that must be present and active; this addon initializes after them. */
	public Set<String> dependencies() {
		return dependencies;
	}

	/** Addon IDs that, if present, initialize before this addon. */
	public Set<String> optionalDependencies() {
		return optionalDependencies;
	}

	public Optional<AddonInitializer> initializer() {
		return Optional.ofNullable(initializer);
	}

	/** Server permissions this addon declares; their levels are configurable in {@code mejtacore.permissions.json}. */
	public List<MejtaPermission> permissions() {
		return permissions;
	}

	@Override
	public String toString() {
		return "AddonDefinition[" + id + " " + version + "]";
	}

	public static final class Builder {
		private final String id;
		private String name;
		private String version = "0.0.0";
		private String description = "";
		private String iconItem = "minecraft:book";
		private ConfigSpec config;
		private final Set<String> dependencies = new LinkedHashSet<>();
		private final Set<String> optionalDependencies = new LinkedHashSet<>();
		private AddonInitializer initializer;
		private final Set<MejtaPermission> permissions = new LinkedHashSet<>();

		private Builder(String id) {
			Objects.requireNonNull(id, "id");
			if (!ID_PATTERN.matcher(id).matches()) {
				throw new IllegalArgumentException("Invalid addon id '" + id + "': must match " + ID_PATTERN.pattern());
			}
			if (RESERVED_IDS.contains(id)) {
				throw new IllegalArgumentException("Addon id '" + id + "' is reserved by MejtaCore");
			}
			this.id = id;
		}

		/** Human-readable name shown in menus. Defaults to the ID. */
		public Builder name(String name) {
			this.name = requireText(name, "name");
			return this;
		}

		public Builder version(String version) {
			this.version = requireText(version, "version");
			return this;
		}

		public Builder description(String description) {
			this.description = Objects.requireNonNull(description, "description");
			return this;
		}

		/** Item ID for the menu icon, e.g. {@code "minecraft:diamond"}. Unknown items fall back to a barrier icon. */
		public Builder icon(String itemId) {
			this.iconItem = requireText(itemId, "icon");
			return this;
		}

		/** Configuration schema; MejtaCore stores it in {@code config/mejta/<id>.json}. */
		public Builder config(ConfigSpec spec) {
			this.config = Objects.requireNonNull(spec, "spec");
			return this;
		}

		public Builder dependsOn(String... addonIds) {
			for (String dep : addonIds) {
				dependencies.add(checkDependency(dep));
			}
			return this;
		}

		public Builder optionallyDependsOn(String... addonIds) {
			for (String dep : addonIds) {
				optionalDependencies.add(checkDependency(dep));
			}
			return this;
		}

		/** Called once, after this addon's dependencies are initialized and its config is loaded. */
		public Builder onInitialize(AddonInitializer initializer) {
			this.initializer = Objects.requireNonNull(initializer, "initializer");
			return this;
		}

		/**
		 * Declares server permissions. Each node must start with this addon's ID and a dot,
		 * e.g. {@code myaddon.alerts}.
		 */
		public Builder permissions(MejtaPermission... toAdd) {
			for (MejtaPermission permission : toAdd) {
				Objects.requireNonNull(permission, "permission");
				if (!permission.owner().equals(id)) {
					throw new IllegalArgumentException("Permission '" + permission.node() + "' must start with '" + id + ".'");
				}
				permissions.add(permission);
			}
			return this;
		}

		public AddonDefinition build() {
			optionalDependencies.removeAll(dependencies);
			return new AddonDefinition(this);
		}

		private String checkDependency(String dep) {
			Objects.requireNonNull(dep, "dependency");
			if (dep.equals(id)) {
				throw new IllegalArgumentException("Addon '" + id + "' cannot depend on itself");
			}
			if (!ID_PATTERN.matcher(dep).matches()) {
				throw new IllegalArgumentException("Invalid dependency id '" + dep + "' in addon '" + id + "'");
			}
			return dep;
		}

		private static String requireText(String value, String what) {
			Objects.requireNonNull(value, what);
			if (value.isBlank()) {
				throw new IllegalArgumentException(what + " must not be blank");
			}
			return value;
		}
	}
}
