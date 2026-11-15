/**
 * Machine pieces: the blocks that rituals, Technomagy's free-form multiblock machines, are built
 * from (D30, D31).
 *
 * <p>
 * So far these are the {@link zone.moddev.mc.technomagy.machine.NodeDirectorBlock node director},
 * which directs mana (D21), the {@link zone.moddev.mc.technomagy.machine.PrismBlock prism}, which
 * splits or combines it (D20, D26), and two creative-only blocks for testing links: a Raw mana
 * source that never runs out and a mana sink that destroys everything it receives. The director's
 * eight-way platform indicator follows its selected visual bearing (D23), while its light follows
 * actual packet activity with the beam idle grace period (D91). Bearing and power changes preserve
 * its block entity, queue and links. Loaded directors have saved activity and cached light
 * refreshed for compatibility with earlier preview saves.
 *
 * <p>
 * <b>Mana links (D87-D93).</b> Directors and prisms are link nodes: their block entities
 * ({@link zone.moddev.mc.technomagy.machine.ManaNodeBlockEntity} and its two subclasses) own
 * directed {@link zone.moddev.mc.technomagy.machine.ManaLink links}, move whole structured packets
 * over them in pulses, and sync each link's visible state to clients as a
 * {@link zone.moddev.mc.technomagy.machine.BeamState}.
 * {@link zone.moddev.mc.technomagy.machine.ManaLinks} makes and removes links and owns the
 * line-of-sight rule; {@link zone.moddev.mc.technomagy.machine.LinkFilter} is what a link carries
 * (D88); the {@link zone.moddev.mc.technomagy.machine.LinkLensItem lenses} raise a link's rate
 * (D90). {@link zone.moddev.mc.technomagy.machine.MachineConfig} holds the link and beam settings.
 * The {@code client} subpackage draws beams and picks them under the crosshair.
 * {@link zone.moddev.mc.technomagy.machine.MachineContent} registers them. They are ordinary
 * blocks, not MMDLib materials. Erosion processing resolves outputs through MMDLib's public
 * crushing recipe type (D61), and Forge resolves live smelting recipes (D51). Shared recipe lookup
 * neither consumes inventory nor changes the world.
 *
 * <p>
 * This package depends on {@code mana} for the capability and packets. It must not depend on
 * {@code crystal}: machine pieces work with mana, not with crystal blocks directly. It must not
 * depend on {@code spell} either: the focus uses links, not the other way round, so which items
 * reveal idle links is the {@code technomagy:reveals_mana_links} item tag, not a code reference.
 */
package zone.moddev.mc.technomagy.machine;
