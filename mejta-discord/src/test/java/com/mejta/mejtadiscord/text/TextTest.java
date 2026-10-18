package com.mejta.mejtadiscord.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class TextTest {
	// Made-up values, assembled at runtime so secret scanners do not mistake the source for a leak.
	private static final String WEBHOOK = "https://discord.com/api/" + "webhooks/123456789012345678/" + "abcdefghijklmnopqrstuvwxyz_ABCDEFG-123";
	private static final String TOKEN = String.join(".", "MTIzNDU2Nzg5MDEyMzQ1Njc4", "GabcDE", "abcdefghijklmnopqrstuvwxyz0123456789AB");

	// ---------------------------------------------------------------- Sanitizer

	@Test
	void mentionsAreNeutralised() {
		String out = Sanitizer.forDiscord("@everyone @here <@123456789012> <@&123456789012> <#123456789012>", 2000);
		assertFalse(out.contains("@everyone"));
		assertFalse(out.contains("@here"));
		assertFalse(out.contains("<@1"));
		assertFalse(out.contains("<@&"));
		assertFalse(out.contains("<#"));
	}

	@Test
	void markdownIsEscapedButUrlsStay() {
		String out = Sanitizer.forDiscord("**bold** [click](https://evil.example) a_b https://example.com/a-b_(c)", 2000);
		assertTrue(out.contains("\\*\\*bold\\*\\*"), out);
		assertTrue(out.contains("\\[click\\]"), out);
		assertTrue(out.contains("https://example.com/a-b"), out);
		assertEquals("\\# not a heading", Sanitizer.forDiscord("# not a heading", 100));
		assertEquals("\\-# not subtext", Sanitizer.forDiscord("-# not subtext", 100));
	}

	@Test
	void minecraftTextHasNoCodesNewlinesOrFilteredWords() {
		String out = Sanitizer.forMinecraft("§chello\nworld\u0007 BadWord", 100, Sanitizer.words("badword, other "));
		assertEquals("hello world *******", out);
		assertEquals(10, Sanitizer.forMinecraft("x".repeat(50), 10, List.of()).length());
	}

	@Test
	void minecraftTextCollapsesBlankLookingCharacters() {
		// Braille blank, Hangul fillers, ideographic and no-break spaces, tabs: one space between words, none around them.
		assertEquals("a b c d", Sanitizer.forMinecraft(" \u2800a \t\u3164\u3000 b\u115F\u1160\uFFA0c\u00A0\u2003\u2028d\r\n ", 100, List.of()));
		assertEquals("", Sanitizer.forMinecraft("\u3164\u2800 \u115F", 32, List.of()), "a name of fillers is empty");
		assertEquals("a b", Sanitizer.forMinecraft("a §l b", 100, List.of()), "removing a code does not leave two spaces");
		assertEquals("a b", Sanitizer.forMinecraft("a \u200B\u0007 b", 100, List.of()), "nor does removing an invisible character");
	}

	@Test
	void minecraftTextDropsPrivateUseUnassignedAndLoneSurrogates() {
		assertEquals("xyz", Sanitizer.forMinecraft("x\uE000y\uDBFF\u0378z\uD800", 100, List.of()));
		assertEquals("\uD83D\uDE00 ok", Sanitizer.forMinecraft("\uD83D\uDE00 ok", 100, List.of()), "a valid emoji pair stays");
		assertEquals("ab", Sanitizer.forMinecraft("a\uDB80\uDC00b", 100, List.of()), "supplementary private use");
	}

	// ---------------------------------------------------------------- Redactor

	@Test
	void secretsNeverSurviveRedaction() {
		Redactor r = new Redactor();
		assertEquals("POST <webhook> failed", r.scrub("POST " + WEBHOOK + " failed"));
		assertEquals("token <token> rejected", r.scrub("token " + TOKEN + " rejected"));
		assertEquals("Authorization: Bot <token>", r.scrub("Authorization: Bot abc.def"));
		r.setSecrets(List.of("hunter2-very-secret"));
		assertEquals("pw <secret>", r.scrub("pw hunter2-very-secret"));
		assertFalse(r.describe(new RuntimeException("wrap", new java.io.IOException(WEBHOOK))).contains("abcdefghij"));
	}

	@Test
	void everyAcceptedTokenShapeIsRedacted() {
		Redactor r = new Redactor();
		String shortest = String.join(".", "a".repeat(20), "b".repeat(4), "c".repeat(20));
		String longest = String.join(".", "a".repeat(100), "b".repeat(20), "c".repeat(200));
		for (String token : List.of(shortest, longest, TOKEN)) {
			assertTrue(WebhookUrls.isTokenShapeOrEmpty(token));
			assertEquals("x <token> y", r.scrub("x " + token + " y"), token);
		}
	}

	@Test
	void replacingSecretsForgetsTheOldOnesAndKeepsTheNew() {
		Redactor r = new Redactor();
		r.setSecrets(List.of("old-secret-value"));
		r.setSecrets(java.util.Arrays.asList("new-secret-value", null, "short", "new-secret-value-longer"));
		assertEquals("old-secret-value", r.scrub("old-secret-value"));
		assertEquals("<secret> <secret>", r.scrub("new-secret-value new-secret-value-longer"), "the longer one is replaced whole");
	}

	// ---------------------------------------------------------------- Routing

	@Test
	void routingFirstMatchWins() {
		Routing r = Routing.parse("mejtaac.enforcement=kicks; mejtaac.*=alerts; mejtautils.freeze=none; *=default");
		assertEquals(Optional.of("kicks"), r.route("mejtaac.enforcement"));
		assertEquals(Optional.of("alerts"), r.route("mejtaac.alert"));
		assertEquals(Optional.empty(), r.route("mejtautils.freeze"));
		assertEquals(Optional.of("default"), r.route("mejtautils.invsee"));
		assertTrue(r.problems().isEmpty());
		assertEquals(java.util.Set.of("kicks", "alerts", "default"), r.targets());
		Routing bad = Routing.parse("nonsense; a.b=;  =x; ok.*=default");
		assertEquals(3, bad.problems().size());
		assertEquals(Optional.of("default"), bad.route("ok.thing"));
		assertEquals(Optional.empty(), bad.route("other.thing"), "no catch-all: not sent");
	}

	// ---------------------------------------------------------------- UserRateLimiter

	@Test
	void rateLimitSlidesOverAMinute() {
		AtomicLong now = new AtomicLong(1_000_000);
		UserRateLimiter limiter = new UserRateLimiter(now::get);
		for (int i = 0; i < 3; i++) {
			assertTrue(limiter.tryAcquire("u", 3));
		}
		assertFalse(limiter.tryAcquire("u", 3));
		assertTrue(limiter.tryAcquire("other", 3), "per sender");
		now.addAndGet(60_000);
		assertTrue(limiter.tryAcquire("u", 3));
	}

	@Test
	void globalRateLimitCapsEveryoneTogether() {
		AtomicLong now = new AtomicLong(1_000_000);
		UserRateLimiter limiter = new UserRateLimiter(now::get);
		assertTrue(limiter.tryAcquire("a", 10, 3));
		assertTrue(limiter.tryAcquire("b", 10, 3));
		assertFalse(limiter.tryAcquire("b", 1, 3), "per sender");
		assertTrue(limiter.tryAcquire("c", 10, 3), "a refused message does not use up the global budget");
		assertFalse(limiter.tryAcquire("d", 10, 3), "global cap reached");
		now.addAndGet(60_000);
		assertTrue(limiter.tryAcquire("d", 10, 3));
	}

	@Test
	void rateLimiterForgetsIdleSendersAndStaysBounded() {
		AtomicLong now = new AtomicLong(1_000_000);
		UserRateLimiter limiter = new UserRateLimiter(now::get, 3);
		for (int i = 0; i < 10; i++) {
			assertTrue(limiter.tryAcquire("user" + i, 5, 100));
			now.addAndGet(10);
		}
		assertEquals(3, limiter.trackedSenders(), "the least recently active are dropped past the cap");
		assertTrue(limiter.tryAcquire("user9", 5, 100));
		now.addAndGet(60_000);
		assertTrue(limiter.tryAcquire("new", 5, 100));
		assertEquals(1, limiter.trackedSenders(), "senders with an empty window are forgotten");
	}

	// ---------------------------------------------------------------- WebhookUrls

	@Test
	void onlyDiscordWebhooksAreAccepted() {
		assertTrue(WebhookUrls.isValidOrEmpty(""));
		assertTrue(WebhookUrls.isValidOrEmpty(WEBHOOK));
		assertTrue(WebhookUrls.isValidOrEmpty(WEBHOOK.replace("discord.com", "canary.discord.com") + "?thread_id=123456789012"));
		assertFalse(WebhookUrls.isValidOrEmpty(WEBHOOK.replace("https", "http")), "not over plain HTTP");
		assertFalse(WebhookUrls.isValidOrEmpty(WEBHOOK.replace("discord.com", "discord.com.evil.example")));
		assertFalse(WebhookUrls.isValidOrEmpty("https://evil.example/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz"));
		assertTrue(WebhookUrls.isTokenShapeOrEmpty(TOKEN));
		assertFalse(WebhookUrls.isTokenShapeOrEmpty("Bot " + TOKEN));
		assertTrue(WebhookUrls.isSnowflakeOrEmpty("123456789012345678"));
		assertFalse(WebhookUrls.isSnowflakeOrEmpty("#general"));
	}

	// ---------------------------------------------------------------- Embeds

	@Test
	void embedStaysWithinDiscordLimits() {
		Map<String, String> fields = new LinkedHashMap<>();
		for (int i = 0; i < 40; i++) {
			fields.put("field" + i, "v".repeat(2000));
		}
		fields.put("empty", "");
		JsonObject payload = Embeds.payload("Mejta", "", "t".repeat(400), "d".repeat(9000), "CRITICAL", fields, "mejtaac.alert",
				Instant.parse("2026-01-01T00:00:00Z"));
		JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertTrue(embed.get("title").getAsString().length() <= Embeds.TITLE);
		assertTrue(embed.get("description").getAsString().length() <= Embeds.DESCRIPTION);
		assertEquals(0xE74C3C, embed.get("color").getAsInt());
		JsonArray list = embed.getAsJsonArray("fields");
		assertTrue(list.size() <= Embeds.FIELDS);
		int total = embed.get("title").getAsString().length() + embed.get("description").getAsString().length()
				+ embed.getAsJsonObject("footer").get("text").getAsString().length();
		for (var e : list) {
			JsonObject f = e.getAsJsonObject();
			assertTrue(f.get("value").getAsString().length() <= Embeds.FIELD_VALUE);
			assertFalse(f.get("value").getAsString().isBlank());
			total += f.get("name").getAsString().length() + f.get("value").getAsString().length();
		}
		assertTrue(total <= Embeds.TOTAL, "total " + total);
		assertEquals("2026-01-01T00:00:00Z", embed.get("timestamp").getAsString());
	}

	@Test
	void embedEscapesPlayerText() {
		JsonObject payload = Embeds.payload("Mejta", "", "x_y_z failed Speed", "reason **@everyone**", "WARNING",
				Map.of("player", "x_y_z"), "mejtaac.alert", Instant.now());
		String json = payload.toString();
		assertFalse(json.contains("@everyone"));
		JsonObject field = payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields").get(0).getAsJsonObject();
		assertEquals("x\\_y\\_z", field.get("value").getAsString());
	}

	@Test
	void webhookUsernamesDiscordWouldRejectAreAdjusted() {
		assertEquals("Dlscord bot", Embeds.webhookUsername("Discord bot"));
		assertEquals("Clyd3", Embeds.webhookUsername("clyde"));
		assertTrue(Embeds.webhookUsername("n".repeat(200)).length() <= Embeds.USERNAME);
		JsonObject text = Embeds.text("Steve", "", "hi");
		assertEquals("Steve", text.get("username").getAsString());
		assertFalse(text.has("avatar_url"));
	}

	// ---------------------------------------------------------------- ChatFormat

	@Test
	void templatesFillInOnePass() {
		assertEquals("[Discord] Alex: {user} said {message}",
				ChatFormat.fill("[Discord] {user}: {message}", Map.of("user", "Alex", "message", "{user} said {message}")));
		assertEquals("{unknown} x", ChatFormat.fill("{unknown} {message}", Map.of("message", "x")));
		assertEquals("$1 \\", ChatFormat.fill("{message}", Map.of("message", "$1 \\")));
		assertTrue(ChatFormat.isMessageTemplate("<{user}> {message}"));
		assertFalse(ChatFormat.isMessageTemplate("<{user}>"));
	}

	// ---------------------------------------------------------------- Inbound

	static JsonObject discordMessage(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void inboundReadsPeopleInTheChannel() {
		JsonObject d = discordMessage("""
				{"channel_id":"111111111111","type":0,"content":"hi <@222222222222> <:pog:333333333333> <#444444444444> <@&555555555555>",
				 "author":{"id":"666666666666","username":"alex","global_name":"Alex G"},
				 "member":{"nick":"Lexi"},
				 "mentions":[{"id":"222222222222","username":"bob"}],
				 "attachments":[{"id":"1"}]}
				""");
		Inbound in = Inbound.from(d, "111111111111", "999999999999").orElseThrow();
		assertEquals("666666666666", in.userId());
		assertEquals("Lexi", in.name());
		assertEquals("hi @bob :pog: #channel @role [1 attachment]", in.content());
	}

	@Test
	void inboundIgnoresBotsWebhooksOtherChannelsAndSystemMessages() {
		String channel = "111111111111";
		String base = "\"channel_id\":\"111111111111\",\"content\":\"x\"";
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"author\":{\"id\":\"777777777777\",\"username\":\"a\"}}"), channel, "9").isPresent());
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"author\":{\"id\":\"777777777777\",\"bot\":true}}"), channel, "9").isEmpty());
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"webhook_id\":\"1\",\"author\":{\"id\":\"777777777777\"}}"), channel, "9").isEmpty(),
				"our own chat relay must not echo");
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"author\":{\"id\":\"999999999999\"}}"), channel, "999999999999").isEmpty());
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"type\":7,\"author\":{\"id\":\"777777777777\"}}"), channel, "9").isEmpty());
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"author\":{\"id\":\"777777777777\"}}"), "222222222222", "9").isEmpty());
		assertTrue(Inbound.from(discordMessage("{" + base + ",\"author\":{\"id\":\"777777777777\"}}"), "", "9").isEmpty(), "no channel set");
		assertTrue(Inbound.from(discordMessage("{\"channel_id\":\"111111111111\",\"content\":\"  \",\"author\":{\"id\":\"777777777777\"}}"),
				channel, "9").isEmpty(), "nothing to show");
	}
}
