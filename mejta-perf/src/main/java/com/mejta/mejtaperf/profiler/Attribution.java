package com.mejta.mejtaperf.profiler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which mod each sample's time belongs to. A sample is charged to the innermost frame that belongs
 * to a mod other than Minecraft and Java: if a mod's code is anywhere on the stack, the time is spent on its
 * behalf, even when the innermost frames are vanilla code it called. Samples with no such frame are charged
 * to Minecraft (or Java, for threads outside the game).
 *
 * <p>A frame belongs to a mod when its class does, or when it is a mixin handler that the mod injected into
 * another class: Fabric names those {@code handler$<id>$<modid>$<method>} (and similarly for
 * redirects, modifiers and wrappers).
 */
public final class Attribution {
	public static final String MINECRAFT = "minecraft";
	public static final String JAVA = "java";

	private static final Pattern MIXIN_METHOD = Pattern.compile("^[a-zA-Z]+\\$[a-z0-9]+\\$([a-z][a-z0-9_-]*)\\$.+");

	private final Function<String, String> classOwner;
	private final Set<String> modIds;
	private final Map<String, String> frameCache = new HashMap<>();

	/**
	 * @param classOwner the mod ID owning a class name, or {@code null} when unknown; called at most once per class
	 * @param modIds every installed mod ID, to recognise mixin handlers
	 */
	public Attribution(Function<String, String> classOwner, Set<String> modIds) {
		Map<String, String> classCache = new HashMap<>();
		this.classOwner = name -> classCache.computeIfAbsent(name, n -> {
			String owner = classOwner.apply(n);
			return owner != null ? owner : fallbackOwner(n, modIds);
		});
		this.modIds = modIds;
	}

	/** The mod a frame ({@code class.method}) belongs to. */
	public String ownerOfFrame(String frame) {
		return frameCache.computeIfAbsent(frame, f -> {
			int dot = f.lastIndexOf('.');
			String cls = dot < 0 ? f : f.substring(0, dot);
			String method = dot < 0 ? "" : f.substring(dot + 1);
			Matcher m = MIXIN_METHOD.matcher(method);
			if (m.matches() && modIds.contains(m.group(1))) {
				return m.group(1);
			}
			return classOwner.apply(stripHidden(cls));
		});
	}

	/** {@code Foo$$Lambda/0x1234} and similar generated classes belong to {@code Foo}. */
	static String stripHidden(String cls) {
		int lambda = cls.indexOf("$$Lambda");
		if (lambda > 0) {
			return cls.substring(0, lambda);
		}
		int slash = cls.indexOf('/');
		return slash > 0 ? cls.substring(0, slash) : cls;
	}

	/** By package when the class's file is unknown: Java's own, Minecraft's, or a package segment that is a mod ID. */
	static String fallbackOwner(String cls, Set<String> modIds) {
		if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("com.sun.")) {
			return JAVA;
		}
		if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) {
			return MINECRAFT;
		}
		String[] parts = cls.split("\\.");
		for (int i = parts.length - 2; i >= 0; i--) {
			if (modIds.contains(parts[i]) && !parts[i].equals(MINECRAFT) && !parts[i].equals(JAVA)) {
				return parts[i];
			}
		}
		return "unknown";
	}

	static boolean isVanilla(String owner) {
		return owner.equals(MINECRAFT) || owner.equals(JAVA) || owner.equals("unknown") || owner.equals("fabricloader")
				|| owner.equals("mixinextras");
	}

	/** Samples per mod over the whole tree, most first. {@code skip} removes subtrees (for example idle samples). */
	public Map<String, Long> byMod(CallTree tree, java.util.function.Predicate<List<String>> skip) {
		Map<String, Long> totals = new HashMap<>();
		walk(tree.root(), new java.util.ArrayList<>(), null, totals, skip);
		Map<String, Long> sorted = new LinkedHashMap<>();
		totals.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return sorted;
	}

	private void walk(CallTree.Node node, List<String> path, String charged, Map<String, Long> totals,
			java.util.function.Predicate<List<String>> skip) {
		String current = charged;
		boolean pushed = false;
		if (!node.frame().equals("<root>")) {
			path.add(node.frame());
			pushed = true;
			String owner = ownerOfFrame(node.frame());
			if (!isVanilla(owner)) {
				current = owner;
			} else if (current == null || isVanilla(current)) {
				// Minecraft beats Java: a JDK call made by the game is the game's time.
				current = current != null && current.equals(MINECRAFT) ? MINECRAFT : owner.equals(MINECRAFT) ? MINECRAFT : JAVA;
			}
		}
		if (node.self() > 0 && !skip.test(path)) {
			totals.merge(current == null ? JAVA : current, node.self(), Long::sum);
		}
		for (CallTree.Node child : node.children()) {
			walk(child, path, current, totals, skip);
		}
		if (pushed) {
			path.remove(path.size() - 1);
		}
	}
}
