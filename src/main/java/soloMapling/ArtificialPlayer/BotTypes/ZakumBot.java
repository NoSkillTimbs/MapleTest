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

    /*
     * Dialogue cooldowns.
     *
     * Combat ticks happen every 250ms, so dialogue must never be allowed
     * to fire once per combat tick.
     */
    private static final long RECRUIT_DIALOGUE_INTERVAL_MS = 15_000;
    private static final long COMBAT_DIALOGUE_INTERVAL_MS = 8_000;
    private static final long PLAYER_REACTION_INTERVAL_MS = 4_000;

    /*
     * Only about 10% of dialogue selections should prefer lines containing
     * context tokens such as {PLAYER_NAME}.
     *
     * The dialogue handler falls back to plain lines if a contextual line
     * cannot be resolved.
     */
    private static final double PLAYER_CONTEXT_CHANCE = 0.10;

    private static final int CONTEXT_REROLLS = 6;

    private static final Set<ZakumBot> ACTIVE_ZAKUM_BOTS =
            ConcurrentHashMap.newKeySet();

    private static volatile boolean combatTickerStarted = false;

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted) {
            return;
        }

        combatTickerStarted = true;

        ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
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
                bot.debug("Combat ticker error: " + t.getMessage());
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

    private int recruitedPlayerId = -1;

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
     * Previous HP values for real party members.
     *
     * Key   = character ID
     * Value = last observed HP
     */
    private final Map<Integer, Integer> previousPartyHp =
            new ConcurrentHashMap<>();

    /*
     * Prevents PlayerDeath from firing repeatedly after the player has died.
     *
     * There is intentionally no revive mechanic here.
     */
    private final Set<Integer> knownDeadPlayerIds =
            ConcurrentHashMap.newKeySet();

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

            enterPhase(Phase.FINISHED);
            return;
        }

        if (isInZakumBossMap(chr)) {
            beginFighting();
            return;
        }

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            beginTravelTo(ZAKUM_DOOR_MAP);
            enterPhase(Phase.TRAVELING_TO_ZAKUM);
            return;
        }

        debug("At Zakum door. Waiting for party invitation.");

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
                    chr.getItemQuantity(EYE_OF_FIRE_ITEM_ID, false);

            int missing =
                    EYE_OF_FIRE_COUNT - currentQuantity;

            if (missing <= 0) {
                zakumItemsGranted = true;

                debug(
                        "Already has "
                                + currentQuantity
                                + "x Eye of Fire."
                );

                return;
            }

            boolean added = InventoryManipulator.addById(
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

    private void doWaitingForParty() {
        Character chr = getChr();

        if (chr == null) {
            return;
        }

        if (chr.getLevel() < ZAKUM_MIN_LEVEL
                || chr.getLevel() > ZAKUM_MAX_LEVEL) {

            debug("Zakum bot no longer within level range.");

            enterPhase(Phase.FINISHED);
            return;
        }

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            debug(
                    "Zakum bot is not at the Door to Zakum. Returning."
            );

            beginTravelTo(ZAKUM_DOOR_MAP);
            enterPhase(Phase.TRAVELING_TO_ZAKUM);

            return;
        }

        /*
         * The bot has no player target yet while waiting for an invite.
         * Therefore PartyInviteWait should primarily contain plain lines.
         *
         * If the YAML happens to contain {PLAYER_NAME}, the dialogue
         * resolver will safely skip that line because player == null.
         */
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

        if (chr.getParty() != null) {
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

        if (chr.getParty() == null) {
            cleanupExpeditionState();

            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader = getRealPartyLeader();

        if (leader == null) {
            debug(
                    "Party joined, but real party leader is unavailable."
            );

            return;
        }

        debug(
                "Party joined. Following leader "
                        + leader.getName()
                        + " to Zakum altar entrance."
        );

        enterPhase(Phase.WAITING_FOR_EXPEDITION);
    }

    private Character findRealPartyMember() {
        Character chr = getChr();

        if (chr == null || chr.getParty() == null) {
            return null;
        }

        Party party = chr.getParty();

        for (PartyCharacter pc : party.getMembers()) {
            if (pc == null) {
                continue;
            }

            Character player = pc.getPlayer();

            if (player == null
                    || player.getId() == chr.getId()
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

        Character leader = leaderPc.getPlayer();

        if (leader == null
                || leader.getId() == chr.getId()
                || isBot(leader)
                || !leader.isLoggedinWorld()) {
            return null;
        }

        return leader;
    }

    private void doWaitingForExpedition() {
        Character chr = getChr();

        if (chr == null || chr.getMap() == null) {
            return;
        }

        if (expeditionRegistered) {
            if (isInZakumBossMap(chr)) {
                beginFighting();
                return;
            }

            if (chr.getMapId() == ZAKUM_ALTAR_ENTRANCE_MAP) {
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
            cleanupExpeditionState();

            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader = getRealPartyLeader();

        if (leader == null) {
            debug("Waiting for real party leader.");
            return;
        }

        if (chr.getMapId() != ZAKUM_ALTAR_ENTRANCE_MAP) {
            if (leader.getMapId()
                    == ZAKUM_ALTAR_ENTRANCE_MAP) {

                debug(
                        "Leader reached Zakum altar entrance. Following."
                );
            } else {
                debug(
                        "Following leader "
                                + leader.getName()
                                + " to Zakum altar entrance."
                );
            }

            beginTravelTo(ZAKUM_ALTAR_ENTRANCE_MAP);

            enterPhase(Phase.TRAVELING_TO_ZAKUM);
            return;
        }

        long now = now();

        if (now < nextExpeditionCheckMs) {
            return;
        }

        nextExpeditionCheckMs =
                now + EXPEDITION_CHECK_INTERVAL_MS;

        if (leader.getClient() == null
                || leader.getClient().getChannelServer() == null) {
            return;
        }

        Expedition expedition =
                leader.getClient()
                        .getChannelServer()
                        .getExpedition(ExpeditionType.ZAKUM);

        if (expedition == null) {
            debug(
                    "At Zakum altar; waiting for Zakum expedition."
            );

            return;
        }

        zakumExpedition = expedition;

        if (!expedition.isRegistering()) {
            debug(
                    "Zakum expedition exists but is no longer registering."
            );

            return;
        }

        if (expedition.contains(chr)) {
            expeditionRegistered = true;

            debug(
                    "Bot is already registered in Zakum expedition."
            );

            enterPhase(Phase.TRAVELING_TO_ZAKUM);
            return;
        }

        int result = expedition.addMemberInt(chr);

        if (result == 0) {
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

            enterPhase(Phase.TRAVELING_TO_ZAKUM);
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

    private void doTravelToZakum() {
        Character chr = getChr();

        if (chr == null || chr.getMap() == null) {
            return;
        }

        /*
         * Once registered, the expedition controls the warp.
         * Never send the bot back to the entrance.
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
                    "Expedition registered; waiting for expedition warp. "
                            + "Current map="
                            + chr.getMapId()
            );

            return;
        }

        if (chr.getParty() == null) {
            debug(
                    "Lost party before expedition registration."
            );

            cleanupExpeditionState();

            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader = getRealPartyLeader();

        if (leader == null) {
            debug(
                    "Lost real party leader while travelling to Zakum."
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

                    enterPhase(Phase.WAITING_FOR_PARTY);
                    return;
                }

                if (chr.getMapId()
                        == ZAKUM_ALTAR_ENTRANCE_MAP) {

                    enterPhase(Phase.WAITING_FOR_EXPEDITION);
                    return;
                }

                debug(
                        "Travel succeeded but destination is unexpected: "
                                + chr.getMapId()
                );

                enterPhase(Phase.WAITING_FOR_EXPEDITION);
                return;
            }

            debug("Zakum travel failed.");

            resetTravelState();

            if (chr.getMapId()
                    == ZAKUM_DOOR_MAP) {

                enterPhase(Phase.WAITING_FOR_PARTY);
            } else {
                enterPhase(Phase.WAITING_FOR_EXPEDITION);
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

                enterPhase(Phase.WAITING_FOR_PARTY);
            } else {
                enterPhase(Phase.WAITING_FOR_EXPEDITION);
            }
        }
    }

    private void beginTravelTo(int destinationMapId) {
        Character chr = getChr();

        if (chr == null || chr.getMap() == null) {
            return;
        }

        if (chr.getMapId() == destinationMapId) {
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
                    public void accept(Boolean success) {
                        travelSucceeded =
                                success != null && success;

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

    private void beginFighting() {
        Character chr = getChr();

        if (chr == null || !isInZakumBossMap(chr)) {
            return;
        }

        bossSpawnGraceUntilMs =
                now() + BOSS_SPAWN_GRACE_MS;

        ACTIVE_ZAKUM_BOTS.add(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();

        nextCombatDialogueMs = now();
        nextPlayerReactionMs = now();

        debug("Entered Zakum boss map.");

        enterPhase(Phase.FIGHTING_ZAKUM);
    }

    private void doFightingZakum() {
        Character chr = getChr();

        if (chr == null) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            return;
        }

        if (!isInZakumBossMap(chr)) {
            ACTIVE_ZAKUM_BOTS.remove(this);

            debug("Left Zakum boss map.");

            enterPhase(Phase.FINISHED);
            return;
        }

        if (now() < bossSpawnGraceUntilMs) {
            return;
        }

        if (zakumExpedition != null
                && zakumExpedition.getBossLogs().size()
                >= ZAKUM_PART_COUNT) {

            debug(
                    "Zakum boss logs indicate completion."
            );

            ACTIVE_ZAKUM_BOTS.remove(this);

            enterPhase(Phase.FINISHED);
            return;
        }

        if (!hasLivingZakumMob(chr)) {
            debug(
                    "No living Zakum parts remain."
            );

            ACTIVE_ZAKUM_BOTS.remove(this);

            enterPhase(Phase.FINISHED);
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
         * Detect player hits/deaths before normal combat dialogue.
         */
        processPartyReactions();

        /*
         * General combat chatter.
         */
        long now = now();

        if (now >= nextCombatDialogueMs) {
            Character player = findRealPartyMember();

            sayDialogue(
                    "ZakumCombat",
                    player,
                    PLAYER_CONTEXT_CHANCE
            );

            nextCombatDialogueMs =
                    now + COMBAT_DIALOGUE_INTERVAL_MS;
        }

        /*
         * Keep the actual attack loop independent from dialogue.
         */
        try {
            BotAttackDriver.botAttack(chr);
        } catch (Throwable ignored) {
        }
    }

    private void processPartyReactions() {
        Character chr = getChr();

        if (chr == null || chr.getParty() == null) {
            return;
        }

        long now = now();

        for (PartyCharacter pc : chr.getParty().getMembers()) {
            if (pc == null) {
                continue;
            }

            Character player = pc.getPlayer();

            if (player == null
                    || player.getId() == chr.getId()
                    || isBot(player)
                    || !player.isLoggedinWorld()) {
                continue;
            }

            /*
             * Ignore players who somehow aren't in the boss map.
             */
            if (player.getMapId() != ZAKUM_BOSS_MAP) {
                continue;
            }

            int playerId = player.getId();
            int currentHp = player.getHp();

            Integer previousHp =
                    previousPartyHp.put(playerId, currentHp);

            /*
             * Death takes priority over ordinary damage.
             */
            if (!player.isAlive()) {
                if (knownDeadPlayerIds.add(playerId)) {
                    sayDialogue(
                            "PlayerDeath",
                            player,
                            PLAYER_CONTEXT_CHANCE
                    );

                    /*
                     * Do not remove the player from knownDeadPlayerIds.
                     * There is no revive mechanic.
                     */
                }

                continue;
            }

            /*
             * If the player is alive, a positive HP drop means
             * the player was hit.
             *
             * The first observation only seeds the HP cache.
             */
            if (previousHp != null
                    && previousHp > currentHp
                    && currentHp > 0
                    && !knownDeadPlayerIds.contains(playerId)
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

    private boolean hasLivingZakumMob(Character chr) {
        if (chr == null || chr.getMap() == null) {
            return false;
        }

        for (Monster mob : chr.getMap().getAllMonsters()) {
            if (mob != null && isZakumMob(mob.getId())) {
                return true;
            }
        }

        return false;
    }

    private boolean isZakumMob(int mobId) {
        return mobId == MobId.ZAKUM_1
                || mobId == MobId.ZAKUM_2
                || mobId == MobId.ZAKUM_3
                || MobId.isZakumArm(mobId);
    }

    private void pollRecruitInvite() {
        Character chr = getChr();

        if (chr == null || chr.getParty() != null) {
            return;
        }

        BotPartyQueue.PartyInviteEntry entry =
                BotPartyQueue.getInstance()
                        .getPartyInvite(chr);

        if (entry == null) {
            return;
        }

        Character recruiter = entry.getInviter();

        if (recruiter == null) {
            debug(
                    "Zakum party invite has no recruiter: "
                            + chr.getName()
            );

            BotPartyQueue.getInstance()
                    .removePartyInvite(chr);

            return;
        }

        debug(
                "Zakum party invite received: bot="
                        + chr.getName()
                        + " recruiter="
                        + recruiter.getName()
                        + " partyId="
                        + entry.getPartyId()
        );

        boolean joined =
                BotPartyCommands.botAcceptPartyInvite(chr);

        if (!joined) {
            debug(
                    "Zakum bot failed to join party: bot="
                            + chr.getName()
                            + " recruiter="
                            + recruiter.getName()
                            + " partyId="
                            + entry.getPartyId()
            );

            return;
        }

        recruitedPlayerId =
                recruiter.getId();

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

    private void doFinished() {
        ACTIVE_ZAKUM_BOTS.remove(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();
    }

    private void cleanupExpeditionState() {
        Character chr = getChr();

        ACTIVE_ZAKUM_BOTS.remove(this);

        previousPartyHp.clear();
        knownDeadPlayerIds.clear();

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

        if (chr != null) {
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            GCMovement.disable(chr);

            if (zakumExpedition != null
                    && zakumExpedition.isRegistering()
                    && zakumExpedition.contains(chr)) {

                zakumExpedition.removeMember(chr);
            }

            BotRecruitManager.clearArmed(chr.getId());
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
     * Non-blocking dialogue playback.
     *
     * We intentionally do NOT call executeBotContextDialogue() here.
     * BotDialogueHandler's normal playback sleeps for the YAML duration,
     * which would block the shared Zakum combat ticker.
     *
     * This implementation:
     *   1. Loads the node.
     *   2. Splits contextual/plain lines.
     *   3. Gives contextual lines a 10% chance.
     *   4. Resolves {PLAYER_NAME}, etc.
     *   5. Preserves the selected line's YAML emote.
     *   6. Calls BotSpeak/BotEmote without sleeping.
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

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                if (DialogueContextResolver.hasTokens(line)) {
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
                        "No resolvable dialogue line: node="
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
                        "Dialogue resolution failed: node="
                                + node
                );

                return;
            }

            int emote =
                    dialog.getEmoteForIndex(spokenIndex);

            debug(
                    "Dialogue: node="
                            + node
                            + " line=\""
                            + resolved
                            + "\" emote="
                            + emote
            );

            BotSpeak(chr, resolved);
            BotEmote(chr, emote);

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

        if (pool == null || pool.isEmpty()) {
            return null;
        }

        int tries =
                Math.min(
                        CONTEXT_REROLLS,
                        pool.size()
                );

        for (int attempt = 0; attempt < tries; attempt++) {
            int index =
                    pool.get(
                            random.nextInt(pool.size())
                    );

            String raw =
                    lines.get(index);

            if (!DialogueContextResolver.hasTokens(raw)) {
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
         * Deterministic fallback through the selected pool.
         */
        for (Integer index : pool) {
            if (index == null
                    || index < 0
                    || index >= lines.size()) {
                continue;
            }

            String raw = lines.get(index);

            if (!DialogueContextResolver.hasTokens(raw)) {
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

        if (!DialogueContextResolver.hasTokens(raw)) {
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

    private long now() {
        return System.currentTimeMillis();
    }

    private boolean isInZakumBossMap(Character chr) {
        return chr != null
                && chr.getMapId() == ZAKUM_BOSS_MAP;
    }

    private void enterPhase(Phase next) {
        if (phase == next) {
            return;
        }

        Phase previous = phase;

        phase = next;

        debug(
                previous
                        + " -> "
                        + next
        );
    }

    private void debug(String message) {
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
    public void displayCommands(Character chr) {
    }

    public boolean canLoot() {
        return false;
    }
}