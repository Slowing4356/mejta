package com.mejta.mejtautils.offline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Changes a staff member queued for an offline player's inventory or ender chest. Nothing here ever
 * writes the player's save file: the change is applied to the live player when they next join.
 *
 * <p>Items are kept as opaque JSON (the encoded item stack), so this class needs no game classes and
 * can be unit tested. The game layer supplies how live slot contents compare to the recorded ones.
 *
 * <p>Duplication cannot happen by construction:
 * <ul>
 * <li>A removed item stays in the player's save until the change is applied. Only then is it moved to
 * the staff member's claims (or deleted, if they chose so).</li>
 * <li>An added item is taken from the staff member at once and stored here. It goes into the player's
 * inventory when the change is applied; if the change cannot be applied, it is returned to the staff
 * member's claims.</li>
 * </ul>
 *
 * <p>Life cycle, each step written durably before the game state changes:
 * <pre>
 * OPEN --player joins--> APPLYING --applied and player saved--> APPLIED (claims) --all claimed--> deleted
 *   \--cancelled or conflict--> CANCELLED (claims: added items)
 * </pre>
 * A crash during {@code APPLYING} is resolved by {@link #recover}: the live player shows either the state
 * before or after the change, or neither (then a person decides).
 */
public final class PendingChange {
	public enum Container {
		INVENTORY,
		ENDER_CHEST
	}

	public enum Kind {
		/** Take {@code item} out of the slot. */
		REMOVE,
		/** Put {@code item} into the (empty) slot. */
		ADD
	}

	/** One queued slot change. {@code item} is the encoded stack removed or added. */
	public record SlotOp(Kind kind, Container container, int slot, JsonElement item) {
		public SlotOp {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(container, "container");
			Objects.requireNonNull(item, "item");
		}
	}

	public record SlotRef(Container container, int slot) {
	}

	public enum State {
		/** Queued; can still be edited by its staff member. */
		OPEN,
		/** Being applied to the player who just joined. */
		APPLYING,
		/** Applied; {@link #claims()} holds removed items the staff member has not collected yet. */
		APPLIED,
		/** Not applied (cancelled or conflicting); {@link #claims()} holds the added items to give back. */
		CANCELLED
	}

	private final UUID target;
	private final String targetName;
	private final UUID staff;
	private final String staffName;
	private final Instant created;
	private boolean deleteRemoved;
	private State state = State.OPEN;
	private String note = "";
	private final Map<SlotRef, SlotOp> ops = new LinkedHashMap<>();
	private final List<JsonElement> claims = new ArrayList<>();

	public PendingChange(UUID target, String targetName, UUID staff, String staffName, Instant created) {
		this.target = Objects.requireNonNull(target);
		this.targetName = Objects.requireNonNull(targetName);
		this.staff = Objects.requireNonNull(staff);
		this.staffName = Objects.requireNonNull(staffName);
		this.created = Objects.requireNonNull(created);
	}

	public UUID target() {
		return target;
	}

	public String targetName() {
		return targetName;
	}

	public UUID staff() {
		return staff;
	}

	public String staffName() {
		return staffName;
	}

	public Instant created() {
		return created;
	}

	/** Whether removed items are deleted instead of going to the staff member's claims. */
	public boolean deleteRemoved() {
		return deleteRemoved;
	}

	public void setDeleteRemoved(boolean deleteRemoved) {
		this.deleteRemoved = deleteRemoved;
	}

	public State state() {
		return state;
	}

	/** Why the change ended up {@code CANCELLED}, for staff. */
	public String note() {
		return note;
	}

	public void markApplying() {
		requireState(State.OPEN);
		state = State.APPLYING;
	}

	/** After applying: the plan's claims become collectable. */
	public void markApplied(List<JsonElement> toClaims) {
		requireState(State.APPLYING);
		state = State.APPLIED;
		claims.clear();
		claims.addAll(toClaims);
	}

	/** Not applied: added items go back to the staff member. Removals are dropped (the items stay with the player). */
	public void markCancelled(String reason) {
		if (state != State.OPEN && state != State.APPLYING) {
			throw new IllegalStateException("cannot cancel a change in state " + state);
		}
		state = State.CANCELLED;
		note = Objects.requireNonNull(reason);
		claims.clear();
		claims.addAll(addedItems());
	}

	/** Items waiting to be collected by the staff member ({@code APPLIED} or {@code CANCELLED}). */
	public List<JsonElement> claims() {
		return List.copyOf(claims);
	}

	/** Removes one claim (after it was given to the staff member). */
	public JsonElement takeClaim(int index) {
		return claims.remove(index);
	}

	/** Whether nothing is left to do: finished and every claim collected. */
	public boolean isDone() {
		return (state == State.APPLIED || state == State.CANCELLED) && claims.isEmpty();
	}

	private void requireState(State expected) {
		if (state != expected) {
			throw new IllegalStateException("change is " + state + ", expected " + expected);
		}
	}

	private void requireOpen() {
		requireState(State.OPEN);
	}

	public List<SlotOp> ops() {
		return List.copyOf(ops.values());
	}

	public boolean isEmpty() {
		return ops.isEmpty();
	}

	public Optional<SlotOp> op(Container container, int slot) {
		return Optional.ofNullable(ops.get(new SlotRef(container, slot)));
	}

	/**
	 * Queues removal of {@code item}, which the snapshot shows in that slot.
	 *
	 * @return {@code false} if the slot already has a queued change
	 */
	public boolean remove(Container container, int slot, JsonElement item) {
		requireOpen();
		return ops.putIfAbsent(new SlotRef(container, slot), new SlotOp(Kind.REMOVE, container, slot, item)) == null;
	}

	/**
	 * Queues adding {@code item} to a slot the snapshot shows empty. The caller must already have taken
	 * the item from the staff member.
	 *
	 * @return {@code false} if the slot already has a queued change (the caller must give the item back)
	 */
	public boolean add(Container container, int slot, JsonElement item) {
		requireOpen();
		return ops.putIfAbsent(new SlotRef(container, slot), new SlotOp(Kind.ADD, container, slot, item)) == null;
	}

	/**
	 * Cancels the queued change of a slot. For an {@code ADD}, the returned op's item must be given back
	 * to the staff member by the caller.
	 */
	public Optional<SlotOp> undo(Container container, int slot) {
		requireOpen();
		return Optional.ofNullable(ops.remove(new SlotRef(container, slot)));
	}

	/** Items the staff member gave and must get back if the whole change is cancelled. */
	public List<JsonElement> addedItems() {
		List<JsonElement> out = new ArrayList<>();
		for (SlotOp op : ops.values()) {
			if (op.kind() == Kind.ADD) {
				out.add(op.item());
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- applying

	/**
	 * What applying the change to the live player means.
	 *
	 * @param applicable {@code false} if any slot no longer holds what the change was made against
	 * @param conflicts  slots that did not match (empty if applicable)
	 * @param writes     slot contents to set when applicable: the item for {@code ADD}, {@code null} (empty) for {@code REMOVE}
	 * @param toClaims   items for the staff member's claims: removed items (unless deleted) when applicable, or
	 *                   every added item when not
	 */
	public record Plan(boolean applicable, List<SlotRef> conflicts, Map<SlotRef, JsonElement> writes, List<JsonElement> toClaims) {
	}

	/**
	 * @param live    current item in a slot of the live player, in the game's own representation ({@code null} if empty)
	 * @param matches whether a live item equals an encoded item; never called with a {@code null} live item
	 */
	public <T> Plan plan(Function<SlotRef, T> live, BiPredicate<T, JsonElement> matches) {
		List<SlotRef> conflicts = new ArrayList<>();
		for (SlotOp op : ops.values()) {
			SlotRef ref = new SlotRef(op.container(), op.slot());
			T current = live.apply(ref);
			boolean ok = switch (op.kind()) {
				case REMOVE -> current != null && matches.test(current, op.item());
				case ADD -> current == null;
			};
			if (!ok) {
				conflicts.add(ref);
			}
		}
		if (!conflicts.isEmpty()) {
			return new Plan(false, List.copyOf(conflicts), Map.of(), addedItems());
		}
		Map<SlotRef, JsonElement> writes = new LinkedHashMap<>();
		List<JsonElement> claims = new ArrayList<>();
		for (SlotOp op : ops.values()) {
			SlotRef ref = new SlotRef(op.container(), op.slot());
			if (op.kind() == Kind.ADD) {
				writes.put(ref, op.item());
			} else {
				writes.put(ref, null);
				if (!deleteRemoved) {
					claims.add(op.item());
				}
			}
		}
		return new Plan(true, List.of(), Collections.unmodifiableMap(writes), List.copyOf(claims));
	}

	public enum Recovery {
		/** The live player already shows the result: mark it applied. */
		ALREADY_APPLIED,
		/** The live player still shows the state the change was made against: apply it again. */
		NOT_APPLIED,
		/** Neither: change nothing, a person must decide. */
		UNKNOWN
	}

	/** For a change found in state {@code APPLYING} (the server stopped while applying it). */
	public <T> Recovery recover(Function<SlotRef, T> live, BiPredicate<T, JsonElement> matches) {
		boolean before = true;
		boolean after = true;
		for (SlotOp op : ops.values()) {
			T current = live.apply(new SlotRef(op.container(), op.slot()));
			boolean holdsItem = current != null && matches.test(current, op.item());
			boolean empty = current == null;
			if (op.kind() == Kind.REMOVE) {
				before &= holdsItem;
				after &= empty;
			} else {
				before &= empty;
				after &= holdsItem;
			}
		}
		if (after && !ops.isEmpty()) {
			return Recovery.ALREADY_APPLIED;
		}
		return before ? Recovery.NOT_APPLIED : Recovery.UNKNOWN;
	}

	// ---------------------------------------------------------------- JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("format", 1);
		o.addProperty("target", target.toString());
		o.addProperty("target_name", targetName);
		o.addProperty("staff", staff.toString());
		o.addProperty("staff_name", staffName);
		o.addProperty("created", created.toString());
		o.addProperty("delete_removed", deleteRemoved);
		o.addProperty("state", state.name());
		o.addProperty("note", note);
		JsonArray claimList = new JsonArray();
		claims.forEach(c -> claimList.add(c.deepCopy()));
		o.add("claims", claimList);
		JsonArray list = new JsonArray();
		for (SlotOp op : ops.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("kind", op.kind().name());
			j.addProperty("container", op.container().name());
			j.addProperty("slot", op.slot());
			j.add("item", op.item().deepCopy());
			list.add(j);
		}
		o.add("ops", list);
		return o;
	}

	/** @throws IllegalArgumentException if the document is not a valid pending change */
	public static PendingChange fromJson(JsonObject o) {
		try {
			if (o.get("format").getAsInt() != 1) {
				throw new IllegalArgumentException("unknown format " + o.get("format"));
			}
			PendingChange change = new PendingChange(UUID.fromString(o.get("target").getAsString()), o.get("target_name").getAsString(),
					UUID.fromString(o.get("staff").getAsString()), o.get("staff_name").getAsString(),
					Instant.parse(o.get("created").getAsString()));
			change.deleteRemoved = o.get("delete_removed").getAsBoolean();
			change.state = State.valueOf(o.get("state").getAsString());
			change.note = o.get("note").getAsString();
			for (JsonElement claim : o.getAsJsonArray("claims")) {
				change.claims.add(claim.deepCopy());
			}
			for (JsonElement e : o.getAsJsonArray("ops")) {
				JsonObject j = e.getAsJsonObject();
				Kind kind = Kind.valueOf(j.get("kind").getAsString());
				Container container = Container.valueOf(j.get("container").getAsString());
				int slot = j.get("slot").getAsInt();
				if (slot < 0 || slot >= (container == Container.INVENTORY ? 41 : 27)) {
					throw new IllegalArgumentException("slot " + slot + " out of range for " + container);
				}
				SlotRef ref = new SlotRef(container, slot);
				if (change.ops.putIfAbsent(ref, new SlotOp(kind, container, slot, j.get("item").deepCopy())) != null) {
					throw new IllegalArgumentException("two changes for " + ref);
				}
			}
			return change;
		} catch (RuntimeException e) {
			throw e instanceof IllegalArgumentException iae ? iae : new IllegalArgumentException("invalid pending change: " + e, e);
		}
	}
}
