package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;

public class JobCommand extends Command {
    {
        setDescription("Change job of a player.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        if (params.length == 1) {
            Integer jobId = parseJobId(player, params[0]);

            if (jobId == null) {
                return;
            }

            if (changeJob(player, jobId)) {
                player.message("Your job has been changed to " + jobId + ".");
            }
        }
        else if (params.length == 2) {
            Character victim = c.getWorldServer()
                    .getPlayerStorage()
                    .getCharacterByName(params[0]);

            if (victim == null) {
                player.message("Player '" + params[0] + "' could not be found.");
                return;
            }

            Integer jobId = parseJobId(player, params[1]);

            if (jobId == null) {
                return;
            }

            if (changeJob(victim, jobId)) {
                player.message("Changed " + victim.getName() + "'s job to " + jobId + ".");
                victim.message("Your job has been changed to " + jobId + " by a GM.");
            }
        }
        else {
            player.message("Syntax: !job <job id> OR !job <IGN> <job id>");
        }
    }

    private Integer parseJobId(Character player, String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            player.message("Invalid job id: " + input);
            return null;
        }
    }

    private boolean changeJob(Character chr, int jobId) {
        Job job = Job.getById(jobId);

        if (job == null) {
            chr.message("Jobid " + jobId + " is not available.");
            return false;
        }

        chr.changeJob(job);
        chr.equipChanged();

        return true;
    }
}