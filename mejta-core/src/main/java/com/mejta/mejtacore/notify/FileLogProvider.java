package com.mejta.mejtacore.notify;

import java.util.Map;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.storage.RecordLogs;

/**
 * Built-in provider: writes every notification of every addon (alerts, kicks, config changes...) to
 * {@code mejta-data/mejtacore/notifications.jsonl}, one JSON object per line, with rotation. This is the
 * persistent log when no external provider such as MejtaDiscord is installed, and an archive when one
 * is. The records contain exactly what external providers would receive.
 */
public final class FileLogProvider implements NotificationProvider {
	public static final String ID = "file";
	public static final String LOG_NAME = "notifications";

	private volatile RecordLog log;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean accepts(Notification notification) {
		return MejtaCore.coreConfig().get(CoreSettings.NOTIFICATIONS_FILE_LOG);
	}

	@Override
	public void deliver(Notification n) {
		JsonObject record = new JsonObject();
		record.addProperty("type", "notification");
		record.addProperty("ts", n.timestamp().toString());
		record.addProperty("source", n.source());
		record.addProperty("channel", n.channel());
		record.addProperty("severity", n.severity().name());
		record.addProperty("title", n.title());
		record.addProperty("message", n.message());
		JsonObject fields = new JsonObject();
		for (Map.Entry<String, String> field : n.fields().entrySet()) {
			fields.addProperty(field.getKey(), field.getValue());
		}
		record.add("fields", fields);
		log().append(record);
	}

	private RecordLog log() {
		RecordLog current = log;
		if (current == null) {
			synchronized (this) {
				if (log == null) {
					log = RecordLogs.open(MejtaCore.MOD_ID, LOG_NAME, RecordLogOptions.DEFAULT);
				}
				current = log;
			}
		}
		return current;
	}
}
