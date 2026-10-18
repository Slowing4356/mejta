package com.mejta.example;

import net.fabricmc.api.ModInitializer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/**
 * Shows the alternative registration style: calling {@link MejtaCoreAPI#registerAddon} directly
 * from a normal {@code ModInitializer}. "example_extra" depends on "example", so MejtaCore
 * initializes it second even though this code may run before the entrypoint above.
 */
public final class ExampleMod implements ModInitializer {
	public static final Setting<Boolean> SHOW_TIPS = Setting.bool("show_tips", true)
			.describedAs("Show tips from the extra module");

	/** Whether "example" was already active when "example_extra" initialized (dependency ordering). */
	public static volatile boolean extraSawExampleActive;

	@Override
	public void onInitialize() {
		MejtaCoreAPI.registerAddon(AddonDefinition.builder("example_extra")
				.name("Example Extra")
				.version("1.0.0")
				.description("Second addon that requires the Example Addon")
				.icon("minecraft:emerald")
				.config(ConfigSpec.builder(1).add(SHOW_TIPS).build())
				.dependsOn(ExampleAddon.ID)
				.optionallyDependsOn("some_optional_addon")
				.onInitialize(ctx -> {
					extraSawExampleActive = MejtaCoreAPI.isAddonActive(ExampleAddon.ID);
					ctx.logger().info("Example Extra initialized (example active: {})", extraSawExampleActive);
				})
				.build());
	}
}
