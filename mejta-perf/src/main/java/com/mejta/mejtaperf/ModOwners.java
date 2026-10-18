package com.mejta.mejtaperf;

import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Finds the mod a class was loaded from by comparing the class's code source with each mod's files. Mods
 * nested inside another mod's jar are not found this way; {@code Attribution} then falls back to the package
 * name. Classes are looked up without being initialised, and only classes already seen on a stack are asked for.
 */
final class ModOwners implements Function<String, String> {
	private record Root(Path path, String modId) {
	}

	private final List<Root> roots = new ArrayList<>();
	private final ClassLoader loader;

	ModOwners(ClassLoader loader) {
		this.loader = loader;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			try {
				ModOrigin origin = mod.getOrigin();
				if (origin.getKind() == ModOrigin.Kind.PATH) {
					for (Path p : origin.getPaths()) {
						roots.add(new Root(p.toAbsolutePath().normalize(), id));
					}
				}
			} catch (RuntimeException e) {
				// No usable origin (built-in mods on some launchers): the package fallback applies.
			}
		}
		// Most specific first, so a mod inside another mod's folder (development runs) wins.
		roots.sort(Comparator.comparingInt((Root r) -> r.path().getNameCount()).reversed());
	}

	static Set<String> modIds() {
		Set<String> ids = new TreeSet<>();
		FabricLoader.getInstance().getAllMods().forEach(m -> ids.add(m.getMetadata().getId()));
		return ids;
	}

	@Override
	public String apply(String className) {
		try {
			Class<?> cls = Class.forName(className, false, loader);
			CodeSource source = cls.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return null;
			}
			Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
			for (Root root : roots) {
				if (path.startsWith(root.path())) {
					return root.modId();
				}
			}
		} catch (Throwable t) {
			// Unknown, hidden or unloadable class: fall back to the package name.
		}
		return null;
	}
}
