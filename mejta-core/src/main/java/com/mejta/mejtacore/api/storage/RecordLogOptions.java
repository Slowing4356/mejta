package com.mejta.mejtacore.api.storage;

/**
 * Size and retention limits of a {@link RecordLog}. Disk usage is bounded by roughly
 * {@code maxFileBytes * maxFiles}.
 *
 * @param maxFileBytes  rotate the current file when it would exceed this size (64 KiB to 256 MiB)
 * @param maxFiles      files kept, including the current one (1 to 100); the oldest is deleted
 * @param retentionDays delete rotated files older than this many days; {@code 0} keeps them until
 *                      {@code maxFiles} pushes them out
 */
public record RecordLogOptions(long maxFileBytes, int maxFiles, int retentionDays) {
	public static final RecordLogOptions DEFAULT = new RecordLogOptions(5L * 1024 * 1024, 5, 30);

	public RecordLogOptions {
		if (maxFileBytes < 64 * 1024 || maxFileBytes > 256L * 1024 * 1024) {
			throw new IllegalArgumentException("maxFileBytes must be between 64 KiB and 256 MiB");
		}
		if (maxFiles < 1 || maxFiles > 100) {
			throw new IllegalArgumentException("maxFiles must be between 1 and 100");
		}
		if (retentionDays < 0) {
			throw new IllegalArgumentException("retentionDays must be >= 0");
		}
	}
}
