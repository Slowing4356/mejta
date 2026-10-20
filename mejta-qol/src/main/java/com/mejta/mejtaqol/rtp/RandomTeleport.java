package com.mejta.mejtaqol.rtp;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Durations;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Names;

/**
 * {@code /rtp}: tries random columns around the centre, one per server tick, until one has safe ground
 * with two free blocks above it. Each try loads (or generates) that chunk on the server thread, which is
 * why the setting is marked experimental and the number of tries is limited.
 */
public final class RandomTeleport {
	/** Blocks scanned downwards from the surface of one column (enough to get below the Nether's roof). */
	static final int MAX_SCAN = 160;
	/** Cooldown key of a search; the teleport itself uses the feature's key. */
	static final String SEARCH_COOLDOWN = "rtp-search";

	private record Search(String dimension, int centerX, int centerZ, int attemptsLeft) {
	}

	private final ConfigHandle config;
	private final Teleports teleports;
	private final Map<UUID, Search> searches = new ConcurrentHashMap<>();
	private final RandomGenerator random = RandomGenerator.getDefault();

	public RandomTeleport(ConfigHandle config, Teleports teleports) {
		this.config = config;
		this.teleports = teleports;
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaQoL.ID, "rtp", (root, ctx) -> root
				.requires(Cmds.available(Features.RTP))
				.executes(c -> MejtaQoL.rtp().start(c.getSource().getPlayerOrException()) ? 1 : 0));
	}

	public boolean start(ServerPlayer player) {
		ServerLevel level = player.level();
		String dimension = Locations.dimension(level);
		if (!Names.list(config.get(QolSettings.RTP_DIMENSIONS), true).contains(dimension)) {
			player.sendSystemMessage(Msg.error("/rtp doesn't work in this dimension."));
			return false;
		}
		if (searches.containsKey(player.getUUID())) {
			player.sendSystemMessage(Msg.info("Still looking for a place for you..."));
			return false;
		}
		Optional<Component> refused = teleports.refusal(player, Features.RTP);
		if (refused.isPresent()) {
			player.sendSystemMessage(refused.get());
			return false;
		}
		// A search loads chunks, so the cooldown also counts from its start: a failed or cancelled search
		// cannot be repeated at once.
		long now = System.currentTimeMillis();
		if (!teleports.bypasses(player)) {
			long wait = teleports.cooldowns().remaining(player.getUUID(), SEARCH_COOLDOWN, config.get(QolSettings.RTP_COOLDOWN), now);
			if (wait > 0) {
				player.sendSystemMessage(Msg.error("You can use /rtp again in " + Durations.format(wait) + "."));
				return false;
			}
			teleports.cooldowns().use(player.getUUID(), SEARCH_COOLDOWN, now);
		}
		int cx = 0;
		int cz = 0;
		RtpMath.Center center = RtpMath.parseCenter(config.get(QolSettings.RTP_CENTER)).orElse(new RtpMath.Center.Spawn());
		if (center instanceof RtpMath.Center.Fixed fixed) {
			cx = fixed.x();
			cz = fixed.z();
		} else {
			BlockPos spawn = level.getServer().overworld().getRespawnData().globalPos().pos();
			cx = spawn.getX();
			cz = spawn.getZ();
		}
		searches.put(player.getUUID(), new Search(dimension, cx, cz, config.get(QolSettings.RTP_ATTEMPTS)));
		player.sendSystemMessage(Msg.info("Looking for a safe place..."));
		return true;
	}

	/** Every server tick: one try for each player who is searching. */
	public void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Search>> it = searches.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Search> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			Search s = e.getValue();
			if (player == null || !Locations.dimension(player.level()).equals(s.dimension())) {
				it.remove();
				continue;
			}
			ServerLevel level = player.level();
			RtpMath.Column column = RtpMath.randomColumn(s.centerX(), s.centerZ(), config.get(QolSettings.RTP_MIN_RADIUS),
					config.get(QolSettings.RTP_MAX_RADIUS), random);
			Optional<Location> found = tryColumn(level, column);
			if (found.isPresent()) {
				it.remove();
				Location to = found.get();
				teleports.request(player, Features.RTP, Component.literal("a random place (" + to.describe() + ")"), t -> Teleports.Result.to(to));
			} else if (s.attemptsLeft() <= 1) {
				it.remove();
				player.sendSystemMessage(Msg.error("Couldn't find a safe place this time. Try again."));
			} else {
				e.setValue(new Search(s.dimension(), s.centerX(), s.centerZ(), s.attemptsLeft() - 1));
			}
		}
	}

	private static Optional<Location> tryColumn(ServerLevel level, RtpMath.Column column) {
		BlockPos probe = new BlockPos(column.x(), level.getMinY() + 1, column.z());
		if (!level.getWorldBorder().isWithinBounds(probe)) {
			return Optional.empty();
		}
		if (water(level, column.x(), column.z())) {
			return Optional.empty(); // asked the generator: no chunk loaded for an ocean or river
		}
		level.getChunk(column.x() >> 4, column.z() >> 4);
		OptionalInt y = safeY(level, column.x(), column.z());
		if (y.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Location(Locations.dimension(level), column.x() + 0.5, y.getAsInt(), column.z() + 0.5, 0, 0));
	}

	/**
	 * Whether the world generator puts an ocean or river at sea level here. Answered from the biome noise,
	 * without loading or generating the chunk.
	 */
	static boolean water(ServerLevel level, int x, int z) {
		Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z));
		return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
	}

	/** The feet height of a safe standing spot in the column, scanning down from the surface. */
	public static OptionalInt safeY(ServerLevel level, int x, int z) {
		int top = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), level.getMaxY() - 1);
		BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
		for (int y = top; y > level.getMinY() + 1 && top - y <= MAX_SCAN; y--) {
			ground.set(x, y - 1, z);
			BlockState g = level.getBlockState(ground);
			if (y == top && !g.getFluidState().isEmpty()) {
				return OptionalInt.empty();
			}
			feet.set(x, y, z);
			head.set(x, y + 1, z);
			if (safeGround(level, ground, g) && free(level, feet) && free(level, head)) {
				return OptionalInt.of(y);
			}
		}
		return OptionalInt.empty();
	}

	private static boolean safeGround(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && !state.is(Blocks.BEDROCK) && !hazard(state)
				&& state.isFaceSturdy(level, pos, Direction.UP);
	}

	private static boolean free(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getFluidState().isEmpty() && !hazard(state) && state.getCollisionShape(level, pos).isEmpty();
	}

	private static boolean hazard(BlockState state) {
		return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
	}

	public void onLeave(UUID player) {
		searches.remove(player);
	}

	public void reset() {
		searches.clear();
	}

	public int searching() {
		return searches.size();
	}
}
