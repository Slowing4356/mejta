package com.mejta.mejtacore.addons;

import java.util.Optional;

import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;

/** Mutable registry record behind the read-only {@link AddonInfo}. */
final class RegisteredAddon implements AddonInfo {
	private final AddonDefinition definition;
	private final String source;
	private volatile AddonState state = AddonState.PENDING;
	private volatile String failureReason;
	private volatile ConfigHandle config;

	RegisteredAddon(AddonDefinition definition, String source) {
		this.definition = definition;
		this.source = source;
	}

	void markActive() {
		state = AddonState.ACTIVE;
	}

	void markFailed(String reason) {
		failureReason = reason;
		state = AddonState.FAILED;
	}

	void setConfig(ConfigHandle config) {
		this.config = config;
	}

	@Override
	public String id() {
		return definition.id();
	}

	@Override
	public String name() {
		return definition.name();
	}

	@Override
	public String version() {
		return definition.version();
	}

	@Override
	public String description() {
		return definition.description();
	}

	@Override
	public String iconItem() {
		return definition.iconItem();
	}

	@Override
	public AddonState state() {
		return state;
	}

	@Override
	public Optional<String> failureReason() {
		return Optional.ofNullable(failureReason);
	}

	@Override
	public String source() {
		return source;
	}

	@Override
	public Optional<ConfigHandle> config() {
		return Optional.ofNullable(config);
	}

	@Override
	public AddonDefinition definition() {
		return definition;
	}
}
