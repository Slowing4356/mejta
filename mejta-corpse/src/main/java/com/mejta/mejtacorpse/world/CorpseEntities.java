package com.mejta.mejtacorpse.world;

import java.util.Optional;
import java.util.UUID;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.TagValueInput;

import com.mejta.mejtacorpse.data.CorpseRecord;

/**
 * The two entities that show a corpse, both vanilla so players need no mod:
 * <ul>
 * <li>a <b>mannequin</b> lying down (the sleeping pose) with the owner's skin and a name tag;</li>
 * <li>an invisible <b>interaction</b> entity over the body, because a lying mannequin's own hitbox is
 *     tiny. It receives the clicks.</li>
 * </ul>
 * Both carry the tags {@code mejtacorpse} and {@code mejtacorpse.<id>}. They hold no items: if one is
 * killed or removed, the corpse is unchanged and the entities are put back.
 */
public final class CorpseEntities {
	public static final String TAG = "mejtacorpse";
	public static final String TAG_PREFIX = "mejtacorpse.";
	static final float HITBOX_WIDTH = 2.0f;
	static final float HITBOX_HEIGHT = 0.6f;

	private CorpseEntities() {
	}

	/** The corpse ID an entity belongs to, if it is a corpse entity. */
	public static Optional<String> corpseId(Entity entity) {
		if (!entity.getTags().contains(TAG)) {
			return Optional.empty();
		}
		for (String tag : entity.getTags()) {
			if (tag.startsWith(TAG_PREFIX)) {
				return Optional.of(tag.substring(TAG_PREFIX.length()));
			}
		}
		return Optional.empty();
	}

	/** The skin the player is wearing now, to store with their corpse. */
	public static CorpseRecord.Skin skinOf(ServerPlayer player) {
		for (Property p : player.getGameProfile().properties().get("textures")) {
			return new CorpseRecord.Skin(p.value(), p.signature());
		}
		return null;
	}

	public static Optional<ServerLevel> level(MinecraftServer server, CorpseRecord corpse) {
		Identifier id = Identifier.tryParse(corpse.dimension());
		return id == null ? Optional.empty() : Optional.ofNullable(server.getLevel(ResourceKey.create(Registries.DIMENSION, id)));
	}

	/** Whether the corpse would fall forever: below the world, or with only air under it down to the bottom. */
	public static boolean overVoid(ServerLevel level, double x, double y, double z) {
		if (y < level.getMinY()) {
			return true;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int by = (int) Math.floor(y); by >= level.getMinY(); by--) {
			pos.set(Math.floor(x), by, Math.floor(z));
			if (!level.getBlockState(pos).isAir()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Spawns both entities (loading the chunk if needed) and returns the corpse with their UUIDs, or empty
	 * if the dimension is gone or the game refused to create them.
	 */
	public static Optional<CorpseRecord> spawn(MinecraftServer server, CorpseRecord corpse, boolean playerSkin, boolean showName) {
		Optional<ServerLevel> found = level(server, corpse);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		ServerLevel level = found.get();
		level.getChunk(BlockPos.containing(corpse.x(), corpse.y(), corpse.z()));
		boolean noGravity = corpse.remote() || overVoid(level, corpse.x(), corpse.y(), corpse.z());
		double y = Math.max(corpse.y(), level.getMinY() + 1);

		CompoundTag body = new CompoundTag();
		body.putString("id", "minecraft:mannequin");
		body.put("profile", ResolvableProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile(corpse, playerSkin)).getOrThrow());
		body.putString("pose", "sleeping");
		body.putBoolean("immovable", true);
		body.putBoolean("hide_description", true);
		Entity mannequin = load(level, body);

		CompoundTag box = new CompoundTag();
		box.putString("id", "minecraft:interaction");
		box.putFloat("width", HITBOX_WIDTH);
		box.putFloat("height", HITBOX_HEIGHT);
		box.putBoolean("response", true);
		Entity hitbox = load(level, box);
		if (mannequin == null || hitbox == null) {
			return Optional.empty();
		}

		mannequin.setPos(corpse.x(), y, corpse.z());
		mannequin.setYRot(corpse.yaw());
		mannequin.setYHeadRot(corpse.yaw());
		mannequin.setYBodyRot(corpse.yaw());
		mannequin.setNoGravity(noGravity);
		mannequin.setInvulnerable(true);
		mannequin.setSilent(true);
		if (showName) {
			mannequin.setCustomName(Component.literal(corpse.ownerName() + "'s corpse").withStyle(ChatFormatting.GRAY));
			mannequin.setCustomNameVisible(true);
		}
		hitbox.setPos(corpse.x(), y, corpse.z());
		hitbox.setInvulnerable(true);
		for (Entity e : new Entity[] {mannequin, hitbox}) {
			e.addTag(TAG);
			e.addTag(TAG_PREFIX + corpse.id());
		}
		if (!level.addFreshEntity(mannequin)) {
			return Optional.empty();
		}
		if (!level.addFreshEntity(hitbox)) {
			mannequin.discard();
			return Optional.empty();
		}
		return Optional.of(corpse.withEntities(mannequin.getUUID(), hitbox.getUUID()));
	}

	private static Entity load(ServerLevel level, CompoundTag tag) {
		return EntityType.loadEntityRecursive(TagValueInput.create(new ProblemReporter.Collector(), level.registryAccess(), tag), level,
				EntitySpawnReason.COMMAND, e -> e);
	}

	private static ResolvableProfile profile(CorpseRecord corpse, boolean playerSkin) {
		CorpseRecord.Skin skin = playerSkin ? corpse.skin() : null;
		PropertyMap properties = new PropertyMap(skin == null ? ImmutableMultimap.of()
				: ImmutableMultimap.of("textures", new Property("textures", skin.value(), skin.signature())));
		String name = corpse.ownerName().length() <= 16 ? corpse.ownerName() : corpse.ownerName().substring(0, 16);
		return ResolvableProfile.createResolved(new GameProfile(playerSkin ? corpse.owner() : new UUID(0, 0), name, properties));
	}

	/** The corpse's entities, where loaded. */
	public static Optional<Entity> find(MinecraftServer server, CorpseRecord corpse, UUID entity) {
		if (entity == null) {
			return Optional.empty();
		}
		return level(server, corpse).map(level -> level.getEntity(entity));
	}
}
