package com.mejta.mejtacorpse.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacorpse.data.Access.Decision;

class AccessTest {
	static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
	static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
	static final long MIN = 60_000;
	static final CorpseRecord CORPSE = new CorpseRecord("id", OWNER, "Owner", 10 * MIN, CorpseRecord.State.ACTIVE, "minecraft:overworld",
			0, 0, 0, 0, false, Map.of(), null, null, null);

	@Test
	void ownerAndStaffAlways() {
		assertEquals(Decision.OWNER, Access.decide(OWNER, CORPSE, false, -1, 10 * MIN));
		assertEquals(Decision.OWNER, Access.decide(OWNER, CORPSE, true, -1, 10 * MIN), "owner wins over staff");
		assertEquals(Decision.STAFF, Access.decide(OTHER, CORPSE, true, -1, 10 * MIN));
	}

	@Test
	void othersOnlyOncePublic() {
		assertEquals(Decision.DENIED, Access.decide(OTHER, CORPSE, false, -1, 10_000 * MIN), "never public");
		assertEquals(Decision.PUBLIC, Access.decide(OTHER, CORPSE, false, 0, 10 * MIN), "public at once");
		assertEquals(Decision.DENIED, Access.decide(OTHER, CORPSE, false, 5, 15 * MIN - 1));
		assertEquals(Decision.PUBLIC, Access.decide(OTHER, CORPSE, false, 5, 15 * MIN));
	}

	@Test
	void timeUntilPublic() {
		assertEquals(-1, Access.untilPublic(CORPSE, -1, 0));
		assertEquals(2 * MIN, Access.untilPublic(CORPSE, 5, 13 * MIN));
		assertEquals(0, Access.untilPublic(CORPSE, 5, 100 * MIN));
	}

	@Test
	void expiry() {
		assertFalse(Access.expired(CORPSE, 0, Long.MAX_VALUE / 2), "0 = never");
		assertFalse(Access.expired(CORPSE, 30, 40 * MIN - 1));
		assertTrue(Access.expired(CORPSE, 30, 40 * MIN));
	}
}
