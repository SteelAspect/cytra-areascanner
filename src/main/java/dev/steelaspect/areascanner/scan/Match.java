package dev.steelaspect.areascanner.scan;

import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * What a position matched. Instances are shared (one per group, one per custom block), so two
 * positions with the same colour hold the same instance and can be compared with ==.
 */
public final class Match {
    public final Category category;
    /** The custom-list block for {@link Category#CUSTOM}, otherwise null. */
    @Nullable
    public final Block block;

    Match(Category category, @Nullable Block block) {
        this.category = category;
        this.block = block;
    }
}
