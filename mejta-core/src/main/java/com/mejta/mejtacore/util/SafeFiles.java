package com.mejta.mejtacore.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** File helpers that never leave a half-written file behind. */
public final class SafeFiles {
	private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
	private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");

	private SafeFiles() {
	}

	/** Writes {@code content} to a temp file next to {@code target}, then moves it into place. */
	public static void writeAtomically(Path target, String content) throws IOException {
		writeAtomically(target, content, false);
	}

	/**
	 * Like {@link #writeAtomically(Path, String)}. With {@code ownerOnly}, the file (and a newly created
	 * parent folder) is made readable by the owner only before the content is written, on file systems
	 * with POSIX permissions. Elsewhere (Windows) the folder's inherited permissions apply.
	 */
	public static void writeAtomically(Path target, String content, boolean ownerOnly) throws IOException {
		write(target, content.getBytes(StandardCharsets.UTF_8), ownerOnly, false);
	}

	/**
	 * Like {@link #writeAtomically(Path, String)}, but forces the content to the storage device before
	 * the move, so the new file survives a crash or power loss once this returns.
	 */
	public static void writeDurably(Path target, String content) throws IOException {
		write(target, content.getBytes(StandardCharsets.UTF_8), false, true);
	}

	private static void write(Path target, byte[] bytes, boolean ownerOnly, boolean sync) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		if (ownerOnly && !Files.isDirectory(parent) && supportsPosix()) {
			Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(OWNER_DIR));
		} else {
			Files.createDirectories(parent);
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.deleteIfExists(temp);
		if (ownerOnly && supportsPosix()) {
			Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_FILE));
		}
		try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			if (sync) {
				channel.force(true);
			}
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		if (ownerOnly && supportsPosix()) {
			// An older file moved over keeps the temp file's permissions; this also tightens a file created by hand.
			Files.setPosixFilePermissions(target, OWNER_FILE);
		}
		if (sync) {
			// Makes the rename itself durable on Linux and macOS. Windows cannot open folders this way; NTFS
			// journals the rename instead.
			try (FileChannel dir = FileChannel.open(parent, StandardOpenOption.READ)) {
				dir.force(true);
			} catch (IOException | UnsupportedOperationException e) {
				// best effort
			}
		}
	}

	/** Renames a broken file to {@code <name>.broken-<millis>} and returns the new path. */
	public static Path backupBroken(Path file) throws IOException {
		Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
		Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
		return backup;
	}

	private static boolean supportsPosix() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}
}
