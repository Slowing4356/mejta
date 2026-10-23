package com.mejta.mejtacrates;

import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.KeySerial;
import com.mejta.mejtacrates.util.ItemJson;

/**
 * Physical keys: items (a tripwire hook unless the crate names another) that carry their crate and the
 * serial of their batch in custom data. Keys of one batch stack; keys of different batches do not.
 */
public final class KeyItems {
	private static final String CRATE_TAG = "mejtacrates_key";
	private static final String SERIAL_TAG = "mejtacrates_serial";

	/** What a key item says it is. Only the serial's record decides whether it still opens anything. */
	public record KeyInfo(String crate, String serial) {
	}

	private KeyItems() {
	}

	/** One key of {@code crate} from batch {@code serial}; set the count afterwards. */
	public static ItemStack create(HolderLookup.Provider registries, Crate crate, String serial) {
		ItemStack base = crate.keyItem().flatMap(json -> ItemJson.decode(registries, json)).filter(s -> !s.isEmpty())
				.orElseGet(() -> new ItemStack(Items.TRIPWIRE_HOOK));
		ItemStack key = MenuIcons.of(base.copyWithCount(1), Component.literal(crate.name() + " Key").withStyle(CrateTexts.color(crate)),
				List.of(MenuIcons.line("Opens one " + crate.name() + "."),
						MenuIcons.line("Right-click to open it" + (MejtaCrates.config().get(CrateSettings.PHYSICAL_OPEN_ANYWHERE) ? "." : " at the crate.")),
						Component.literal("Crate key").withStyle(ChatFormatting.DARK_GRAY)));
		CompoundTag tag = new CompoundTag();
		tag.putString(CRATE_TAG, crate.id());
		tag.putString(SERIAL_TAG, serial);
		key.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		key.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return key;
	}

	/** The crate and serial a stack claims to be a key of, if it is a crate key at all. */
	public static Optional<KeyInfo> read(ItemStack stack) {
		if (stack.isEmpty()) {
			return Optional.empty();
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return Optional.empty();
		}
		CompoundTag tag = data.copyTag();
		String crate = tag.getStringOr(CRATE_TAG, "");
		if (crate.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new KeyInfo(crate, tag.getStringOr(SERIAL_TAG, "")));
	}

	public static boolean isKey(ItemStack stack) {
		return read(stack).isPresent();
	}

	public static boolean validSerialFormat(KeyInfo info) {
		return Crate.isValidId(info.crate()) && KeySerial.isValidSerial(info.serial());
	}
}
