package com.mejta.mejtaperf.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack samples merged into a tree, root (thread entry) first. Frames are {@code class.method}; line numbers
 * are dropped so one method is one node. Memory is bounded: past {@link #MAX_NODES} nodes, new branches are
 * cut at the deepest existing node and counted as {@link #truncated()}.
 *
 * <p>Written by the sampler thread only; read once sampling has stopped.
 */
public final class CallTree {
	public static final int MAX_NODES = 200_000;
	public static final int MAX_DEPTH = 512;

	public static final class Node {
		private final String frame;
		private final Map<String, Node> children = new HashMap<>();
		private long total;
		private long self;

		Node(String frame) {
			this.frame = frame;
		}

		public String frame() {
			return frame;
		}

		/** Samples in which this frame (with this exact path to it) was on the stack. */
		public long total() {
			return total;
		}

		/** Samples in which this frame was the innermost one. */
		public long self() {
			return self;
		}

		/** Children, most samples first. */
		public List<Node> children() {
			List<Node> list = new ArrayList<>(children.values());
			list.sort(Comparator.comparingLong(Node::total).reversed().thenComparing(Node::frame));
			return list;
		}
	}

	private final Node root = new Node("<root>");
	private int nodes = 1;
	private long samples;
	private long truncated;

	/** Adds one sample. {@code stack} is as {@link Thread#getStackTrace()} returns it: innermost frame first. */
	public void add(StackTraceElement[] stack) {
		String[] frames = new String[Math.min(stack.length, MAX_DEPTH)];
		// Keep the outermost frames when a stack is deeper than the limit: they place the sample in the tree.
		for (int i = 0; i < frames.length; i++) {
			StackTraceElement e = stack[stack.length - 1 - i];
			frames[i] = e.getClassName() + "." + e.getMethodName();
		}
		add(frames);
	}

	/** Adds one sample given outermost frame first. */
	public void add(String[] rootFirst) {
		samples++;
		Node node = root;
		node.total++;
		for (String frame : rootFirst) {
			Node child = node.children.get(frame);
			if (child == null) {
				if (nodes >= MAX_NODES) {
					truncated++;
					break;
				}
				child = new Node(frame);
				node.children.put(frame, child);
				nodes++;
			}
			child.total++;
			node = child;
		}
		node.self++;
	}

	public Node root() {
		return root;
	}

	public long samples() {
		return samples;
	}

	/** Samples whose stack was cut short because the tree was full. */
	public long truncated() {
		return truncated;
	}

	public int nodeCount() {
		return nodes;
	}
}
