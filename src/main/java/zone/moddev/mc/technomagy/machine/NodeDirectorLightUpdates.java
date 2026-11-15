package zone.moddev.mc.technomagy.machine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Reconciles saved director activity and lighting after a chunk becomes available (D23, D91). Old
 * preview blocks had no block entity and emitted a constant seven. A loaded director now needs its
 * live block entity, a power flag derived from real transfers, and a light recheck.
 *
 * <p>
 * Chunk-load events can precede FULL status and arrive on a worker. They only inspect the supplied
 * section palettes and enqueue a chunk position. The server's post-level tick processes queued
 * positions only after {@code getChunkNow} returns the already-loaded chunk; it never requests a
 * chunk. There is no recurring world scan: an empty queue returns immediately. Unload and stop
 * events discard pending work and level references.
 */
@EventBusSubscriber(modid = "technomagy")
public final class NodeDirectorLightUpdates {
    private static final ConcurrentHashMap<ServerLevel, Set<Long>> PENDING = new ConcurrentHashMap<>();

    private NodeDirectorLightUpdates() {
    }

    /** Queues only chunks whose current palettes can contain a director, without level access. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        var director = MachineContent.NODE_DIRECTOR.get();
        for (var section : chunk.getSections()) {
            if (section.maybeHas(state -> state.is(director))) {
                PENDING.computeIfAbsent(level, unused -> ConcurrentHashMap.newKeySet())
                    .add(chunk.getPos().toLong());
                return;
            }
        }
    }

    /** Reconciles queued chunks on the server thread only after their FULL future is ready. */
    @SubscribeEvent
    public static void afterLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var pending = PENDING.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (long key : pending) {
            var pos = new ChunkPos(key);
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                pending.remove(key);
                refresh(level, chunk);
            }
        }
    }

    /** Creates missing legacy block entities and queues light checks without changing links. */
    private static void refresh(ServerLevel level, LevelChunk chunk) {
        var director = MachineContent.NODE_DIRECTOR.get();
        var sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        var light = level.getChunkSource().getLightEngine();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            if (!section.maybeHas(state -> state.is(director))) {
                continue;
            }
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(director)) {
                            var pos = new BlockPos(minX + x, minY + y, minZ + z);
                            // CHECK, the default, leaves old preview blocks without a ticker.
                            if (chunk.getBlockEntity(pos,
                                LevelChunk.EntityCreationType.IMMEDIATE) instanceof NodeDirectorBlockEntity node) {
                                node.updatePower(level, level.getGameTime());
                            }
                            light.checkBlock(pos);
                        }
                    }
                }
            }
        }
    }

    /** Cancels work for a chunk that was unloaded before its refresh. */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pending = PENDING.get(level);
            if (pending != null) {
                pending.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    /** Releases this level's queue when its world closes. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.remove(level);
        }
    }

    /** Also clears any pending references if server shutdown bypassed a level-unload callback. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.keySet().removeIf(level -> level.getServer() == event.getServer());
    }
}
