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

	// Real server thread frames (Minecraft 26.1 is not obfuscated), outermost first.
	private static final String[] LOOP = {"java.lang.Thread.run", "java.lang.Thread.runWith",
			"net.minecraft.server.MinecraftServer.lambda$spin$2", "net.minecraft.server.MinecraftServer.runServer"};
	private static final String[] MAIN_WAIT = {"net.minecraft.server.MinecraftServer.waitUntilNextTick",
			"net.minecraft.util.thread.BlockableEventLoop.managedBlock", "net.minecraft.server.MinecraftServer.waitForTasks",
			"java.util.concurrent.locks.LockSupport.parkNanos", "jdk.internal.misc.Unsafe.park"};
	/** BlockableEventLoop.waitForTasks: Thread.yield, then a 100 µs park. */
	private static final String[] TASK_WAIT_PARK = {"net.minecraft.util.thread.BlockableEventLoop.managedBlock",
			"net.minecraft.util.thread.BlockableEventLoop.waitForTasks", "java.util.concurrent.locks.LockSupport.parkNanos",
			"jdk.internal.misc.Unsafe.park"};
	private static final String[] TASK_WAIT_YIELD = {"net.minecraft.util.thread.BlockableEventLoop.managedBlock",
			"net.minecraft.util.thread.BlockableEventLoop.waitForTasks", "java.lang.Thread.yield", "java.lang.Thread.yield0"};
	private static final String[] TICK = {"net.minecraft.server.MinecraftServer.tickServer", "net.minecraft.server.MinecraftServer.tickChildren",
			"net.minecraft.server.level.ServerLevel.tick"};

	private static List<String> sample(String phase, String[]... parts) {
		List<String> all = new java.util.ArrayList<>(List.of(phase));
		for (String[] part : parts) {
			all.addAll(List.of(part));
		}
		return all;
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
			tree.add(sample(StackSampler.BETWEEN_TICKS, LOOP, MAIN_WAIT).toArray(String[]::new));
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
		// The main loop's wait for the next tick, in either phase (the phase is read just before the stack).
		assertTrue(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, MAIN_WAIT)));
		assertTrue(StackSampler.isIdle(sample(StackSampler.IN_TICK, LOOP, MAIN_WAIT)));
		assertTrue(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, new String[] {"net.minecraft.server.MinecraftServer.waitUntilNextTick",
				"net.minecraft.util.thread.BlockableEventLoop.managedBlock", "net.minecraft.util.thread.BlockableEventLoop.waitForTasks",
				"java.lang.Thread.yield", "java.lang.Thread.yield0"})), "an inherited waitForTasks, yielding");
		assertTrue(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, new String[] {"java.lang.Thread.sleep", "java.lang.Thread.sleepNanos",
				"java.lang.Thread.sleep0"})), "between ticks, a wait right in the server's own loop");

		// A tick waiting for a chunk load: same managedBlock and waitForTasks, but below ServerChunkCache.getChunk.
		String[] getChunk = {"net.minecraft.world.entity.Entity.tick", "net.minecraft.server.level.ServerChunkCache.getChunk"};
		assertFalse(StackSampler.isIdle(sample(StackSampler.IN_TICK, LOOP, TICK, getChunk, TASK_WAIT_PARK)), "a chunk wait in a tick is lag");
		assertFalse(StackSampler.isIdle(sample(StackSampler.IN_TICK, LOOP, TICK, getChunk, TASK_WAIT_YIELD)));
		assertFalse(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, TICK, getChunk, TASK_WAIT_PARK)), "even with a stale phase");
		// An autosave waiting for chunks to be written.
		assertFalse(StackSampler.isIdle(sample(StackSampler.IN_TICK, LOOP, new String[] {"net.minecraft.server.MinecraftServer.tickServer",
				"net.minecraft.server.MinecraftServer.autoSave", "net.minecraft.server.MinecraftServer.saveEverything",
				"net.minecraft.server.level.ChunkMap.saveAllChunks"}, TASK_WAIT_PARK)), "an autosave wait is lag");

		// Between ticks, packet tasks run from inside the main loop's managedBlock. Their waits are not idle.
		String[] task = {"net.minecraft.server.MinecraftServer.waitUntilNextTick", "net.minecraft.util.thread.BlockableEventLoop.managedBlock",
				"net.minecraft.server.MinecraftServer.pollTask", "net.minecraft.util.thread.BlockableEventLoop.doRunTask",
				"net.minecraft.network.protocol.PacketUtils.lambda$ensureRunningOnSameThread$0",
				"net.minecraft.server.network.ServerGamePacketListenerImpl.handleChatCommand"};
		assertFalse(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, task, new String[] {
				"java.util.concurrent.locks.ReentrantLock.lock", "java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire",
				"java.util.concurrent.locks.LockSupport.park", "jdk.internal.misc.Unsafe.park"})), "a packet task blocked on a lock");
		assertFalse(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, task, getChunk, TASK_WAIT_PARK)),
				"a packet task waiting for a chunk: two managedBlocks");
		assertFalse(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, task)), "a packet task working");
		assertFalse(StackSampler.isIdle(sample(StackSampler.BETWEEN_TICKS, LOOP, new String[] {"com.example.Mod.onTick", "java.lang.Thread.sleep",
				"java.lang.Thread.sleepNanos", "java.lang.Thread.sleep0"})), "a mod sleeping on the server thread");
		assertFalse(StackSampler.isIdle(List.of()));
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
