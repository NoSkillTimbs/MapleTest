package soloMapling.ArtificialPlayer;

import client.Character;
import server.maps.MapItem;
import server.maps.MapObject;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.SoloMaplingConstants;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BotHelpers - bot related utility methods.
 *
 * Handles:
 * - Bot lookup by ID
 * - Bot identification
 * - Bot storage repair
 * - Item name lookup
 * - Miscellaneous bot-related utilities
 */
public class BotHelpers {

    /**
     * Finds an artificial player by ID.
     *
     * Lookup order:
     *
     * 1. World storage
     * 2. Channel storage
     * 3. Legacy/un-offset bot ID
     *
     * If a bot exists in channel storage but is missing from world
     * storage, it is restored to world storage.
     *
     * The console bot ID 999 is treated as a special exact ID and
     * is never converted to another ID.
     */
    public static Character getBotById(int cid) {
        if (cid <= 0) {
            return null;
        }

        /*
         * Exact ID lookup in world storage.
         *
         * This is required for normal generated bot IDs as well as
         * the special console bot ID 999.
         */
        Character bot =
                SoloMaplingUtilities.world
                        .getPlayerStorage()
                        .getCharacterById(cid);

        if (isBot(bot)) {
            return bot;
        }

        /*
         * Exact ID lookup in channel storage.
         */
        bot =
                SoloMaplingUtilities.channel
                        .getPlayerStorage()
                        .getCharacterById(cid);

        if (isBot(bot)) {
            registerBotInWorldStorage(bot);
            return bot;
        }

        /*
         * Legacy/un-offset bot ID support.
         *
         * Older code may have stored a bot using an ID such as:
         *
         *     100
         *
         * while the actual generated ID is:
         *
         *     BOT_BASE_ID + 100
         *
         * Do not transform console ID 999.
         */
        if (cid < 1000 && !isConsole(cid)) {
            int botId =
                    SoloMaplingConstants.GameConstants.BOT_BASE_ID
                            + cid;

            bot =
                    SoloMaplingUtilities.world
                            .getPlayerStorage()
                            .getCharacterById(botId);

            if (isBot(bot)) {
                return bot;
            }

            bot =
                    SoloMaplingUtilities.channel
                            .getPlayerStorage()
                            .getCharacterById(botId);

            if (isBot(bot)) {
                registerBotInWorldStorage(bot);
                return bot;
            }
        }

        return null;
    }

    /**
     * Legacy compatibility method.
     *
     * The argument represents a character ID, not a Character object.
     */
    public static Character getCharFromChannelStorage(int cid) {
        return getBotById(cid);
    }

    /**
     * Makes sure the bot exists in world storage.
     *
     * Channel storage and world storage are separate, so a bot found
     * only in channel storage must be restored to world storage.
     */
    private static void registerBotInWorldStorage(Character bot) {
        if (bot == null || !isBot(bot)) {
            return;
        }

        Character existing =
                SoloMaplingUtilities.world
                        .getPlayerStorage()
                        .getCharacterById(bot.getId());

        /*
         * Already registered with the exact same Character instance.
         */
        if (existing == bot) {
            return;
        }

        /*
         * Never overwrite a different Character instance.
         */
        if (existing != null && existing != bot) {
            System.err.println(
                    "[BOT STORAGE] World storage already contains a "
                            + "different Character for bot "
                            + bot.getId()
            );

            return;
        }

        SoloMaplingUtilities.world
                .getPlayerStorage()
                .addPlayer(bot);
    }

    /**
     * Null-safe bot check.
     */
    public static boolean isBot(Character chr) {
        return chr != null && isBot(chr.getId());
    }

    /**
     * Determines whether a character ID belongs to an artificial
     * player.
     *
     * This overload is public because callers such as Expedition may
     * have an ID but not yet have a Character instance.
     */
    public static boolean isBot(int id) {
        return isArtificial(id) || isConsole(id);
    }

    /**
     * Determines whether an ID belongs to a generated artificial
     * player.
     *
     * Generated bot IDs are strictly greater than BOT_BASE_ID.
     */
    private static boolean isArtificial(int id) {
        return id > SoloMaplingConstants.GameConstants.BOT_BASE_ID;
    }

    /**
     * Special persistent console bot.
     */
    private static boolean isConsole(int id) {
        return id == 999;
    }

    /**
     * Converts an item ID to a cached item name when available.
     */
    public static String convertItemIdToName(int itemId) {
        if (itemId <= 0) {
            return "Unknown";
        }

        try {
            if (EquipMetadataCache.isInitialized()) {
                String name =
                        EquipMetadataCache.get()
                                .getCachedName(itemId);

                if (name != null) {
                    return name;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return String.valueOf(itemId);
    }

    /**
     * Adjusts the X coordinate of a point according to the configured
     * increment pattern.
     */
    public static Point adjustCenterPositionXAxis(
            Point center,
            int currIndex,
            int initialIncrement,
            int subsequentIncrement,
            int offset) {

        if (center == null) {
            return null;
        }

        if (currIndex < initialIncrement) {
            center.x += offset;
        } else {
            int adjustedIndex =
                    currIndex - initialIncrement;

            int cycle =
                    (adjustedIndex / subsequentIncrement) % 2;

            if (cycle == 0) {
                center.x -= offset;
            } else {
                center.x += offset;
            }
        }

        return center;
    }

    /**
     * Checks whether every MapObject in list2 is also present in list1.
     */
    public static boolean checkSecondListInsideFirstList(
            List<MapObject> list1,
            List<MapObject> list2) {

        if (list1 == null || list2 == null) {
            return false;
        }

        if (list1.size() < list2.size()) {
            System.out.println(
                    "Current List is greater than 1st"
            );

            return false;
        }

        for (MapObject obj2 : list2) {
            boolean found = false;

            for (MapObject obj1 : list1) {
                if (areObjectsEqual(obj1, obj2)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                System.out.println(
                        "Item Not Found"
                );

                return false;
            }
        }

        return true;
    }

    /**
     * Compares two MapItem objects by item ID, owner ID, and quantity.
     */
    private static boolean areObjectsEqual(
            MapObject obj1a,
            MapObject obj2b) {

        if (!(obj1a instanceof MapItem)
                || !(obj2b instanceof MapItem)) {
            return false;
        }

        MapItem obj1 =
                (MapItem) obj1a;

        MapItem obj2 =
                (MapItem) obj2b;

        if (obj1 == obj2) {
            return true;
        }

        if (obj1 == null || obj2 == null) {
            return false;
        }

        return obj1.getItemId() == obj2.getItemId()
                && obj1.getOwnerId() == obj2.getOwnerId()
                && obj1.getItem().getQuantity()
                == obj2.getItem().getQuantity();
    }

    /**
     * Sleeps while preserving interrupt state.
     *
     * @return true if the sleep completed, false if interrupted
     */
    public static boolean blockingSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Creates a rectangle centered around the supplied point.
     */
    public static Rectangle createRectangle(
            Point center,
            int width,
            int height) {

        if (center == null) {
            throw new IllegalArgumentException(
                    "Center point cannot be null."
            );
        }

        int halfWidth =
                width / 2;

        int halfHeight =
                height / 2;

        int verticalOffset =
                (int) (height * 0.2);

        int centerYAdjusted =
                center.y
                        - halfHeight
                        + verticalOffset;

        int topLeftX =
                center.x
                        - halfWidth;

        int topLeftY =
                centerYAdjusted
                        - halfHeight;

        return new Rectangle(
                topLeftX,
                topLeftY,
                width,
                height
        );
    }

    /**
     * Determines whether a point is inside a rectangle represented
     * by its top-left and bottom-right points.
     */
    public static boolean isPointWithinRectangle(
            Point[] rectangle,
            Point point) {

        if (rectangle == null
                || rectangle.length != 2) {

            throw new IllegalArgumentException(
                    "Rectangle must have exactly two points: "
                            + "top-left and bottom-right."
            );
        }

        if (point == null) {
            return false;
        }

        Point topLeft =
                rectangle[0];

        Point bottomRight =
                rectangle[1];

        return point.x >= topLeft.x
                && point.x <= bottomRight.x
                && point.y >= topLeft.y
                && point.y <= bottomRight.y;
    }

    /**
     * Waits for the difference between two timestamps.
     */
    public static boolean waitBetweenTwoLong(
            long timestamp1,
            long timestamp2) {

        long diff =
                Math.max(
                        0,
                        timestamp2 - timestamp1
                );

        if (diff > 2000) {
            System.out.println(
                    "More than 2 seconds waiting"
            );
        }

        try {
            Thread.sleep(diff);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Randomizes only the X coordinate using the default range.
     */
    public static Point getRandomizedPointXAxis(
            Point original) {

        return getRandomizedPointXAxis(
                original,
                50
        );
    }

    /**
     * Randomizes only the X coordinate within the supplied range.
     */
    public static Point getRandomizedPointXAxis(
            Point original,
            int range) {

        if (original == null) {
            return new Point(0, 0);
        }

        if (range < 0) {
            throw new IllegalArgumentException(
                    "Range cannot be negative."
            );
        }

        int minX =
                original.x - range;

        int maxX =
                original.x + range;

        int randomX =
                ThreadLocalRandom.current()
                        .nextInt(
                                minX,
                                maxX + 1
                        );

        return new Point(
                randomX,
                original.y
        );
    }
}

