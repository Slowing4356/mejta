package com.mejta.mejtaac.check.protocol;

/**
 * The raw values of one serverbound movement packet, before vanilla clamps or wraps them.
 * Position fields are meaningless when {@code hasPos} is false, rotation fields when {@code hasRot} is false.
 */
public record MoveData(boolean hasPos, double x, double y, double z, boolean hasRot, float yRot, float xRot) {
	public static MoveData position(double x, double y, double z) {
		return new MoveData(true, x, y, z, false, 0, 0);
	}

	public static MoveData rotation(float yRot, float xRot) {
		return new MoveData(false, 0, 0, 0, true, yRot, xRot);
	}

	public static MoveData both(double x, double y, double z, float yRot, float xRot) {
		return new MoveData(true, x, y, z, true, yRot, xRot);
	}
}
