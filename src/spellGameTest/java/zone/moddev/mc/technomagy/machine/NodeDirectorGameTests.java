package zone.moddev.mc.technomagy.machine;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;

/** Actual block interaction, placement, transform, and saved-state checks for D21 and D23. */
@GameTestHolder("technomagy")
@PrefixGameTestTemplate(false)
public final class NodeDirectorGameTests {
    private NodeDirectorGameTests() {
    }

    /** Both mountings support all bearings, a full click cycle, and legacy saved block states. */
    @GameTest(template = "empty")
    public static void bearingsAndSavedStates(GameTestHelper helper) {
        var block = MachineContent.NODE_DIRECTOR.get();
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        helper.assertTrue(block.getStateDefinition().getPossibleStates().size() == 32,
            "Expected eight bearings, two mountings and two power states");
        for (boolean hanging : new boolean[]{false, true}) {
            for (boolean powered : new boolean[]{false, true}) {
                var state = block.defaultBlockState().setValue(NodeDirectorBlock.HANGING, hanging)
                    .setValue(NodeDirectorBlock.POWERED, powered);
                helper.setBlock(relative, state);
                for (var bearing : NodeDirectorBlock.Bearing.values()) {
                    var before = level.getBlockState(absolute);
                    helper.assertTrue(before.getValue(NodeDirectorBlock.DIRECTION) == bearing,
                        "Unexpected bearing before empty-hand use");
                    var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute,
                        false);
                    helper.assertTrue(before.useWithoutItem(level, player, hit).consumesAction(),
                        "Director did not handle empty-hand use");
                    var after = level.getBlockState(absolute);
                    helper.assertTrue(
                        after.getValue(NodeDirectorBlock.DIRECTION) == bearing.clockwise()
                            && after.getValue(NodeDirectorBlock.HANGING) == hanging
                            && after.getValue(NodeDirectorBlock.POWERED) == powered,
                        "Click must change only the compass bearing");
                    var saved = NbtUtils.writeBlockState(after);
                    var loaded = NbtUtils.readBlockState(
                        level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), saved);
                    helper.assertTrue(loaded == after, "Saved direction failed to round trip");
                }
            }
            var old = new CompoundTag();
            old.putString("Name", "technomagy:node_director");
            var properties = new CompoundTag();
            properties.putString("hanging", Boolean.toString(hanging));
            old.put("Properties", properties);
            var loaded = NbtUtils.readBlockState(
                level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), old);
            helper.assertTrue(
                loaded.getValue(NodeDirectorBlock.DIRECTION) == NodeDirectorBlock.Bearing.NORTH
                    && loaded.getValue(NodeDirectorBlock.HANGING) == hanging
                    && !loaded.getValue(NodeDirectorBlock.POWERED),
                "Old states must retain mounting and acquire the default bearing");
            // The earlier direction preview saved a bearing but had no power flag.
            properties.putString("direction", "south_west");
            loaded = NbtUtils.readBlockState(
                level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(), old);
            helper.assertTrue(
                loaded.getValue(NodeDirectorBlock.DIRECTION) == NodeDirectorBlock.Bearing.SOUTH_WEST
                    && loaded.getValue(NodeDirectorBlock.HANGING) == hanging
                    && !loaded.getValue(NodeDirectorBlock.POWERED),
                "Existing preview states must retain their bearing and become unpowered");
        }
        helper.succeed();
    }

    /** A preview-era block without saved entity data acquires a ticker and clears stale power. */
    @GameTest(template = "empty")
    public static void previewChunkCreatesMissingEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var state = MachineContent.NODE_DIRECTOR.get().defaultBlockState()
            .setValue(NodeDirectorBlock.HANGING, true)
            .setValue(NodeDirectorBlock.DIRECTION, NodeDirectorBlock.Bearing.SOUTH_WEST)
            .setValue(NodeDirectorBlock.POWERED, true);
        helper.setBlock(relative, state);
        level.removeBlockEntity(absolute);
        var chunk = level.getChunkAt(absolute);
        helper.assertTrue(
            chunk.getBlockEntity(absolute, LevelChunk.EntityCreationType.CHECK) == null,
            "The legacy fixture must have no saved block entity");
        NodeDirectorLightUpdates.onChunkLoad(new ChunkEvent.Load(chunk, false));
        helper.runAfterDelay(2, () -> {
            var entity = chunk.getBlockEntity(absolute, LevelChunk.EntityCreationType.CHECK);
            helper.assertTrue(entity instanceof NodeDirectorBlockEntity,
                "Chunk migration must create and register the missing director entity");
            helper.assertTrue(
                level.getBlockState(absolute) == state.setValue(NodeDirectorBlock.POWERED, false),
                "Chunk migration must clear stale power while retaining the mounting and bearing");
            helper.assertTrue(((NodeDirectorBlockEntity) entity).handler()
                .receivePacket(ManaPacket.leaf(ManaType.FIRE, 1), false),
                "The migrated director must accept mana");
            helper.runAfterDelay((MachineConfig.IDLE_PULSES.get() + 2)
                * MachineConfig.PULSE_INTERVAL.get(), () -> {
                    helper.assertTrue(
                        !level.getBlockState(absolute).getValue(NodeDirectorBlock.POWERED),
                        "The migrated director's registered ticker must clear idle power");
                    helper.succeed();
                });
        });
    }

    /** Placement sectors, negative yaw, rotation, mirrors, and adventure restrictions agree. */
    @GameTest(template = "empty")
    public static void placementAndTransforms(GameTestHelper helper) {
        var block = MachineContent.NODE_DIRECTOR.get();
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var bearings = NodeDirectorBlock.Bearing.values();
        for (var bearing : bearings) {
            for (int turn : new int[]{-360, 0, 360}) {
                player.setYRot(bearing.ordinal() * 45f - 180f + turn);
                for (var face : new Direction[]{Direction.UP, Direction.DOWN}) {
                    var hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                    var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                        new ItemStack(block), hit);
                    var placed = block.getStateForPlacement(context);
                    helper.assertTrue(placed != null
                        && placed.getValue(NodeDirectorBlock.DIRECTION) == bearing
                        && placed.getValue(NodeDirectorBlock.HANGING) == (face == Direction.DOWN)
                        && !placed.getValue(NodeDirectorBlock.POWERED),
                        "Placement must use the world-space yaw and clicked face");
                }
            }
            var state = block.defaultBlockState().setValue(NodeDirectorBlock.DIRECTION, bearing)
                .setValue(NodeDirectorBlock.POWERED, true);
            for (var rotation : Rotation.values()) {
                var expected = bearings[rotation.rotate(bearing.ordinal(), 8)];
                helper.assertTrue(
                    state.rotate(rotation).getValue(NodeDirectorBlock.DIRECTION) == expected
                        && state.rotate(rotation).getValue(NodeDirectorBlock.POWERED),
                    "Rotation must handle diagonal bearings");
            }
            helper.assertTrue(state.mirror(Mirror.LEFT_RIGHT).getValue(
                NodeDirectorBlock.DIRECTION) == bearings[Math.floorMod(4 - bearing.ordinal(), 8)],
                "North/south mirror failed");
            helper.assertTrue(
                state.mirror(Mirror.FRONT_BACK).getValue(
                    NodeDirectorBlock.DIRECTION) == bearings[Math.floorMod(-bearing.ordinal(), 8)],
                "East/west mirror failed");
        }
        player.getAbilities().mayBuild = false;
        var state = block.defaultBlockState();
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        helper.assertTrue(!state.useWithoutItem(helper.getLevel(), player, hit).consumesAction(),
            "Adventure interaction must not turn the director");
        helper.succeed();
    }

    /** Unpowered placement is dark, and the real light engine propagates and removes level six. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void poweredLightUpdates(GameTestHelper helper) {
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        var off = MachineContent.NODE_DIRECTOR.get().defaultBlockState();
        // GameTest status beacons and nearby tests can light an otherwise empty
        // template. A closed stone shell isolates this emitter from that light.
        for (var wall : BlockPos.betweenClosed(0, 0, 0, 2, 2, 2)) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(relative, off);
        helper.assertTrue(!off.getValue(NodeDirectorBlock.POWERED)
            && off.getLightEmission(level, absolute) == 0, "A standalone director must be unlit");
        var node = (NodeDirectorBlockEntity) level.getBlockEntity(absolute);
        helper.assertTrue(node.handler().receivePacket(ManaPacket.leaf(ManaType.FIRE, 1), false),
            "Director must accept a real packet");
        var on = level.getBlockState(absolute);
        helper.assertTrue(on.getLightEmission(level, absolute) == 6,
            "Powered directors must emit light level six");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(
                level.getBrightness(LightLayer.BLOCK, absolute) == 6,
                "Powered light has not propagated; observed "
                    + level.getBrightness(LightLayer.BLOCK, absolute)))
            .thenWaitUntil(() -> helper.assertTrue(
                !level.getBlockState(absolute).getValue(NodeDirectorBlock.POWERED),
                "An idle director must turn off without an operator changing its state"))
            .thenWaitUntil(() -> helper.assertTrue(
                level.getBrightness(LightLayer.BLOCK, absolute) == 0,
                "Unpowered light has not cleared; observed "
                    + level.getBrightness(LightLayer.BLOCK, absolute)))
            .thenSucceed();
    }

    /** Simulations and refused packets cannot activate or prolong a stalled director's light. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void simulationsAndRefusalsStayDark(GameTestHelper helper) {
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var pos = helper.absolutePos(relative);
        helper.setBlock(relative, MachineContent.NODE_DIRECTOR.get());
        var node = (NodeDirectorBlockEntity) level.getBlockEntity(pos);
        var packet = ManaPacket.leaf(ManaType.FIRE, 1);
        helper.assertTrue(node.handler().receivePacket(packet, true), "Simulation must fit");
        helper.assertTrue(node.held().isEmpty()
            && !level.getBlockState(pos).getValue(NodeDirectorBlock.POWERED),
            "A simulated receipt must not queue or light anything");
        for (int i = 0; i < MachineConfig.DIRECTOR_CAPACITY.get(); i++) {
            helper.assertTrue(node.handler().receivePacket(packet, false), "Packet must fit");
        }
        int pulse = MachineConfig.PULSE_INTERVAL.get();
        helper.runAfterDelay((MachineConfig.IDLE_PULSES.get() + 2) * pulse + 1, () -> {
            helper.assertTrue(!level.getBlockState(pos).getValue(NodeDirectorBlock.POWERED),
                "Stored packets alone must not keep an idle director lit");
            helper.assertTrue(!node.handler().receivePacket(packet, false),
                "A full director must refuse a packet");
            helper.runAfterDelay(pulse + 1, () -> {
                helper.assertTrue(!level.getBlockState(pos).getValue(NodeDirectorBlock.POWERED),
                    "Refusal must not prolong the light");
                helper.succeed();
            });
        });
    }

    /** Rotating the artwork and updating power retain the live upstream queue and upgraded link. */
    @GameTest(template = "empty")
    public static void bearingKeepsLinkedBlockEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        var relative = new BlockPos(1, 1, 1);
        var pos = helper.absolutePos(relative);
        var target = helper.absolutePos(new BlockPos(5, 1, 1));
        helper.setBlock(relative, MachineContent.NODE_DIRECTOR.get());
        helper.setBlock(new BlockPos(5, 1, 1), MachineContent.NODE_DIRECTOR.get());
        helper.assertTrue(ManaLinks.toggle(level, pos, target,
            Optional.of(LinkFilter.pure(ManaType.FIRE))) == ManaLinks.Result.CREATED,
            "Directors must link using upstream's rules");
        var node = (NodeDirectorBlockEntity) level.getBlockEntity(pos);
        var link = node.link(pos, target).orElseThrow();
        link.setTier(2);
        var packet = ManaPacket.leaf(ManaType.FIRE, 1);
        helper.assertTrue(node.handler().receivePacket(packet, false), "Packet must fit");
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().mayBuild = true;
        var before = level.getBlockState(pos);
        before.useWithoutItem(level, player,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        helper.assertTrue(level.getBlockEntity(pos) == node
            && node.held().size() == 1 && node.held().getFirst() == packet
            && node.link(pos, target).orElseThrow() == link && link.tier() == 2
            && level.getBlockState(pos).getValue(NodeDirectorBlock.POWERED)
            && level.getBlockState(pos).getValue(NodeDirectorBlock.DIRECTION) == before
                .getValue(NodeDirectorBlock.DIRECTION).clockwise(),
            "Visual state changes must retain the block entity, packet and fitted link");
        var saved = node.saveWithFullMetadata(level.registryAccess());
        node.loadWithComponents(saved, level.registryAccess());
        level.setBlockAndUpdate(pos,
            level.getBlockState(pos).setValue(NodeDirectorBlock.POWERED, false));
        helper.runAfterDelay(MachineConfig.PULSE_INTERVAL.get() + 1, () -> {
            helper.assertTrue(node.held().isEmpty()
                && level.getBlockState(pos).getValue(NodeDirectorBlock.POWERED)
                && level.getBlockState(target).getValue(NodeDirectorBlock.POWERED)
                && ((NodeDirectorBlockEntity) level.getBlockEntity(target)).held().size() == 1,
                "A reloaded queue must power sender and receiver when its packet actually moves");
            var oldHandler = node.handler();
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            oldHandler.receivePacket(packet, false);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE),
                "An old capability must not resurrect a broken director through a light update");
            helper.succeed();
        });
    }
}
