package soloMapling.FreeMarket;

import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.EquipType;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.itemPool.ItemNode;
import soloMapling.itemPool.UniqueEquipList;

import java.util.*;
import soloMapling.itemPool.UniqueEquipList;

import static soloMapling.FreeMarket.FMEconomyManager.multiplyWzPriceByJobStyle;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getJobStyleFromItemId;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipType;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getReqJobViaJobStyle;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getWzPrice;
import static soloMapling.itemPool.ItemInformationProviderUtilities.selectWeightedRandom;
import static soloMapling.itemPool.QuantitySelector.distributedTierSelector;
import static soloMapling.itemPool.UpgradeSimulator.ScrollGivenItem;
import static soloMapling.itemPool.UpgradeSimulator.checkIfEquipIsScrollable;
import static soloMapling.itemPool.UpgradeSimulator.getEquipMarketValue;
import static soloMapling.itemPool.ItemSelector.getRandomItemFull;
import static soloMapling.itemPool.ItemSelector.pickRandomVariantId;



public class EquipListGenerator {

    public static List<FMEquip> generateEquipList(String itemPool, List<String> equipCategories, String tier) {
        return generateEquipList(itemPool, equipCategories, tier, false);
    }

    public static List<FMEquip> generateEquipList(String itemPool, List<String> equipCategories, String tier, boolean hotRoom) {
//        Map<Equip, Integer> equipMap = new LinkedHashMap<>();
        List<FMEquip> equipList = new ArrayList<>();
        List<Integer> selectionPattern = generatePattern(tier, hotRoom);

        // Generate a list of equips based on the pattern
        List<String> selectedEquipCategories = selectEquipCategoriesByPattern(equipCategories, selectionPattern);
        String distTier = distributedTierSelector(tier);
        for (String equipType : selectedEquipCategories) {
            ItemNode item = getRandomItemFull(itemPool, equipType, distTier); // og tier

            if (item == null) {
                continue;
            }

            int itemId = pickRandomVariantId(item.getVariantId());
            int price = item.getCurrentPrice();
            Equip sellItem = (Equip) generateCleanItemEquip(itemId);

            if (checkIfEquipIsScrollable(sellItem)) {
                Equip scrolledItem = ScrollGivenItem(sellItem);
                price = getEquipMarketValue(scrolledItem);
                equipList.add(new FMEquip(scrolledItem, price));
//                }
            } else {
                equipList.add(new FMEquip(sellItem, price));
            }
        }
        return equipList;
    }


    public static List<FMEquip> generateEquipListIIPU(String tier, Job jobStyle) {
        return generateEquipListIIPU(tier, jobStyle, false);
    }

    public static List<FMEquip> generateEquipListIIPU(String tier, Job jobStyle, boolean scrollThisItem) {
        if (!EquipMetadataCache.isInitialized()) return Collections.emptyList();

        List<FMEquip> equipList = new ArrayList<>();

        int maxLevel = getLevelOnTier(tier);

        int listSize = 4;
        int maxAttempts = 15;

        for (int i = 0; i < maxAttempts && equipList.size() < listSize; i++) {
            EquipType eqType = getRandomEquipType(jobStyle);

            Integer itemId = getRandomEquipCached(eqType, maxLevel, jobStyle, tier);

            if (itemId != null) {
                FMEquip equip = processItemIdToFMEquip(itemId, scrollThisItem);

                if (equip != null) {
                    equipList.add(equip);
                }
            }
        }

        return equipList;
    }
    private static int getPriceFloor(String tier) {
        return switch (tier.toUpperCase()) {
            case "S" -> 100_000;
            case "A" -> 50_000;
            case "B" -> 20_000;
            default -> 10_000;
        };
    }

    private static int getMinLevelForClass(int maxLevel, boolean classSpecific) {
        if (classSpecific) {
            // Keep class equips near the top of the tier.
            return Math.max((int) (maxLevel * 0.85), 30);
        }

        // Common equips can be slightly lower.
        return Math.max((int) (maxLevel * 0.65), 20);
    }

    private static int weightedLevelPick(List<EquipMetadataCache.EquipEntry> list, int maxLevel) {
        int totalWeight = 0;

        for (EquipMetadataCache.EquipEntry e : list) {
            int distance = maxLevel - e.reqLevel;
            int weight = Math.max(1, 100 - distance * 3);
            totalWeight += weight;
        }

        int roll = RANDOM.nextInt(totalWeight);

        for (int i = 0; i < list.size(); i++) {
            EquipMetadataCache.EquipEntry e = list.get(i);
            int distance = maxLevel - e.reqLevel;
            int weight = Math.max(1, 100 - distance * 3);

            roll -= weight;
            if (roll < 0)
                return i;
        }

        return list.size() - 1;
    }

    public static FMEquip processItemIdToFMEquip(Integer itemId, boolean scrollThisItem) {
        if (itemId != null) {
            Job jobStyle = getJobStyleFromItemId(itemId);

            int price = multiplyWzPriceByJobStyle(getWzPrice(itemId), jobStyle);

            // Unique item price boost
            if (UniqueEquipList.isUnique(itemId)) {
                price = Math.max(price, 1000000); // minimum base value
                price *= UniqueEquipList.getMultiplier(itemId);
            }

            Equip sellItem = (Equip) generateCleanItemEquip(itemId);

            if (Math.random() > 0.5) { // Above average item
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                ii.randomizeUpgradeStats(sellItem);
                price = (int) (price * 1.5);
            }

            if (checkIfEquipIsScrollable(sellItem) && scrollThisItem) {
                Equip scrolledItem = ScrollGivenItem(sellItem);
                price = getEquipMarketValue(scrolledItem);

                // Reapply unique multiplier after scroll valuation
                if (UniqueEquipList.isUnique(itemId)) {
                    price *= UniqueEquipList.getMultiplier(itemId);
                }

                return new FMEquip(scrolledItem, price);
            } else {
                return new FMEquip(sellItem, price);
            }
        }

        return null; // failed to process
    }

    private static Integer getRandomEquipCached(EquipType eqType, int maxLevel, Job jobStyle, String tier) {

        int gender = RANDOM.nextBoolean() ? 0 : 1;
        int reqJob = getReqJobViaJobStyle(jobStyle);
        boolean classSpecific = reqJob != 0;

        int minLevel = getMinLevelForClass(maxLevel, classSpecific);
        int priceFloor = getPriceFloor(tier);

        List<EquipMetadataCache.EquipEntry> candidates =
                EquipMetadataCache.get().getByType(eqType);

        List<EquipMetadataCache.EquipEntry> valid = new ArrayList<>();

        for (int id : UniqueEquipList.getItems()) {

            EquipMetadataCache.EquipEntry special =
                    EquipMetadataCache.get()
                            .all()
                            .stream()
                            .filter(e -> e.id == id)
                            .findFirst()
                            .orElse(null);

            if (special != null) {
                valid.add(special);
            }

        }
        for (EquipMetadataCache.EquipEntry e : candidates) {

            if (e.untradeable || e.quest || e.cash)
                continue;

            if (e.reqJob != 0 && e.reqJob != reqJob)
                continue;

            if (e.gender != 2 && e.gender != gender)
                continue;

            // Always allow special desirable equips.
            if (DesirableEquipList.isDesirable(e.id)) {
                valid.add(e);
                continue;
            }

            if (e.reqLevel < minLevel || e.reqLevel > maxLevel)
                continue;

            if (e.wzPrice < priceFloor)
                continue;

            valid.add(e);
        }

        if (valid.isEmpty())
            return null;

        // Highest level first.
        valid.sort(Comparator.comparingInt(e -> e.reqLevel));

        // Take only the best 30%.
        int index = weightedLevelPick(valid, maxLevel);
        return valid.get(index).id;
    }
    private static List<String> selectEquipCategoriesByPattern(List<String> equipCategories, List<Integer> pattern) {
        List<String> selectedEquipCategories = new ArrayList<>();

        // Select unique elements from equipCategories based on the number of items in the pattern
        List<String> randomSelection = getRandomUniqueSelection(equipCategories, pattern.size());

        // Add each selected element to the list according to the pattern count
        for (int i = 0; i < pattern.size(); i++) {
            int count = pattern.get(i);
            String equipType = randomSelection.get(i);

            // Add 'count' copies of equipType to the final list
            selectedEquipCategories.addAll(Collections.nCopies(count, equipType));
        }

        return selectedEquipCategories;
    }

    public static Item generateCleanItemEquip(int id) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item cleanItem;
        if (ItemConstants.getInventoryType(id) == InventoryType.EQUIP) {
            cleanItem = ii.getEquipById(id);
        } else {
            cleanItem = new client.inventory.Item(id, (short) 0, (short) 1);
        }
        return cleanItem;
    }

    public static List<String> getRandomUniqueSelection(List<String> equipCategories, int count) {
        if (count > equipCategories.size()) {
            count = equipCategories.size();
        }

        // Generate a list of indices to shuffle
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < equipCategories.size(); i++) {
            indices.add(i);
        }

        // Shuffle and pick the first 'count' indices
        Collections.shuffle(indices);
        List<Integer> selectedIndices = indices.subList(0, count);

        // Collect selected elements
        List<String> selectedItems = new ArrayList<>();
        for (int index : selectedIndices) {
            selectedItems.add(equipCategories.get(index));
        }

        return selectedItems;
    }

    private static final Random RANDOM = new Random();

    public static int getLevelOnTier(String tier) {
        return switch (tier.toUpperCase()) {
            case "S" -> RANDOM.nextInt(41) + 110; // 110-160
            case "A" -> RANDOM.nextInt(41) + 70;  // 80-110
            case "B" -> RANDOM.nextInt(51) + 40;  // 40-90
            case "C", "D" -> RANDOM.nextInt(51);  // 0-50
            default -> RANDOM.nextInt(51);
        };
    }
    public static List<Integer> generatePattern(String tier) {
        return generatePattern(tier, false);
    }

    public static List<Integer> generatePattern(String tier, boolean hotRoom) {
        if (hotRoom && "S".equalsIgnoreCase(tier)) {
            return List.of(2, 2, 1, 1);
        }
        return switch (tier.toUpperCase()) {
            case "S" -> List.of(2, 1, 1);
            case "A", "B" -> List.of(2, 2);
            default -> List.of(2, 1, 1);
        };
    }

    public static void main(String[] args) {
        List<String> thief_equips = List.of("Weapon", "Hat", "Top", "Pants", "Shield");
        List<Integer> selectionPattern = generatePattern("C");
        System.out.println(selectEquipCategoriesByPattern(thief_equips, selectionPattern));
    }

}
