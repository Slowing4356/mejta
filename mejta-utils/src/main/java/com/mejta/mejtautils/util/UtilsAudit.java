package com.mejta.mejtautils.util;

import java.util.Map;
import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtautils.MejtaUtils;

/** Shortcuts for MejtaUtils' audit entries. Every sensitive action and every refusal goes through here. */
public final class UtilsAudit {
	private UtilsAudit() {
	}

	public static void record(String action, AuditActor actor, UUID targetId, String targetName, AuditOutcome outcome,
			Map<String, ?> details, MejtaPermission staffAudience) {
		AuditEntry.Builder b = AuditEntry.builder(MejtaUtils.ID, action).actor(actor).outcome(outcome);
		if (targetId != null) {
			b.target(targetId, targetName);
		} else if (targetName != null) {
			b.target(targetName);
		}
		details.forEach(b::detail);
		if (staffAudience != null) {
			MejtaCoreAPI.audit().record(b.build(), staffAudience);
		} else {
			MejtaCoreAPI.audit().record(b.build());
		}
	}

	public static void record(String action, CommandSourceStack source, Targets.Target target, AuditOutcome outcome, Map<String, ?> details) {
		record(action, MejtaCoreAPI.actor(source), target.id(), target.name(), outcome, details, null);
	}

	public static void record(String action, ServerPlayer actor, ServerPlayer target, AuditOutcome outcome, Map<String, ?> details) {
		record(action, MejtaCoreAPI.actor(actor), target.getUUID(), target.getGameProfile().name(), outcome, details, null);
	}
}
