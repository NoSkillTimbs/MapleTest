package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import constants.inventory.EquipType;
import soloMapling.ArtificialPlayer.BotCustomization;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HenehoeDecorator {

    private HenehoeDecorator() {
    }

    public static void apply(Character bot) {

        if (!EquipMetadataCache.isInitialized()) {
            return;
        }

        EquipMetadataCache cache = EquipMetadataCache.get();

        equipRandom(
                bot,
                cache.getCashByType(EquipType.CAP)
        );

        equipRandom(
                bot,
                cache.getCashByType(EquipType.FACE)
        );

        if (Math.random() < 0.60) {
            equipRandom(
                    bot,
                    cache.getCashByType(EquipType.ACCESSORY)
            );
        }

        equipRandom(
                bot,
                cache.getCashByType(EquipType.CAPE)
        );

        if (Math.random() < 0.30) {
            equipRandom(
                    bot,
                    cache.queryCashWeapons().asList()
            );
        }
    }


    private static void equipRandom(
            Character bot,
            List<EquipMetadataCache.EquipEntry> items
    ) {

        if (items == null || items.isEmpty()) {
            return;
        }

        EquipMetadataCache.EquipEntry item =
                items.get(
                        ThreadLocalRandom.current()
                                .nextInt(items.size())
                );

        BotCustomization.EquipBot(
                bot,
                item.id
        );
    }
}