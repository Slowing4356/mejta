package com.mejta.mejtacore.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.audit.AuditEntry;

/**
 * Turns audit entries into hash-chained JSON lines and verifies such chains. Pure logic; the sink
 * decides where lines go (a record log in production, a list in tests).
 *
 * <p>A line's {@code hash} is the SHA-256 of the line without its {@code hash} field, serialized
 * compactly with HTML escaping off. Every value is a string, so re-serializing a parsed line gives
 * exactly the same text.
 */
public final class AuditChain {
	public static final String GENESIS = "0".repeat(64);
	private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().create();

	private final Supplier<String> headLoader;
	private final Consumer<JsonObject> sink;
	private String head;

	/**
	 * @param headLoader returns the {@code hash} of the newest existing line, or {@link #GENESIS}; called
	 *                   once, on the first append
	 */
	public AuditChain(Supplier<String> headLoader, Consumer<JsonObject> sink) {
		this.headLoader = headLoader;
		this.sink = sink;
	}

	/** Builds the line for {@code entry}, links it to the previous one and hands it to the sink. */
	public synchronized JsonObject append(AuditEntry entry) {
		if (head == null) {
			String loaded = headLoader.get();
			head = loaded == null || loaded.isEmpty() ? GENESIS : loaded;
		}
		JsonObject line = toJson(entry);
		line.addProperty("prev", head);
		String hash = hashOf(line);
		line.addProperty("hash", hash);
		sink.accept(line);
		head = hash;
		return line;
	}

	static JsonObject toJson(AuditEntry e) {
		JsonObject line = new JsonObject();
		line.addProperty("type", "audit");
		line.addProperty("ts", e.timestamp().toString());
		line.addProperty("source", e.source());
		line.addProperty("action", e.action());
		line.addProperty("actor", e.actor().name());
		line.addProperty("actor_uuid", e.actor().uuid().map(Object::toString).orElse(""));
		line.addProperty("actor_kind", e.actor().kind().name());
		line.addProperty("target", e.targetName());
		line.addProperty("target_uuid", e.targetUuid().map(Object::toString).orElse(""));
		line.addProperty("outcome", e.outcome().name());
		JsonObject details = new JsonObject();
		for (Map.Entry<String, String> d : e.details().entrySet()) {
			details.addProperty(d.getKey(), d.getValue());
		}
		line.add("details", details);
		return line;
	}

	/** SHA-256 (hex) of {@code line} without its {@code hash} field. */
	public static String hashOf(JsonObject line) {
		JsonObject copy = line.deepCopy();
		copy.remove("hash");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(CANONICAL.toJson(copy).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
		}
	}

	/**
	 * @param records       lines read back, oldest first
	 * @param startsAtGenesis whether the first line should be the very first ever written (no older
	 *                        files were rotated out); if not, its {@code prev} is accepted as given
	 */
	public static Verification verify(List<JsonObject> records, boolean startsAtGenesis) {
		String expectedPrev = null;
		int index = 0;
		for (JsonObject line : records) {
			String prev = text(line, "prev");
			String hash = text(line, "hash");
			if (prev == null || hash == null) {
				return new Verification(records.size(), index, index, "line " + (index + 1) + " has no hash fields");
			}
			if (expectedPrev == null) {
				if (startsAtGenesis && !GENESIS.equals(prev)) {
					return new Verification(records.size(), index, index, "the first line does not start the chain; older lines are missing");
				}
			} else if (!expectedPrev.equals(prev)) {
				return new Verification(records.size(), index, index,
						"line " + (index + 1) + " does not follow line " + index + ": a line was removed or reordered");
			}
			if (!hashOf(line).equals(hash)) {
				return new Verification(records.size(), index, index, "line " + (index + 1) + " was changed after it was written");
			}
			expectedPrev = hash;
			index++;
		}
		return new Verification(records.size(), index, -1, "");
	}

	private static String text(JsonObject line, String key) {
		JsonElement e = line.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	/**
	 * @param brokenAt index of the first bad line, or {@code -1} if the whole chain is intact
	 */
	public record Verification(int records, int verified, int brokenAt, String problem) {
		public boolean intact() {
			return brokenAt < 0;
		}
	}
}
