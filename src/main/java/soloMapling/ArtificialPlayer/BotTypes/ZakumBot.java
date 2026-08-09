package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.MobId;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotDialogueHandler.DialogueConstructor;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.DialogueContextResolver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;
import static soloMapling.server.SoloMaplingUtilities.random;

public class ZakumBot extends BotSM {

    private static final int ZAKUM_MIN_LEVEL = 100;
    private static final int ZAKUM_MAX_LEVEL = 150;

    private static final int ZAKUM_DOOR_MAP = 211042300;
    private static final int ZAKUM_ALTAR_ENTRANCE_MAP = 211042400;
    private static final int ZAKUM_BOSS_MAP = 280030000;

    private static final int ZAKUM_PART_COUNT = 11;

    private static final int EYE_OF_FIRE_ITEM_ID = 4001017;
    private static final int EYE_OF_FIRE_COUNT = 5;

    private static final long COMBAT_TICK_MS = 250;
    private static final long BOSS_SPAWN_GRACE_MS = 10_000;
    private static final long TRAVEL_TIMEOUT_MS = 120_000;
    private static final long EXPEDITION_CHECK_INTERVAL_MS = 2_000;

    private static final long RECRUIT_DIALOGUE_INTERVAL_MS = 15_000;
    private static final long COMBAT_DIALOGUE_INTERVAL_MS = 8_000;
    private static final long PLAYER_REACTION_INTERVAL_MS = 4_000;

    private static final double PLAYER_CONTEXT_CHANCE = 0.10;
    private static final int CONTEXT_REROLLS = 6;

    /*
     * BotWanderSystem is intentionally accessed through reflection.
     *
     * The actual wander API was not part of the ZakumBot source, so this
     * avoids hard-coding an unknown method signature.
     *
     * Supported method names:
     *
     * Start:
     *   start
     *   startWandering
     *   startWander
     *   wander
     *   enable
     *
     * Stop:
     *   stop
     *   stopWandering
     *   stopWander
     *   disable
     *   cancel
     */
    private static final String WANDER_SYSTEM_CLASS =
            "soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem";

    private static final String[] WANDER_START_METHODS = {
            "start",
            "startWandering",
            "startWander",
            "wander",
            "enable"
    };

    private static final String[] WANDER_STOP_METHODS = {
            "stop",
            "stopWandering",
            "stopWander",
            "disable",
            "cancel"
    };

    private static volatile Class<?> wanderSystemClass;
    private static volatile boolean wanderSystemResolved = false;

    private static final Set<ZakumBot> ACTIVE_ZAKUM_BOTS =
            ConcurrentHashMap.newKeySet();

    private static volatile boolean combatTickerStarted = false;

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted) {
            return;
        }

        combatTickerStarted = true;

        ExecutorServiceManager
                .getScheduledExecutorService()
                .scheduleAtFixedRate(
                        ZakumBot::combatTickAll,
                        COMBAT_TICK_MS,
                        COMBAT_TICK_MS,
                        TimeUnit.MILLISECONDS
                );
    }

    private static void combatTickAll() {
        for (ZakumBot bot : ACTIVE_ZAKUM_BOTS) {
            try {
                bot.combatTick();
            } catch (Throwable t) {
                bot.debug(
                        "Combat ticker error: "
                                + t.getMessage()
                );
            }
        }
    }

    public static int activeGrinderCount() {
        return ACTIVE_ZAKUM_BOTS.size();
    }

    private enum Phase {
        INIT,
        WAITING_FOR_PARTY,
        PARTY_JOINED,
        WAITING_FOR_EXPEDITION,
        TRAVELING_TO_ZAKUM,
        FIGHTING_ZAKUM,
        FINISHED
    }

    private volatile Phase phase = Phase.INIT;

    private Expedition zakumExpedition;

    private boolean expeditionRegistered = false;
    private boolean zakumItemsGranted = false;

    private long nextExpeditionCheckMs = 0L;

    private volatile boolean travelDone = false;
    private volatile boolean travelSucceeded = false;

    private boolean travelStarted = false;
    private long travelDeadlineMs = 0L;

    private long bossSpawnGraceUntilMs = 0L;

    /*
     * Dialogue timers.
     */
    private long nextRecruitDialogueMs = 0L;
    private long nextCombatDialogueMs = 0L;
    private long nextPlayerReactionMs = 0L;

    /*
     * Party HP tracking.
     */
    private final Map<Integer, Integer> previousPartyHp =
            new ConcurrentHashMap<>();

    /*
     * Players already detected as dead.
     */
    private final Set<Integer> knownDeadPlayerIds =
            ConcurrentHashMap.newKeySet();

    /*
     * Prevents repeatedly starting the wander system on every update tick.
     */
    private volatile boolean wandering = false;

    public ZakumBot(Character character) {
        super(character);

        botType = "ZakumBot";
        dialoguePath = "ZakumBot.yaml";
    }

    @Override
    public void updateState() {
        super.updateState();

        if (checkIfNotRunningOrPaused()) {
            return;
        }

        Character chr = getChr();

        if (chr == null || chr.getMap() == null) {
            return;
        }

        pollRecruitInvite();

        switch (phase) {
            case INIT -> doInit();
            case WAITING_FOR_PARTY -> doWaitingForParty();
            case PARTY_JOINED -> doPartyJoined();
            case WAITING_FOR_EXPEDITION -> doWaitingForExpedition();
            case TRAVELING_TO_ZAKUM -> doTravelToZakum();
            case FIGHTING_ZAKUM -> doFightingZakum();
            case FINISHED -> doFinished();
        }
    }

    /*
     * ---------------------------------------------------------------------
     * WANDERING
     * ---------------------------------------------------------------------
     */

    private void startWandering() {
        Character chr = getChr();

        if (chr == null || chr.getMap() == null) {
            return;
        }

        if (wandering) {
            return;
        }

        if (phase != Phase.WAITING_FOR_PARTY
                && phase != Phase.WAITING_FOR_EXPEDITION) {
            return;
        }

        if (expeditionRegistered) {
            return;
        }

        try {
            Class<?> clazz = getWanderSystemClass();

            if (clazz == null) {
                return;
            }

            boolean invoked = invokeWanderMethod(
                    clazz,
                    WANDER_START_METHODS,
                    chr
            );

            if (invoked) {
                wandering = true;

                debug(
                        "Wander system started."
                );
            }
        } catch (Throwable t) {
            debug(
                    "Unable to start wander system: "
                            + t.getMessage()
            );
        }
    }

    private void stopWandering() {
        Character chr = getChr();

        if (!wandering && chr == null) {
            return;
        }

        try {
            Class<?> clazz = getWanderSystemClass();

            if (clazz != null && chr != null) {
                invokeWanderMethod(
                        clazz,
                        WANDER_STOP_METHODS,
                        chr
                );
            }
        } catch (Throwable t) {
            debug(
                    "Unable to stop wander system: "
                            + t.getMessage()
            );
        } finally {
            wandering = false;
        }
    }

    private static Class<?> getWanderSystemClass() {
        if (wanderSystemResolved) {
            return wanderSystemClass;
        }

        synchronized (ZakumBot.class) {
            if (wanderSystemResolved) {
                return wanderSystemClass;
            }

            try {
                wanderSystemClass =
                        Class.forName(WANDER_SYSTEM_CLASS);
            } catch (Throwable ignored) {
                wanderSystemClass = null;
            }

            wanderSystemResolved = true;

            return wanderSystemClass;
        }
    }

    private boolean invokeWanderMethod(
            Class<?> clazz,
            String[] methodNames,
            Character chr) {

        for (String methodName : methodNames) {
            Method method =
                    findWanderMethod(
                            clazz,
                            methodName
                    );

            if (method == null) {
                continue;
            }

            try {
                method.setAccessible(true);

                Class<?>[] parameters =
                        method.getParameterTypes();

                if (parameters.length == 1
                        && parameters[0].isAssignableFrom(
                        chr.getClass())) {

                    method.invoke(null, chr);
                    return true;
                }

                if (parameters.length == 1
                        && parameters[0].isAssignableFrom(
                        Character.class)) {

                    method.invoke(null, chr);
                    return true;
                }

                /*
                 * Some systems expose instance methods.
                 *
                 * If the method is not static, do not attempt to
                 * construct the wander system because its constructor
                 * is unknown.
                 */
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
            } catch (Throwable t) {
                debug(
                        "Wander method failed: "
                                + methodName
                                + " error="
                                + t.getMessage()
                );
            }
        }

        return false;
    }

    private Method findWanderMethod(
            Class<?> clazz,
            String methodName) {

        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }

            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            Class<?>[] parameters =
                    method.getParameterTypes();

            if (parameters.length != 1) {
                continue;
            }

            if (!parameters[0].isAssignableFrom(
                    Character.class)) {
                continue;
            }

            return method;
        }

        return null;
    }

    /*
     * ---------------------------------------------------------------------
     * INITIALIZATION
     * ---------------------------------------------------------------------
     */

    private void doInit() {
        ensureCombatTicker();

        Character chr = getChr();

        if (chr == null) {
            return;
        }

        grantZakumItems();

        if (chr.getLevel() < ZAKUM_MIN_LEVEL
                || chr.getLevel() > ZAKUM_MAX_LEVEL) {

            debug(
                    "Outside Zakum level range: "
                            + chr.getLevel()
                            + " (allowed "
                            + ZAKUM_MIN_LEVEL
                            + "-"
                            + ZAKUM_MAX_LEVEL
                            + ")"
            );

            stopWandering();
            enterPhase(Phase.FINISHED);
            return;
        }

        if (isInZakumBossMap(chr)) {
            beginFighting();
            return;
        }

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            stopWandering();

            beginTravelTo(ZAKUM_DOOR_MAP);
            enterPhase(Phase.TRAVELING_TO_ZAKUM);
            return;
        }

        debug(
                "At Zakum door. Waiting for party invitation."
        );

        enterPhase(Phase.WAITING_FOR_PARTY);
    }

    private void grantZakumItems() {
        if (zakumItemsGranted) {
            return;
        }

        Character chr = getChr();

        if (chr == null || chr.getClient() == null) {
            return;
        }

        try {
            int currentQuantity =
                    chr.getItemQuantity(
                            EYE_OF_FIRE_ITEM_ID,
                            false
                    );

            int missing =
                    EYE_OF_FIRE_COUNT
                            - currentQuantity;

            if (missing <= 0) {
                zakumItemsGranted = true;

                debug(
                        "Already has "
                                + currentQuantity
                                + "x Eye of Fire."
                );

                return;
            }

            boolean added =
                    InventoryManipulator.addById(
                            chr.getClient(),
                            EYE_OF_FIRE_ITEM_ID,
                            (short) missing
                    );

            if (added) {
                zakumItemsGranted = true;

                debug(
                        "Granted "
                                + missing
                                + "x Eye of Fire ("
                                + EYE_OF_FIRE_ITEM_ID
                                + "). Total required="
                                + EYE_OF_FIRE_COUNT
                );
            } else {
                debug(
                        "Failed to grant "
                                + missing
                                + "x Eye of Fire."
                );
            }
        } catch (Throwable t) {
            debug(
                    "Exception while granting Eye of Fire: "
                            + t.getMessage()
            );
        }
    }

    /*
     * ---------------------------------------------------------------------
     * PARTY WAITING
     * ---------------------------------------------------------------------
     */

    private void doWaitingForParty() {
        Character chr = getChr();

        if (chr == null) {
            return;
        }

        if (chr.getLevel() < ZAKUM_MIN_LEVEL
                || chr.getLevel() > ZAKUM_MAX_LEVEL) {

            stopWandering();

            debug(
                    "Zakum bot no longer within level range."
            );

            enterPhase(Phase.FINISHED);
            return;
        }

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            stopWandering();

            debug(
                    "Zakum bot is not at the Door to Zakum. "
                            + "Returning."
            );

            beginTravelTo(ZAKUM_DOOR_MAP);
            enterPhase(Phase.TRAVELING_TO_ZAKUM);

            return;
        }

        /*
         * This is the important change:
         *
         * While waiting for a real player to recruit the bot,
         * the bot actively wanders instead of standing still.
         */
        startWandering();

        long now = now();

        if (now >= nextRecruitDialogueMs) {
            sayDialogue(
                    "PartyInviteWait",
                    null,
                    PLAYER_CONTEXT_CHANCE
            );

            nextRecruitDialogueMs =
                    now + RECRUIT_DIALOGUE_INTERVAL_MS;
        }

        /*
         * If an invite arrived during this update cycle,
         * immediately stop wandering.
         */
        if (chr.getParty() != null) {
            stopWandering();

            debug(
                    "Zakum bot joined party "
                            + chr.getParty().getId()
            );

            enterPhase(Phase.PARTY_JOINED);
        }
    }

    private void doPartyJoined() {
        Character chr = getChr();

        if (chr == null) {
            return;
        }

        stopWandering();

        if (chr.getParty() == null) {
            cleanupExpeditionState();

            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader =
                getRealPartyLeader();

        if (leader == null) {
            debug(
                    "Party joined, but real party leader "
                            + "is unavailable."
            );

            return;
        }

        debug(
                "Party joined. Following leader "
                        + leader.getName()
                        + " to Zakum altar entrance."
        );

        enterPhase(
                Phase.WAITING_FOR_EXPEDITION
        );
    }

    private Character findRealPartyMember() {
        Character chr = getChr();

        if (chr == null || chr.getParty() == null) {
            return null;
        }

        Party party = chr.getParty();

        for (PartyCharacter pc :
                party.getMembers()) {

            if (pc == null) {
                continue;
            }

            Character player =
                    pc.getPlayer();

            if (player == null
                    || player.getId()
                    == chr.getId()
                    || isBot(player)
                    || !player.isLoggedinWorld()) {
                continue;
            }

            return player;
        }

        return null;
    }

    private Character getRealPartyLeader() {
        Character chr = getChr();

        if (chr == null || chr.getParty() == null) {
            return null;
        }

        PartyCharacter leaderPc =
                chr.getParty().getLeader();

        if (leaderPc == null) {
            return null;
        }

        Character leader =
                leaderPc.getPlayer();

        if (leader == null
                || leader.getId()
                == chr.getId()
                || isBot(leader)
                || !leader.isLoggedinWorld()) {
            return null;
        }

        return leader;
    }

    /*
     * ---------------------------------------------------------------------
     * EXPEDITION
     * ---------------------------------------------------------------------
     */

    private void doWaitingForExpedition() {
        Character chr = getChr();

        if (chr == null
                || chr.getMap() == null) {
            return;
        }

        if (expeditionRegistered) {
            stopWandering();

            if (isInZakumBossMap(chr)) {
                beginFighting();
                return;
            }

            /*
             * Once registered, the expedition owns the warp.
             * Do not wander and do not initiate another travel.
             */
            if (chr.getMapId()
                    == ZAKUM_ALTAR_ENTRANCE_MAP) {
                return;
            }

            debug(
                    "Already registered for Zakum expedition; "
                            + "waiting for expedition warp. map="
                            + chr.getMapId()
            );

            return;
        }

        if (chr.getParty() == null) {
            stopWandering();

            cleanupExpeditionState();

            enterPhase(
                    Phase.WAITING_FOR_PARTY
            );

            return;
        }

        Character leader =
                getRealPartyLeader();

        if (leader == null) {
            /*
             * No valid leader currently available.
             * The bot can idle/wander safely.
             */
            startWandering();
            return;
        }

        if (chr.getMapId()
                != ZAKUM_ALTAR_ENTRANCE_MAP) {

            stopWandering();

            debug(
                    "Following leader "
                            + leader.getName()
                            + " to Zakum altar entrance."
            );

            beginTravelTo(
                    ZAKUM_ALTAR_ENTRANCE_MAP
            );

            enterPhase(
                    Phase.TRAVELING_TO_ZAKUM
            );

            return;
        }

        /*
         * At the altar, waiting for expedition registration.
         * Wandering is allowed here until registration succeeds.
         */
        startWandering();

        long now = now();

        if (now < nextExpeditionCheckMs) {
            return;
        }

        nextExpeditionCheckMs =
                now + EXPEDITION_CHECK_INTERVAL_MS;

        if (leader.getClient() == null
                || leader.getClient()
                .getChannelServer() == null) {
            return;
        }

        Expedition expedition =
                leader.getClient()
                        .getChannelServer()
                        .getExpedition(
                                ExpeditionType.ZAKUM
                        );

        if (expedition == null) {
            debug(
                    "At Zakum altar; waiting for "
                            + "Zakum expedition."
            );

            return;
        }

        zakumExpedition =
                expedition;

        if (!expedition.isRegistering()) {
            debug(
                    "Zakum expedition exists but "
                            + "is no longer registering."
            );

            return;
        }

        if (expedition.contains(chr)) {
            stopWandering();

            expeditionRegistered = true;

            debug(
                    "Bot is already registered "
                            + "in Zakum expedition."
            );

            enterPhase(
                    Phase.TRAVELING_TO_ZAKUM
            );

            return;
        }

        int result =
                expedition.addMemberInt(chr);

        if (result == 0) {
            stopWandering();

            expeditionRegistered = true;

            sayDialogue(
                    "ExpeditionJoined",
                    leader,
                    PLAYER_CONTEXT_CHANCE
            );

            debug(
                    "Successfully joined Zakum expedition. "
                            + "Waiting for expedition start."
            );

            enterPhase(
                    Phase.TRAVELING_TO_ZAKUM
            );

            return;
        }

        debug(
                "Expedition registration failed. result="
                        + result
                        + " contains="
                        + expedition.contains(chr)
                        + " registering="
                        + expedition.isRegistering()
        );
    }

    /*
     * ---------------------------------------------------------------------
     * TRAVEL
     * ---------------------------------------------------------------------
     */

    private void doTravelToZakum() {
        Character chr = getChr();

        if (chr == null
                || chr.getMap() == null) {
            return;
        }

        stopWandering();

        /*
         * Once registered, the expedition controls the warp.
         *
         * Never check party membership here.
         * Never send the bot back to the altar.
         */
        if (expeditionRegistered) {
            if (isInZakumBossMap(chr)) {
                debug(
                        "Successfully entered Zakum boss map."
                );

                resetTravelState();
                beginFighting();

                return;
            }

            if (chr.getMapId()
                    == ZAKUM_ALTAR_ENTRANCE_MAP) {
                return;
            }

            debug(
                    "Expedition registered; waiting for "
                            + "expedition warp. Current map="
                            + chr.getMapId()
            );

            return;
        }

        if (chr.getParty() == null) {
            debug(
                    "Lost party before expedition registration."
            );

            cleanupExpeditionState();

            enterPhase(
                    Phase.WAITING_FOR_PARTY
            );

            return;
        }

        Character leader =
                getRealPartyLeader();

        if (leader == null) {
            debug(
                    "Lost real party leader while "
                            + "travelling to Zakum."
            );

            return;
        }

        if (travelDone) {
            if (travelSucceeded) {
                debug(
                        "Travel completed at map "
                                + chr.getMapId()
                );

                resetTravelState();

                if (chr.getMapId()
                        == ZAKUM_DOOR_MAP) {

                    enterPhase(
                            Phase.WAITING_FOR_PARTY
                    );

                    return;
                }

                if (chr.getMapId()
                        == ZAKUM_ALTAR_ENTRANCE_MAP) {

                    enterPhase(
                            Phase.WAITING_FOR_EXPEDITION
                    );

                    return;
                }

                debug(
                        "Travel succeeded but destination "
                                + "is unexpected: "
                                + chr.getMapId()
                );

                enterPhase(
                        Phase.WAITING_FOR_EXPEDITION
                );

                return;
            }

            debug(
                    "Zakum travel failed."
            );

            resetTravelState();

            if (chr.getMapId()
                    == ZAKUM_DOOR_MAP) {

                enterPhase(
                        Phase.WAITING_FOR_PARTY
                );
            } else {
                enterPhase(
                        Phase.WAITING_FOR_EXPEDITION
                );
            }

            return;
        }

        if (travelStarted
                && now() > travelDeadlineMs) {

            debug(
                    "Zakum travel timed out at map "
                            + chr.getMapId()
            );

            GCMovement.cancelTravel(chr);

            resetTravelState();

            if (chr.getMapId()
                    == ZAKUM_DOOR_MAP) {

                enterPhase(
                        Phase.WAITING_FOR_PARTY
                );
            } else {
                enterPhase(
                        Phase.WAITING_FOR_EXPEDITION
                );
            }
        }
    }

    private void beginTravelTo(
            int destinationMapId) {

        Character chr = getChr();

        if (chr == null
                || chr.getMap() == null) {
            return;
        }

        /*
         * Never allow wandering and GCMovement to operate
         * simultaneously.
         */
        stopWandering();

        if (chr.getMapId()
                == destinationMapId) {

            travelDone = true;
            travelSucceeded = true;

            return;
        }

        if (travelStarted) {
            return;
        }

        travelStarted = true;
        travelDone = false;
        travelSucceeded = false;

        travelDeadlineMs =
                now() + TRAVEL_TIMEOUT_MS;

        debug(
                "Travelling to map "
                        + destinationMapId
        );

        GCMovement.travel(
                chr,
                destinationMapId,
                new Consumer<Boolean>() {
                    @Override
                    public void accept(
                            Boolean success) {

                        travelSucceeded =
                                success != null
                                        && success;

                        travelDone = true;
                    }
                }
        );
    }

    private void resetTravelState() {
        travelStarted = false;
        travelDone = false;
        travelSucceeded = false;
        travelDeadlineMs = 0L;
    }

    /*
     * ---------------------------------------------------------------------
     * COMBAT
     * ---------------------------------------------------------------------
     */

    private void beginFighting() {
        Character chr = getChr();

        if (chr == null
                || !isInZakumBossMap(chr)) {
            return;
        }

        stopWandering();

        bossSpawnGraceUntilMs =
                now() + BOSS_SPAWN_GRACE_MS;

        ACTIVE_ZAKUM_BOTS.add(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();

        nextCombatDialogueMs = now();
        nextPlayerReactionMs = now();

        debug(
                "Entered Zakum boss map."
        );

        enterPhase(
                Phase.FIGHTING_ZAKUM
        );
    }

    private void doFightingZakum() {
        Character chr = getChr();

        if (chr == null) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            return;
        }

        stopWandering();

        if (!isInZakumBossMap(chr)) {
            ACTIVE_ZAKUM_BOTS.remove(this);

            debug(
                    "Left Zakum boss map."
            );

            enterPhase(
                    Phase.FINISHED
            );

            return;
        }

        if (now()
                < bossSpawnGraceUntilMs) {
            return;
        }

        if (zakumExpedition != null
                && zakumExpedition
                .getBossLogs()
                .size()
                >= ZAKUM_PART_COUNT) {

            debug(
                    "Zakum boss logs indicate completion."
            );

            ACTIVE_ZAKUM_BOTS.remove(this);

            enterPhase(
                    Phase.FINISHED
            );

            return;
        }

        if (!hasLivingZakumMob(chr)) {
            debug(
                    "No living Zakum parts remain."
            );

            ACTIVE_ZAKUM_BOTS.remove(this);

            enterPhase(
                    Phase.FINISHED
            );
        }
    }

    private void combatTick() {
        Character chr = getChr();

        if (chr == null) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            return;
        }

        if (phase != Phase.FIGHTING_ZAKUM) {
            return;
        }

        if (!isInZakumBossMap(chr)) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            return;
        }

        /*
         * Never allow wandering during combat.
         */
        stopWandering();

        /*
         * Detect player hits/deaths.
         */
        processPartyReactions();

        long now = now();

        if (now >= nextCombatDialogueMs) {
            Character player =
                    findRealPartyMember();

            sayDialogue(
                    "ZakumCombat",
                    player,
                    PLAYER_CONTEXT_CHANCE
            );

            nextCombatDialogueMs =
                    now + COMBAT_DIALOGUE_INTERVAL_MS;
        }

        /*
         * Attack independently of dialogue.
         */
        try {
            BotAttackDriver.botAttack(chr);
        } catch (Throwable ignored) {
        }
    }

    private void processPartyReactions() {
        Character chr = getChr();

        if (chr == null
                || chr.getParty() == null) {
            return;
        }

        long now = now();

        for (PartyCharacter pc :
                chr.getParty().getMembers()) {

            if (pc == null) {
                continue;
            }

            Character player =
                    pc.getPlayer();

            if (player == null
                    || player.getId()
                    == chr.getId()
                    || isBot(player)
                    || !player.isLoggedinWorld()) {
                continue;
            }

            if (player.getMapId()
                    != ZAKUM_BOSS_MAP) {
                continue;
            }

            int playerId =
                    player.getId();

            int currentHp =
                    player.getHp();

            Integer previousHp =
                    previousPartyHp.put(
                            playerId,
                            currentHp
                    );

            /*
             * Death takes priority.
             */
            if (!player.isAlive()) {
                if (knownDeadPlayerIds.add(
                        playerId)) {

                    sayDialogue(
                            "PlayerDeath",
                            player,
                            PLAYER_CONTEXT_CHANCE
                    );
                }

                continue;
            }

            /*
             * A positive HP decrease means the
             * player was hit.
             */
            if (previousHp != null
                    && previousHp > currentHp
                    && currentHp > 0
                    && !knownDeadPlayerIds
                    .contains(playerId)
                    && now >= nextPlayerReactionMs) {

                sayDialogue(
                        "PlayerHit",
                        player,
                        PLAYER_CONTEXT_CHANCE
                );

                nextPlayerReactionMs =
                        now + PLAYER_REACTION_INTERVAL_MS;
            }
        }
    }

    private boolean hasLivingZakumMob(
            Character chr) {

        if (chr == null
                || chr.getMap() == null) {
            return false;
        }

        for (Monster mob :
                chr.getMap().getAllMonsters()) {

            if (mob != null
                    && isZakumMob(
                    mob.getId())) {

                return true;
            }
        }

        return false;
    }

    private boolean isZakumMob(
            int mobId) {

        return mobId == MobId.ZAKUM_1
                || mobId == MobId.ZAKUM_2
                || mobId == MobId.ZAKUM_3
                || MobId.isZakumArm(mobId);
    }

    /*
     * ---------------------------------------------------------------------
     * PARTY INVITES
     * ---------------------------------------------------------------------
     */

    private void pollRecruitInvite() {
        Character chr = getChr();

        if (chr == null
                || chr.getParty() != null) {
            return;
        }

        BotPartyQueue.PartyInviteEntry entry =
                BotPartyQueue.getInstance()
                        .getPartyInvite(chr);

        if (entry == null) {
            return;
        }

        Character recruiter =
                entry.getInviter();

        if (recruiter == null) {
            debug(
                    "Zakum party invite has no recruiter: "
                            + chr.getName()
            );

            BotPartyQueue.getInstance()
                    .removePartyInvite(chr);

            return;
        }

        /*
         * Immediately stop wandering before accepting.
         */
        stopWandering();

        debug(
                "Zakum party invite received: bot="
                        + chr.getName()
                        + " recruiter="
                        + recruiter.getName()
                        + " partyId="
                        + entry.getPartyId()
        );

        boolean joined =
                BotPartyCommands
                        .botAcceptPartyInvite(chr);

        if (!joined) {
            debug(
                    "Zakum bot failed to join party: bot="
                            + chr.getName()
                            + " recruiter="
                            + recruiter.getName()
                            + " partyId="
                            + entry.getPartyId()
            );

            /*
             * If accepting failed, the bot may safely resume
             * wandering while waiting for another invite.
             */
            if (phase == Phase.WAITING_FOR_PARTY) {
                startWandering();
            }

            return;
        }

        debug(
                "Zakum bot joined party: bot="
                        + chr.getName()
                        + " recruiter="
                        + recruiter.getName()
                        + " partyId="
                        + entry.getPartyId()
        );

        sayDialogue(
                "PartyJoined",
                recruiter,
                PLAYER_CONTEXT_CHANCE
        );
    }

    /*
     * ---------------------------------------------------------------------
     * CLEANUP
     * ---------------------------------------------------------------------
     */

    private void doFinished() {
        stopWandering();

        ACTIVE_ZAKUM_BOTS.remove(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();
    }

    private void cleanupExpeditionState() {
        Character chr = getChr();

        ACTIVE_ZAKUM_BOTS.remove(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();

        stopWandering();

        if (chr != null) {
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
        }

        zakumExpedition = null;
        expeditionRegistered = false;

        resetTravelState();
    }

    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();

        ACTIVE_ZAKUM_BOTS.remove(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();

        stopWandering();

        if (chr != null) {
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            GCMovement.disable(chr);

            if (zakumExpedition != null
                    && zakumExpedition.isRegistering()
                    && zakumExpedition.contains(chr)) {

                zakumExpedition.removeMember(chr);
            }

            BotRecruitManager.clearArmed(
                    chr.getId()
            );
        }

        super.stopScheduledTask();

        log(
                "[ZakumBot] stopped: "
                        + (chr != null
                        ? chr.getName()
                        : "?")
        );
    }

    /*
     * ---------------------------------------------------------------------
     * DIALOGUE
     * ---------------------------------------------------------------------
     */

    private void sayDialogue(
            String node,
            Character player,
            double contextChance) {

        Character chr = getChr();

        if (chr == null
                || chr.getMap() == null) {

            debug(
                    "Dialogue aborted: bot/map missing. node="
                            + node
            );

            return;
        }

        try {
            DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(
                            dialoguePath,
                            botType,
                            node
                    );

            if (dialog == null
                    || dialog.getDialogue() == null
                    || dialog.getDialogue().isEmpty()) {

                debug(
                        "Dialogue node missing or empty: "
                                + node
                );

                return;
            }

            List<String> lines =
                    dialog.getDialogue();

            List<Integer> contextLines =
                    new ArrayList<>();

            List<Integer> plainLines =
                    new ArrayList<>();

            for (int i = 0;
                 i < lines.size();
                 i++) {

                String line =
                        lines.get(i);

                if (DialogueContextResolver
                        .hasTokens(line)) {

                    contextLines.add(i);
                } else {
                    plainLines.add(i);
                }
            }

            boolean preferContext =
                    player != null
                            && random.nextDouble()
                            < contextChance;

            List<Integer> first =
                    preferContext
                            ? contextLines
                            : plainLines;

            List<Integer> second =
                    preferContext
                            ? plainLines
                            : contextLines;

            Integer spokenIndex =
                    findSpeakableDialogueIndex(
                            lines,
                            first,
                            chr,
                            player
                    );

            if (spokenIndex == null) {
                spokenIndex =
                        findSpeakableDialogueIndex(
                                lines,
                                second,
                                chr,
                                player
                        );
            }

            if (spokenIndex == null) {
                debug(
                        "No resolvable dialogue line: "
                                + node
                );

                return;
            }

            String raw =
                    lines.get(spokenIndex);

            String resolved =
                    resolveDialogueLine(
                            raw,
                            chr,
                            player
                    );

            if (resolved == null
                    || resolved.trim().isEmpty()) {

                debug(
                        "Dialogue resolution failed: "
                                + node
                );

                return;
            }

            int emote =
                    dialog.getEmoteForIndex(
                            spokenIndex
                    );

            debug(
                    "Dialogue: node="
                            + node
                            + " line=\""
                            + resolved
                            + "\" emote="
                            + emote
            );

            /*
             * Do not sleep here.
             */
            BotSpeak(
                    chr,
                    resolved
            );

            BotEmote(
                    chr,
                    emote
            );

        } catch (Throwable t) {
            debug(
                    "Dialogue ERROR: node="
                            + node
                            + " error="
                            + t
            );
        }
    }

    private Integer findSpeakableDialogueIndex(
            List<String> lines,
            List<Integer> pool,
            Character speaker,
            Character player) {

        if (pool == null
                || pool.isEmpty()) {
            return null;
        }

        int tries =
                Math.min(
                        CONTEXT_REROLLS,
                        pool.size()
                );

        for (int attempt = 0;
             attempt < tries;
             attempt++) {

            int index =
                    pool.get(
                            random.nextInt(
                                    pool.size()
                            )
                    );

            String raw =
                    lines.get(index);

            if (!DialogueContextResolver
                    .hasTokens(raw)) {

                return index;
            }

            Optional<String> resolved =
                    DialogueContextResolver.fill(
                            raw,
                            speaker,
                            player
                    );

            if (resolved.isPresent()) {
                return index;
            }
        }

        /*
         * Deterministic fallback.
         */
        for (Integer index : pool) {
            if (index == null
                    || index < 0
                    || index >= lines.size()) {

                continue;
            }

            String raw =
                    lines.get(index);

            if (!DialogueContextResolver
                    .hasTokens(raw)) {

                return index;
            }

            Optional<String> resolved =
                    DialogueContextResolver.fill(
                            raw,
                            speaker,
                            player
                    );

            if (resolved.isPresent()) {
                return index;
            }
        }

        return null;
    }

    private String resolveDialogueLine(
            String raw,
            Character speaker,
            Character player) {

        if (raw == null) {
            return null;
        }

        if (!DialogueContextResolver
                .hasTokens(raw)) {

            return raw;
        }

        Optional<String> resolved =
                DialogueContextResolver.fill(
                        raw,
                        speaker,
                        player
                );

        return resolved.orElse(null);
    }

    /*
     * ---------------------------------------------------------------------
     * GENERAL HELPERS
     * ---------------------------------------------------------------------
     */

    private long now() {
        return System.currentTimeMillis();
    }

    private boolean isInZakumBossMap(
            Character chr) {

        return chr != null
                && chr.getMapId()
                == ZAKUM_BOSS_MAP;
    }

    private void enterPhase(
            Phase next) {

        if (phase == next) {
            return;
        }

        Phase previous = phase;

        /*
         * Any transition away from an idle phase must stop wandering.
         *
         * The new idle phase will explicitly restart it.
         */
        if (next != Phase.WAITING_FOR_PARTY
                && next != Phase.WAITING_FOR_EXPEDITION) {

            stopWandering();
        }

        phase = next;

        debug(
                previous
                        + " -> "
                        + next
        );
    }

    private void debug(
            String message) {

        Character chr = getChr();

        log(
                "[ZakumBot] "
                        + (chr != null
                        ? chr.getName()
                        : "?")
                        + " ["
                        + phase
                        + "]: "
                        + message
        );
    }

    @Override
    public void displayCommands(
            Character chr) {
    }

    public boolean canLoot() {
        return false;
    }
}
