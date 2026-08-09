package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import constants.inventory.EquipType;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class FashionPicker {

    private FashionPicker() {
    }

    public static Optional<EquipMetadataCache.EquipEntry> pickCashHat(Character bot) {

        return random(
                EquipMetadataCache.get()
                        .queryCash(EquipType.CAP)
                        .asList()
        );
    }


    public static Optional<EquipMetadataCache.EquipEntry> pickFaceAccessory(Character bot) {

        return random(
                EquipMetadataCache.get()
                        .query(EquipType.FACE)
                        .cashOnly()
                        .asList()
        );
    }


    public static Optional<EquipMetadataCache.EquipEntry> pickEyeAccessory(Character bot) {

        return random(
                EquipMetadataCache.get()
                        .query(EquipType.ACCESSORY)
                        .cashOnly()
                        .asList()
        );
    }


    public static Optional<EquipMetadataCache.EquipEntry> pickCape(Character bot) {

        return random(
                EquipMetadataCache.get()
                        .queryCash(EquipType.CAPE)
                        .asList()
        );
    }


    public static Optional<EquipMetadataCache.EquipEntry> pickCashWeapon(Character bot) {

        List<EquipMetadataCache.EquipEntry> weapons =
                EquipMetadataCache.get()
                        .all()
                        .stream()
                        .filter(e -> e.cash)
                        .filter(e -> e.id >= 1300000 && e.id < 1500000)
                        .toList();

        return random(weapons);
    }


    private static Optional<EquipMetadataCache.EquipEntry> random(
            List<EquipMetadataCache.EquipEntry> items) {

        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                items.get(
                        ThreadLocalRandom.current()
                                .nextInt(items.size())
                )
        );
    }
}