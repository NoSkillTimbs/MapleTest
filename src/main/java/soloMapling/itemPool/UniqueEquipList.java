package soloMapling.itemPool;
import java.util.HashSet;
import java.util.Arrays;

import java.util.Set;

public class UniqueEquipList {

    private static final Set<Integer> UNIQUE_ITEMS = Set.of(
            1002515,     // Maple Bandana White
            1002516,     // Maple Bandana Yellow
            1002517,     // Maple Bandana Red
            1002518,      // Maple Bandana Blue
            1002600,     // Red Maple Bandana
            1002601,     // Yellow Maple Bandana
            1002602,     // Blue Maple Bandana
            1002603,      // White Maple Bandana
            1002210,     // Spectrum Goggles
            1002211,     // Green Spectrum Goggles
            1002212,     // Blue Spectrum Goggles
            1002213,     // Red Spectrum Goggles
            1442018,     // Seal Cushion
            1002267,     // Spiegelmann's Mustache
            1032024,     // Broken Glasses
            1010000,     // Branch Nose
            1022000,     // Raccoon Mask
            1002067     // Bamboo Hat

    );
    public static Set<Integer> getItems() {
        return UNIQUE_ITEMS;
    }


    public static boolean isUnique(int itemId) {
        return UNIQUE_ITEMS.contains(itemId);
    }

    public static int getMultiplier(int itemId) {
        if (!isUnique(itemId))
            return 1;

        return 25;
    }

}