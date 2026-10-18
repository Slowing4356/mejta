package com.mejta.mejtaperf.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class ProfilerTest {
	private static final Set<String> MODS = Set.of("minecraft", "java", "mejtaac", "fabric-lifecycle-events-v1", "lithium");

	/** Owners by package, as the game would report them. */
	private static String owner(String cls) {
		if (cls.startsWith("com.mejta.mejtaac.")) {
			return "mejtaac";
		}
		if (cls.startsWith("net.caffeinemc.")) {
			return "lithium";
		}
		if (cls.startsWith("net.minecraft.")) {
			return "minecraft";
		}
		return null;
	}

	private static String[] path(String... frames) {
		return frames;
	}

	private static CallTree sampleTree() {
		CallTree tree = new CallTree();
		String[] tickBase = {StackSampler.IN_TICK, "java.lang.Thread.run", "net.minecraft.server.MinecraftServer.runServer",
				"net.minecraft.server.MinecraftServer.tickServer"};
		for (int i = 0; i < 60; i++) {
			tree.add(concat(tickBase, "net.minecraft.server.level.ServerLevel.tick", "net.minecraft.world.entity.Entity.tick"));
		}
		for (int i = 0; i < 20; i++) {
			// Vanilla code called by a mod: charged to the mod.
			tree.add(concat(tickBase, "net.minecraft.server.MinecraftServer.handler$zza000$mejtaac$onTick",
					"com.mejta.mejtaac.AntiCheat.onServerTickEnd", "java.util.HashMap.get"));
		}
		for (int i = 0; i < 20; i++) {
			tree.add(concat(tickBase, "net.caffeinemc.mods.lithium.Something.tick"));
		}
		for (int i = 0; i < 100; i++) {
			tree.add(path(StackSampler.BETWEEN_TICKS, "java.lang.Thread.run", "net.minecraft.server.MinecraftServer.runServer",
					"net.minecraft.server.MinecraftServer.waitUntilNextTick", "jdk.internal.misc.Unsafe.park"));
		}
		return tree;
	}

	private static String[] concat(String[] base, String... more) {
		String[] all = new String[base.length + more.length];
		System.arraycopy(base, 0, all, 0, base.length);
		System.arraycopy(more, 0, all, base.length, more.length);
		return all;
	}

	@Test
	void treeMergesSamplesAndCountsSelfTime() {
		CallTree tree = sampleTree();
		assertEquals(200, tree.samples());
		CallTree.Node root = tree.root();
		assertEquals(200, root.total());
		assertEquals(List.of(StackSampler.BETWEEN_TICKS, StackSampler.IN_TICK), root.children().stream().map(CallTree.Node::frame).toList(),
				"equal totals: by name");
		assertEquals(100, root.children().get(1).total());
		assertEquals(60, root.children().get(1).children().get(0).children().get(0).children().get(0).children().get(0).total());
	}

	@Test
	void treeStopsGrowingAtItsLimit() {
		CallTree tree = new CallTree();
		for (int i = 0; i < CallTree.MAX_NODES + 10; i++) {
			tree.add(path("root", "frame" + i));
		}
		assertEquals(CallTree.MAX_NODES, tree.nodeCount());
		assertTrue(tree.truncated() > 0);
		assertEquals(CallTree.MAX_NODES + 10, tree.samples(), "truncated samples still count");
	}

	@Test
	void stackTracesAreReadInnermostFirst() {
		CallTree tree = new CallTree();
		tree.add(new StackTraceElement[] {new StackTraceElement("b.Inner", "work", "Inner.java", 5),
				new StackTraceElement("a.Outer", "main", "Outer.java", 1)});
		CallTree.Node outer = tree.root().children().get(0);
		assertEquals("a.Outer.main", outer.frame());
		assertEquals("b.Inner.work", outer.children().get(0).frame());
		assertEquals(1, outer.children().get(0).self());
	}

	@Test
	void attributionChargesTheInnermostModFrame() {
		Attribution attribution = new Attribution(ProfilerTest::owner, MODS);
		assertEquals("mejtaac", attribution.ownerOfFrame("net.minecraft.server.MinecraftServer.handler$zza000$mejtaac$onTick"));
		assertEquals("minecraft", attribution.ownerOfFrame("net.minecraft.server.MinecraftServer.handler$zza000$notamod$onTick"));
		assertEquals("java", attribution.ownerOfFrame("java.util.HashMap.get"));
		assertEquals("mejtaac", attribution.ownerOfFrame("com.mejta.mejtaac.AntiCheat$$Lambda/0x0000012345.run"));
		assertEquals("lithium", Attribution.fallbackOwner("me.jellysquid.lithium.Thing", MODS), "package segment naming a mod");
		assertEquals("unknown", Attribution.fallbackOwner("org.example.Thing", MODS));

		Map<String, Long> byMod = attribution.byMod(sampleTree(), StackSampler::isIdle);
		assertEquals(Map.of("minecraft", 60L, "mejtaac", 20L, "lithium", 20L), byMod, "idle samples are left out");
		assertEquals("minecraft", byMod.keySet().iterator().next());
	}

	@Test
	void idleIsWaitingForTheNextTickNotWaitingInsideOne() {
		assertTrue(StackSampler.isIdle(List.of(StackSampler.BETWEEN_TICKS, "x.Y.run", "jdk.internal.misc.Unsafe.park")));
		assertFalse(StackSampler.isIdle(List.of(StackSampler.BETWEEN_TICKS, "x.Y.run", "x.Y.handlePacket")));
		assertFalse(StackSampler.isIdle(List.of(StackSampler.IN_TICK, "net.minecraft.server.level.ServerChunkCache.getChunk",
				"jdk.internal.misc.Unsafe.park")), "a tick waiting for a chunk load is lag");
		assertTrue(StackSampler.isIdle(List.of(StackSampler.IN_TICK, "net.minecraft.server.MinecraftServer.waitUntilNextTick",
				"jdk.internal.misc.Unsafe.park")));
	}

	@Test
	void reportShowsModsMethodsAndTree() {
		ProfileReport report = new ProfileReport(sampleTree(), new Attribution(ProfilerTest::owner, MODS));
		ProfileReport.Summary s = report.summary();
		assertEquals(200, s.samples());
		assertEquals(100, s.busy());
		assertEquals("net.minecraft.world.entity.Entity.tick", s.hotMethods().get(0).getKey());
		String text = report.render(new ProfileReport.Info("2026-10-18T20:00:00Z (test)", "stopped", 2.0, 10, "40 ticks"));
		assertTrue(text.contains(" 60.00%") && text.contains("minecraft"), text);
		assertTrue(text.contains("com.mejta.mejtaac.AntiCheat.onServerTickEnd  [mejtaac]"), text);
		assertFalse(text.contains("waitUntilNextTick"), "idle branches are not in the tree");
		List<String> chat = report.chatLines(3, 2);
		assertEquals("By mod: minecraft 60.0%, lithium 20.0%, mejtaac 20.0%", chat.get(0));
		assertEquals("  60.0% Entity.tick [minecraft]", chat.get(2));
	}

	@Test
	void samplerSamplesARealThreadAndStops() throws InterruptedException {
		CountDownLatch finished = new CountDownLatch(1);
		AtomicBoolean run = new AtomicBoolean(true);
		Thread busy = new Thread(() -> {
			while (run.get()) {
				spin();
			}
		}, "busy");
		busy.setDaemon(true);
		busy.start();
		StackSampler sampler = new StackSampler(busy, 2_000_000, 10_000_000_000L, () -> true, System::nanoTime, finished::countDown);
		sampler.start();
		Thread.sleep(300);
		sampler.stop(StackSampler.End.STOPPED);
		assertTrue(sampler.await(5_000));
		assertEquals(0, finished.getCount());
		assertEquals(StackSampler.End.STOPPED, sampler.end());
		assertTrue(sampler.samples() >= 20, "samples: " + sampler.samples());
		assertEquals(sampler.samples(), sampler.tree().samples());
		assertEquals(StackSampler.IN_TICK, sampler.tree().root().children().get(0).frame());
		run.set(false);

		CountDownLatch limited = new CountDownLatch(1);
		StackSampler shortRun = new StackSampler(Thread.currentThread(), 1_000_000, 50_000_000, () -> false, System::nanoTime, limited::countDown);
		shortRun.start();
		assertTrue(shortRun.await(5_000));
		assertEquals(StackSampler.End.TIME_LIMIT, shortRun.end());
	}

	private static volatile long sink;

	private static void spin() {
		long x = 0;
		for (int i = 0; i < 10_000; i++) {
			x += i * 31L;
		}
		sink = x;
	}

	@Test
	void shortNames() {
		assertEquals("Level.tick", ProfileReport.shorten("net.minecraft.world.level.Level.tick"));
		assertEquals("Main.run", ProfileReport.shorten("Main.run"));
	}
}
