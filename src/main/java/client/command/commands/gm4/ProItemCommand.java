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

        Character player = c.getPlayer();

        if (params.length < 2) {
            player.yellowMessage("Syntax:");
            player.yellowMessage("!proitem <itemid> str=30 watk=50 hp=300 jump=5 slots=7");
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        int itemId;

        try {
            itemId = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Invalid item id.");
            return;
        }

        if (ii.getName(itemId) == null) {
            player.yellowMessage("Item does not exist.");
            return;
        }

        if (ItemConstants.getInventoryType(itemId) != InventoryType.EQUIP) {
            player.yellowMessage("Item must be an equip.");
            return;
        }

        Equip equip = (Equip) ii.getEquipById(itemId);
        equip.setOwner("");

        for (int i = 1; i < params.length; i++) {

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
        InventoryManipulator.addFromDrop(c, equip);

        player.yellowMessage("Created: " + ii.getName(itemId));
    }
}

