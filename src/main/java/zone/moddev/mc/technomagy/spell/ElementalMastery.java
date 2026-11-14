package zone.moddev.mc.technomagy.spell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import org.bitwisemadness.abilitycore.api.AbilityDefinition;
import org.bitwisemadness.abilitycore.api.AbilityPresentation;
import org.bitwisemadness.abilitycore.api.ActivationType;
import org.bitwisemadness.abilitycore.api.MasteryAxis;
import org.bitwisemadness.abilitycore.api.MasteryDeclarations;
import org.bitwisemadness.abilitycore.api.MasterySpec;
import org.bitwisemadness.abilitycore.api.PresentationField;
import org.bitwisemadness.abilitycore.api.PresentationProviders;
import org.bitwisemadness.abilityhotbar.UnlockRule;
import org.bitwisemadness.abilityhotbar.compat.CoreMasteryCompat;
import org.bitwisemadness.abilityhotbar.registry.MenuTabs;

import zone.moddev.mc.technomagy.mana.ManaType;

/**
 * The one Technomagy menu tab and the six elemental mastery entries (D82, D86): organization only -
 * a count-chip strip by element count under the single tab, an element tag per component element on
 * every ability, and one non-castable entry per element presenting its bucket's level. No balance
 * lives here; the boosts the entries describe are D80's aggregate math.
 */
final class ElementalMastery {

    /**
     * The single menu tab (D86, superseding D82's four-tab strip): everything of ours, split by
     * element count through the {@code type:} count chips rather than separate tabs.
     */
    static final ResourceLocation MAIN_TAB = ResourceLocation.fromNamespaceAndPath("technomagy",
        "main");

    /**
     * The tab's icon: the spell focus's own texture - the focus wand, D71's personal casting
     * device. The item instance cannot be built here: declarations run at mod construction, before
     * registry events hand out items, so the tab wears the item's art directly.
     */
    static final ResourceLocation FOCUS_WAND_ICON = ResourceLocation.fromNamespaceAndPath(
        "technomagy", "textures/item/spell_focus.png");

    /**
     * The count chips' declared draw order (D86): Single for one element (the six entries and any
     * future single-element ability), Duo for two, Trio for three, Quadra for four. Quadra never
     * draws until a four-element ability exists - the strip skips a declared chip with no entries,
     * which keeps D82's hidden-Quadra rule as the strip's own.
     */
    static final List<String> COUNT_ORDER = List.of("type:single", "type:duo", "type:trio",
        "type:quadra");

    /**
     * The count chips' art (D86): the three alchemical count symbols that wore the old tabs - the
     * philosopher's stone for Single, the amalgam for Duo, the amalgam (alternate) for Trio, from
     * symbols.com - now drawn on the strip's chips. Quadra declares no art: its chip never draws
     * until a four-element ability exists (D82's rule) and no symbol has been chosen for it, so it
     * stays text-only if it ever draws.
     */
    private static final Map<String, ResourceLocation> COUNT_ICONS = Map.of(
        "type:single",
        ResourceLocation.fromNamespaceAndPath("technomagy", "textures/gui/tab/single.png"),
        "type:duo",
        ResourceLocation.fromNamespaceAndPath("technomagy", "textures/gui/tab/duo.png"),
        "type:trio",
        ResourceLocation.fromNamespaceAndPath("technomagy", "textures/gui/tab/trio.png"));

    /** The meter label key (D81's presentation seam). */
    private static final String METER_LABEL = "technomagy.mastery.progress";

    /** The meter's value text key: the localised level inside the bar. */
    private static final String LEVEL_VALUE = "technomagy.mastery.level_value";

    private ElementalMastery() {
    }

    /**
     * Declares the one Technomagy tab with the focus-wand icon and its count-chip order, the six
     * entry presentation providers, each entry's pane-display mastery spec (D85), and no snapshot
     * listener of its own - the client reads hotbar's shared snapshot store instead. Runs once at
     * mod construction beside the D79/D80 declarations (D82, D85, D86).
     */
    static void declare() {
        MenuTabs.register(MAIN_TAB, "tab.technomagy.main", FOCUS_WAND_ICON, null,
            (id, definition) -> "technomagy".equals(id.getNamespace()), content(), false);
        MenuTabs.subCategoryOrder(MAIN_TAB, COUNT_ORDER);
        COUNT_ICONS.forEach((typeTag, icon) -> MenuTabs.subCategoryIcon(typeTag, icon, null));
        for (ManaType element : ManaType.FLAVORS) {
            PresentationProviders.declare(id(element), ElementalMastery::present);
            // D85's display contract: the entry never casts, so these curves never apply -
            // they exist so the pane's Mastery bonuses section reads POWER and DURATION at the
            // element's own level (the ISS school-mastery pattern). No has() guard: a second
            // declaration must trip core's duplicate-owner error, never silently win.
            MasteryDeclarations.declare(id(element), MasterySpec.gained(0.0)
                .withEffect(MasteryAxis.POWER)
                .withEffect(MasteryAxis.DURATION));
        }
    }

    /**
     * The one tab's live content (D83 through D86): every id of ours the player has learned - the
     * six entries (always) and each spell whose unlock verdict is not proven-unlearned - so a Duo
     * or Trio spell stays out until its elements qualify, exactly as the per-tab contents did
     * before the four tabs collapsed into one. Unknown still lists - hotbar's never-guess rule
     * holds on this side too, so a player whose snapshot or threshold payload has not answered sees
     * every id rather than a wrongly emptied tab.
     *
     * @return the content handler hotbar queries per menu frame
     */
    private static MenuTabs.TabContent content() {
        return player -> {
            List<ResourceLocation> ids = new ArrayList<>();
            for (ManaType element : ManaType.FLAVORS) {
                if (listed(player, id(element))) {
                    ids.add(id(element));
                }
            }
            for (PlayerSpell spell : PlayerSpell.values()) {
                if (listed(player, spell.id())) {
                    ids.add(spell.id());
                }
            }
            return ids;
        };
    }

    /** Whether one id lists under a tab: everything except a proven-unlearned answer. */
    private static boolean listed(Player player, ResourceLocation abilityId) {
        return !UnlockRule.provenUnlearned(SpellUnlocks.learnedFor(player, abilityId));
    }

    /**
     * The mastery entry's ability id: {@code technomagy:<element>_mastery}.
     *
     * @param element
     *            one of the six personal-pool flavors
     * @return the entry's stable id
     */
    static ResourceLocation id(ManaType element) {
        return ResourceLocation.fromNamespaceAndPath("technomagy",
            tagName(element) + "_mastery");
    }

    /**
     * Whether an id names one of the six elemental mastery entries.
     *
     * @param abilityId
     *            the id being asked about
     * @return true only for the six entries' ids
     */
    static boolean isMastery(ResourceLocation abilityId) {
        for (ManaType element : ManaType.FLAVORS) {
            if (id(element).equals(abilityId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The element-tag vocabulary name for one flavor: its lowercase mana name ({@code fire},
     * {@code light}, ...), the same word the personal pool, the element tag and the
     * {@code <name>_mastery} entry id all derive from.
     *
     * @param element
     *            one of the six personal-pool flavors
     * @return the raw tag string
     */
    static String tagName(ManaType element) {
        return element.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The element tags for one spell: every component element of its stream (D82), so an ability
     * carries all of its parts' element tags beside its count chip (D86).
     *
     * @param spell
     *            the spell whose construction supplies the elements
     * @return the unmodifiable tag set
     */
    static Set<String> elementTags(PlayerSpell spell) {
        return spell.inputUnits().keySet().stream()
            .map(ElementalMastery::tagName)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The {@code type:} count chip an ability of this element count wears under the tab (D86): the
     * strip's category vocabulary - one, two, three or four elements.
     *
     * @param elementCount
     *            the ability's element count (1..4)
     * @return the prefixed chip tag, e.g. {@code type:duo}
     * @throws IllegalStateException
     *             for a count no chip names - a fifth element would need a new chip here
     */
    static String countTag(int elementCount) {
        return switch (elementCount) {
            case 1 -> "type:single";
            case 2 -> "type:duo";
            case 3 -> "type:trio";
            case 4 -> "type:quadra";
            default -> throw new IllegalStateException(
                "no count chip for an element count of " + elementCount);
        };
    }

    /**
     * Builds one elemental mastery entry's definition (D82): non-castable by construction -
     * {@code NONE} activation declares no activation path at all, the empty cost list and
     * {@code static} resolver mean no pricing can ever run - carrying its one element tag, its
     * {@code type:single} count chip (D86) and the boost description.
     *
     * @param element
     *            the entry's element
     * @return the registry entry for {@code <element>_mastery}
     */
    static AbilityDefinition definition(ManaType element) {
        String name = tagName(element);
        return new AbilityDefinition(
            "mastery.technomagy." + name,
            entryIcon(element),
            ResourceLocation.fromNamespaceAndPath("technomagy", "mastery"),
            ActivationType.NONE,
            List.of(),
            "all",
            "static",
            Set.of(name, countTag(1)),
            Map.of("description", "mastery.technomagy." + name + ".description"));
    }

    /**
     * The row icon texture for one element (D82): the element's alchemical symbol at 32 pixels -
     * the mastery entry's icon and the source the spell-icon generator composes from.
     *
     * @param element
     *            one of the six personal-pool flavors
     * @return the texture id
     */
    static ResourceLocation entryIcon(ManaType element) {
        return ResourceLocation.fromNamespaceAndPath("technomagy",
            "textures/item/" + tagName(element) + "_mastery.png");
    }

    /**
     * The menu presentation for one entry (D82 through D81's seam): hotbar's stored copy of the
     * server's answer decides - the element's level text and in-level fill once it has answered, an
     * empty presentation before it - never a guessed zero. It is the same {@link CoreMasteryCompat}
     * read the spells' presentations use (fenced to the local player's exchange), so this class
     * keeps no client copy of its own. Toggle stays null because nothing here is castable.
     *
     * @param player
     *            the player whose menu is rendering (unused: the snapshot already names them)
     * @param abilityId
     *            the mastery entry being presented
     * @return the presentation; never null
     */
    static AbilityPresentation present(Player player, ResourceLocation abilityId) {
        if (!CoreMasteryCompat.answered()) {
            return new AbilityPresentation(null, List.of());
        }
        return presentation(abilityId, CoreMasteryCompat::levelOf, CoreMasteryCompat::progressOf);
    }

    /**
     * The pure half of {@link #present}: the id travels untransformed to the injected store readers
     * and their answers become the meter - no client state touched. The readers are parameters so
     * the GameTests can pin the exact id this method looks up: a transformed id (the old
     * pool-resource id, say) would silently answer level 0 and still build a meter.
     *
     * @param abilityId
     *            the entry id to look up, passed through unchanged
     * @param levelOf
     *            the store's own-target level reader
     * @param progressOf
     *            the store's in-level fill reader
     * @return the meter presentation; never null
     */
    static AbilityPresentation presentation(ResourceLocation abilityId,
        ToIntFunction<ResourceLocation> levelOf,
        ToDoubleFunction<ResourceLocation> progressOf) {
        int level = levelOf.applyAsInt(abilityId);
        double fill = progressOf.applyAsDouble(abilityId);
        PresentationField meter = PresentationField.progress(METER_LABEL, (float) fill,
            Component.translatable(LEVEL_VALUE, level).getString());
        return new AbilityPresentation(null, List.of(meter));
    }
}
