        /*
         * This file is part of the OdinMS Maple Story Server
         * Copyright (C) 2008 Patrick Huy [patrick.huy@frz.cc]
         * Matthias Butz [matze@odinms.de]
         * Jan Christian Meyer [vimes@odinms.de]
         *
         * This program is free software: you can redistribute it and/or modify
         * it under the terms of the GNU Affero General Public License as
         * published by the Free Software Foundation version 3 as published
         * by the Free Software Foundation.
         *
         * This program is distributed in the hope that it will be useful,
         * but WITHOUT ANY WARRANTY; without even the implied warranty of
         * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
         * GNU Affero General Public License for more details.
         *
         * You should have received a copy of the GNU Affero General Public License
         * along with this program. If not, see <http://www.gnu.org/licenses/>.
         */

        package server.expeditions;

import client.Character;
import constants.id.MapId;
import constants.id.MobId;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.server.SoloMaplingUtilities;
import tools.PacketCreator;

import java.awt.Point;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static soloMapling.ArtificialPlayer.BotHelpers.getBotById;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/**
 * @author Alan (SharpAceX)
 */
public class Expedition {

    private static final Logger log =
            LoggerFactory.getLogger(Expedition.class);

    private static final int[] EXPEDITION_BOSSES = {
            MobId.ZAKUM_1,
            MobId.ZAKUM_2,
            MobId.ZAKUM_3,

            MobId.ZAKUM_ARM_1,
            MobId.ZAKUM_ARM_2,
            MobId.ZAKUM_ARM_3,
            MobId.ZAKUM_ARM_4,
            MobId.ZAKUM_ARM_5,
            MobId.ZAKUM_ARM_6,
            MobId.ZAKUM_ARM_7,
            MobId.ZAKUM_ARM_8,

            MobId.HORNTAIL_PREHEAD_LEFT,
            MobId.HORNTAIL_PREHEAD_RIGHT,
            MobId.HORNTAIL_HEAD_A,
            MobId.HORNTAIL_HEAD_B,
            MobId.HORNTAIL_HEAD_C,
            MobId.HORNTAIL_HAND_LEFT,
            MobId.HORNTAIL_HAND_RIGHT,
            MobId.HORNTAIL_WINGS,
            MobId.HORNTAIL_LEGS,
            MobId.HORNTAIL_TAIL,

            MobId.SCARLION_STATUE,
            MobId.SCARLION,
            MobId.ANGRY_SCARLION,
            MobId.FURIOUS_SCARLION,

            MobId.TARGA_STATUE,
            MobId.TARGA,
            MobId.ANGRY_TARGA,
            MobId.FURIOUS_TARGA
    };

    private final Character leader;
    private final ExpeditionType type;
    private final MapleMap startMap;

    private boolean registering;

    private final List<String> bossLogs;
    private ScheduledFuture<?> schedule;

    /*
     * Expedition membership can be modified while server tasks are
     * resolving active members, so this must remain concurrent.
     */
    private final Map<Integer, String> members =
            new ConcurrentHashMap<>();

    private final List<Integer> banned =
            new CopyOnWriteArrayList<>();

    private long startTime;

    private final Properties props =
            new Properties();

    private final boolean silent;
    private final int minSize;
    private final int maxSize;

    private final Lock pL =
            new ReentrantLock(true);

    public Expedition(
            Character player,
            ExpeditionType met,
            boolean sil,
            int minPlayers,
            int maxPlayers) {

        if (player == null) {
            throw new IllegalArgumentException(
                    "Expedition leader cannot be null."
            );
        }

        if (met == null) {
            throw new IllegalArgumentException(
                    "Expedition type cannot be null."
            );
        }

        leader = player;

        members.put(
                player.getId(),
                player.getName()
        );

        startMap = player.getMap();
        type = met;
        silent = sil;

        minSize =
                (minPlayers != 0)
                        ? minPlayers
                        : type.getMinSize();

        maxSize =
                (maxPlayers != 0)
                        ? maxPlayers
                        : type.getMaxSize();

        bossLogs =
                new CopyOnWriteArrayList<>();
    }

    public int getMinSize() {
        return minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void beginRegistration() {
        registering = true;

        leader.sendPacket(
                PacketCreator.getClock(
                        (int) MINUTES.toSeconds(
                                type.getRegistrationMinutes()
                        )
                )
        );

        if (!silent) {
            startMap.broadcastMessage(
                    leader,
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] "
                                    + leader.getName()
                                    + " has been declared the expedition captain. "
                                    + "Please register for the expedition."
                    ),
                    false
            );

            leader.sendPacket(
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] You have become the expedition captain. "
                                    + "Gather enough people for your team then talk "
                                    + "to the NPC to start."
                    )
            );
        }

        scheduleRegistrationEnd();
    }

    private void scheduleRegistrationEnd() {
        final Expedition expedition = this;

        startTime =
                System.currentTimeMillis()
                        + MINUTES.toMillis(
                        type.getRegistrationMinutes()
                );

        schedule =
                TimerManager.getInstance().schedule(
                        () -> {
                            if (!registering) {
                                return;
                            }

                            try {
                                expedition.removeChannelExpedition(
                                        startMap.getChannelServer()
                                );
                            } catch (Throwable t) {
                                log.warn(
                                        "[EXPEDITION] Failed to remove "
                                                + "expired expedition from channel.",
                                        t
                                );
                            }

                            if (!silent) {
                                startMap.broadcastMessage(
                                        PacketCreator.serverNotice(
                                                6,
                                                "[Expedition] The time limit has "
                                                        + "been reached. Expedition "
                                                        + "has been disbanded."
                                        )
                                );
                            }

                            dispose(false);
                        },
                        MINUTES.toMillis(
                                type.getRegistrationMinutes()
                        )
                );
    }

    public void dispose(boolean writeLog) {
        broadcastExped(
                PacketCreator.removeClock()
        );

        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }

        if (writeLog && !registering) {
            log();
        }
    }

    private void log() {
        String gmMessage =
                type
                        + " Expedition with leader "
                        + leader.getName()
                        + " finished after "
                        + getTimeString(getStartTime());

        Server.getInstance().broadcastGMMessage(
                getLeader().getWorld(),
                PacketCreator.serverNotice(
                        6,
                        gmMessage
                )
        );

        StringBuilder logText =
                new StringBuilder();

        logText
                .append(type)
                .append(" EXPEDITION\r\n");

        logText
                .append(getTimeString(startTime))
                .append("\r\n");

        for (String memberName : getMembers().values()) {
            logText
                    .append(">>")
                    .append(memberName)
                    .append("\r\n");
        }

        logText.append("BOSS KILLS\r\n");

        for (String message : bossLogs) {
            logText.append(message);
        }

        logText.append("\r\n");

        Expedition.log.info(
                logText.toString()
        );
    }

    private static String getTimeString(long then) {
        long duration =
                System.currentTimeMillis() - then;

        int seconds =
                (int) (
                        duration
                                / SECONDS.toMillis(1)
                ) % 60;

        int minutes =
                (int) (
                        duration
                                / MINUTES.toMillis(1)
                ) % 60;

        return minutes
                + " Minutes and "
                + seconds
                + " Seconds";
    }

    public void finishRegistration() {
        registering = false;
    }

    public void start() {
        finishRegistration();

        registerExpeditionAttempt();

        broadcastExped(
                PacketCreator.removeClock()
        );

        if (!silent) {
            broadcastExped(
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] The expedition has started! "
                                    + "Good luck, brave heroes!"
                    )
            );
        }

        startTime =
                System.currentTimeMillis();

        Server.getInstance().broadcastGMMessage(
                startMap.getWorld(),
                PacketCreator.serverNotice(
                        6,
                        "[Expedition] "
                                + type
                                + " Expedition started with leader: "
                                + leader.getName()
                )
        );
    }

    public String addMember(Character player) {
        if (player == null) {
            return "Sorry, that player could not be registered.";
        }

        if (!registering) {
            return "Sorry, this expedition is already underway. Registration is closed!";
        }

        if (banned.contains(player.getId())) {
            return "Sorry, you've been banned from this expedition by #b"
                    + leader.getName()
                    + "#k.";
        }

        if (members.containsKey(player.getId())) {
            return "You are already registered for this expedition.";
        }

        if (members.size() >= getMaxSize()) {
            return "Sorry, this expedition is full!";
        }

        int channel =
                getRecruitingMap()
                        .getChannelServer()
                        .getId();

        if (!ExpeditionBossLog.attemptBoss(
                player.getId(),
                channel,
                this,
                false
        )) {
            return "Sorry, you've already reached the quota of attempts "
                    + "for this expedition! Try again another day...";
        }

        members.put(
                player.getId(),
                player.getName()
        );

        int remainingSeconds =
                Math.max(
                        0,
                        (int) (
                                (startTime - System.currentTimeMillis())
                                        / 1000L
                        )
                );

        player.sendPacket(
                PacketCreator.getClock(
                        remainingSeconds
                )
        );

        if (!silent) {
            broadcastExped(
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] "
                                    + player.getName()
                                    + " has joined the expedition!"
                    )
            );
        }

        return "You have registered for the expedition successfully!";
    }

    public int addMemberInt(Character player) {
        if (player == null) {
            return 4;
        }

        if (!registering) {
            return 1;
        }

        if (banned.contains(player.getId())) {
            return 2;
        }

        if (members.containsKey(player.getId())) {
            return 4;
        }

        if (members.size() >= getMaxSize()) {
            return 3;
        }

        members.put(
                player.getId(),
                player.getName()
        );

        int remainingSeconds =
                Math.max(
                        0,
                        (int) (
                                (startTime - System.currentTimeMillis())
                                        / 1000L
                        )
                );

        player.sendPacket(
                PacketCreator.getClock(
                        remainingSeconds
                )
        );

        if (!silent) {
            broadcastExped(
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] "
                                    + player.getName()
                                    + " has joined the expedition!"
                    )
            );
        }

        return 0;
    }

    private void registerExpeditionAttempt() {
        int channel =
                getRecruitingMap()
                        .getChannelServer()
                        .getId();

        for (Character chr : getActiveMembers()) {
            ExpeditionBossLog.attemptBoss(
                    chr.getId(),
                    channel,
                    this,
                    true
            );
        }
    }

    private void broadcastExped(Packet packet) {
        if (packet == null) {
            return;
        }

        for (Character chr : getActiveMembers()) {
            try {
                chr.sendPacket(packet);
            } catch (Throwable t) {
                log.warn(
                        "[EXPEDITION] Failed to send packet to member {}.",
                        chr.getName(),
                        t
                );
            }
        }
    }

    public boolean removeMember(Character chr) {
        if (chr == null) {
            return false;
        }

        if (members.remove(chr.getId()) != null) {
            chr.sendPacket(
                    PacketCreator.removeClock()
            );

            if (!silent) {
                broadcastExped(
                        PacketCreator.serverNotice(
                                6,
                                "[Expedition] "
                                        + chr.getName()
                                        + " has left the expedition."
                        )
                );

                chr.dropMessage(
                        6,
                        "[Expedition] You have left this expedition."
                );
            }

            return true;
        }

        return false;
    }

    public void ban(Entry<Integer, String> chr) {
        if (chr == null) {
            return;
        }

        int cid = chr.getKey();

        if (banned.contains(cid)) {
            return;
        }

        banned.add(cid);
        members.remove(cid);

        if (!silent) {
            broadcastExped(
                    PacketCreator.serverNotice(
                            6,
                            "[Expedition] "
                                    + chr.getValue()
                                    + " has been banned from the expedition."
                    )
            );
        }

        Character player =
                startMap
                        .getWorldServer()
                        .getPlayerStorage()
                        .getCharacterById(cid);

        /*
         * Artificial players may not be present in normal world storage,
         * so always perform the bot lookup as a fallback.
         */
        if (player == null) {
            player = getBotById(cid);
        }

        if (player == null) {
            log.warn(
                    "[EXPEDITION] Unable to resolve banned member {}.",
                    cid
            );
            return;
        }

        if (!player.isLoggedinWorld() && !isBot(player)) {
            return;
        }

        player.sendPacket(
                PacketCreator.removeClock()
        );

        if (!silent) {
            player.dropMessage(
                    6,
                    "[Expedition] You have been banned from this expedition."
            );
        }

        if (ExpeditionType.ARIANT.equals(type)
                || ExpeditionType.ARIANT1.equals(type)
                || ExpeditionType.ARIANT2.equals(type)) {

            if (isBot(player)) {
                warpBot(
                        player,
                        MapId.ARPQ_LOBBY,
                        0
                );
            } else {
                player.changeMap(
                        MapId.ARPQ_LOBBY
                );
            }
        }
    }

    public void monsterKilled(
            Character chr,
            Monster mob) {

        if (mob == null) {
            return;
        }

        for (int expeditionBoss : EXPEDITION_BOSSES) {
            if (mob.getId() != expeditionBoss) {
                continue;
            }

            String timeStamp =
                    new SimpleDateFormat("HH:mm:ss")
                            .format(new Date());

            bossLogs.add(
                    ">"
                            + mob.getName()
                            + " was killed after "
                            + getTimeString(startTime)
                            + " - "
                            + timeStamp
                            + "\r\n"
            );

            return;
        }
    }

    public void setProperty(
            String key,
            String value) {

        if (key == null) {
            return;
        }

        pL.lock();

        try {
            if (value == null) {
                props.remove(key);
            } else {
                props.setProperty(
                        key,
                        value
                );
            }
        } finally {
            pL.unlock();
        }
    }

    public String getProperty(String key) {
        if (key == null) {
            return null;
        }

        pL.lock();

        try {
            return props.getProperty(key);
        } finally {
            pL.unlock();
        }
    }

    public ExpeditionType getType() {
        return type;
    }

    /**
     * Resolves all currently available expedition members.
     *
     * Normal players are resolved from world storage.
     *
     * Bots are additionally resolved through BotHelpers.getBotById().
     * Bots do not need to satisfy isLoggedinWorld().
     */
    /**
     * Resolves all currently available expedition members.
     *
     * Normal players are resolved from world storage.
     *
     * Bots are additionally resolved through BotHelpers.getBotById().
     * Bots do not need to satisfy isLoggedinWorld().
     */
    public List<Character> getActiveMembers() {
        PlayerStorage ps =
                startMap.getWorldServer().getPlayerStorage();

        List<Character> activeMembers =
                new LinkedList<>();

        for (Integer chrid : members.keySet()) {
            if (chrid == null) {
                continue;
            }

            Character chr =
                    ps.getCharacterById(chrid);

            /*
             * If the character is not in normal world storage, try the
             * centralized bot lookup.
             */
            if (chr == null) {
                chr = getBotById(chrid);
            }

            if (chr == null) {
                log.warn(
                        "[EXPEDITION] Unable to resolve member {}.",
                        chrid
                );
                continue;
            }

            /*
             * Bots do not need to satisfy the normal logged-in-world
             * requirement.
             */
            if (isBot(chr)) {
                activeMembers.add(chr);
                continue;
            }

            /*
             * Normal players must still be logged into the world.
             */
            if (chr.isLoggedinWorld()) {
                activeMembers.add(chr);
            } else {
                log.debug(
                        "[EXPEDITION] Member {} ({}) is not active in world.",
                        chrid,
                        chr.getName()
                );
            }
        }

        return activeMembers;
    }
    public Map<Integer, String> getMembers() {
        return new HashMap<>(members);
    }

    public List<Entry<Integer, String>> getMemberList() {
        List<Entry<Integer, String>> memberList =
                new LinkedList<>();

        Entry<Integer, String> leaderEntry = null;

        for (Entry<Integer, String> e :
                getMembers().entrySet()) {

            if (!isLeader(e.getKey())) {
                memberList.add(e);
            } else {
                leaderEntry = e;
            }
        }

        if (leaderEntry != null) {
            memberList.add(
                    0,
                    leaderEntry
            );
        }

        return memberList;
    }

    public final boolean isExpeditionTeamTogether() {
        List<Character> chars =
                getActiveMembers();

        if (chars.size() <= 1) {
            return true;
        }

        Iterator<Character> iterator =
                chars.iterator();

        Character first =
                iterator.next();

        int mapId =
                first.getMapId();

        while (iterator.hasNext()) {
            Character chr =
                    iterator.next();

            if (chr.getMapId() != mapId) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolves the destination map for a bot.
     *
     * Bots can exist on a channel different from the expedition's
     * recruiting channel. Prefer the bot's current channel when
     * available, then fall back to the expedition channel and finally
     * to the shared map utility.
     */
    private MapleMap getBotDestinationMap(
            Character bot,
            int mapId) {

        if (bot == null) {
            return null;
        }

        /*
         * First try the bot's current channel.
         */
        try {
            MapleMap currentMap =
                    bot.getMap();

            if (currentMap != null) {
                Channel botChannel =
                        currentMap.getChannelServer();

                if (botChannel != null) {
                    MapleMap destination =
                            botChannel
                                    .getMapFactory()
                                    .getMap(mapId);

                    if (destination != null) {
                        return destination;
                    }
                }
            }
        } catch (Throwable t) {
            log.warn(
                    "[EXPEDITION] Failed to resolve destination map {} "
                            + "from bot {} current channel.",
                    mapId,
                    bot.getName(),
                    t
            );
        }

        /*
         * Next try the expedition's recruiting channel.
         */
        try {
            Channel expeditionChannel =
                    startMap.getChannelServer();

            if (expeditionChannel != null) {
                MapleMap destination =
                        expeditionChannel
                                .getMapFactory()
                                .getMap(mapId);

                if (destination != null) {
                    return destination;
                }
            }
        } catch (Throwable t) {
            log.warn(
                    "[EXPEDITION] Failed to resolve destination map {} "
                            + "from expedition channel for bot {}.",
                    mapId,
                    bot.getName(),
                    t
            );
        }

        /*
         * Final fallback to the shared utility.
         */
        try {
            return SoloMaplingUtilities.getMapleMapById(mapId);
        } catch (Throwable t) {
            log.error(
                    "[EXPEDITION] Failed to resolve destination map {} "
                            + "for bot {}.",
                    mapId,
                    bot.getName(),
                    t
            );

            return null;
        }
    }

    /**
     * Resolves the requested portal position.
     *
     * Falls back to portal 0 and ultimately to (0, 0) if the requested
     * portal cannot be resolved.
     */
    private Point getSpawnPoint(
            MapleMap destination,
            int portal) {

        if (destination == null) {
            return new Point(0, 0);
        }

        try {
            if (destination.getPortal(portal) != null) {
                return new Point(
                        destination
                                .getPortal(portal)
                                .getPosition()
                );
            }

            if (destination.getPortal(0) != null) {
                return new Point(
                        destination
                                .getPortal(0)
                                .getPosition()
                );
            }
        } catch (Throwable t) {
            log.warn(
                    "[EXPEDITION] Failed to resolve spawn point for map {}.",
                    destination.getId(),
                    t
            );
        }

        return new Point(0, 0);
    }

    /**
     * Performs a bot-specific expedition warp.
     *
     * Normal players use Character.changeMap().
     *
     * Bots use BotGeneration.warpBotDirect() because they do not
     * have the normal client-side map transition lifecycle.
     */
    private void warpBot(
            Character bot,
            int warpTo,
            int portal) {

        if (bot == null) {
            return;
        }

        MapleMap destination =
                getBotDestinationMap(
                        bot,
                        warpTo
                );

        if (destination == null) {
            log.error(
                    "[EXPEDITION] Destination map {} could not be resolved "
                            + "for bot {} ({}).",
                    warpTo,
                    bot.getName(),
                    bot.getId()
            );
            return;
        }

        Point spawnPoint =
                getSpawnPoint(
                        destination,
                        portal
                );

        int oldMapId =
                bot.getMapId();

        log.info(
                "[EXPEDITION] BOT WARP: {} ({}) {} -> {} portal={} destination={}",
                bot.getName(),
                bot.getId(),
                oldMapId,
                warpTo,
                portal,
                destination.getId()
        );

        try {
            BotGeneration.warpBotDirect(
                    bot,
                    spawnPoint,
                    destination
            );

            log.info(
                    "[EXPEDITION] BOT WARP COMPLETE: {} ({}) now map={}",
                    bot.getName(),
                    bot.getId(),
                    bot.getMapId()
            );
        } catch (Throwable t) {
            log.error(
                    "[EXPEDITION] BOT WARP FAILED: {} ({}) {} -> {}",
                    bot.getName(),
                    bot.getId(),
                    oldMapId,
                    warpTo,
                    t
            );
        }
    }

    public final void warpExpeditionTeam(
            int warpFrom,
            int warpTo) {

        List<Character> players =
                getActiveMembers();

        for (Character chr : players) {
            if (chr.getMapId() != warpFrom) {
                continue;
            }

            try {
                if (isBot(chr)) {
                    warpBot(
                            chr,
                            warpTo,
                            0
                    );
                } else {
                    chr.changeMap(
                            warpTo
                    );
                }
            } catch (Throwable t) {
                log.error(
                        "[EXPEDITION] Failed to warp expedition "
                                + "member {} to map {}.",
                        chr.getName(),
                        warpTo,
                        t
                );
            }
        }
    }

    public final void warpExpeditionTeam(
            int warpTo) {

        List<Character> players =
                getActiveMembers();

        for (Character chr : players) {
            try {
                if (isBot(chr)) {
                    warpBot(
                            chr,
                            warpTo,
                            0
                    );
                } else {
                    chr.changeMap(
                            warpTo
                    );
                }
            } catch (Throwable t) {
                log.error(
                        "[EXPEDITION] Failed to warp expedition "
                                + "member {} to map {}.",
                        chr.getName(),
                        warpTo,
                        t
                );
            }
        }
    }

    public final void warpExpeditionTeamToMapSpawnPoint(
            int warpFrom,
            int warpTo,
            int toSp) {

        List<Character> players =
                getActiveMembers();

        for (Character chr : players) {
            if (chr.getMapId() != warpFrom) {
                continue;
            }

            try {
                if (isBot(chr)) {
                    warpBot(
                            chr,
                            warpTo,
                            toSp
                    );
                } else {
                    chr.changeMap(
                            warpTo,
                            toSp
                    );
                }
            } catch (Throwable t) {
                log.error(
                        "[EXPEDITION] Failed to warp expedition "
                                + "member {} to map {} spawn {}.",
                        chr.getName(),
                        warpTo,
                        toSp,
                        t
                );
            }
        }
    }

    public final void warpExpeditionTeamToMapSpawnPoint(
            int warpTo,
            int toSp) {

        List<Character> players =
                getActiveMembers();

        for (Character chr : players) {
            try {
                if (isBot(chr)) {
                    warpBot(
                            chr,
                            warpTo,
                            toSp
                    );
                } else {
                    chr.changeMap(
                            warpTo,
                            toSp
                    );
                }
            } catch (Throwable t) {
                log.error(
                        "[EXPEDITION] Failed to warp expedition "
                                + "member {} to map {} spawn {}.",
                        chr.getName(),
                        warpTo,
                        toSp,
                        t
                );
            }
        }
    }

    public final boolean addChannelExpedition(
            Channel ch) {

        if (ch == null) {
            return false;
        }

        return ch.addExpedition(this);
    }

    public final void removeChannelExpedition(
            Channel ch) {

        if (ch == null) {
            return;
        }

        ch.removeExpedition(this);
    }

    public Character getLeader() {
        return leader;
    }

    public MapleMap getRecruitingMap() {
        return startMap;
    }

    public boolean contains(Character player) {
        if (player == null) {
            return false;
        }

        return members.containsKey(player.getId())
                || isLeader(player);
    }

    public boolean isLeader(Character player) {
        return player != null
                && isLeader(player.getId());
    }

    public boolean isLeader(int playerid) {
        return leader.getId() == playerid;
    }

    public boolean isRegistering() {
        return registering;
    }

    public boolean isInProgress() {
        return !registering;
    }

    public long getStartTime() {
        return startTime;
    }

    public List<String> getBossLogs() {
        return bossLogs;
    }
}

