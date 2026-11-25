package io.github.jumpstartpz.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Which mods need which, from mod.info's require=. */
class ModDepsTest {
    /** damnlib <- car1, car2; tsarslib <- svu (also needs damnlib) <- svuAddon; lonely needs nothing. */
    private static Map<String, List<String>> mods() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("damnlib", List.of());
        m.put("car1", ModDeps.cleaned(List.of("\\damnlib")));
        m.put("car2", ModDeps.cleaned(List.of("\\DamnLib", "\\ZombieBuddy")));
        m.put("tsarslib", List.of());
        m.put("svu", ModDeps.cleaned(List.of("\\tsarslib", "\\damnlib")));
        m.put("svuAddon", ModDeps.cleaned(List.of("\\svu", "\\notInstalled")));
        m.put("lonely", List.of());
        return m;
    }

    @Test
    void requirementsAreCleaned() {
        assertEquals(List.of("tsarslib", "StandardizedVehicleUpgrades3Core"),
                ModDeps.cleaned(List.of("\\tsarslib", " \\StandardizedVehicleUpgrades3Core ", "", "\\ZombieBuddy", "\\tsarslib")));
        assertEquals("damnlib", ModDeps.id("\\damnlib"));
    }

    @Test
    void switchingALibraryOffNamesEveryModThatNeedsIt() {
        Set<String> on = new HashSet<>(mods().keySet());
        on.remove("car2");
        List<String> order = ModDeps.dependentsOn("damnlib", mods(), on::contains);
        assertEquals(Set.of("car1", "svu", "svuAddon"), new HashSet<>(order), "car2 is off already");
        assertTrue(order.indexOf("svuAddon") < order.indexOf("svu"), "a mod goes off before the mod it needs: " + order);
        assertEquals(List.of(), ModDeps.dependentsOn("lonely", mods(), on::contains));
    }

    @Test
    void switchingAModOnNeedsItsLibrariesFirst() {
        Set<String> on = Set.of("lonely");
        ModDeps.Needs n = ModDeps.needs("svuAddon", mods(), on::contains);
        assertEquals(List.of("tsarslib", "damnlib", "svu"), n.off(), "libraries before the mods that need them");
        assertEquals(List.of("notInstalled"), n.missing());
        assertTrue(ModDeps.needs("car2", mods(), Set.of("damnlib")::contains).none(), "ZombieBuddy comes with Jumpstart");
    }

    @Test
    void aLibraryNoModNeedsAnyMoreIsNamed() {
        Set<String> on = new HashSet<>(mods().keySet());
        on.remove("car1");
        assertEquals(List.of(), ModDeps.unneeded(List.of("car1"), mods(), on::contains), "car2 and svu still need damnlib");
        on.removeAll(Set.of("car2", "svu", "svuAddon"));
        assertEquals(List.of("tsarslib", "damnlib"), ModDeps.unneeded(List.of("svu", "svuAddon", "car2", "car1"), mods(), on::contains));
    }

    @Test
    void cyclesDoNotLoop() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("a", List.of("b"));
        m.put("b", List.of("a"));
        assertEquals(List.of("b"), ModDeps.dependentsOn("a", m, x -> true));
        assertEquals(List.of("b"), ModDeps.needs("a", m, x -> false).off());
        assertEquals(List.of("b"), ModDeps.neededBy("a", m));
    }
}
