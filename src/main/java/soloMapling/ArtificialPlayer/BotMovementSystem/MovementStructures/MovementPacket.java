package soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures;

import net.packet.InPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MovementPacket {

    private final long timestamp;
    private final InPacket packet;


    public MovementPacket(long timestamp, InPacket packet) {
        this.timestamp = timestamp;
        this.packet = packet;
    }


    public long getTimestamp() {
        return timestamp;
    }


    public InPacket getPacket() {
        return packet;
    }


    @Override
    public String toString() {
        return "Packet{timestamp=" + timestamp +
                ", data=" + Arrays.toString(packet.getBytes()) + "}";
    }


    public static MovementPacketParseResult parseNumCommandMovementPacketRecFromPacket(
            MovementPacket mp
    ) {

        InPacket p = mp.getPacket();

        p.skip(9);

        List<SingleMoveCommand> list = new ArrayList<>();

        int numCommands = p.readByte() & 0xFF;


        for (int i = 0; i < numCommands; i++) {

            int command = p.readByte() & 0xFF;

            switch (command) {


                // Normal movement
                case 0:
                case 5:
                case 17: {

                    short xpos = p.readShort();
                    short ypos = p.readShort();

                    short xwobble = p.readShort();
                    short ywobble = p.readShort();

                    short fh = p.readShort();

                    byte newstate = p.readByte();

                    short duration = p.readShort();


                    list.add(
                            new SingleMoveCommand(
                                    (byte) command,
                                    xpos,
                                    ypos,
                                    xwobble,
                                    ywobble,
                                    fh,
                                    newstate,
                                    duration
                            )
                    );

                    break;
                }


                // Relative movement
                case 1:
                case 2:
                case 6:
                case 12:
                case 13:
                case 16:
                case 18:
                case 19:
                case 20:
                case 22: {

                    p.skip(4);

                    byte newstate = p.readByte();
                    short duration = p.readShort();

                    break;
                }


                // Teleport style movement
                case 3:
                case 4:
                case 7:
                case 8:
                case 9:
                case 11: {

                    p.skip(8);

                    byte newstate = p.readByte();

                    break;
                }

                // Jump down
                case 14: {

                    p.skip(9);

                    break;
                }

                // Change equip
                case 10: {

                    p.readByte();

                    break;
                }


                // Jump down movement
                case 15: {

                    p.skip(12);

                    byte newstate = p.readByte();

                    short duration = p.readShort();

                    break;
                }

                // Aran attack movement
                case 21: {

                    p.skip(3);

                    break;
                }

                default:
                    throw new IllegalArgumentException(
                            "Unknown movement command: " + command
                    );
            }
        }

        return new MovementPacketParseResult(
                numCommands,
                list
        );
    }
}