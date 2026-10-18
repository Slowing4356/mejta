package com.mejta.mejtacore.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** File helpers that never leave a half-written file behind. */
public final class SafeFiles {
	private SafeFiles() {
	}

	/** Writes {@code content} to a temp file next to {@code target}, then moves it into place. */
	public static void writeAtomically(Path target, String content) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temp, content, StandardCharsets.UTF_8);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Renames a broken file to {@code <name>.broken-<millis>} and returns the new path. */
	public static Path backupBroken(Path file) throws IOException {
		Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
		Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
		return backup;
	}
}
