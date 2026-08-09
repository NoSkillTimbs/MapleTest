package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import constants.id.MapId;
import constants.id.MobId;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;

public class ZakumBot extends BotSM {

    private static final int ZAKUM_MIN_LEVEL = 50;
    private static final int ZAKUM_BOSS_MAP = 280030000;
    private static final int ZAKUM_DOOR_MAP = MapId.DOOR_TO_ZAKUM;
    private static final int ZAKUM_PART_COUNT = 11;
    private static final long COMBAT_TICK_MS = 250;
    private static final long BOSS_SPAWN_GRACE_MS = 10_000;
    private static final long TRAVEL_TIMEOUT_MS = 120_000;
    private static final long RECRUIT_MESSAGE_COOLDOWN_MS = 30_000;
    private static final long EXPEDITION_CHECK_INTERVAL_MS = 2_000;

    private static final java.util.Set<ZakumBot> ACTIVE_ZAKUM_BOTS =
            ConcurrentHashMap.newKeySet();
    private static volatile boolean combatTickerStarted = false;

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted) return;
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
            } catch (Throwable ignored) {}
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

    private long nextRecruitMessageMs = 0L;
    private long nextExpeditionCheckMs = 0L;

    private volatile boolean travelDone = false;
    private volatile boolean travelSucceeded = false;
    private boolean travelStarted = false;
    private long travelDeadlineMs = 0L;

    private long bossSpawnGraceUntilMs = 0L;

    public ZakumBot(Character character) {
        super(character);
        botType = "ZakumBot";
        dialoguePath = "ZakumBotDialogue.yaml";
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;

        Character chr = getChr();
        if (chr == null || chr.getMap() == null) return;

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

        if (chr.getLevel() < ZAKUM_MIN_LEVEL) {
            debug("Below Zakum level requirement.");
            enterPhase(Phase.FINISHED);
            return;
        }

        if (isInZakumBossMap(chr)) {
            beginFighting();
            return;
        }

        enterPhase(Phase.WAITING_FOR_PARTY);
    }

    private void doWaitingForParty() {
        Character chr = getChr();

        if (chr.getParty() != null) {
            enterPhase(Phase.PARTY_JOINED);
            return;
        }

        long now = now();
        if (now < nextRecruitMessageMs) return;

        Character player = findRealPlayerNearby();
        if (player == null) return;

        sayRecruit("ZakumRecruit", player);

        BotRecruitManager.RecruitAnswer answer =
                BotRecruitManager.rollPartyAsk(chr, player, 1.0, false);

        recruitedPlayerId = player.getId();
        nextRecruitMessageMs = now + RECRUIT_MESSAGE_COOLDOWN_MS;

        if (answer == BotRecruitManager.RecruitAnswer.ACCEPTED)
            debug("Accepted recruitment from " + player.getName());
    }

    private Character findRealPlayerNearby() {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) return null;

        for (Character player : chr.getMap().getAllPlayers()) {
            if (player == null
                    || player.getId() == chr.getId()
                    || isBot(player)
                    || !player.isLoggedinWorld()) continue;
            return player;
        }
        return null;
    }

    private void doPartyJoined() {
        Character chr = getChr();
        Party party = chr.getParty();

        if (party == null) {
            zakumExpedition = null;
            expeditionRegistered = false;
            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        if (findRealPartyMember() == null) {
            debug("Party has no real player.");
            return;
        }

        enterPhase(Phase.WAITING_FOR_EXPEDITION);
    }

    private Character findRealPartyMember() {
        Character chr = getChr();
        Party party = chr.getParty();
        if (party == null) return null;

        for (PartyCharacter pc : party.getMembers()) {
            if (pc == null) continue;

            Character player = pc.getPlayer();
            if (player == null
                    || player.getId() == chr.getId()
                    || isBot(player)
                    || !player.isLoggedinWorld()) continue;

            return player;
        }
        return null;
    }

    private void doWaitingForExpedition() {
        Character chr = getChr();
        if (chr.getParty() == null) {
            zakumExpedition = null;
            expeditionRegistered = false;
            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader = findRealPartyMember();
        if (leader == null) return;

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            beginTravelTo(ZAKUM_DOOR_MAP);
            enterPhase(Phase.TRAVELING_TO_ZAKUM);
            return;
        }

        long now = now();
        if (now < nextExpeditionCheckMs) return;
        nextExpeditionCheckMs = now + EXPEDITION_CHECK_INTERVAL_MS;

        Expedition expedition = leader.getClient()
                .getChannelServer()
                .getExpedition(ExpeditionType.ZAKUM);

        if (expedition == null) return;
        zakumExpedition = expedition;

        if (expedition.isRegistering()) {
            if (!expedition.contains(chr)) {
                int result = expedition.addMemberInt(chr);
                if (result == 0) {
                    expeditionRegistered = true;
                    sayRecruit("ExpeditionJoined", leader);
                    debug("Registered for Zakum expedition.");
                }
            } else {
                expeditionRegistered = true;
            }
            return;
        }

        enterPhase(Phase.TRAVELING_TO_ZAKUM);
    }

    private void doTravelToZakum() {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) return;

        if (zakumExpedition == null) {
            enterPhase(Phase.WAITING_FOR_EXPEDITION);
            return;
        }

        if (chr.getParty() == null) {
            cleanupExpeditionState();
            enterPhase(Phase.WAITING_FOR_PARTY);
            return;
        }

        Character leader = findRealPartyMember();
        if (leader == null) return;

        if (isInZakumBossMap(chr)) {
            beginFighting();
            return;
        }

        if (chr.getMapId() != ZAKUM_DOOR_MAP) {
            if (!travelStarted) beginTravelTo(ZAKUM_DOOR_MAP);

            if (travelDone) {
                if (travelSucceeded) {
                    resetTravelState();
                } else {
                    debug("Failed to reach Zakum door.");
                    resetTravelState();
                    enterPhase(Phase.WAITING_FOR_EXPEDITION);
                }
            } else if (now() > travelDeadlineMs) {
                debug("Zakum door travel timed out.");
                GCMovement.cancelTravel(chr);
                resetTravelState();
                enterPhase(Phase.WAITING_FOR_EXPEDITION);
            }
            return;
        }

        if (isInZakumBossMap(leader)) {
            chr.changeMap(ZAKUM_BOSS_MAP);
        }
    }

    private void beginTravelTo(int destinationMapId) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) return;

        if (chr.getMapId() == destinationMapId) {
            travelDone = true;
            travelSucceeded = true;
            return;
        }

        if (travelStarted) return;

        travelStarted = true;
        travelDone = false;
        travelSucceeded = false;
        travelDeadlineMs = now() + TRAVEL_TIMEOUT_MS;

        debug("Travelling to map " + destinationMapId);

        GCMovement.travel(chr, destinationMapId, new Consumer<Boolean>() {
            @Override
            public void accept(Boolean success) {
                travelSucceeded = success != null && success;
                travelDone = true;
            }
        });
    }

    private void resetTravelState() {
        travelStarted = false;
        travelDone = false;
        travelSucceeded = false;
        travelDeadlineMs = 0L;
    }

    private void beginFighting() {
        Character chr = getChr();
        if (chr == null || !isInZakumBossMap(chr)) return;

        bossSpawnGraceUntilMs = now() + BOSS_SPAWN_GRACE_MS;
        ACTIVE_ZAKUM_BOTS.add(this);

        debug("Entered Zakum boss map.");
        enterPhase(Phase.FIGHTING_ZAKUM);
    }

    private void doFightingZakum() {
        Character chr = getChr();
        if (chr == null) return;

        if (!isInZakumBossMap(chr)) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            enterPhase(Phase.FINISHED);
            return;
        }

        if (now() < bossSpawnGraceUntilMs) return;

        if (zakumExpedition != null
                && zakumExpedition.getBossLogs().size() >= ZAKUM_PART_COUNT) {
            debug("Zakum boss logs indicate completion.");
            ACTIVE_ZAKUM_BOTS.remove(this);
            enterPhase(Phase.FINISHED);
            return;
        }

        if (!hasLivingZakumMob(chr)) {
            debug("No living Zakum parts remain.");
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

        if (phase != Phase.FIGHTING_ZAKUM) return;

        if (!isInZakumBossMap(chr)) {
            ACTIVE_ZAKUM_BOTS.remove(this);
            return;
        }

        try {
            BotAttackDriver.botAttack(chr);
        } catch (Throwable ignored) {}
    }

    private boolean hasLivingZakumMob(Character chr) {
        if (chr.getMap() == null) return false;

        for (Monster mob : chr.getMap().getAllMonsters()) {
            if (mob != null && isZakumMob(mob.getId())) return true;
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
        if (chr == null || !BotPartyQueue.getInstance().hasPendingInvite(chr))
            return;

        int recruiterId = BotRecruitManager.armedInviterId(chr.getId());
        BotRecruitManager.InvitePoll result =
                BotRecruitManager.pollInvites(chr);

        if (result != BotRecruitManager.InvitePoll.JOINED) return;

        recruitedPlayerId = recruiterId;

        Character recruiter = chr.getClient()
                .getChannelServer()
                .getPlayerStorage()
                .getCharacterById(recruiterId);

        sayRecruit("PartyJoined", recruiter);
    }

    private void doFinished() {
        ACTIVE_ZAKUM_BOTS.remove(this);
    }

    private void cleanupExpeditionState() {
        Character chr = getChr();

        ACTIVE_ZAKUM_BOTS.remove(this);

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

        log("[ZakumBot] stopped: " +
                (chr != null ? chr.getName() : "?"));
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private boolean isInZakumBossMap(Character chr) {
        return chr != null && chr.getMapId() == ZAKUM_BOSS_MAP;
    }

    private void enterPhase(Phase next) {
        if (phase == next) return;

        Phase previous = phase;
        phase = next;
        debug(previous + " -> " + next);
    }

    private void debug(String message) {
        // log("[ZakumBot] " + getChr().getName() + ": " + message);
    }

    private void sayRecruit(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || player == null) return;

        try {
            String line = soloMapling.ArtificialPlayer.BotDialogueHandler
                    .getRandomResolvedLine(
                            dialoguePath,
                            botType,
                            node,
                            chr,
                            player
                    );

            if (line != null) BotSpeak(chr, line);
        } catch (Exception ignored) {}
    }

    @Override
    public void displayCommands(Character chr) {
    }

    public boolean canLoot() {
        return false;
    }
}