package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteResult;
import net.server.coordinator.world.InviteCoordinator.InviteResultType;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import server.maps.MapleMap;
import tools.PacketCreator;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyCommands {


    public static boolean botMakeParty(Character fakechar) {
        if (fakechar == null) {
            return false;
        }

        if (fakechar.getParty() != null) {
            debugprint("botMakeParty: bot already in a party, skipping.");
            return false;
        }

        boolean created = Party.createParty(fakechar, false);
        debugprint("botMakeParty: bot=" + fakechar.getName() + " created=" + created);
        return created;
    }

    // Server-side only leave. Bots do not need the normal player-client cleanup.
    public static void botLeaveParty(Character fakechar) {
        if (fakechar == null) {
            return;
        }

        Party party = fakechar.getParty();
        if (party == null) {
            debugprint("botLeaveParty: no party, skipping.");
            return;
        }

        PartyCharacter botPC = fakechar.getMPC();
        if (botPC == null) {
            botPC = new PartyCharacter(fakechar);
        }

        World world = fakechar.getWorldServer();
        int partyId = party.getId();

        if (botPC.getId() == party.getLeaderId()) {
            world.removeMapPartyMembers(partyId);
            world.updateParty(partyId, PartyOperation.DISBAND, botPC);

            debugprint(
                    "botLeaveParty: bot=" + fakechar.getName()
                            + " disbanded party " + partyId
            );
        } else {
            MapleMap map = fakechar.getMap();

            if (map != null) {
                map.removePartyMember(fakechar, partyId);
            }

            world.updateParty(partyId, PartyOperation.LEAVE, botPC);

            debugprint(
                    "botLeaveParty: bot=" + fakechar.getName()
                            + " left party " + partyId
            );
        }

        fakechar.setParty(null);
    }

    /**
     * Accept the currently queued party invitation.
     *
     * IMPORTANT:
     * The incoming party-invite packet must first be intercepted by the bot
     * party handler and stored in BotPartyQueue. If the normal player handler
     * rejects bot invitations before this method runs, there will be no queue
     * entry to accept.
     */
    public static boolean botAcceptPartyInvite(Character fakechar) {
        if (fakechar == null) {
            return false;
        }

        if (fakechar.getParty() != null) {
            debugprint(
                    "botAcceptPartyInvite: bot already has party: "
                            + fakechar.getName()
            );
            return false;
        }

        BotPartyQueue.PartyInviteEntry entry =
                BotPartyQueue.getInstance().getPartyInvite(fakechar);

        if (entry == null) {
            debugprint(
                    "botAcceptPartyInvite: no pending invite for "
                            + fakechar.getName()
            );
            return false;
        }

        int partyId = entry.getPartyId();

        InviteResult res = InviteCoordinator.answerInvite(
                InviteType.PARTY,
                fakechar.getId(),
                partyId,
                true
        );

        if (res.result != InviteResultType.ACCEPTED) {
            BotPartyQueue.getInstance().removePartyInvite(fakechar);

            debugprint(
                    "botAcceptPartyInvite: coordinator rejected invite. "
                            + "bot=" + fakechar.getName()
                            + " partyId=" + partyId
                            + " result=" + res.result
            );

            return false;
        }

        boolean joined = Party.joinParty(fakechar, partyId, false);

        BotPartyQueue.getInstance().removePartyInvite(fakechar);

        if (!joined) {
            Character inviter = entry.getInviter();

            if (inviter != null) {
                inviter.sendPacket(
                        PacketCreator.serverNotice(
                                5,
                                fakechar.getName()
                                        + " couldn't join your party "
                                        + "(it was full or disbanded)."
                        )
                );
            }

            debugprint(
                    "botAcceptPartyInvite: Party.joinParty failed. "
                            + "bot=" + fakechar.getName()
                            + " partyId=" + partyId
            );

            return false;
        }

        debugprint(
                "botAcceptPartyInvite: SUCCESS. "
                        + "bot=" + fakechar.getName()
                        + " joined partyId=" + partyId
        );

        return true;
    }

    /**
     * Reject a queued party invitation.
     *
     * This should ONLY be called when the bot's AI explicitly decides to
     * decline. It should not be the default path for incoming invitations.
     */
    public static boolean botRejectPartyInvite(Character fakechar) {
        if (fakechar == null) {
            return false;
        }

        BotPartyQueue.PartyInviteEntry entry =
                BotPartyQueue.getInstance().getPartyInvite(fakechar);

        if (entry == null) {
            debugprint(
                    "botRejectPartyInvite: no pending invite for "
                            + fakechar.getName()
            );
            return false;
        }

        InviteResult res = InviteCoordinator.answerInvite(
                InviteType.PARTY,
                fakechar.getId(),
                entry.getPartyId(),
                false
        );

        BotPartyQueue.getInstance().removePartyInvite(fakechar);

        Character inviter = entry.getInviter();

        if (inviter != null && res.result == InviteResultType.DENIED) {
            inviter.sendPacket(
                    PacketCreator.serverNotice(
                            5,
                            fakechar.getName()
                                    + " has declined your party request."
                    )
            );
        }

        debugprint(
                "botRejectPartyInvite: bot=" + fakechar.getName()
                        + " result=" + res.result
        );

        return res.result == InviteResultType.DENIED;
    }

    /**
     * Bot sends a party invitation to a real player.
     *
     * If the bot has no party, it creates one and becomes leader.
     */
    public static boolean botInvitePlayer(
            Character fakechar,
            Character target
    ) {
        if (fakechar == null || target == null) {
            debugprint("botInvitePlayer: null bot or target.");
            return false;
        }

        if (target.getParty() != null) {
            debugprint(
                    "botInvitePlayer: target already in a party."
            );
            return false;
        }

        Party party = fakechar.getParty();

        if (party == null) {
            if (!Party.createParty(fakechar, false)) {
                debugprint(
                        "botInvitePlayer: failed to create party for bot."
                );
                return false;
            }

            party = fakechar.getParty();
        }

        if (party == null) {
            debugprint(
                    "botInvitePlayer: party still null after creation."
            );
            return false;
        }

        if (party.getLeaderId() != fakechar.getId()) {
            debugprint(
                    "botInvitePlayer: bot is not party leader."
            );
            return false;
        }

        if (party.getMembers().size() >= 6) {
            debugprint(
                    "botInvitePlayer: party is full."
            );
            return false;
        }

        boolean created = InviteCoordinator.createInvite(
                InviteType.PARTY,
                fakechar,
                party.getId(),
                target.getId()
        );

        if (!created) {
            debugprint(
                    "botInvitePlayer: InviteCoordinator rejected invite."
            );
            return false;
        }

        target.sendPacket(
                PacketCreator.partyInvite(fakechar)
        );

        debugprint(
                "botInvitePlayer: invite sent to "
                        + target.getName()
                        + " for partyId=" + party.getId()
        );

        return true;
    }


}
