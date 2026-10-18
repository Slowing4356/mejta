package com.mejta.mejtautils.staffmode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtautils.util.Hashes;
import com.mejta.mejtautils.util.ItemJson;

/**
 * A staff member's survival state, as JSON: inventory (all 41 slots, every item component), position,
 * rotation and dimension, XP, health, food, air, fire, effects, game mode and abilities. The ender
 * chest is not part of it (staff mode does not touch it, and opens it read-only).
 */
public final class StaffSnapshot {
	private StaffSnapshot() {
	}

	public static JsonObject capture(ServerPlayer p) {
		HolderLookup.Provider registries = p.level().getServer().registryAccess();
		JsonObject o = new JsonObject();
		o.addProperty("game_version", gameVersion());
		JsonObject inventory = new JsonObject();
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				inventory.add(String.valueOf(i), ItemJson.encode(registries, stack));
			}
		}
		o.add("inventory", inventory);
		o.addProperty("selected_slot", inv.getSelectedSlot());
		JsonObject pos = new JsonObject();
		pos.addProperty("dimension", p.level().dimension().identifier().toString());
		pos.addProperty("x", p.getX());
		pos.addProperty("y", p.getY());
		pos.addProperty("z", p.getZ());
		pos.addProperty("yaw", p.getYRot());
		pos.addProperty("pitch", p.getXRot());
		o.add("position", pos);
		o.addProperty("xp_level", p.experienceLevel);
		o.addProperty("xp_progress", p.experienceProgress);
		o.addProperty("xp_total", p.totalExperience);
		o.addProperty("health", p.getHealth());
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("saturation", p.getFoodData().getSaturationLevel());
		o.addProperty("air", p.getAirSupply());
		o.addProperty("fire", p.getRemainingFireTicks());
		JsonArray effects = new JsonArray();
		for (MobEffectInstance e : p.getActiveEffects()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", e.getEffect().unwrapKey().orElseThrow().identifier().toString());
			j.addProperty("duration", e.getDuration());
			j.addProperty("amplifier", e.getAmplifier());
			j.addProperty("ambient", e.isAmbient());
			j.addProperty("visible", e.isVisible());
			j.addProperty("icon", e.showIcon());
			effects.add(j);
		}
		o.add("effects", effects);
		o.addProperty("game_mode", p.gameMode.getGameModeForPlayer().getName());
		o.addProperty("may_fly", p.getAbilities().mayfly);
		o.addProperty("flying", p.getAbilities().flying);
		o.addProperty("fly_speed", p.getAbilities().getFlyingSpeed());
		o.addProperty("invulnerable", p.getAbilities().invulnerable);
		return o;
	}

	public static String hash(JsonObject snapshot) {
		return Hashes.sha256(snapshot);
	}

	/** Every item still decodes. */
	public static boolean itemsDecode(JsonObject snapshot, HolderLookup.Provider registries) {
		for (var entry : snapshot.getAsJsonObject("inventory").entrySet()) {
			if (ItemJson.decode(registries, entry.getValue()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Whether the player's current inventory equals the saved one. */
	public static boolean inventoryMatches(ServerPlayer p, JsonObject snapshot) {
		HolderLookup.Provider registries = p.level().getServer().registryAccess();
		JsonObject saved = snapshot.getAsJsonObject("inventory");
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack now = inv.getItem(i);
			JsonElement then = saved.get(String.valueOf(i));
			if (then == null ? !now.isEmpty() : !ItemJson.matches(registries, now, then)) {
				return false;
			}
		}
		return true;
	}

	public static Optional<ServerLevel> level(MinecraftServer server, JsonObject snapshot) {
		Identifier id = Identifier.tryParse(snapshot.getAsJsonObject("position").get("dimension").getAsString());
		if (id == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(server.getLevel(ResourceKey.create(Registries.DIMENSION, id)));
	}

	public static boolean sameGameVersion(JsonObject snapshot) {
		return gameVersion().equals(snapshot.get("game_version").getAsString());
	}

	/**
	 * Replaces the player's state with the snapshot. Replacing (never adding) makes it safe to repeat:
	 * restoring twice gives the same result, so a crash during restoration cannot duplicate items.
	 *
	 * @param fallback level to use if the saved dimension is gone: the top block at the saved x and z
	 */
	public static void restore(ServerPlayer p, JsonObject snapshot, ServerLevel fallback) {
		MinecraftServer server = p.level().getServer();
		HolderLookup.Provider registries = server.registryAccess();
		Inventory inv = p.getInventory();
		JsonObject saved = snapshot.getAsJsonObject("inventory");
		for (int i = 0; i < inv.getContainerSize(); i++) {
			JsonElement item = saved.get(String.valueOf(i));
			inv.setItem(i, item == null ? ItemStack.EMPTY : ItemJson.decode(registries, item).orElse(ItemStack.EMPTY));
		}
		inv.setSelectedSlot(Math.clamp(snapshot.get("selected_slot").getAsInt(), 0, 8));

		GameType mode = GameType.byName(snapshot.get("game_mode").getAsString(), GameType.SURVIVAL);
		p.setGameMode(mode);
		p.getAbilities().mayfly = snapshot.get("may_fly").getAsBoolean();
		p.getAbilities().flying = snapshot.get("flying").getAsBoolean();
		p.getAbilities().setFlyingSpeed(snapshot.get("fly_speed").getAsFloat());
		p.getAbilities().invulnerable = snapshot.get("invulnerable").getAsBoolean();
		p.onUpdateAbilities();

		p.removeAllEffects();
		List<MobEffectInstance> effects = new ArrayList<>();
		for (JsonElement e : snapshot.getAsJsonArray("effects")) {
			JsonObject j = e.getAsJsonObject();
			Identifier id = Identifier.tryParse(j.get("id").getAsString());
			Optional<MobEffect> effect = id == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.getOptional(id);
			effect.ifPresent(value -> effects.add(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(value),
					j.get("duration").getAsInt(), j.get("amplifier").getAsInt(), j.get("ambient").getAsBoolean(),
					j.get("visible").getAsBoolean(), j.get("icon").getAsBoolean())));
		}
		effects.forEach(p::addEffect);

		p.experienceLevel = snapshot.get("xp_level").getAsInt();
		p.experienceProgress = snapshot.get("xp_progress").getAsFloat();
		p.totalExperience = snapshot.get("xp_total").getAsInt();
		p.setHealth(Math.max(1.0f, snapshot.get("health").getAsFloat()));
		p.getFoodData().setFoodLevel(snapshot.get("food").getAsInt());
		p.getFoodData().setSaturation(snapshot.get("saturation").getAsFloat());
		p.setAirSupply(snapshot.get("air").getAsInt());
		p.setRemainingFireTicks(snapshot.get("fire").getAsInt());

		JsonObject pos = snapshot.getAsJsonObject("position");
		Optional<ServerLevel> level = level(server, snapshot);
		if (level.isPresent()) {
			p.teleport(new TeleportTransition(level.get(), new Vec3(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(),
					pos.get("z").getAsDouble()), Vec3.ZERO, pos.get("yaw").getAsFloat(), pos.get("pitch").getAsFloat(), TeleportTransition.DO_NOTHING));
		} else {
			int x = (int) Math.floor(pos.get("x").getAsDouble());
			int z = (int) Math.floor(pos.get("z").getAsDouble());
			Vec3 top = new Vec3(x + 0.5, fallback.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
			p.teleport(new TeleportTransition(fallback, top, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
		}
		p.inventoryMenu.sendAllDataToRemote();
	}

	/** Puts the player into staff mode's state: empty inventory with the tools, the configured game mode, flight. */
	public static void applyStaffState(ServerPlayer p, GameType mode, boolean flight) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			inv.setItem(i, ItemStack.EMPTY);
		}
		List<ItemStack> kit = StaffTools.kit();
		for (int i = 0; i < kit.size(); i++) {
			inv.setItem(i, kit.get(i));
		}
		p.removeAllEffects();
		p.setGameMode(mode);
		p.getAbilities().invulnerable = true;
		if (flight) {
			p.getAbilities().mayfly = true;
		}
		p.onUpdateAbilities();
		p.inventoryMenu.sendAllDataToRemote();
	}

	private static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}
}
