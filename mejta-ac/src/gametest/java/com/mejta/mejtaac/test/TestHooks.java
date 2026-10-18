package com.mejta.mejtaac.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** State shared between the test thread and the test-only mixins (client and in-process server). */
public final class TestHooks {
	/** Client: when true, every movement packet claims onGround=true (simulated ground-spoof cheat). */
	public static volatile boolean spoofGround;

	/** Server: entity IDs attacked since the last client tick end. */
	private static final List<Integer> currentTickAttacks = new ArrayList<>();
	/** Server: the attacked IDs of every client tick that contained two or more attacks. */
	public static final List<List<Integer>> ticksWithSeveralAttacks = new CopyOnWriteArrayList<>();
	public static volatile int attacksReceived;
	/** Server: block break starts and item uses on blocks received from clients. */
	public static volatile int blockActionsReceived;
	/** Server: largest eye-to-target distance at the moment an attack arrived (no compensation). */
	public static volatile double maxUncompensatedDistance;

	private TestHooks() {
	}

	public static synchronized void onServerAttack(int entityId, double currentDistance) {
		currentTickAttacks.add(entityId);
		attacksReceived++;
		maxUncompensatedDistance = Math.max(maxUncompensatedDistance, currentDistance);
	}

	public static synchronized void onServerClientTickEnd() {
		if (currentTickAttacks.size() >= 2) {
			ticksWithSeveralAttacks.add(List.copyOf(currentTickAttacks));
		}
		currentTickAttacks.clear();
	}

	public static synchronized void resetAttacks() {
		currentTickAttacks.clear();
		ticksWithSeveralAttacks.clear();
		attacksReceived = 0;
		maxUncompensatedDistance = 0;
	}
}
