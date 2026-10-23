package com.mejta.mejtacore.api.economy;

import java.util.Objects;

/**
 * The outcome of a balance change made through {@link EconomyService}.
 *
 * @param status  what happened; only {@link Status#SUCCESS} changed anything
 * @param balance the account's balance after the call, in minor units (for a transfer: the payer's);
 *                {@code 0} if unknown, e.g. with {@link Status#NO_PROVIDER}
 * @param message a short explanation for failures that a command can show to players; empty on success
 * @since API version 6
 */
public record EconomyResult(Status status, long balance, String message) {
	public enum Status {
		/** The change was made and saved. */
		SUCCESS,
		/** The payer has less than the amount. */
		INSUFFICIENT_FUNDS,
		/** The receiver would go over the provider's maximum balance. */
		BALANCE_LIMIT,
		/** The amount is zero, negative, or a payer pays themselves. */
		INVALID_AMOUNT,
		/** The provider has no account for the player and cannot create one (e.g. never joined). */
		NO_ACCOUNT,
		/** No economy addon is installed, or it is not active. */
		NO_PROVIDER,
		/** The provider refused it for another reason (frozen account, disabled feature, failure). */
		FAILED
	}

	public EconomyResult {
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(message, "message");
	}

	public boolean success() {
		return status == Status.SUCCESS;
	}

	public static EconomyResult success(long balance) {
		return new EconomyResult(Status.SUCCESS, balance, "");
	}

	public static EconomyResult failure(Status status, long balance, String message) {
		if (status == Status.SUCCESS) {
			throw new IllegalArgumentException("failure() needs a failure status");
		}
		return new EconomyResult(status, balance, message);
	}
}
