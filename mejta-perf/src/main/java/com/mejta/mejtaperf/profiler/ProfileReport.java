package com.mejta.mejtaperf.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text report of a finished profile: time per mod, the hottest methods, and the call tree. Samples in which
 * the server was only waiting for the next tick are counted but left out of the breakdowns, so percentages are
 * shares of the time the server thread was actually working.
 */
public final class ProfileReport {
	/** Branches below this share of busy samples are not printed in the call tree. */
	static final double TREE_MIN_SHARE = 0.005;
	static final int TREE_MAX_DEPTH = 80;

	/** What the caller knows about the run, beyond the samples. */
	/** @param filter which samples were kept, empty when all were (only-slow-ticks mode) */
	public record Info(String started, String endReason, double seconds, double intervalMs, String ticks, String filter) {
	}

	/** The computed figures, also used for the chat summary. */
	public record Summary(long samples, long busy, long idle, Map<String, Long> byMod, List<Map.Entry<String, Long>> hotMethods) {
		public double busyShare(long count) {
			return busy == 0 ? 0 : count / (double) busy;
		}
	}

	private final CallTree tree;
	private final Attribution attribution;
	private final Map<CallTree.Node, Long> busyTotals = new IdentityHashMap<>();
	private final Summary summary;

	public ProfileReport(CallTree tree, Attribution attribution) {
		this.tree = tree;
		this.attribution = attribution;
		Map<String, Long> methods = new HashMap<>();
		long busy = busy(tree.root(), new ArrayList<>(), methods);
		List<Map.Entry<String, Long>> hot = new ArrayList<>(methods.entrySet());
		hot.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		Map<String, Long> byMod = attribution.byMod(tree, StackSampler::isIdle);
		this.summary = new Summary(tree.samples(), busy, tree.samples() - busy, byMod, List.copyOf(hot.subList(0, Math.min(hot.size(), 50))));
	}

	/** Fills {@link #busyTotals} and per-method self counts; returns the busy samples under {@code node}. */
	private long busy(CallTree.Node node, List<String> path, Map<String, Long> methods) {
		boolean pushed = !node.frame().equals("<root>");
		if (pushed) {
			path.add(node.frame());
		}
		long total = 0;
		if (node.self() > 0 && !StackSampler.isIdle(path)) {
			total += node.self();
			if (pushed && !node.frame().startsWith("[")) {
				methods.merge(node.frame(), node.self(), Long::sum);
			}
		}
		for (CallTree.Node child : node.children()) {
			total += busy(child, path, methods);
		}
		if (pushed) {
			path.remove(path.size() - 1);
		}
		busyTotals.put(node, total);
		return total;
	}

	public Summary summary() {
		return summary;
	}

	public String render(Info info) {
		StringBuilder out = new StringBuilder(64 * 1024);
		out.append("MejtaPerf profile\n=================\n\n");
		out.append("Started:   ").append(info.started()).append('\n');
		out.append("Duration:  ").append(fmt("%.1f s", info.seconds())).append(", one sample every ").append(fmt("%.0f ms", info.intervalMs()))
				.append(" (").append(info.endReason()).append(")\n");
		out.append("Server:    ").append(info.ticks()).append('\n');
		if (!info.filter().isEmpty()) {
			out.append("Filter:    ").append(info.filter()).append('\n');
		}
		out.append("Samples:   ").append(summary.samples()).append(": working in ").append(summary.busy())
				.append(", waiting for the next tick in ").append(summary.idle()).append('\n');
		if (tree.truncated() > 0) {
			out.append("Note:      ").append(tree.truncated()).append(" sample(s) were cut short because the call tree reached ")
					.append(CallTree.MAX_NODES).append(" nodes.\n");
		}
		out.append("\nPercentages are shares of the samples in which the server thread was working.\n");

		out.append("\nTime by mod\n-----------\n");
		out.append("Each sample is charged to the innermost frame that belongs to a mod other than Minecraft: a mod's\n")
				.append("code on the stack means the time was spent on its behalf. Mixin handlers count for the mod that added them.\n\n");
		for (Map.Entry<String, Long> e : summary.byMod().entrySet()) {
			out.append(fmt("  %6.2f%%  %8d  %s%n", 100 * summary.busyShare(e.getValue()), e.getValue(), e.getKey()));
		}

		out.append("\nHottest methods (time in the method itself)\n-------------------------------------------\n\n");
		for (Map.Entry<String, Long> e : summary.hotMethods()) {
			out.append(fmt("  %6.2f%%  %8d  %s  [%s]%n", 100 * summary.busyShare(e.getValue()), e.getValue(), e.getKey(),
					attribution.ownerOfFrame(e.getKey())));
		}

		out.append("\nCall tree\n---------\n");
		out.append(fmt("Branches under %.1f%% are hidden; at most %d levels. Each line: share, samples, frame [mod].%n%n",
				TREE_MIN_SHARE * 100, TREE_MAX_DEPTH));
		for (CallTree.Node child : tree.root().children()) {
			printTree(out, child, 0);
		}
		return out.toString();
	}

	private void printTree(StringBuilder out, CallTree.Node node, int depth) {
		long busy = busyTotals.getOrDefault(node, 0L);
		if (summary.busy() == 0 || busy < summary.busy() * TREE_MIN_SHARE || depth >= TREE_MAX_DEPTH) {
			return;
		}
		out.append(fmt("%6.2f%% %8d ", 100 * summary.busyShare(busy), busy));
		out.append("  ".repeat(Math.min(depth, 40)));
		out.append(node.frame());
		if (!node.frame().startsWith("[")) {
			out.append("  [").append(attribution.ownerOfFrame(node.frame())).append(']');
		}
		out.append('\n');
		for (CallTree.Node child : node.children()) {
			printTree(out, child, depth + 1);
		}
	}

	/** A few lines for chat. */
	public List<String> chatLines(int mods, int methods) {
		List<String> lines = new ArrayList<>();
		if (summary.busy() == 0) {
			lines.add("No samples of the server working were taken.");
			return lines;
		}
		Map<String, Long> topMods = new LinkedHashMap<>();
		summary.byMod().entrySet().stream().limit(mods).forEach(e -> topMods.put(e.getKey(), e.getValue()));
		lines.add("By mod: " + String.join(", ", topMods.entrySet().stream()
				.map(e -> e.getKey() + " " + fmt("%.1f%%", 100 * summary.busyShare(e.getValue()))).toList()));
		lines.add("Hottest methods:");
		summary.hotMethods().stream().limit(methods).forEach(e -> lines.add(fmt(" %5.1f%% ", 100 * summary.busyShare(e.getValue()))
				+ shorten(e.getKey()) + " [" + attribution.ownerOfFrame(e.getKey()) + "]"));
		return lines;
	}

	/** {@code net.minecraft.world.level.Level.tick} to {@code Level.tick}. */
	static String shorten(String frame) {
		int dot = frame.lastIndexOf('.');
		if (dot < 0) {
			return frame;
		}
		int classDot = frame.lastIndexOf('.', dot - 1);
		return classDot < 0 ? frame : frame.substring(classDot + 1);
	}

	private static String fmt(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}
}
