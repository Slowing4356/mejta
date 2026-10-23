package com.mejta.mejtamarket.price;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;

/**
 * {@code config/mejta/mejtamarket-prices.json}: the price list as a file admins can edit. A missing file
 * is written with the defaults; a broken one is renamed {@code .broken-<millis>} and replaced.
 */
public final class PriceFile {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path path;
	private final Logger log;

	public PriceFile(Path path, Logger log) {
		this.path = path;
		this.log = log;
	}

	public Path path() {
		return path;
	}

	public PriceList load() {
		if (!Files.exists(path)) {
			PriceList defaults = PriceList.withDefaults();
			save(defaults);
			return defaults;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			List<String> problems = new ArrayList<>();
			PriceList list = PriceList.fromJson(root, problems);
			if (!problems.isEmpty()) {
				log.warn("Skipped invalid entries in {}: {} (prices must be positive numbers, items namespace:id)", path.getFileName(), problems);
			}
			return list;
		} catch (IOException | RuntimeException e) {
			Path broken = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
			log.error("Could not read {}; renamed it to {} and wrote the default prices", path.getFileName(), broken.getFileName(), e);
			try {
				Files.move(path, broken);
			} catch (IOException moveFailed) {
				log.error("Could not rename {}", path, moveFailed);
				return PriceList.withDefaults();
			}
			PriceList defaults = PriceList.withDefaults();
			save(defaults);
			return defaults;
		}
	}

	/** Writes the list (atomically, so a crash never leaves half a file). @return whether it was written */
	public boolean save(PriceList list) {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(list.toJson()) + "\n", StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			log.error("Could not write {}", path, e);
			return false;
		}
	}
}
