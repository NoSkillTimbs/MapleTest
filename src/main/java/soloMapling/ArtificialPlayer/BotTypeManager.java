package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import net.server.Server;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.Blackjack.BlackjackDealerBot;
import soloMapling.ArtificialPlayer.BotTypes.DiceBot;
import soloMapling.ArtificialPlayer.BotTypes.FMBot;
import soloMapling.ArtificialPlayer.BotTypes.GachaBot;
import soloMapling.ArtificialPlayer.BotTypes.HenesysBot;
import soloMapling.ArtificialPlayer.BotTypes.HenesysJQBot;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.ScrollingBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.TutorialBot;
import soloMapling.ArtificialPlayer.BotTypes.GameZoneHostBot;
import soloMapling.ArtificialPlayer.BotTypes.DropGameBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TestAttackBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.ZakumBot;
import soloMapling.ArtificialPlayer.BotSM;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.FM_BOT;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.DebugUtilities.fmt;
import static soloMapling.server.ExecutorServiceManager.runAsync;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;

/*
 * Handle bot types, setting them, activating, stopping bots.
 * Also include commands for mass bot commands.
 */
public class BotTypeManager {

    private static final int ZAKUM_DOOR_MAP = 211042300;

    /*
     * Safe default position inside The Door to Zakum.
     *
     * If your map has a preferred foothold/spawn point, replace these
     * coordinates with that location.
     */
    private static final Point ZAKUM_SPAWN_POINT = new Point(0, 0);

    public enum BotType {

        DICE_BOT {
            @Override
            public void createAndSetBot(Character character) {
                DiceBot diceBot = new DiceBot(character);
                CharacterStorage.addActiveBot(character.getId(), diceBot);
            }
        },

        TUTORIAL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                TutorialBot tutBot = new TutorialBot(character);
                CharacterStorage.addActiveBot(character.getId(), tutBot);
            }
        },

        FM_BOT {
            @Override
            public void createAndSetBot(Character character) {
                FMBot fmBot = new FMBot(character);
                CharacterStorage.addActiveBot(character.getId(), fmBot);
            }
        },

        SCROLL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                ScrollingBot scrollBot = new ScrollingBot(character);
                CharacterStorage.addActiveBot(character.getId(), scrollBot);
            }
        },

        SELLING_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                SellingMerchantBot bot = new SellingMerchantBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        BUYING_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                BuyingMerchantBot bot = new BuyingMerchantBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        NX_MERCHANT_BOT {
            @Override
            public void createAndSetBot(Character character) {
                NXMerchantBot bot = new NXMerchantBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        GACHA_BOT {
            @Override
            public void createAndSetBot(Character character) {
                GachaBot gachaBot = new GachaBot(character);
                CharacterStorage.addActiveBot(character.getId(), gachaBot);
            }
        },

        HENESYS_BOT {
            @Override
            public void createAndSetBot(Character character) {
                HenesysBot henesysBot = new HenesysBot(character);
                CharacterStorage.addActiveBot(character.getId(), henesysBot);
            }
        },

        HENESYS_JQ_BOT {
            @Override
            public void createAndSetBot(Character character) {
                HenesysJQBot jqBot = new HenesysJQBot(character);
                CharacterStorage.addActiveBot(character.getId(), jqBot);
            }
        },

        GAME_ZONE_HOST_BOT {
            @Override
            public void createAndSetBot(Character character) {
                GameZoneHostBot bot = new GameZoneHostBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        BLACKJACK_DEALER {
            @Override
            public void createAndSetBot(Character character) {
                BlackjackDealerBot bjBot = new BlackjackDealerBot(character);
                CharacterStorage.addActiveBot(character.getId(), bjBot);
            }
        },

        DROP_GAME_BOT {
            @Override
            public void createAndSetBot(Character character) {
                DropGameBot bot = new DropGameBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        OPQ_BOT {
            @Override
            public void createAndSetBot(Character character) {
                OPQBot opqBot = new OPQBot(character);
                CharacterStorage.addActiveBot(character.getId(), opqBot);
            }
        },

        SOCIAL_BOT {
            @Override
            public void createAndSetBot(Character character) {
                SocialBot socialBot = new SocialBot(character);
                CharacterStorage.addActiveBot(character.getId(), socialBot);
            }
        },

        TOWN_WANDERER_BOT {
            @Override
            public void createAndSetBot(Character character) {
                TownWandererBot bot = new TownWandererBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        TEST_ATTACK_BOT {
            @Override
            public void createAndSetBot(Character character) {
                TestAttackBot bot = new TestAttackBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        TRAINING_BOT {
            @Override
            public void createAndSetBot(Character character) {
                TrainingBot bot = new TrainingBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }
        },

        FOLLOWER_BOT {
            @Override
            public void createAndSetBot(Character character) {
                FollowerBot bot = new FollowerBot(character);
                CharacterStorage.addActiveBot(character.getId(), bot);
            }

        },
        ZAKUM_BOT {
            @Override
            public void createAndSetBot(Character character) {
                ZakumBot zakumBot = new ZakumBot(character);
                CharacterStorage.addActiveBot(character.getId(), zakumBot);
            }
        };

        public abstract void createAndSetBot(Character character);
    }

    public static void manuallyStartBot(Character fakechar) {
        if (fakechar == null) {
            debugprint("manuallyStartBot: character is null.");
            return;
        }

        BotSM bot = getBotById(fakechar.getId());

        if (bot == null) {
            debugprint("manuallyStartBot: no BotSM found for "
                    + fakechar.getName()
                    + " id=" + fakechar.getId());
            return;
        }

        if (bot.getRunning()) {
            return;
        }

        bot.setRunning(true);

        long initialDelay =
                BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS
                        + ThreadLocalRandom.current().nextLong(0, 3000);

        bot.startScheduledTask(initialDelay);
    }

    public static void manuallyStopBot(Character fakechar) {
        if (fakechar == null) {
            return;
        }

        BotSM bot = getBotById(fakechar.getId());

        if (bot == null) {
            return;
        }

        bot.setRunning(false);
        bot.stopScheduledTask();
    }

    public static void startAttackTestBot(Character fakechar) {
        BotSM bot = getBotById(fakechar.getId());

        if (bot == null || bot.getRunning()) {
            return;
        }

        bot.setRunning(true);
        bot.startScheduledTask(2000L);
    },

    // Re-type a live bot in place.
    public static boolean convertBotType(
            Character fakechar,
            BotType botType) {

        if (fakechar == null || botType == null) {
            return false;
        }

        BotSM existing = getBotById(fakechar.getId());

        if (existing != null) {
            if (existing.getState() == BotSM.BotState.TRADING) {
                debugprint(
                        "convertBotType: refused, bot is mid-trade: "
                                + fakechar.getName()
                );
                return false;
            }

            manuallyStopBot(fakechar);
        }

        botType.createAndSetBot(fakechar);
        manuallyStartBot(fakechar);

        return true;
    }
    public static boolean convertBotType(Character fakechar, BotType botType) {
        BotSM existing = getBotById(fakechar.getId());

        if (existing != null) {
            if (existing.getState() == BotSM.BotState.TRADING) {
                debugprint("convertBotType: refused, bot is mid-trade: " + fakechar.getName());
                return false;
            }

            manuallyStopBot(fakechar);
        }

        botType.createAndSetBot(fakechar);
        manuallyStartBot(fakechar);

        return true;
    }

    public static void massCreateBots(
            Integer start,
            Integer end,
            Client c) {

        for (int x = start; x < end; x++) {
            MapleMap map =
                    getMapleMapById(c.getPlayer().getMapId());

            Point pos =
                    c.getPlayer().getPosition();

            BotGeneration.createBot(pos, map);

            BotHelpers.blockingSleep(50);
        }
    }

    public static void createBots(Client c) {
        MapleMap map =
                Server.getInstance()
                        .getChannel(0, 1)
                        .getMapFactory()
                        .getMap(c.getPlayer().getMapId());

        Point pos =
                c.getPlayer().getPosition();

        runAsync(() ->
                BotGeneration.createBot(pos, map)
        );
    }

    /*
     * Creates ONE ZakumBot and puts it directly into
     * The Door to Zakum (211042300).
     */
    public static void createZakumBot(Client c) {

        MapleMap map =
                Server.getInstance()
                        .getChannel(0, 1)
                        .getMapFactory()
                        .getMap(ZAKUM_DOOR_MAP);

        if (map == null) {
            debugprint(
                    "createZakumBot: map "
                            + ZAKUM_DOOR_MAP
                            + " does not exist."
            );
            return;
        }

        runAsync(() -> {

            int botId =
                    BotGeneration.createBot(
                            ZAKUM_SPAWN_POINT,
                            map
                    );

            Character fakechar =
                    BotHelpers.getCharFromChannelStorage(botId);

            if (fakechar == null) {
                debugprint(
                        "createZakumBot: failed to retrieve bot "
                                + botId
                );
                return;
            }

            /*
             * The Character already exists on 211042300.
             * Now attach the Zakum FSM.
             */
            ZAKUM_BOT.createAndSetBot(fakechar);

            /*
             * Start the FSM after spawn choreography.
             */
            manuallyStartBot(fakechar);

            debugprint(
                    "Created ZakumBot "
                            + fakechar.getName()
                            + " id="
                            + fakechar.getId()
                            + " map="
                            + fakechar.getMapId()
            );
        });
    }

    /*
     * Creates multiple ZakumBots.
     *
     * Every bot:
     *
     *   1. is created
     *   2. is placed on 211042300
     *   3. is wrapped as a ZakumBot
     *   4. has its FSM started
     *   5. waits for a party invitation
     */
    public static void createZakumBots(int count) {

        if (count <= 0) {
            debugprint(
                    "createZakumBots: count must be greater than zero."
            );
            return;
        }

        MapleMap map =
                Server.getInstance()
                        .getChannel(0, 1)
                        .getMapFactory()
                        .getMap(ZAKUM_DOOR_MAP);

        if (map == null) {
            debugprint(
                    "createZakumBots: map "
                            + ZAKUM_DOOR_MAP
                            + " not found."
            );
            return;
        }

        runAsync(() -> {

            for (int i = 0; i < count; i++) {

                int botId =
                        BotGeneration.createBot(
                                ZAKUM_SPAWN_POINT,
                                map
                        );

                Character fakechar =
                        BotHelpers.getCharFromChannelStorage(botId);

                if (fakechar == null) {
                    debugprint(
                            "createZakumBots: failed to retrieve bot "
                                    + botId
                    );
                    continue;
                }

                ZAKUM_BOT.createAndSetBot(fakechar);

                manuallyStartBot(fakechar);

                debugprint(
                        "ZakumBot created: "
                                + fakechar.getName()
                                + " id="
                                + fakechar.getId()
                                + " map="
                                + fakechar.getMapId()
                );

                /*
                 * Small stagger between bot creation.
                 * BotGeneration itself handles the visual
                 * spawn choreography asynchronously.
                 */
                BotHelpers.blockingSleep(250);
            }
        });
    }

    public static void massFMBots(
            Integer start,
            Integer end) {

        for (int x = start; x <= end; x++) {

            Character fakechar =
                    BotHelpers.getCharFromChannelStorage(x);

            if (fakechar == null) {
                continue;
            }

            FM_BOT.createAndSetBot(fakechar);
        }
    }

    public static void massManualStart(
            Integer start,
            Integer end) {

        for (int x = start; x <= end; x++) {

            Character fakechar =
                    BotHelpers.getCharFromChannelStorage(x);

            if (fakechar == null) {
                continue;
            }

            manuallyStartBot(fakechar);
        }
    }

    public static void massManualStop(
            Integer start,
            Integer end) {

        for (int x = start; x <= end; x++) {

            Character fakechar =
                    BotHelpers.getCharFromChannelStorage(x);

            if (fakechar == null) {
                continue;
            }

            manuallyStopBot(fakechar);

            BotHelpers.blockingSleep(150);
        }
    }

    public static void setBotTypes(
            List<Integer> botIds,
            BotType botType) {

        for (Integer id : botIds) {

            Character fakechar =
                    getValidBot(id);

            if (fakechar == null) {
                continue;
            }

            botType.createAndSetBot(fakechar);
        }
    }

    public static void startBots(List<Integer> botIds) {

        for (Integer id : botIds) {

            Character fakechar =
                    getValidBot(id);

            if (fakechar == null) {
                continue;
            }

            manuallyStartBot(fakechar);
        }
    }

    public static void stopBots(List<Integer> botIds) {

        for (Integer id : botIds) {

            Character fakechar =
                    getValidBot(id);

            if (fakechar == null) {
                continue;
            }

            manuallyStopBot(fakechar);
        }
    }

    public static void setAndStartBots(
            List<Integer> botIds,
            BotType botType) {

        debugprint(
                fmt(
                        "Setting and starting bots to {}. {}",
                        botType,
                        botIds
                )
        );

        for (Integer id : botIds) {

            Character fakechar =
                    getValidBot(id);

            if (fakechar == null) {
                continue;
            }

            botType.createAndSetBot(fakechar);
            manuallyStartBot(fakechar);
        }
    }

    private static Character getValidBot(Integer id) {

        if (id == null) {
            debugprint(
                    "This integer is null. Cannot get valid bot"
            );
            return null;
        }

        Character fakechar =
                BotHelpers.getCharFromChannelStorage(id);

        if (fakechar == null) {
            debugprint(
                    fmt(
                            "Failed to get bot: {}",
                            id
                    )
            );
        }

        return fakechar;
    }
}
```
