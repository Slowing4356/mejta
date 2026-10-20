package com.mejta.mejtasmp.xp;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.mixin.ExperienceOrbAccessor;

/**
 * Experience orbs, like the Clumps mod: orbs near each other merge into one orb holding all their
 * experience ({@code xp.clump}), and a player takes a touching orb at once ({@code xp.instant_pickup}).
 * Server thread only.
 */
public final class XpClumps {
	/** Parts of one orb taken in one touch, at most (vanilla orbs of equal value count their parts). */
	public static final int MAX_PARTS = 10_000;
	/** Experience one orb may hold after merging. */
	static final long MAX_VALUE = 1_000_000;

	private XpClumps() {
	}

	public static boolean instant() {
		ConfigHandle config = MejtaSMP.configOrNull();
		return config != null && config.get(SmpSettings.XP_INSTANT);
	}

	/** Replaces vanilla's merge scan for {@code self}. Returns false for vanilla's rule. */
	public static boolean clump(ExperienceOrb self) {
		ConfigHandle config = MejtaSMP.configOrNull();
		if (config == null || !config.get(SmpSettings.XP_CLUMP) || !(self.level() instanceof ServerLevel level) || self.isRemoved()) {
			return false;
		}
		ExperienceOrbAccessor into = (ExperienceOrbAccessor) self;
		for (ExperienceOrb other : level.getEntitiesOfClass(ExperienceOrb.class,
				self.getBoundingBox().inflate(config.get(SmpSettings.XP_CLUMP_RADIUS)), o -> o != self && !o.isRemoved())) {
			ExperienceOrbAccessor from = (ExperienceOrbAccessor) other;
			long sum = worth(self) + worth(other);
			if (sum > MAX_VALUE) {
				continue;
			}
			into.mejtasmp$setAge(Math.min(into.mejtasmp$age(), from.mejtasmp$age()));
			other.discard();
			into.mejtasmp$setValue((int) sum);
			into.mejtasmp$setCount(1);
		}
		return true;
	}

	/** All the experience in an orb: its value times its parts. */
	static long worth(ExperienceOrb orb) {
		return (long) orb.getValue() * ((ExperienceOrbAccessor) orb).mejtasmp$count();
	}
}
