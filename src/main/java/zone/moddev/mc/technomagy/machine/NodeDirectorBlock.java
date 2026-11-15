package zone.moddev.mc.technomagy.machine;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

/**
 * A machine piece that directs a mana stream (D21) through the glass director at its tip. Its
 * platform displays one of eight compass bearings. It stands on a floor, or hangs upside down when
 * placed against a ceiling.
 *
 * <p>
 * Its {@link NodeDirectorBlockEntity} holds its links and packet queue (D89). Links are made with
 * the focus's linking mode (D87) and drawn as braided beams (D91). An empty-hand click turns the
 * displayed compass bearing by 45 degrees without changing those links.
 *
 * <p>
 * It does not need support: removing the floor or ceiling it was placed against leaves it in place.
 * It starts unpowered and unlit. Successful packet transfers set {@link #POWERED}; it clears after
 * the same configured idle window used by beams (D91). Redstone does not drive this state. Powered
 * directors show their blue direction lamp and emit light level {@value #LIGHT} (D23).
 */
public class NodeDirectorBlock extends Block implements EntityBlock {
    /** Whether it hangs from a ceiling (upside down) rather than standing on a floor. */
    public static final BooleanProperty HANGING = BlockStateProperties.HANGING;

    /** Whether packets have moved recently, using the beam idle window (D23, D91). */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** The world-space bearing indicated by the lamp on the platform (D21). */
    public static final EnumProperty<Bearing> DIRECTION = EnumProperty.create("direction",
        Bearing.class);

    /** The light level emitted while powered; an unpowered director emits no block light. */
    public static final int LIGHT = 6;

    // Outlines in model pixels, matching the model's feet, platform, body and post with tip. The
    // hanging shape is the standing one flipped top to bottom.

    private static final VoxelShape STANDING = Shapes.or(
        Block.box(0, 0, 0, 3, 3, 3),
        Block.box(13, 0, 0, 16, 3, 3),
        Block.box(0, 0, 13, 3, 3, 16),
        Block.box(13, 0, 13, 16, 3, 16),
        Block.box(0, 3, 0, 16, 4, 16),
        Block.box(6, 4, 6, 10, 8, 10),
        Block.box(7, 8, 7, 9, 16, 9));
    private static final VoxelShape HANGING_SHAPE = Shapes.or(
        Block.box(0, 13, 0, 3, 16, 3),
        Block.box(13, 13, 0, 16, 16, 3),
        Block.box(0, 13, 13, 3, 16, 16),
        Block.box(13, 13, 13, 16, 16, 16),
        Block.box(0, 12, 0, 16, 13, 16),
        Block.box(6, 8, 6, 10, 12, 10),
        Block.box(7, 0, 7, 9, 8, 9));

    /**
     * Creates the node director block.
     *
     * @param properties
     *            block properties; occlusion is turned off and the light level set here
     */
    public NodeDirectorBlock(Properties properties) {
        super(properties.noOcclusion().lightLevel(state -> state.getValue(POWERED) ? LIGHT : 0));
        registerDefaultState(defaultBlockState().setValue(HANGING, false)
            .setValue(DIRECTION, Bearing.NORTH).setValue(POWERED, false));
    }

    /** The standing or hanging outline. */
    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HANGING) ? HANGING_SHAPE : STANDING;
    }

    /** Places an unpowered director with the mounting and bearing selected by the player. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HANGING, context.getClickedFace() == Direction.DOWN)
            .setValue(DIRECTION, Bearing.fromYaw(context.getRotation()));
    }

    /** Cycles the preview bearing on the server, using the prism's empty-hand convention (D23). */
    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!player.mayBuild()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            Bearing next = state.getValue(DIRECTION).clockwise();
            level.setBlockAndUpdate(pos, state.setValue(DIRECTION, next));
            level.playSound(null, pos, SoundEvents.COPPER_BULB_TURN_ON,
                SoundSource.BLOCKS, 1.0f, 1.0f);
            player.displayClientMessage(
                Component.translatable("block.technomagy.node_director.direction",
                    Component.translatable("direction.technomagy." + next.getSerializedName())),
                true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Keeps the selected world-space bearing aligned when a structure rotates. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(DIRECTION,
            Bearing.VALUES[rotation.rotate(state.getValue(DIRECTION).ordinal(),
                Bearing.VALUES.length)]);
    }

    /** Reflects cardinal and diagonal bearings without changing the mounting orientation. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(DIRECTION,
            Bearing.VALUES[mirror.mirror(state.getValue(DIRECTION).ordinal(),
                Bearing.VALUES.length)]);
    }

    /**
     * Creates this block's {@link NodeDirectorBlockEntity}.
     *
     * @param pos
     *            the position
     * @param state
     *            the block state
     * @return the new block entity
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NodeDirectorBlockEntity(pos, state);
    }

    /**
     * Ticks the block entity on the server only; clients just draw the synced beams (D92).
     *
     * @param level
     *            the level
     * @param state
     *            the block state
     * @param type
     *            the block entity type being ticked
     * @return the server ticker, or null on the client or for another type
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != MachineContent.NODE_DIRECTOR_ENTITY.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, entity) -> ((ManaNodeBlockEntity) entity)
            .serverTick((ServerLevel) tickLevel);
    }

    /**
     * When the block is broken or replaced (not merely changing state), releases what the node
     * holds and removes its links (D87).
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
        boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
            && level.getBlockEntity(pos) instanceof ManaNodeBlockEntity node) {
            node.broken(server);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Declares mounting, bearing and power. Older states acquire an unpowered default. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HANGING, DIRECTION, POWERED);
    }

    /** Eight horizontal directions in clockwise order, beginning with north (D21). */
    public enum Bearing implements StringRepresentable {
        /** Negative Z. */
        NORTH,
        /** Positive X and negative Z. */
        NORTH_EAST,
        /** Positive X. */
        EAST,
        /** Positive X and positive Z. */
        SOUTH_EAST,
        /** Positive Z. */
        SOUTH,
        /** Negative X and positive Z. */
        SOUTH_WEST,
        /** Negative X. */
        WEST,
        /** Negative X and negative Z. */
        NORTH_WEST;

        private static final Bearing[] VALUES = values();

        /** Returns the next bearing, 45 degrees clockwise. */
        public Bearing clockwise() {
            return VALUES[(ordinal() + 1) % VALUES.length];
        }

        /** Selects the nearest 45-degree sector. Minecraft yaw zero points south. */
        static Bearing fromYaw(float yaw) {
            return VALUES[Math.floorMod(Mth.floor(yaw / 45f + 0.5f) + 4, VALUES.length)];
        }

        /** The stable lowercase identifier used by block states, textures, and translations. */
        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
