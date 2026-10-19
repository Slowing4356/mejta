package com.mejta.mejtaqol.tpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaqol.tpa.TpaRequests.Direction;
import com.mejta.mejtaqol.tpa.TpaRequests.Request;

class TpaRequestsTest {
	static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	static final UUID C = UUID.fromString("00000000-0000-4000-8000-00000000000c");

	@Test
	void travellerDependsOnDirection() {
		Request to = new Request(A, "a", B, "b", Direction.TO_TARGET, 0, 1);
		assertEquals(A, to.traveller());
		assertEquals(B, to.destination());
		Request here = new Request(A, "a", B, "b", Direction.TO_SENDER, 0, 1);
		assertEquals(B, here.traveller());
		assertEquals(A, here.destination());
	}

	@Test
	void newRequestToSameTargetReplacesTheOldOne() {
		TpaRequests r = new TpaRequests();
		assertTrue(r.add(A, "a", B, "b", Direction.TO_TARGET, 0, 60_000).isEmpty());
		Optional<Request> replaced = r.add(A, "a", B, "b", Direction.TO_SENDER, 10, 60_000);
		assertEquals(Direction.TO_TARGET, replaced.orElseThrow().direction());
		assertEquals(1, r.size());
		assertEquals(Direction.TO_SENDER, r.incoming(B).get(0).direction());
	}

	@Test
	void acceptWithoutNameTakesTheNewest() {
		TpaRequests r = new TpaRequests();
		r.add(A, "a", C, "c", Direction.TO_TARGET, 0, 60_000);
		r.add(B, "b", C, "c", Direction.TO_TARGET, 5, 60_000);
		assertEquals(List.of(B, A), r.incoming(C).stream().map(Request::sender).toList());
		assertEquals(B, r.take(C, Optional.empty()).orElseThrow().sender());
		assertEquals(A, r.take(C, Optional.of(A)).orElseThrow().sender());
		assertTrue(r.take(C, Optional.empty()).isEmpty());
	}

	@Test
	void takeByUnknownSenderTakesNothing() {
		TpaRequests r = new TpaRequests();
		r.add(A, "a", C, "c", Direction.TO_TARGET, 0, 60_000);
		assertTrue(r.take(C, Optional.of(B)).isEmpty());
		assertEquals(1, r.size());
	}

	@Test
	void requestsExpire() {
		TpaRequests r = new TpaRequests();
		r.add(A, "a", B, "b", Direction.TO_TARGET, 0, 1_000);
		r.add(C, "c", B, "b", Direction.TO_TARGET, 500, 1_000);
		assertEquals(List.of(), r.expire(999));
		assertEquals(List.of(A), r.expire(1_000).stream().map(Request::sender).toList());
		assertEquals(1, r.size());
		assertTrue(r.take(B, Optional.of(A)).isEmpty());
		// Adding expires old ones too.
		r.add(A, "a", C, "c", Direction.TO_TARGET, 2_000, 1_000);
		assertEquals(1, r.size());
	}

	@Test
	void leavingRemovesBothDirections() {
		TpaRequests r = new TpaRequests();
		r.add(A, "a", B, "b", Direction.TO_TARGET, 0, 60_000);
		r.add(B, "b", C, "c", Direction.TO_TARGET, 0, 60_000);
		r.add(A, "a", C, "c", Direction.TO_TARGET, 0, 60_000);
		assertEquals(2, r.removeAll(B).size());
		assertEquals(1, r.size());
		assertEquals(A, r.outgoing(A).get(0).sender());
	}
}
