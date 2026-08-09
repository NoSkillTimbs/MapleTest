package soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures;

import java.util.List;

public class MovementPacketParseResult {

    private final int numCommands;
    private final List<SingleMoveCommand> commands;

    public MovementPacketParseResult(int numCommands, List<SingleMoveCommand> commands) {
        this.numCommands = numCommands;
        this.commands = commands;
    }

    public int getNumCommands() {
        return numCommands;
    }

    public List<SingleMoveCommand> getCommands() {
        return commands;
    }
}