package exerelin.skills.scripts;

import com.fs.starfarer.api.characters.CharacterStatsSkillEffect;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import exerelin.SectorManager;

public class FleetDeploymentPerk1 implements CharacterStatsSkillEffect {

    public void apply(MutableCharacterStatsAPI stats, String id, float level)
    {
        if(SectorManager.getCurrentSectorManager() != null && !SectorManager.getCurrentSectorManager().getEliteShipPerkTriggered())
        {
           // SectorManager.getCurrentSectorManager().getSectorEventManager().triggerEvent("eliteShip");
            SectorManager.getCurrentSectorManager().setEliteShipPerkTriggered(true);
        }
    }

    public void unapply(MutableCharacterStatsAPI stats, String id)
    {
    }

	public String getEffectDescription(float level) {
		return "+" + (int)(ExerelinSkillData.FACTION_FLEETDEPLOYMENT_PERK_ELITE_SHIP_CHANCE_BONUS_PERCENTAGE) + "% increased chance of deploying an additional elite capital ship with each fleet";
	}
	
	public String getEffectPerLevelDescription() {
		return "" + (int)(ExerelinSkillData.FACTION_FLEETDEPLOYMENT_PERK_ELITE_SHIP_CHANCE_BONUS_PERCENTAGE) + "%";
	}

	public ScopeDescription getScopeDescription() {
		return ScopeDescription.ALL_OUTPOSTS;
	}

}
