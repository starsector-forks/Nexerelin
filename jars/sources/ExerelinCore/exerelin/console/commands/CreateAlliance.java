package exerelin.console.commands;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import exerelin.campaign.AllianceManager;
import exerelin.campaign.AllianceManager.Alignment;
import exerelin.utilities.ExerelinUtils;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.CommandUtils;
import org.lazywizard.console.CommonStrings;
import org.lazywizard.console.Console;

public class CreateAlliance implements BaseCommand {

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context != CommandContext.CAMPAIGN_MAP) {
            Console.showMessage(CommonStrings.ERROR_CAMPAIGN_ONLY);
            return CommandResult.WRONG_CONTEXT;
        }
        
        if (args.isEmpty())
        {
            return CommandResult.BAD_SYNTAX;
        }

        String[] tmp = args.split(" ");

        if (tmp.length < 2)
        {
            return CommandResult.BAD_SYNTAX;
        }

        String faction1 = tmp[0];
        String faction2 = tmp[1];

        Alignment alignment = (Alignment) ExerelinUtils.getRandomArrayElement(Alignment.values());
        if (tmp.length == 3)
        {
            try
            {
                alignment = Alignment.valueOf(tmp[2].toUpperCase());
            }
            catch (Exception ex)
            {
                Console.showMessage("Error: invalid alignment for alliance!");
                return CommandResult.BAD_SYNTAX;
            }
        }
        if (faction1.equals(Factions.PLAYER) || faction2.equals(Factions.PLAYER))
        {
            Console.showMessage("Error: player faction not supported for alliances!");
            Console.showMessage("(player_npc works as long as the faction is alive)");
            return CommandResult.ERROR;
        }

        FactionAPI fac1 = CommandUtils.findBestFactionMatch(faction1);
        FactionAPI fac2 = CommandUtils.findBestFactionMatch(faction2);

        if (fac1 == null)
        {
            Console.showMessage("Error: no such faction '" + faction1 + "'!");
            return CommandResult.ERROR;
        }
        if (fac2 == null)
        {
            Console.showMessage("Error: no such faction '" + faction2 + "'!");
            return CommandResult.ERROR;
        }

        AllianceManager.createAlliance(fac1.getId(), fac2.getId(), alignment);
        Console.showMessage("Created " + alignment.toString().toLowerCase() + " alliance between "
                + CommandUtils.getFactionName(fac1) + " and "
                + CommandUtils.getFactionName(fac2));
        return CommandResult.SUCCESS;
    }
}