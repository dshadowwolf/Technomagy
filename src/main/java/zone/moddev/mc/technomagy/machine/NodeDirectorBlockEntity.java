package zone.moddev.mc.technomagy.machine;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import zone.moddev.mc.technomagy.mana.ManaPacket;

/**
 * A node director's state (D89): a first-in, first-out queue of up to
 * {@link MachineConfig#DIRECTOR_CAPACITY} whole packets, sent on over any number of outgoing links.
 *
 * <p>
 * A director never merges packets; only prisms merge, which answers the P2 question. Each pulse,
 * each outgoing link in turn takes the earliest queued packets its filter admits and its target
 * accepts, up to its rate. The link that goes first rotates every pulse, so links admitting the
 * same packets share them round-robin. A packet no link admits waits without blocking the packets
 * behind it; a full director refuses new packets.
 *
 * <p>
 * Not thread-safe; see {@link ManaNodeBlockEntity}.
 */
public final class NodeDirectorBlockEntity extends ManaNodeBlockEntity {
    private static final Codec<List<ManaPacket>> PACKETS = ManaPacket.CODEC.listOf();

    private final ArrayDeque<Held> queue = new ArrayDeque<>();
    private int firstLink;

    // Like ManaLink.lastMoved, this is runtime state: reloaded nodes start idle.
    private long lastActivity = Long.MIN_VALUE;

    /**
     * Creates the block entity.
     *
     * @param pos
     *            its position
     * @param state
     *            its block state
     */
    public NodeDirectorBlockEntity(BlockPos pos, BlockState state) {
        super(MachineContent.NODE_DIRECTOR_ENTITY.get(), pos, state);
    }

    /** Queues the packet if there is room; any source may feed a director. */
    @Override
    boolean accept(ManaPacket packet, BlockPos from, long now, boolean simulate) {
        if (queue.size() >= MachineConfig.DIRECTOR_CAPACITY.get()) {
            return false;
        }
        if (!simulate) {
            queue.addLast(new Held(packet, now));
            recordActivity(now);
            setChanged();
        }
        return true;
    }

    /** Directors do not transform packets. */
    @Override
    protected void process(ServerLevel level, long now) {
    }

    /** Sends queued packets round-robin over the outgoing links, as the class describes. */
    @Override
    protected void send(ServerLevel level, long now) {
        List<ManaLink> out = outgoing();
        if (out.isEmpty()) {
            updatePower(level, now);
            return;
        }
        int count = out.size();
        for (int turn = 0; turn < count; turn++) {
            ManaLink link = out.get((firstLink + turn) % count);
            int budget = link.rate();
            Iterator<Held> queued = queue.iterator();
            while (budget > 0 && queued.hasNext()) {
                Held held = queued.next();
                if (held.arrived() >= now || !link.filter().admits(held.packet())) {
                    continue;
                }
                if (deliver(level, link, held.packet(), now, true)) {
                    if (!deliver(level, link, held.packet(), now, false)) {
                        throw new IllegalStateException("Target of " + link
                            + " accepted a packet when simulated only");
                    }
                    queued.remove();
                    recordActivity(now);
                    budget--;
                }
            }
        }
        firstLink = (firstLink + 1) % count;
        updatePower(level, now);
    }

    /** Records only committed receipt/delivery, never a simulation or refused packet. */
    private void recordActivity(long now) {
        lastActivity = now;
        if (level instanceof ServerLevel server) {
            updatePower(server, now);
        }
    }

    /** Keeps the lamp and level-six light in step with D91's configurable idle grace period. */
    void updatePower(ServerLevel level, long now) {
        // A cached capability may outlive its block; never relight or resurrect a replacement.
        if (isRemoved() || !level.isLoaded(worldPosition)
            || level.getBlockEntity(worldPosition) != this) {
            return;
        }
        long idleTicks = (long) MachineConfig.IDLE_PULSES.get()
            * MachineConfig.PULSE_INTERVAL.get();
        boolean powered = lastActivity != Long.MIN_VALUE && now - lastActivity <= idleTicks;
        var state = getBlockState();
        if (state.getValue(NodeDirectorBlock.POWERED) != powered) {
            level.setBlockAndUpdate(worldPosition,
                state.setValue(NodeDirectorBlock.POWERED, powered));
        }
    }

    @Override
    protected List<ManaPacket> held() {
        return queue.stream().map(Held::packet).toList();
    }

    @Override
    protected void clearHeld() {
        queue.clear();
    }

    /** Any number of links may arrive. */
    @Override
    int maxIncoming() {
        return Integer.MAX_VALUE;
    }

    /** Any number of links may leave. */
    @Override
    int maxOutgoing() {
        return Integer.MAX_VALUE;
    }

    /** Saves the queue in order. */
    @Override
    protected void saveHeld(CompoundTag tag) {
        tag.put("queue", PACKETS.encodeStart(NbtOps.INSTANCE, held()).getOrThrow());
    }

    /**
     * Loads the queue. Loaded packets count as having arrived long ago, so they may move on the
     * first pulse.
     */
    @Override
    protected void loadHeld(CompoundTag tag) {
        lastActivity = Long.MIN_VALUE;
        queue.clear();
        for (ManaPacket packet : decodeList(PACKETS, tag.get("queue"), "queued packets")) {
            queue.addLast(new Held(packet, Long.MIN_VALUE));
        }
    }
}
