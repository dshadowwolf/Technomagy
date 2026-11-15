package zone.moddev.mc.technomagy.machine;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import zone.moddev.mc.technomagy.mana.ManaPacket;
import zone.moddev.mc.technomagy.mana.ManaType;
import zone.moddev.mc.technomagy.mana.SpellStream;

/**
 * Dedicated-server checks for mana links (D87-D90): link rules, pulsed transfer from a flat source,
 * prism splitting and combining by port order, exact construction filters, breaking a node, and
 * saving. Each waits a few transfer pulses (10 ticks each by default) before checking.
 */
@GameTestHolder("technomagy")
@PrefixGameTestTemplate(false)
public final class ManaLinkGameTests {
    /** Enough ticks for several pulses at the default interval. */
    private static final int SETTLE = 60;

    private ManaLinkGameTests() {
    }

    /** A Raw source feeds a director over a pull link, one 1-unit Raw packet per pulse. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void directorPullsRawFromSource(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = place(helper, 2, 1, 2, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        BlockPos director = place(helper, 6, 1, 2, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        require(helper, ManaLinks.toggle(level, source, director,
            Optional.of(LinkFilter.pure(ManaType.RAW))) == ManaLinks.Result.CREATED,
            "Raw source to director must link");
        helper.runAfterDelay(SETTLE, () -> {
            List<ManaPacket> held = node(helper, director).held();
            require(helper, held.size() >= 3, "Director must have pulled several packets");
            for (ManaPacket packet : held) {
                require(helper, packet.isElement() && packet.stream().element() == ManaType.RAW
                    && packet.stream().units() == 1, "Each pulled packet is 1 unit of Raw");
            }
            require(helper, node(helper, director).links().size() == 1,
                "A pull link is owned by its target");
            require(helper, level.getBlockState(director).getValue(NodeDirectorBlock.POWERED),
                "Real pull-link packets must power the director");
            helper.succeed();
        });
    }

    /**
     * A chain ending at a mana sink never fills: long after a director alone would have filled, the
     * director still has room and its link into the sink is still flowing (D91).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sinkKeepsAChainFlowing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = place(helper, 2, 1, 2, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        BlockPos director = place(helper, 6, 1, 2, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos sink = place(helper, 10, 1, 2, MachineContent.MANA_SINK.get()
            .defaultBlockState());
        LinkFilter raw = LinkFilter.pure(ManaType.RAW);
        require(helper, link(level, source, director, raw), "Raw source to director must link");
        require(helper, link(level, director, sink, raw), "Director to sink must link");
        // Several times longer than the director needs to fill without a drain.
        int wait = 3 * MachineConfig.DIRECTOR_CAPACITY.get() * MachineConfig.PULSE_INTERVAL.get();
        helper.runAfterDelay(wait, () -> {
            ManaNodeBlockEntity node = node(helper, director);
            require(helper, node.held().size() < MachineConfig.DIRECTOR_CAPACITY.get(),
                "A director draining into a sink must never fill");
            long idleTicks = (long) MachineConfig.IDLE_PULSES.get()
                * MachineConfig.PULSE_INTERVAL.get();
            ManaLink out = node.link(director, sink).orElseThrow();
            require(helper, out.flowing(level.getGameTime(), idleTicks),
                "The link into the sink must still be flowing");
            require(helper, level.getBlockState(director).getValue(NodeDirectorBlock.POWERED),
                "Continuous packet transfer must keep the director lit");
            require(helper, ManaLinks.handler(level, sink).extractPacket(ManaType.RAW, 1, true)
                .isEmpty(), "A sink supplies nothing");
            helper.succeed();
        });
    }

    /** A split prism turns Raw into six flavors, each leaving on the link filtered to it (D27). */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void splitPrismRoutesRawByFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = place(helper, 7, 1, 3, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        BlockPos prism = place(helper, 7, 1, 7, MachineContent.PRISM.get().defaultBlockState());
        BlockPos[] targets = {
            new BlockPos(3, 3, 7), new BlockPos(11, 3, 7), new BlockPos(7, 3, 11),
            new BlockPos(3, 3, 11), new BlockPos(11, 3, 11), new BlockPos(3, 3, 3)};
        require(helper, ManaLinks.toggle(level, source, prism,
            Optional.of(LinkFilter.pure(ManaType.RAW))) == ManaLinks.Result.CREATED,
            "Raw source to prism must link");
        for (int i = 0; i < targets.length; i++) {
            BlockPos target = place(helper, targets[i].getX(), targets[i].getY(), targets[i].getZ(),
                MachineContent.NODE_DIRECTOR.get().defaultBlockState());
            targets[i] = target;
            require(helper, ManaLinks.toggle(level, prism, target,
                Optional.of(LinkFilter.pure(ManaType.FLAVORS.get(i)))) == ManaLinks.Result.CREATED,
                "Prism to flavor director " + i + " must link");
        }
        helper.runAfterDelay(SETTLE, () -> {
            for (int i = 0; i < targets.length; i++) {
                ManaType flavor = ManaType.FLAVORS.get(i);
                List<ManaPacket> held = node(helper, targets[i]).held();
                require(helper, !held.isEmpty(), flavor + " director must receive packets");
                for (ManaPacket packet : held) {
                    require(helper, packet.isElement() && packet.stream().element() == flavor,
                        flavor + " director must receive only pure " + flavor);
                }
            }
            helper.succeed();
        });
    }

    /**
     * A split prism with only some flavor links still splits Raw: linked flavors flow on, the rest
     * sputter away, and the prism keeps working instead of stalling (D89).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void splitPrismSputtersUnlinkedFlavors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = place(helper, 2, 1, 6, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        BlockPos prism = place(helper, 6, 1, 6, MachineContent.PRISM.get().defaultBlockState());
        BlockPos fire = place(helper, 10, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        require(helper, link(level, source, prism, LinkFilter.pure(ManaType.RAW)),
            "Raw source to prism must link");
        require(helper, link(level, prism, fire, LinkFilter.pure(ManaType.FIRE)),
            "Prism to Fire director must link");
        helper.runAfterDelay(SETTLE, () -> {
            List<ManaPacket> held = node(helper, fire).held();
            require(helper, held.size() >= 2,
                "The prism must keep splitting with five flavors unlinked");
            for (ManaPacket packet : held) {
                require(helper, packet.isElement() && packet.stream().element() == ManaType.FIRE,
                    "Only Fire may leave on the Fire link");
            }
            helper.succeed();
        });
    }

    /**
     * A split prism with one outgoing link sends a merge's primary on it and sputters the
     * secondary; a split prism with no outgoing link holds its input (D89).
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void splitPrismNeedsOneLinkAndSputtersSecondary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos feeder = place(helper, 2, 1, 2, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos prism = place(helper, 6, 1, 2, MachineContent.PRISM.get().defaultBlockState());
        BlockPos out = place(helper, 10, 1, 2, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        ManaPacket steam = ManaPacket.merge(ManaPacket.leaf(ManaType.FIRE, 2),
            ManaPacket.leaf(ManaType.WATER, 1));
        seed(helper, feeder, steam);
        require(helper, link(level, feeder, prism, LinkFilter.of(steam.stream())),
            "Feeder to prism must link");
        require(helper, link(level, prism, out, LinkFilter.pure(ManaType.FIRE)),
            "Prism to output must link");

        BlockPos lonelyFeeder = place(helper, 2, 1, 10, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos lonely = place(helper, 6, 1, 10, MachineContent.PRISM.get()
            .defaultBlockState());
        seed(helper, lonelyFeeder, ManaPacket.leaf(ManaType.FIRE, 1));
        require(helper, link(level, lonelyFeeder, lonely, LinkFilter.pure(ManaType.FIRE)),
            "Feeder to unlinked prism must link");
        helper.runAfterDelay(SETTLE, () -> {
            List<ManaPacket> arrived = node(helper, out).held();
            require(helper, arrived.size() == 1 && arrived.getFirst().isElement()
                && arrived.getFirst().stream().element() == ManaType.FIRE
                && arrived.getFirst().stream().units() == 2,
                "The primary (2 Fire) must leave on the only link");
            require(helper, node(helper, prism).held().isEmpty(),
                "The secondary must sputter away, not wait in the prism");
            require(helper, node(helper, lonely).held().size() == 1,
                "A split prism with no outgoing link must hold its input");
            helper.succeed();
        });
    }

    /** A combine prism merges its first link's packet as primary, its second's as secondary. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void combinePrismMergesByLinkOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos fire = place(helper, 2, 1, 2, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos water = place(helper, 2, 1, 10, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos prism = place(helper, 7, 1, 6, MachineContent.PRISM.get().defaultBlockState()
            .setValue(PrismBlock.MODE, PrismBlock.Mode.COMBINE));
        BlockPos out = place(helper, 12, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        seed(helper, fire, ManaPacket.leaf(ManaType.FIRE, 2));
        seed(helper, water, ManaPacket.leaf(ManaType.WATER, 1));
        require(helper, link(level, fire, prism, LinkFilter.pure(ManaType.FIRE)),
            "Fire director to prism (primary) must link");
        require(helper, link(level, water, prism, LinkFilter.pure(ManaType.WATER)),
            "Water director to prism (secondary) must link");
        LinkFilter steam = LinkFilter.of(SpellStream.merge(
            SpellStream.element(ManaType.FIRE, 1), SpellStream.element(ManaType.WATER, 1)));
        require(helper, link(level, prism, out, steam), "Prism to output must link");
        require(helper,
            ManaLinks.toggle(level, out, prism, Optional.of(steam)) == ManaLinks.Result.PORTS_FULL,
            "A combining prism takes only two inputs");
        helper.runAfterDelay(SETTLE, () -> {
            List<ManaPacket> held = node(helper, out).held();
            require(helper, held.size() == 1, "Exactly one merged packet must arrive");
            ManaPacket merged = held.getFirst();
            require(helper, steam.admits(merged), "The merge must be Fire (primary) + Water");
            require(helper, merged.flavorUnits()[ManaType.FIRE.ordinal()] == 2,
                "Merging keeps the amounts");
            helper.succeed();
        });
    }

    /** Filters match construction exactly, ignoring amounts (D88). */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void filtersMatchConstructionExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos from = place(helper, 2, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos to = place(helper, 10, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        var fire = SpellStream.element(ManaType.FIRE, 1);
        var water = SpellStream.element(ManaType.WATER, 1);
        // Steam merged with Fire, and 2 Fire merged with Water: the same totals, built differently.
        ManaPacket steamThenFire = ManaPacket.of(SpellStream.merge(
            SpellStream.merge(fire, water), fire));
        ManaPacket direct = ManaPacket.of(SpellStream.merge(
            SpellStream.merge(fire, fire), SpellStream.element(ManaType.WATER, 3)));
        seed(helper, from, steamThenFire);
        seed(helper, from, direct);
        require(helper, link(level, from, to, LinkFilter.of(SpellStream.merge(
            SpellStream.element(ManaType.FIRE, 2), water))), "Directors must link");
        helper.runAfterDelay(SETTLE, () -> {
            List<ManaPacket> arrived = node(helper, to).held();
            require(helper, arrived.size() == 1 && arrived.getFirst().equals(direct),
                "Only the 2 Fire + Water construction may pass, at any amount");
            require(helper, node(helper, from).held().equals(List.of(steamThenFire)),
                "Steam + Fire must wait in the source director");
            helper.succeed();
        });
    }

    /** Distance, sight, node and toggle rules for making links (D87). */
    @GameTest(template = "empty")
    public static void linkRulesAreEnforced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LinkFilter fire = LinkFilter.pure(ManaType.FIRE);
        BlockPos a = place(helper, 1, 1, 1, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos b = place(helper, 5, 1, 1, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos sourceA = place(helper, 1, 1, 13, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        BlockPos sourceB = place(helper, 5, 1, 13, MachineContent.RAW_MANA_SOURCE.get()
            .defaultBlockState());
        require(helper,
            ManaLinks.toggle(level, a, a, Optional.of(fire)) == ManaLinks.Result.SAME_BLOCK,
            "A block cannot link to itself");
        require(helper,
            ManaLinks.toggle(level, sourceA, sourceB,
                Optional.of(fire)) == ManaLinks.Result.NEEDS_NODE,
            "Two plain handlers cannot link");
        require(helper,
            ManaLinks.toggle(level, a, b, Optional.empty()) == ManaLinks.Result.NEEDS_FILTER,
            "A new link needs a filter");
        helper.setBlock(3, 1, 1, Blocks.STONE);
        require(helper,
            ManaLinks.toggle(level, a, b, Optional.of(fire)) == ManaLinks.Result.NO_LINE_OF_SIGHT,
            "Stone in the way blocks a link");
        helper.setBlock(3, 1, 1, Blocks.AIR);
        require(helper,
            ManaLinks.toggle(level, a, b, Optional.of(fire)) == ManaLinks.Result.CREATED,
            "A clear line links");
        require(helper, node(helper, b).incoming().equals(List.of(a)),
            "The target records the incoming link");
        require(helper, ManaLinks.toggle(level, a, b, Optional.empty()) == ManaLinks.Result.REMOVED,
            "Linking a linked pair removes the link");
        require(helper, node(helper, a).links().isEmpty() && node(helper, b).incoming().isEmpty(),
            "Removal clears both sides");
        ManaNodeBlockEntity far = placeFar(helper);
        require(helper,
            ManaLinks.toggle(level, a, far.getBlockPos(),
                Optional.of(fire)) == ManaLinks.Result.TOO_FAR,
            "Links reach at most the configured length");
        helper.succeed();
    }

    /** Breaking a node releases its packets, drops lenses, and unlinks its neighbours (D87). */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void breakingANodeUnlinksAndReleases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = place(helper, 2, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos b = place(helper, 8, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos c = place(helper, 12, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        require(helper, link(level, a, b, LinkFilter.pure(ManaType.EARTH)), "a -> b must link");
        require(helper, link(level, b, c, LinkFilter.pure(ManaType.EARTH)), "b -> c must link");
        node(helper, b).link(b, c).orElseThrow().setTier(2);
        seed(helper, b, ManaPacket.leaf(ManaType.AIR, 3));
        helper.setBlock(8, 1, 6, Blocks.AIR);
        require(helper, node(helper, a).links().isEmpty(), "The link into the broken node goes");
        require(helper, node(helper, c).incoming().isEmpty(),
            "The broken node's target forgets it");
        helper.assertItemEntityCountIs(MachineContent.LENS_MINOR.get(), new BlockPos(8, 1, 6),
            1.5, 1);
        helper.assertItemEntityCountIs(MachineContent.LENS_GREATER.get(), new BlockPos(8, 1, 6),
            1.5, 1);
        helper.succeed();
    }

    /** Links, tiers, ports and queued packets survive a save and load. */
    @GameTest(template = "empty")
    public static void nodesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = place(helper, 2, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        BlockPos b = place(helper, 8, 1, 6, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        LinkFilter steam = LinkFilter.of(SpellStream.merge(
            SpellStream.element(ManaType.FIRE, 1), SpellStream.element(ManaType.WATER, 1)));
        require(helper, link(level, a, b, steam), "a -> b must link");
        node(helper, a).link(a, b).orElseThrow().setTier(3);
        ManaPacket packet = ManaPacket.of(SpellStream.merge(
            SpellStream.element(ManaType.FIRE, 4), SpellStream.element(ManaType.WATER, 2)));
        seed(helper, a, packet);
        CompoundTag saved = node(helper, a).saveWithFullMetadata(level.registryAccess());
        NodeDirectorBlockEntity copy = new NodeDirectorBlockEntity(a,
            level.getBlockState(a));
        copy.loadWithComponents(saved, level.registryAccess());
        ManaLink link = copy.link(a, b).orElseThrow();
        require(helper, link.tier() == 3 && link.filter().equals(steam),
            "The link keeps its tier and filter");
        require(helper, copy.held().size() == 1 && steam.admits(copy.held().getFirst())
            && copy.held().getFirst().flavorUnits()[ManaType.FIRE.ordinal()] == 4,
            "The queued packet keeps its construction and amounts");
        require(helper, node(helper, b).incoming().equals(List.of(a)),
            "The target keeps its incoming order");
        helper.succeed();
    }

    /** Places a block at a test-relative position and returns its absolute position. */
    private static BlockPos place(GameTestHelper helper, int x, int y, int z,
        net.minecraft.world.level.block.state.BlockState state) {
        helper.setBlock(x, y, z, state);
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** A director just beyond the default link length from test position (1, 1, 1). */
    private static ManaNodeBlockEntity placeFar(GameTestHelper helper) {
        BlockPos far = place(helper, 14, 7, 14, MachineContent.NODE_DIRECTOR.get()
            .defaultBlockState());
        return node(helper, far);
    }

    /** Makes a link, reporting whether it was created. */
    private static boolean link(ServerLevel level, BlockPos from, BlockPos to, LinkFilter filter) {
        return ManaLinks.toggle(level, from, to, Optional.of(filter)) == ManaLinks.Result.CREATED;
    }

    /** Puts a packet into a director's queue through its capability. */
    private static void seed(GameTestHelper helper, BlockPos pos, ManaPacket packet) {
        require(helper, node(helper, pos).handler().receivePacket(packet, false),
            "Director must accept a seeded packet");
    }

    /** The node at an absolute position. */
    private static ManaNodeBlockEntity node(GameTestHelper helper, BlockPos pos) {
        if (helper.getLevel().getBlockEntity(pos) instanceof ManaNodeBlockEntity node) {
            return node;
        }
        throw new AssertionError("No mana node at " + pos);
    }

    /** Fails the test with a message when a condition is false. */
    private static void require(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }
}
