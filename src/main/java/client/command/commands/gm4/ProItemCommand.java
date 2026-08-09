package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

public class ProItemCommand extends Command {

    {
        setDescription("Spawn an item with custom stats.");
    }

    @Override
    public void execute(Client c, String[] params) {

        if (params.length < 1) {
            c.getPlayer().yellowMessage(
                    "Syntax: !proitem [player] <itemid> str=30 watk=50 hp=300 jump=5 slots=7");
            return;
        }

        Character executor = c.getPlayer();
        Character target = executor;

        int index = 0;

        // If first parameter isn't a number, assume it's a player name.
        try {
            Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            target = c.getWorldServer()
                    .getPlayerStorage()
                    .getCharacterByName(params[0]);

            if (target == null) {
                executor.yellowMessage("Player '" + params[0] + "' is not online.");
                return;
            }

            index = 1;
        }

        if (params.length < index + 2) {
            executor.yellowMessage(
                    "Syntax: !proitem [player] <itemid> str=30 watk=50 hp=300 jump=5 slots=7");
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        int itemId;

        try {
            itemId = Integer.parseInt(params[index]);
        } catch (NumberFormatException e) {
            executor.yellowMessage("Invalid item id.");
            return;
        }

        if (ii.getName(itemId) == null) {
            executor.yellowMessage("Item does not exist.");
            return;
        }

        if (ItemConstants.getInventoryType(itemId) != InventoryType.EQUIP) {
            executor.yellowMessage("Item must be an equip.");
            return;
        }

        Equip equip = (Equip) ii.getEquipById(itemId);
        equip.setOwner("");

        for (int i = index + 1; i < params.length; i++) {

            String[] split = params[i].split("=");

            if (split.length != 2)
                continue;

            String stat = split[0].toLowerCase();

            short value;

            try {
                value = (short) Math.max(0, Integer.parseInt(split[1]));
            } catch (NumberFormatException e) {
                continue;
            }

            switch (stat) {

                case "str":
                    equip.setStr(value);
                    break;

                case "dex":
                    equip.setDex(value);
                    break;

                case "int":
                    equip.setInt(value);
                    break;

                case "luk":
                    equip.setLuk(value);
                    break;

                case "watk":
                case "attack":
                    equip.setWatk(value);
                    break;

                case "matk":
                case "magic":
                    equip.setMatk(value);
                    break;

                case "wdef":
                    equip.setWdef(value);
                    break;

                case "mdef":
                    equip.setMdef(value);
                    break;

                case "acc":
                    equip.setAcc(value);
                    break;

                case "avoid":
                case "avoidability":
                    equip.setAvoid(value);
                    break;

                case "speed":
                    equip.setSpeed(value);
                    break;

                case "jump":
                    equip.setJump(value);
                    break;

                case "hp":
                    equip.setHp(value);
                    break;

                case "mp":
                    equip.setMp(value);
                    break;

                case "slots":
                case "slot":
                    equip.setUpgradeSlots((byte) value);
                    break;
            }
        }

        // Leave the flags untouched so the item remains tradeable.
        InventoryManipulator.addFromDrop(target.getClient(), equip);

        if (target == executor) {
            executor.yellowMessage("Created: " + ii.getName(itemId));
        } else {
            executor.yellowMessage("Created " + ii.getName(itemId)
                    + " for " + target.getName() + ".");
            target.yellowMessage("You received a custom "
                    + ii.getName(itemId) + " from " + executor.getName() + ".");
        }
    }
}