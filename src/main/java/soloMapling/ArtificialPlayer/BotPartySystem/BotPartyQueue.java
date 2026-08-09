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

    /*
     * Key by character ID, not Character reference.
     *
     * Bots can be recreated/relogged, so using Character itself as the key
     * can leave an invitation attached to an old Character instance.
     */
    private final ConcurrentHashMap<Integer, PartyInviteEntry> queues =
            new ConcurrentHashMap<>();

    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    public void addPartyInvite(Character fakechar, Character inviter, int partyId) {
        if (fakechar == null || inviter == null) {
            return;
        }

        queues.put(
                fakechar.getId(),
                new PartyInviteEntry(inviter, partyId)
        );

        debugprint(
                "BotPartyQueue: STORED invite"
                        + " bot=" + fakechar.getName()
                        + " botId=" + fakechar.getId()
                        + " inviter=" + inviter.getName()
                        + " inviterId=" + inviter.getId()
                        + " partyId=" + partyId
        );

        BotRecruitManager.wakeBotForInvite(fakechar);
    }

    public PartyInviteEntry getPartyInvite(Character fakechar) {
        if (fakechar == null) {
            return null;
        }

        return queues.get(fakechar.getId());
    }

    public boolean hasPendingInvite(Character fakechar) {
        return fakechar != null
                && queues.containsKey(fakechar.getId());
    }

    public void removePartyInvite(Character fakechar) {
        if (fakechar != null) {
            queues.remove(fakechar.getId());
        }
    }
}