package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.SoloMaplingConstants;

import java.awt.Point;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotClientHandler.getBotClient;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botEnterPortalDropDown;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.microTurnAroundToLeft;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.FreeMarket.FMShopDescGen.getRandomCharacterIGN;
import static soloMapling.server.ExecutorServiceManager.runAsync;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;
import static soloMapling.server.SoloMaplingUtilities.channel;
import static soloMapling.server.SoloMaplingUtilities.world;

public class BotGeneration {

    /*
     * Bots can spawn concurrently, so this must remain atomic.
     */
    private static final AtomicInteger currentBotCount =
            new AtomicInteger(100);

    /**
     * Worst-case duration of the spawn choreography.
     */
    public static final long SPAWN_CHOREOGRAPHY_MAX_MS = 7000;

    /**
     * Total number of bots created since server start.
     */
    public static int getBotsCreatedCount() {
        return currentBotCount.get() - 100;
    }

    /**
     * Returns the persistent console bot.
     */
    public static Character getConsoleBot() {
        Character consoleBot = BotHelpers.getBotById(999);

        if (consoleBot != null) {
            return consoleBot;
        }

        final int botId = 999;
        final int baseId = 2;

        try {
            consoleBot = Character.loadCharFromDB(
                    baseId,
                    getBotClient(),
                    false
            );
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Unable to load base Character for console bot.",
                    e
            );
        }

        if (consoleBot == null) {
            throw new IllegalStateException(
                    "Character.loadCharFromDB returned null for console bot."
            );
        }

        consoleBot = setConsoleBot(consoleBot, botId);

        addBotToServer(consoleBot);

        return consoleBot;
    }

    public static int createBot(
            Point pos,
            MapleMap map) {

        return createBot(
                pos,
                map,
                0,
                0,
                0
        );
    }

    public static int createBot(
            Point pos,
            MapleMap map,
            int baseClass,
            int minLevel,
            int maxLevel) {

        return createBot(
                pos,
                map,
                baseClass,
                minLevel,
                maxLevel,
                0
        );
    }

    /**
     * Creates and registers an artificial player.
     *
     * forcedJobId > 0 pins the exact job.
     * forcedJobId == 0 allows normal/random job selection.
     */
    public static int createBot(
            Point pos,
            MapleMap map,
            int baseClass,
            int minLevel,
            int maxLevel,
            int forcedJobId) {

        final int baseCharacterId = 2;

        if (map == null) {
            throw new IllegalArgumentException(
                    "Cannot create bot on a null map."
            );
        }

        Character bot;

        try {
            bot = Character.loadCharFromDB(
                    baseCharacterId,
                    getBotClient(),
                    false
            );
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Unable to load base Character for bot creation.",
                    e
            );
        }

        if (bot == null) {
            throw new IllegalStateException(
                    "Character.loadCharFromDB returned null for bot creation."
            );
        }

        /*
         * Atomic bot ID allocation.
         */
        final int botId =
                SoloMaplingConstants.GameConstants.BOT_BASE_ID
                        + currentBotCount.getAndIncrement();

        bot = setBotStats(
                bot,
                botId
        );

        /*
         * Register before putting the bot onto the map.
         */
        addBotToServer(bot);

        placeBotOnMap(
                bot,
                pos,
                map
        );

        /*
         * Decorate before the spawn choreography.
         */
        if (baseClass <= 0) {
            BotDecorate.setBotVariables(bot);
        } else {
            BotDecorate.setBotVariables(
                    bot,
                    baseClass,
                    minLevel,
                    maxLevel,
                    forcedJobId
            );
        }

        /*
         * Spawn choreography runs asynchronously.
         */
        final Character finalBot = bot;

        runAsync(() ->
                playSpawnChoreography(finalBot)
        );

        return botId;
    }

    /**
     * Places the bot on a map and performs the normal spawn
     * choreography synchronously.
     */
    public static void warpBotToLocation(
            Character fakechar,
            Point pos,
            MapleMap map) {

        if (fakechar == null) {
            return;
        }

        placeBotOnMap(
                fakechar,
                pos,
                map
        );

        playSpawnChoreography(fakechar);
    }

    /**
     * Immediately moves an existing bot to another map.
     *
     * This is intentionally different from warpBotToLocation().
     *
     * Expedition/boss-room warps should NOT run the normal
     * portal/drop-down spawn choreography.
     *
     * The important sequence is:
     *
     * 1. Remove bot from its old map.
     * 2. Update server-side map state.
     * 3. Set position/stance.
     * 4. Add bot to destination map.
     */
    public static void warpBotDirect(
            Character bot,
            Point position,
            MapleMap destination) {

        if (bot == null) {
            throw new IllegalArgumentException(
                    "Cannot warp a null bot."
            );
        }

        if (!BotHelpers.isBot(bot)) {
            throw new IllegalArgumentException(
                    "warpBotDirect() can only be used with bots. id="
                            + bot.getId()
            );
        }

        if (destination == null) {
            throw new IllegalArgumentException(
                    "Cannot warp bot to a null map. id="
                            + bot.getId()
            );
        }

        if (position == null) {
            position = new Point(0, 0);
        }

        MapleMap oldMap = bot.getMap();

        /*
         * Remove the bot from the old map.
         *
         * This is deliberately done even if oldMap == destination.
         * Otherwise MapleMap may already contain the bot and addPlayer()
         * can leave duplicate/inconsistent map state.
         */
        if (oldMap != null) {
            oldMap.removePlayer(bot);
        }

        /*
         * Update server-side map state.
         */
        bot.setMap(destination);
        bot.setPosition(new Point(position));
        bot.setStance(5);

        /*
         * Insert into the destination map.
         */
        destination.addPlayer(bot);

        System.out.println(
                "[BOT WARP] "
                        + bot.getName()
                        + " ("
                        + bot.getId()
                        + ") "
                        + (oldMap == null
                        ? "null"
                        : oldMap.getId())
                        + " -> "
                        + destination.getId()
                        + " @ "
                        + position
        );
    }

    /**
     * Places a bot on a map.
     *
     * This is the ONE and ONLY placeBotOnMap() implementation.
     */
    private static void placeBotOnMap(
            Character fakechar,
            Point pos,
            MapleMap map) {

        if (fakechar == null) {
            throw new IllegalArgumentException(
                    "Cannot place a null bot on a map."
            );
        }

        if (map == null) {
            throw new IllegalArgumentException(
                    "Cannot place bot on a null map."
            );
        }

        Point spawnPosition =
                pos != null
                        ? new Point(pos)
                        : new Point(0, 0);

        MapleMap oldMap = fakechar.getMap();

        /*
         * Always remove the bot from its current map before adding it
         * to the new map.
         *
         * The old implementation only removed the bot when:
         *
         *     fakechar.getMap() == map
         *
         * which meant moving a bot from Map A -> Map B could leave
         * the bot registered inside Map A.
         */
        if (oldMap != null) {
            oldMap.removePlayer(fakechar);
        }

        fakechar.setMap(map);
        fakechar.setPosition(spawnPosition);
        fakechar.setStance(5);

        map.addPlayer(fakechar);
    }

    /**
     * Plays the normal spawn arrival choreography.
     */
    private static void playSpawnChoreography(
            Character fakechar) {

        if (fakechar == null) {
            return;
        }

        long dropDelayMs =
                ThreadLocalRandom.current()
                        .nextLong(500, 1201);

        if (!BotHelpers.blockingSleep(dropDelayMs)) {
            return;
        }

        botEnterPortalDropDown(fakechar);

        /*
         * Bots spawn facing right by default.
         */
        if (ThreadLocalRandom.current().nextBoolean()) {

            long turnDelayMs =
                    ThreadLocalRandom.current()
                            .nextLong(1000, 1501);

            if (!BotHelpers.blockingSleep(turnDelayMs)) {
                return;
            }

            microTurnAroundToLeft(fakechar);
        }
    }

    /**
     * Configures the persistent console bot.
     */
    private static Character setConsoleBot(
            Character baseChr,
            int botId) {

        if (baseChr == null) {
            throw new IllegalArgumentException(
                    "Cannot configure a null console bot."
            );
        }

        Character onDemandBot = baseChr;

        onDemandBot.setClient(getBotClient());
        onDemandBot.setName("Console");
        onDemandBot.setID(botId);
        onDemandBot.setFame(botId);
        onDemandBot.setLevel(69);
        onDemandBot.setJob(Job.getById(420));

        return onDemandBot;
    }

    /**
     * Configures a newly created artificial player.
     */
    private static Character setBotStats(
            Character baseChr,
            int botId) {

        if (baseChr == null) {
            throw new IllegalArgumentException(
                    "Cannot configure a null bot."
            );
        }

        Character onDemandBot = baseChr;

        onDemandBot.setClient(getBotClient());
        onDemandBot.setName(getRandomCharacterIGN());
        onDemandBot.setID(botId);
        onDemandBot.setFame(botId);

        return onDemandBot;
    }

    /**
     * Removes a bot from the server.
     */
    public static void removeBotFromServer(
            Character fakechar) {

        if (fakechar == null) {
            return;
        }

        final int botId = fakechar.getId();

        /*
         * Remove from map first.
         */
        if (fakechar.getMap() != null) {
            fakechar.getMap().removePlayer(fakechar);
        }

        /*
         * Remove from channel storage.
         */
        channel.removePlayer(fakechar);

        /*
         * Remove from world storage.
         */
        world.getPlayerStorage()
                .removePlayer(botId);

        /*
         * Clear bot-specific auxiliary state.
         */
        CharacterStorage.removeActiveBot(botId);
        BotBuffDriver.clearBot(botId);
        BotBuffRequestHandler.clearBot(botId);
    }

    /**
     * Registers a bot with both channel and world storage.
     */
    private static void addBotToServer(
            Character fakechar) {

        if (fakechar == null) {
            throw new IllegalArgumentException(
                    "Cannot register a null bot."
            );
        }

        final int botId = fakechar.getId();

        /*
         * Channel.addPlayer() registers the bot with channel storage.
         */
        Character existingChannelBot =
                channel.getPlayerStorage()
                        .getCharacterById(botId);

        if (existingChannelBot == null) {
            channel.addPlayer(fakechar);
        }

        /*
         * World storage is separate.
         */
        Character existingWorldBot =
                world.getPlayerStorage()
                        .getCharacterById(botId);

        if (existingWorldBot == null) {
            world.getPlayerStorage()
                    .addPlayer(fakechar);
        }

        /*
         * Verify channel registration.
         */
        Character channelBot =
                channel.getPlayerStorage()
                        .getCharacterById(botId);

        if (channelBot == null) {
            throw new IllegalStateException(
                    "Bot channel registration failed: id="
                            + botId
                            + ", name="
                            + fakechar.getName()
            );
        }

        /*
         * Verify world registration.
         */
        Character worldBot =
                world.getPlayerStorage()
                        .getCharacterById(botId);

        if (worldBot == null) {
            throw new IllegalStateException(
                    "Bot world registration failed: id="
                            + botId
                            + ", name="
                            + fakechar.getName()
            );
        }

        /*
         * Both storage systems should reference the same Character
         * instance.
         */
        if (channelBot != fakechar) {
            throw new IllegalStateException(
                    "Channel storage contains a different Character "
                            + "instance for bot id="
                            + botId
            );
        }

        if (worldBot != fakechar) {
            throw new IllegalStateException(
                    "World storage contains a different Character "
                            + "instance for bot id="
                            + botId
            );
        }
    }

    /**
     * Spawns a bot in the Free Market.
     */
    public static void spawnBotFm(
            Character fakechar,
            Point pt) {

        int fmMapId = 910000000;

        MapleMap spawnMap =
                getBotClient()
                        .getChannelServer()
                        .getMapFactory()
                        .getMap(fmMapId);

        warpBotDirect(
                fakechar,
                pt,
                spawnMap
        );
    }

    /**
     * Creates a bot on demand and waits for it to become retrievable.
     */
    public static Character createBotPollReadiness(
            Point position,
            int mapId) {

        MapleMap map =
                getMapleMapById(mapId);

        if (map == null) {
            System.err.println(
                    "Unable to create bot: map "
                            + mapId
                            + " does not exist."
            );

            return null;
        }

        int botId =
                BotGeneration.createBot(
                        position,
                        map
                );

        for (int i = 0; i < 30; i++) {

            /*
             * Centralized lookup:
             *
             * world storage -> channel storage -> bot repair.
             */
            Character fakechar =
                    BotHelpers.getBotById(botId);

            if (fakechar != null) {

                if (i > 0) {
                    debugprint(
                            "Bot "
                                    + botId
                                    + " ready after "
                                    + (i * 100)
                                    + "ms"
                    );
                }

                return fakechar;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        System.err.println(
                "Bot "
                        + botId
                        + " not found in server storage after 3000ms."
        );

        return null;
    }
}