/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

import static java.util.concurrent.TimeUnit.DAYS;

public class ItemCommand extends Command {
    {
        setDescription("Spawn an item into your inventory.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character target = c.getPlayer();
        Character executor = c.getPlayer();

        int index = 0;

        // If the first parameter isn't a number, assume it's a player name.
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

        if (params.length <= index) {
            executor.yellowMessage("Syntax: !item [player] <itemid> <quantity>");
            return;
        }

        int itemId;
        try {
            itemId = Integer.parseInt(params[index]);
        } catch (NumberFormatException e) {
            executor.yellowMessage("Invalid item id.");
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        if (ii.getName(itemId) == null) {
            executor.yellowMessage("Item id '" + params[index] + "' does not exist.");
            return;
        }

        short quantity = 1;
        if (params.length > index + 1) {
            quantity = Short.parseShort(params[index + 1]);
        }

        if (YamlConfig.config.server.BLOCK_GENERATE_CASH_ITEM && ii.isCash(itemId)) {
            executor.yellowMessage("You cannot create a cash item with this command.");
            return;
        }

        if (ItemConstants.isPet(itemId)) {
            if (params.length > index + 1) {
                quantity = 1;
                long days = Math.max(1, Integer.parseInt(params[index + 1]));
                long expiration = System.currentTimeMillis() + DAYS.toMillis(days);
                int petid = Pet.createPet(itemId);

                InventoryManipulator.addById(
                        target.getClient(),
                        itemId,
                        quantity,
                        executor.getName(),
                        petid,
                        expiration);

                executor.yellowMessage("Created pet for " + target.getName() + ".");
                return;
            } else {
                executor.yellowMessage("Pet Syntax: !item [player] <itemid> <expiration>");
                return;
            }
        }

        short flag = 0;
        if (target.gmLevel() < 3) {
            flag |= ItemConstants.ACCOUNT_SHARING;
            flag |= ItemConstants.UNTRADEABLE;
        }

        InventoryManipulator.addById(
                target.getClient(),
                itemId,
                quantity,
                executor.getName(),
                -1,
                flag,
                -1);

        if (target != executor) {
            executor.yellowMessage("Gave " + quantity + " x " + ii.getName(itemId)
                    + " to " + target.getName() + ".");
            target.yellowMessage("You received " + quantity + " x "
                    + ii.getName(itemId) + " from " + executor.getName() + ".");
        }
    }

}