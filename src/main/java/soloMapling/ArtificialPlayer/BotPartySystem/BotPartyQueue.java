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

    private final ConcurrentHashMap<Character, PartyInviteEntry> queues;
    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    // Last-wins: the entry always mirrors the LATEST invite the engine actually created.
    // Concurrent-invite serialization is already the InviteCoordinator's job (its putIfAbsent
    // refuses a second live invite), so first-wins here only ever preserved STALE entries:
    // the coordinator expires an unanswered invite after ~3 min but this queue never did, and
    // a bot answering with the stale entry's old partyId hit NOT_FOUND at the coordinator -
    // leaving the player's live invite wedged ("taking care of another invitation") for 3 min.
    public void addPartyInvite(Character fakechar, Character inviter, int partyId) {
        if (fakechar == null) {
            debugprint("addPartyInvite: fakechar is null.");
            return;
        }

        if (inviter == null) {
            debugprint(
                    "addPartyInvite: inviter is null for bot="
                            + fakechar.getName()
                            + ", partyId=" + partyId
            );
            return;
        }

        PartyInviteEntry entry = new PartyInviteEntry(inviter, partyId);

        queues.put(fakechar, entry);

        debugprint(
                "addPartyInvite: STORED invite"
                        + " bot=" + fakechar.getName()
                        + " botId=" + fakechar.getId()
                        + " inviter=" + inviter.getName()
                        + " inviterId=" + inviter.getId()
                        + " partyId=" + partyId
        );

        debugprint(
                "addPartyInvite: waking bot "
                        + fakechar.getName()
        );

        BotRecruitManager.wakeBotForInvite(fakechar);

    }
}
