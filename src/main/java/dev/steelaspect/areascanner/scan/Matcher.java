package dev.steelaspect.areascanner.scan;

import dev.steelaspect.areascanner.config.Configs;
import dev.steelaspect.areascanner.config.CustomEntry;
import dev.steelaspect.areascanner.config.ScanLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/** Decides whether a block state matches one of the enabled groups. */
public final class Matcher {
    public static final Match UNMOVABLE = new Match(Category.UNMOVABLE, null);
    public static final Match LIQUID = new Match(Category.LIQUID, null);

    /** Blocks that are always unmovable, regardless of how the generic checks see them. */
    private static final Set<Block> UNMOVABLE_BLOCKS = new ReferenceOpenHashSet<>(new Block[]{
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.REINFORCED_DEEPSLATE,
            Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL,
            Blocks.RESPAWN_ANCHOR, Blocks.ENCHANTING_TABLE, Blocks.ENDER_CHEST, Blocks.BEACON,
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.BARRIER, Blocks.LIGHT,
            Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID, Blocks.JIGSAW,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.MOVING_PISTON, Blocks.PISTON_HEAD
    });

    private static final Map<Block, Match> CUSTOM_MATCHES = new Reference2ObjectOpenHashMap<>();


    /** The match rules, swapped as one immutable object so worker threads always see a consistent set. */
    private record Rules(boolean unmovable, boolean blockEntities, boolean liquids, boolean sourcesOnly,
                         boolean waterlogged, boolean custom, Map<Block, Match> enabledCustom) {
    }

    private static volatile Rules rules = new Rules(true, true, true, false, true, true, Map.of());
    private static String signature = "";

    private Matcher() {
    }

    /** Re-reads the settings (main thread). Returns true if what matches changed (colours don't count). */
    public static boolean refresh() {
        boolean unmovable = Configs.UNMOVABLE_ENABLED.getBooleanValue();
        boolean blockEntities = Configs.UNMOVABLE_BLOCK_ENTITIES.getBooleanValue();
        boolean liquids = Configs.LIQUIDS_ENABLED.getBooleanValue();
        boolean sourcesOnly = Configs.LIQUIDS_SOURCES_ONLY.getBooleanValue();
        boolean waterlogged = Configs.LIQUIDS_WATERLOGGED.getBooleanValue();
        boolean custom = Configs.CUSTOM_ENABLED.getBooleanValue();

        Map<Block, Match> map = new Reference2ObjectOpenHashMap<>();
        StringBuilder sig = new StringBuilder();
        sig.append(unmovable).append(blockEntities).append(liquids).append(sourcesOnly).append(waterlogged).append(custom);
        if (custom) {
            for (CustomEntry entry : ScanLists.custom()) {
                if (!entry.enabled) continue;
                Block block = entry.block();
                if (block == null || block == Blocks.AIR) continue;
                map.put(block, customMatch(block));
                sig.append('|').append(entry.blockId);
            }
        }
        rules = new Rules(unmovable, blockEntities, liquids, sourcesOnly, waterlogged, custom, map);
        String newSignature = sig.toString();
        boolean changed = !newSignature.equals(signature);
        signature = newSignature;
        return changed;
    }

    /** The shared match instance for a custom block. */
    public static synchronized Match customMatch(Block block) {
        return CUSTOM_MATCHES.computeIfAbsent(block, b -> new Match(Category.CUSTOM, b));
    }

    @Nullable
    public static Match match(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) return null;
        Rules r = rules;
        if (r.custom) {
            Match m = r.enabledCustom.get(state.getBlock());
            if (m != null) return m;
        }
        if (r.unmovable && isUnmovable(state, level, pos, r.blockEntities)) return UNMOVABLE;
        if (r.liquids && isLiquid(state, r.waterlogged, r.sourcesOnly)) return LIQUID;
        return null;
    }

    /** Position-independent check, used to skip chunk sections whose palette can't contain a match. */
    public static boolean couldMatch(BlockState state) {
        return match(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != null;
    }

    /** Mirrors the checks in PistonBaseBlock.isPushable, plus the explicit list. */
    private static boolean isUnmovable(BlockState state, BlockGetter level, BlockPos pos, boolean blockEntities) {
        Block block = state.getBlock();
        // Bedrock floors/ceilings would swamp the results; it only shows up if it's on the custom list.
        if (block == Blocks.BEDROCK) return false;
        if (UNMOVABLE_BLOCKS.contains(block)) return true;
        if (block instanceof PistonBaseBlock) {
            return state.getValue(PistonBaseBlock.EXTENDED);
        }
        if (state.getDestroySpeed(level, pos) == -1.0F) return true;
        if (state.getPistonPushReaction() == PushReaction.BLOCK) return true;
        return blockEntities && state.hasBlockEntity();
    }

    private static boolean isLiquid(BlockState state, boolean waterlogged, boolean sourcesOnly) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return false;
        if (!(state.getBlock() instanceof LiquidBlock) && !waterlogged) return false;
        return !sourcesOnly || fluid.isSource();
    }
}
