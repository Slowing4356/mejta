package com.mejta.mejtacore.api.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One sensitive administrative action: who did what to whom, when, and how it ended.
 *
 * <pre>{@code
 * MejtaCoreAPI.audit().record(AuditEntry.builder("myaddon", "inventory.edit")
 *     .actor(MejtaCoreAPI.actor(source))
 *     .target(target.getUUID(), target.getGameProfile().name())
 *     .outcome(AuditOutcome.SUCCESS)
 *     .detail("slot", 12)
 *     .build(), MyPermissions.AUDIT_ALERTS);
 * }</pre>
 *
 * Like notifications, audit entries may be sent to external services; never put passwords, tokens or
 * IP addresses in them.
 *
 * @since API version 4
 */
public final class AuditEntry {
	/** Actions: lowercase, e.g. {@code invsee.edit}, {@code staffmode.enter}. */
	/** The recording addon's ID (the rule of {@code AddonDefinition.ID_PATTERN}). */
	private static final Pattern SOURCE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
	public static final Pattern ACTION_PATTERN = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*");
	private static final int MAX_DETAILS = 32;
	private static final int MAX_TEXT = 500;

	private final String source;
	private final String action;
	private final AuditActor actor;
	private final Optional<UUID> targetUuid;
	private final String targetName;
	private final AuditOutcome outcome;
	private final Map<String, String> details;
	private final Instant timestamp;

	private AuditEntry(Builder b) {
		this.source = b.source;
		this.action = b.action;
		this.actor = Objects.requireNonNull(b.actor, "actor must be set");
		this.targetUuid = Optional.ofNullable(b.targetUuid);
		this.targetName = b.targetName;
		this.outcome = b.outcome;
		this.details = Collections.unmodifiableMap(new LinkedHashMap<>(b.details));
		this.timestamp = b.timestamp == null ? Instant.now() : b.timestamp;
	}

	/**
	 * @param source ID of the addon recording it
	 * @param action what happened, e.g. {@code freeze.start}
	 */
	public static Builder builder(String source, String action) {
		return new Builder(source, action);
	}

	public String source() {
		return source;
	}

	public String action() {
		return action;
	}

	public AuditActor actor() {
		return actor;
	}

	public Optional<UUID> targetUuid() {
		return targetUuid;
	}

	/** Target name, or {@code ""} if the action has no target. */
	public String targetName() {
		return targetName;
	}

	public AuditOutcome outcome() {
		return outcome;
	}

	public Map<String, String> details() {
		return details;
	}

	public Instant timestamp() {
		return timestamp;
	}

	@Override
	public String toString() {
		return "AuditEntry[" + source + " " + action + " by " + actor.name() + " -> " + targetName + ": " + outcome + "]";
	}

	public static final class Builder {
		private final String source;
		private final String action;
		private AuditActor actor;
		private UUID targetUuid;
		private String targetName = "";
		private AuditOutcome outcome = AuditOutcome.SUCCESS;
		private final Map<String, String> details = new LinkedHashMap<>();
		private Instant timestamp;

		private Builder(String source, String action) {
			this.source = Objects.requireNonNull(source, "source");
			if (!SOURCE_PATTERN.matcher(source).matches()) {
				throw new IllegalArgumentException("Invalid audit source '" + source + "': must be an addon ID");
			}
			Objects.requireNonNull(action, "action");
			if (action.length() > 64 || !ACTION_PATTERN.matcher(action).matches()) {
				throw new IllegalArgumentException("Invalid audit action '" + action + "'");
			}
			this.action = action;
		}

		public Builder actor(AuditActor actor) {
			this.actor = Objects.requireNonNull(actor, "actor");
			return this;
		}

		public Builder target(UUID uuid, String name) {
			this.targetUuid = uuid;
			this.targetName = limit(Objects.requireNonNull(name, "name"));
			return this;
		}

		/** A target that is not a player, e.g. a config file or a container position. */
		public Builder target(String description) {
			this.targetUuid = null;
			this.targetName = limit(Objects.requireNonNull(description, "description"));
			return this;
		}

		public Builder outcome(AuditOutcome outcome) {
			this.outcome = Objects.requireNonNull(outcome, "outcome");
			return this;
		}

		/** A structured detail; stored as text. At most 32; extra details are ignored. */
		public Builder detail(String key, Object value) {
			Objects.requireNonNull(key, "key");
			if (details.size() < MAX_DETAILS || details.containsKey(key)) {
				details.put(key, limit(String.valueOf(value)));
			}
			return this;
		}

		public Builder timestamp(Instant timestamp) {
			this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
			return this;
		}

		public AuditEntry build() {
			return new AuditEntry(this);
		}

		private static String limit(String text) {
			return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "...";
		}
	}
}
