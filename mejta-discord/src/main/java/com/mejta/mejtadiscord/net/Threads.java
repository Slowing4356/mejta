package com.mejta.mejtadiscord.net;

import org.slf4j.Logger;

import com.mejta.mejtadiscord.text.Redactor;

/** MejtaDiscord's background threads: daemons whose uncaught failures are logged through the {@link Redactor}. */
final class Threads {
	private Threads() {
	}

	static Thread daemon(String name, Runnable task, Redactor redactor, Logger log) {
		Thread t = new Thread(task, name);
		t.setDaemon(true);
		// Never the raw exception: its message or stack trace can hold a webhook URL.
		t.setUncaughtExceptionHandler((thread, e) -> log.error("[MejtaDiscord] Thread {} stopped: {}", thread.getName(), redactor.describe(e)));
		return t;
	}
}
