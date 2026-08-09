package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;

import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyQueue {

    public static final class PartyInviteEntry {
        private final Character inviter;
        private final int partyId;

        public PartyInviteEntry(Character inviter, int partyId) {
            this.inviter = inviter;
            this.partyId = partyId;
        }

        public Character getInviter() {
            return inviter;
        }

        public int getPartyId() {
            return partyId;
        }
    }

    private final ConcurrentHashMap<Integer, PartyInviteEntry> queues;
    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    /**
     * Store the latest party invitation for this bot.
     *
     * The bot ID is used as the key instead of the Character object so
     * the queue remains valid if the bot Character reference changes.
     */
    public void addPartyInvite(
            Character fakechar,
            Character inviter,
            int partyId
    ) {
        if (fakechar == null || inviter == null) {
            return;
        }

        queues.put(
                fakechar.getId(),
                new PartyInviteEntry(inviter, partyId)
        );

        debugprint(
                "addPartyInvite: bot=" + fakechar.getName()
                        + " botId=" + fakechar.getId()
                        + " inviter=" + inviter.getName()
                        + " inviterId=" + inviter.getId()
                        + " partyId=" + partyId
        );

        BotRecruitManager.wakeBotForInvite(fakechar);
    }

    /**
     * Get the pending invitation for this bot.
     */
    public PartyInviteEntry getPartyInvite(Character fakechar) {
        if (fakechar == null) {
            return null;
        }

        return queues.get(fakechar.getId());
    }

    /**
     * Check whether this bot has a pending invitation.
     */
    public boolean hasPendingInvite(Character fakechar) {
        return fakechar != null
                && queues.containsKey(fakechar.getId());
    }

    /**
     * Remove this bot's pending invitation.
     */
    public void removePartyInvite(Character fakechar) {
        if (fakechar == null) {
            return;
        }

        queues.remove(fakechar.getId());
    }
}