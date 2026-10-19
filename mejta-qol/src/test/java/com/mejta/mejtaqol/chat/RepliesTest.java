package com.mejta.mejtaqol.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RepliesTest {
	static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	static final UUID C = UUID.fromString("00000000-0000-4000-8000-00000000000c");

	@Test
	void bothSidesRememberTheConversation() {
		Replies r = new Replies();
		r.record(A, B);
		assertEquals(new Replies.Partner(B, false), r.partner(A).orElseThrow());
		assertEquals(new Replies.Partner(A, true), r.partner(B).orElseThrow());
		r.record(C, B);
		assertEquals(new Replies.Partner(C, true), r.partner(B).orElseThrow(), "the newest message wins");
		assertEquals(new Replies.Partner(B, false), r.partner(A).orElseThrow());
	}

	@Test
	void messagesToSelfAreIgnored() {
		Replies r = new Replies();
		r.record(A, A);
		assertTrue(r.partner(A).isEmpty());
		r.record(A, B);
		r.forget(A);
		assertTrue(r.partner(A).isEmpty());
	}
}
