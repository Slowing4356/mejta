package com.mejta.mejtadiscord.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.mejta.mejtadiscord.text.Redactor;

class GatewayClientTest {
	private static final Logger LOG = LoggerFactory.getLogger("GatewayClientTest");
	private static final String TOKEN = "MTIz.fake.bot-token-value";
	private static final long WAIT = 3000;

	FakeGateway gateway;
	GatewayClient client;
	final List<String> messages = new CopyOnWriteArrayList<>();

	@BeforeEach
	void start() throws Exception {
		gateway = new FakeGateway();
		client = newClient(10, GatewayClient.HANDSHAKE_TIMEOUT_MS);
		client.start();
	}

	GatewayClient newClient(long backoffMs, long handshakeTimeoutMs) {
		Redactor redactor = new Redactor();
		redactor.setSecrets(List.of(TOKEN));
		return new GatewayClient(gateway.uri(), TOKEN, GatewayClient.CHAT_INTENTS, HttpClient.newHttpClient(), redactor, LOG,
				message -> messages.add(message.get("content").getAsString()), backoffMs, handshakeTimeoutMs);
	}

	/** Replaces the default client (after its first connection) with one using these timings. */
	void restart(long backoffMs, long handshakeTimeoutMs) throws Exception {
		assertNotNull(gateway.next(WAIT), "the default client connected");
		client.close();
		client = newClient(backoffMs, handshakeTimeoutMs);
		client.start();
	}

	@AfterEach
	void stop() throws Exception {
		client.close();
		gateway.close();
	}

	static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 300; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(10);
		}
		return condition.getAsBoolean();
	}

	/** Connects, identifies and becomes ready with sequence {@code seq}. */
	FakeGateway.Conn ready(long heartbeatMs, int seq) throws Exception {
		FakeGateway.Conn conn = gateway.next(WAIT);
		assertNotNull(conn, "client connected");
		conn.hello(heartbeatMs);
		JsonObject identify = conn.expect(2, WAIT);
		assertNotNull(identify, "identified");
		JsonObject d = new JsonObject();
		d.addProperty("session_id", "sess-1");
		d.addProperty("resume_gateway_url", gateway.uri().toString());
		JsonObject user = new JsonObject();
		user.addProperty("id", "42");
		d.add("user", user);
		conn.dispatch("READY", seq, d);
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.READY));
		return conn;
	}

	static JsonObject message(String content) {
		JsonObject d = new JsonObject();
		d.addProperty("content", content);
		return d;
	}

	@Test
	void identifiesThenDeliversMessages() throws Exception {
		FakeGateway.Conn conn = gateway.next(WAIT);
		assertNotNull(conn);
		assertTrue(conn.path.contains("v=10") && conn.path.contains("encoding=json"), conn.path);
		conn.hello(45_000);
		JsonObject identify = conn.expect(2, WAIT);
		assertNotNull(identify);
		assertEquals(TOKEN, identify.getAsJsonObject("d").get("token").getAsString());
		assertEquals(GatewayClient.CHAT_INTENTS, identify.getAsJsonObject("d").get("intents").getAsInt());
		assertEquals(33281, GatewayClient.CHAT_INTENTS);

		JsonObject d = new JsonObject();
		d.addProperty("session_id", "sess-1");
		JsonObject user = new JsonObject();
		user.addProperty("id", "42");
		d.add("user", user);
		conn.dispatch("READY", 1, d);
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.READY));
		assertEquals("42", client.botUserId());

		conn.dispatch("MESSAGE_CREATE", 2, message("hello from discord"));
		assertTrue(waitFor(() -> messages.contains("hello from discord")));
	}

	@Test
	void heartbeatCarriesSequenceAndAnswersServerRequests() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 7);
		conn.received.clear();
		conn.op(1, null); // the server may ask for a heartbeat at any time
		JsonObject beat = conn.expect(1, WAIT);
		assertNotNull(beat);
		assertEquals(7, beat.get("d").getAsInt());
	}

	@Test
	void zombieConnectionIsDroppedAndResumed() throws Exception {
		FakeGateway.Conn conn = ready(150, 5);
		// Never acknowledge: after the second heartbeat interval the client must give up on this socket.
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second, "reconnected after missing acknowledgements");
		second.hello(45_000);
		JsonObject resume = second.expect(6, WAIT);
		assertNotNull(resume, "resumed instead of identifying");
		assertEquals("sess-1", resume.getAsJsonObject("d").get("session_id").getAsString());
		assertEquals(5, resume.getAsJsonObject("d").get("seq").getAsInt());
		assertTrue(waitFor(() -> conn.socket.isClosed() || conn.closeCode != -1));
	}

	@Test
	void acknowledgedHeartbeatsKeepTheConnection() throws Exception {
		FakeGateway.Conn conn = ready(100, 1);
		for (int i = 0; i < 4; i++) {
			assertNotNull(conn.expect(1, WAIT), "heartbeat " + i);
			conn.op(11, null);
		}
		assertNull(gateway.next(50), "no reconnect while acknowledged");
		assertEquals(GatewayClient.State.READY, client.state());
	}

	@Test
	void reconnectRequestResumesAtTheResumeUrl() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 3);
		conn.op(7, null);
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second);
		assertTrue(second.path.contains("v=10"), second.path);
		second.hello(45_000);
		JsonObject resume = second.expect(6, WAIT);
		assertNotNull(resume);
		assertEquals(3, resume.getAsJsonObject("d").get("seq").getAsInt());
		second.dispatch("RESUMED", 4, new JsonObject());
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.READY));
		second.dispatch("MESSAGE_CREATE", 5, message("after resume"));
		assertTrue(waitFor(() -> messages.contains("after resume")));
	}

	@Test
	void abruptDisconnectResumes() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 9);
		conn.abort();
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second, "reconnected after the connection dropped");
		second.hello(45_000);
		JsonObject resume = second.expect(6, WAIT);
		assertNotNull(resume);
		assertEquals(9, resume.getAsJsonObject("d").get("seq").getAsInt());
	}

	@Test
	void invalidSessionStartsOver() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 2);
		conn.op(9, new JsonPrimitive(false));
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second);
		second.hello(45_000);
		assertNotNull(second.expect(2, WAIT), "identifies again: the session cannot be resumed");
	}

	@Test
	void sessionTimeoutCloseStartsOver() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 2);
		conn.close(4009);
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second);
		second.hello(45_000);
		assertNotNull(second.expect(2, WAIT), "4009 cannot be resumed");
	}

	@Test
	void invalidTokenStopsForGood() throws Exception {
		FakeGateway.Conn conn = gateway.next(WAIT);
		assertNotNull(conn);
		conn.hello(45_000);
		assertNotNull(conn.expect(2, WAIT));
		conn.close(4004);
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.FAILED));
		assertTrue(client.lastError().contains("token is invalid"), client.lastError());
		assertFalse(client.lastError().contains(TOKEN));
		assertNull(gateway.next(300), "no retry after a fatal close");
	}

	@Test
	void missingIntentIsExplained() throws Exception {
		FakeGateway.Conn conn = gateway.next(WAIT);
		assertNotNull(conn);
		conn.hello(45_000);
		assertNotNull(conn.expect(2, WAIT));
		conn.close(4014);
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.FAILED));
		assertTrue(client.lastError().contains("Message Content intent"), client.lastError());
	}

	@Test
	void malformedPayloadIsIgnored() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 1);
		JsonObject bad = new JsonObject();
		bad.addProperty("op", 10); // hello without its data
		conn.send(bad);
		conn.dispatch("MESSAGE_CREATE", 2, message("still alive"));
		assertTrue(waitFor(() -> messages.contains("still alive")));
		assertEquals(GatewayClient.State.READY, client.state());
	}

	@Test
	void closeSendsNormalClosure() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 1);
		client.close();
		assertTrue(waitFor(() -> conn.closeCode == 1000), "close code " + conn.closeCode);
		assertEquals(GatewayClient.State.STOPPED, client.state());
		assertNull(gateway.next(200), "no reconnect after close");
	}

	@Test
	void stalledHandshakeIsDroppedAndRetried() throws Exception {
		restart(10, 300);
		FakeGateway.Conn silent = gateway.next(WAIT);
		assertNotNull(silent, "connected");
		// The server accepted the upgrade but never says HELLO.
		FakeGateway.Conn second = gateway.next(WAIT);
		assertNotNull(second, "reconnected after no HELLO");
		assertTrue(client.lastError().contains("HELLO"), client.lastError());
		assertTrue(waitFor(() -> silent.closeCode == 4000), "the stalled connection was closed: " + silent.closeCode);
		second.hello(45_000);
		assertNotNull(second.expect(2, WAIT), "identified");
		// HELLO came, but READY never does.
		FakeGateway.Conn third = gateway.next(WAIT);
		assertNotNull(third, "reconnected after no READY");
		assertTrue(client.lastError().contains("IDENTIFY"), client.lastError());
		third.hello(45_000);
		assertNotNull(third.expect(2, WAIT));
		JsonObject d = new JsonObject();
		d.addProperty("session_id", "sess-1");
		third.dispatch("READY", 1, d);
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.READY));
		assertNull(gateway.next(500), "a ready connection is kept");
	}

	@Test
	void repeatedFailedResumesStartANewSession() throws Exception {
		FakeGateway.Conn conn = ready(45_000, 5);
		conn.close(4000); // resumable
		for (int attempt = 1; attempt <= 3; attempt++) {
			FakeGateway.Conn next = gateway.next(WAIT);
			assertNotNull(next, "resume attempt " + attempt);
			next.hello(45_000);
			assertNotNull(next.expect(6, WAIT), "resumed, attempt " + attempt);
			next.close(4000); // but the resume fails
		}
		FakeGateway.Conn fresh = gateway.next(WAIT);
		assertNotNull(fresh, "connected again");
		fresh.hello(45_000);
		assertNotNull(fresh.expect(2, WAIT), "identifies after three failed resumes");
		fresh.op(1, null);
		JsonObject beat = fresh.expect(1, WAIT);
		assertNotNull(beat);
		assertTrue(beat.get("d").isJsonNull(), "the old sequence is forgotten with the session: " + beat);
	}

	@Test
	void closeDoesNotWaitForAPendingReconnect() throws Exception {
		restart(10_000, GatewayClient.HANDSHAKE_TIMEOUT_MS);
		FakeGateway.Conn conn = gateway.next(WAIT);
		assertNotNull(conn);
		conn.hello(45_000);
		assertNotNull(conn.expect(2, WAIT));
		conn.close(4000); // the next attempt is 10 s or more away
		assertTrue(waitFor(() -> client.state() == GatewayClient.State.BACKOFF));
		long start = System.nanoTime();
		client.close();
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms < 1000, "close took " + ms + " ms");
		assertEquals(GatewayClient.State.STOPPED, client.state());
	}
}
