package exerelin.campaign.fleets;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.AsteroidAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.DisposableFleetManager;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.campaign.MiningHelperLegacy;
import exerelin.plugins.ExerelinModPlugin;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinFactionConfig;
import exerelin.utilities.ExerelinUtilsFaction;
import exerelin.utilities.ExerelinUtilsFleet;
import exerelin.utilities.StringHelper;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.histidine.industry.scripts.MiningHelper;
import org.histidine.industry.scripts.MiningHelper.MiningReport;
import org.lazywizard.lazylib.MathUtils;

public class MiningFleetManagerV2 extends DisposableFleetManager
{
	public static final String MANAGER_MAP_KEY = "exerelin_miningFleetManager";
	
	protected static final float POINT_INCREMENT_PERIOD = 1;
	protected static final float GAS_FLEET_CHANCE = 0.4f;
	
	public static Logger log = Global.getLogger(MiningFleetManagerV2.class);
	
	protected float timer = 0;
	protected float daysElapsed = 0;
		
	protected boolean hasOreFacilities(MarketAPI market)
	{
		return market.hasIndustry(Industries.MINING) || market.hasIndustry(Industries.REFINING)
				|| market.hasCondition("aiw_inorganic_populace");
	}
	
	protected boolean hasGasFacilities(MarketAPI market)
	{
		return market.hasIndustry(Industries.MINING) || market.hasIndustry(Industries.FUELPROD);
	}
	
	protected boolean isOreMineable(SectorEntityToken entity)
	{
		if (entity instanceof AsteroidAPI) return true;
		if (!(entity instanceof PlanetAPI)) return false;
		if (entity.getMarket() == null || !entity.getMarket().isPlanetConditionMarketOnly()) return false;
		if (ExerelinModPlugin.HAVE_STELLAR_INDUSTRIALIST)
		{
			float exhaustion = MiningHelper.getExhaustion(entity);
			if (exhaustion > 0.7f) return false;
			
			MiningReport report = MiningHelper.getMiningReport(null, entity, 1 - exhaustion);
			if (report.totalOutput.containsKey(Commodities.ORE))
				return report.totalOutput.get(Commodities.ORE) > 0.5;
			if (report.totalOutput.containsKey(Commodities.RARE_ORE))
				return report.totalOutput.get(Commodities.RARE_ORE) > 0.05;
			return false;
		}
		else
		{
			float exhaustion = MiningHelperLegacy.getExhaustion(entity);
			if (exhaustion > 0.7f) return false;
			
			MiningHelperLegacy.MiningReport report = MiningHelperLegacy.getMiningReport(null, entity, 1 - exhaustion);
			if (report.totalOutput.containsKey(Commodities.ORE))
				return report.totalOutput.get(Commodities.ORE) > 0.5;
			if (report.totalOutput.containsKey(Commodities.RARE_ORE))
				return report.totalOutput.get(Commodities.RARE_ORE) > 0.05;
			return false;
		}
	}
	
	protected boolean isGasMineable(SectorEntityToken entity)
	{
		if (!(entity instanceof PlanetAPI)) return false;
		if (entity.getMarket() == null || !entity.getMarket().isPlanetConditionMarketOnly()) return false;
		if (ExerelinModPlugin.HAVE_STELLAR_INDUSTRIALIST)
		{
			float exhaustion = MiningHelper.getExhaustion(entity);
			if (exhaustion > 0.7f) return false;
			
			MiningReport report = MiningHelper.getMiningReport(null, entity, 1 - exhaustion);
			if (report.totalOutput.containsKey(Commodities.VOLATILES))
				return report.totalOutput.get(Commodities.VOLATILES) > 0.4;
			return false;
		}
		else
		{
			float exhaustion = MiningHelperLegacy.getExhaustion(entity);
			if (exhaustion > 0.7f) return false;
			
			MiningHelperLegacy.MiningReport report = MiningHelperLegacy.getMiningReport(null, entity, 1 - exhaustion);
			if (report.totalOutput.containsKey(Commodities.VOLATILES))
				return report.totalOutput.get(Commodities.VOLATILES) > 0.4;
			return false;
		}
	}
	
	public CampaignFleetAPI spawnMiningFleet(MarketAPI origin)
	{
		log.info("Trying mining fleet for market " + origin.getName());
		SectorEntityToken target = null;
		
		FactionAPI faction = origin.getFaction();
		if (faction.getId().equals("templars")) return null;
		int marketSize = origin.getSize();
		int maxFP = (int)(Math.pow(marketSize, 1.5f) * 5);
		
		float qf = origin.getShipQualityFactor();
		//qf = Math.max(qf, 0.7f);
		
		boolean isGasMiningFleet = false;
		boolean hasOreFacilities = hasOreFacilities(origin);
		boolean hasGasFacilities = hasGasFacilities(origin);
		if (hasOreFacilities && hasGasFacilities)
			isGasMiningFleet = Math.random() < GAS_FLEET_CHANCE;
		else if (hasOreFacilities)
			isGasMiningFleet = false;
		else if (hasGasFacilities)
			isGasMiningFleet = true;
		else {
			//log.info("Market " + origin.getName() + " is not valid for mining operations");
			return null;
		}
		
		WeightedRandomPicker<SectorEntityToken> targetPicker = new WeightedRandomPicker<>();
		List<SectorEntityToken> planets = origin.getContainingLocation().getEntitiesWithTag("planet");
		if (!isGasMiningFleet) planets.addAll(origin.getContainingLocation().getAsteroids());
		
		for (SectorEntityToken planetToken : planets)
		{
			if (planetToken.getMarket() != null && !planetToken.getMarket().isPlanetConditionMarketOnly())
				continue;
			
			if (isGasMiningFleet)
			{
				if (isGasMineable(planetToken))
					targetPicker.add(planetToken);
			}
			else
			{
				if (!isOreMineable(planetToken))
					continue;
				
				// don't try to mine moons of hostile factions
				OrbitAPI orbit = planetToken.getOrbit();
				if (orbit != null && orbit.getFocus() != null)
				{
					FactionAPI owner = orbit.getFocus().getFaction();
					if (owner != null && owner.isHostileTo(faction)) 
						continue;
				}
				targetPicker.add(planetToken);
			}
		}
		if (targetPicker.isEmpty()) {
			//log.info("No valid target for mining fleet");
			return null;
		}
		else target = targetPicker.pick();
		if (target == null)
		{
			//log.info("Target is null; picker size " + targetPicker.getItems().size());
			return null;
		}
		
		String name = StringHelper.getString("exerelin_fleets", "miningFleetName");
		String factionId = origin.getFactionId();
		ExerelinFactionConfig factionConfig = ExerelinConfig.getExerelinFactionConfig(factionId);
		if (factionConfig != null)
		{
			name = factionConfig.asteroidMiningFleetName;
		}
		if (maxFP < 50) name = StringHelper.getString("exerelin_fleets", "miningFleetPrefixSmall") + " " + name;
		else if (maxFP > 100) name = StringHelper.getString("exerelin_fleets", "miningFleetPrefixLarge") + " " + name;
		
		//log.info("Trying to create mining fleet of size " + maxFP + ", target " + target.getName());
		FleetParamsV3 params = new FleetParamsV3(origin, "exerelinMiningFleet", 
				maxFP/4, // combat
				maxFP*0.45f, // freighters
				0,		// tankers
				0,		// personnel transports
				0,		// liners
				maxFP*0.05f,	// utility
				-0.25f);	// quality mod
		
		//CampaignFleetAPI fleet = FleetFactory.createGenericFleet(origin.getFactionId(), name, qf, maxFP/3);
		CampaignFleetAPI fleet = ExerelinUtilsFleet.customCreateFleet(faction, params);
		if (fleet == null)
			return null;
		
		fleet.setName(name);
		fleet.setAIMode(true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		
		int minerFP = (int)(maxFP * 0.25f + 0.5f);
		while (minerFP > 0)
		{
			FleetMemberAPI miner = ExerelinUtilsFleet.addMiningShipToFleet(fleet);
			minerFP -= miner.getFleetPointCost();
			//log.info("Adding miner to fleet: " + miner.getHullId());
		}
		
		float miningStrength = 0;
		if (ExerelinModPlugin.HAVE_STELLAR_INDUSTRIALIST)
		{
			miningStrength = MiningHelper.getFleetMiningStrength(fleet);
			// take machinery with us
			float machineryRequired = MiningHelper.getRequiredMachinery(miningStrength);
			float machineryToTake = Math.min(machineryRequired * 1.25f, origin.getCommodityData(Commodities.HEAVY_MACHINERY).getStockpile());
			fleet.getCargo().addCommodity(Commodities.HEAVY_MACHINERY, machineryToTake);
			origin.getCommodityData(Commodities.HEAVY_MACHINERY).removeFromStockpile(machineryToTake);
		}
		else
		{
			miningStrength = MiningHelperLegacy.getFleetMiningStrength(fleet);
			// take machinery with us
			float machineryRequired = MiningHelperLegacy.getRequiredMachinery(miningStrength);
			float machineryToTake = Math.min(machineryRequired * 1.25f, origin.getCommodityData(Commodities.HEAVY_MACHINERY).getStockpile());
			fleet.getCargo().addCommodity(Commodities.HEAVY_MACHINERY, machineryToTake);
			origin.getCommodityData(Commodities.HEAVY_MACHINERY).removeFromStockpile(machineryToTake);
		}
		
		SectorEntityToken entity = origin.getPrimaryEntity();
		entity.getContainingLocation().addEntity(fleet);
		CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
		
		boolean noWait = true;
		if (playerFleet != null && playerFleet.getContainingLocation() == entity.getContainingLocation())
			noWait = !MathUtils.isWithinRange(target, playerFleet, playerFleet.getMaxSensorRangeToDetect(fleet) + getInSystemCullRange());
		
		if (!noWait) {
			fleet.setLocation(entity.getLocation().x, entity.getLocation().y);
		}
		else {
			fleet.setLocation(target.getLocation().x, target.getLocation().y);
		}
		
		MiningFleetData data = new MiningFleetData(fleet);
		data.startingFleetPoints = fleet.getFleetPoints();
		data.sourceMarket = origin;
		data.source = origin.getPrimaryEntity();
		data.target = target;
		data.miningStrength = miningStrength;
		data.noWait = noWait;
		
		MiningFleetAI ai = new MiningFleetAI(fleet, data);
		fleet.addScript(ai);
		log.info("\tSpawned " + fleet.getNameWithFaction() + " of size " + maxFP);
		
		return fleet;
	}
	
	@Override
	protected String getActionInsideText(StarSystemAPI system) {
		return "mining";
	}
	
	@Override
	public void advance(float amount)
	{
		super.advance(amount);
		float days = Global.getSector().getClock().convertToDays(amount);
		
		timer += days;
		if (timer < POINT_INCREMENT_PERIOD) {
			return;
		}
		timer -= POINT_INCREMENT_PERIOD;
		
		if (ExerelinModPlugin.HAVE_STELLAR_INDUSTRIALIST)
			MiningHelper.renewResources(POINT_INCREMENT_PERIOD);
		else
			MiningHelperLegacy.renewResources(POINT_INCREMENT_PERIOD);
	}
	
	public static MiningFleetManagerV2 create()
	{
		Map<String, Object> data = Global.getSector().getPersistentData();
		MiningFleetManagerV2 manager = (MiningFleetManagerV2)data.get(MANAGER_MAP_KEY);
		if (manager != null)
			return manager;
		
		manager = new MiningFleetManagerV2();
		data.put(MANAGER_MAP_KEY, manager);
		return manager;
	}
	
	public static MiningFleetManagerV2 getManager()
	{
		Map<String, Object> data = Global.getSector().getPersistentData();
		return (MiningFleetManagerV2)data.get(MANAGER_MAP_KEY);
	}

	@Override
	protected int getDesiredNumFleetsForSpawnLocation() {
		StarSystemAPI system = currSpawnLoc;
		return Global.getSector().getEconomy().getMarkets(system).size();
	}

	@Override
	protected CampaignFleetAPI spawnFleetImpl() {
		StarSystemAPI system = currSpawnLoc;
		if (system == null) return null;
		
		WeightedRandomPicker<MarketAPI> picker = new WeightedRandomPicker<>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system))
		{
			if (ExerelinUtilsFaction.isPirateOrTemplarFaction(market.getFactionId()))
				continue;
			if (!hasOreFacilities(market) && !hasGasFacilities(market))
				continue;
			picker.add(market, market.getSize());
		}
		
		if (picker.isEmpty()) return null;
		MarketAPI market = picker.pick();
		
		CampaignFleetAPI fleet = spawnMiningFleet(market);
		
		return fleet;
	}

	@Override
	protected String getSpawnId() {
		return "nex_miningFleet";
	}
	
	@Override
	protected boolean isOkToDespawnAssumingNotPlayerVisible(CampaignFleetAPI fleet) {
		if (currSpawnLoc == null) return true;
		String system = fleet.getMemoryWithoutUpdate().getString(KEY_SYSTEM);
		if (system == null || !system.equals(currSpawnLoc.getName())) return true;
		return false;
	}
	
	@Override
	protected float getExpireDaysPerFleet() {
		return 30f;
	}
	
	@Override
	public float getSpawnRateMult() {
		return super.getSpawnRateMult() * 5f;
	}
	
	public static class MiningFleetData
	{
		public CampaignFleetAPI fleet;
		public SectorEntityToken source;
		public SectorEntityToken target;
		public MarketAPI sourceMarket;
		public float startingFleetPoints = 0.0F;
		public float miningStrength = 0;
		public boolean noWait = false;
	
		public MiningFleetData(CampaignFleetAPI fleet)
		{
			this.fleet = fleet;
		}
	}
}