package com.mejta.example.test;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.addon.AddonDefinition;

/** Deliberately broken registrations, checked by {@link MejtaCoreClientGameTest}. */
public final class FailureCases implements MejtaAddon {
	static volatile boolean firstDuplicateAccepted;
	static volatile boolean secondDuplicateAccepted = true;
	static volatile boolean invalidIdRejected;
	static volatile boolean reservedIdRejected;
	static volatile boolean selfDependencyRejected;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		firstDuplicateAccepted = registrar.register(AddonDefinition.builder("dup_test").name("Duplicate Test #1").build());
		secondDuplicateAccepted = registrar.register(AddonDefinition.builder("dup_test").name("Duplicate Test #2").build());

		registrar.register(AddonDefinition.builder("needs_missing").name("Needs Missing")
				.dependsOn("does_not_exist").build());
		registrar.register(AddonDefinition.builder("broken_init").name("Broken Init")
				.onInitialize(ctx -> {
					throw new IllegalStateException("intentional failure for testing");
				}).build());
		registrar.register(AddonDefinition.builder("depends_on_broken").name("Depends On Broken")
				.dependsOn("broken_init").build());
		registrar.register(AddonDefinition.builder("cycle_a").dependsOn("cycle_b").build());
		registrar.register(AddonDefinition.builder("cycle_b").dependsOn("cycle_a").build());

		invalidIdRejected = throwsIllegalArgument(() -> AddonDefinition.builder("Bad ID"));
		reservedIdRejected = throwsIllegalArgument(() -> AddonDefinition.builder("gui"));
		selfDependencyRejected = throwsIllegalArgument(() -> AddonDefinition.builder("selfdep").dependsOn("selfdep"));
	}

	private static boolean throwsIllegalArgument(Runnable action) {
		try {
			action.run();
			return false;
		} catch (IllegalArgumentException e) {
			return true;
		}
	}
}
