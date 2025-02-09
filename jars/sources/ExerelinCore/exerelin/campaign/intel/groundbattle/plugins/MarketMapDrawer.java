package exerelin.campaign.intel.groundbattle.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import com.fs.starfarer.prototype.Utils;
import exerelin.campaign.intel.groundbattle.GroundBattleIntel;
import exerelin.campaign.intel.groundbattle.GroundUnit;
import exerelin.campaign.intel.groundbattle.IndustryForBattle;
import exerelin.campaign.ui.CustomPanelPluginWithBorder;
import exerelin.campaign.ui.FramedCustomPanelPlugin;
import exerelin.utilities.rectanglepacker.Packer;
import java.awt.Color;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/**
 * Draws the tacticool planet map.
 */
public class MarketMapDrawer {
	
	/*
	TODO:
	draw arrows for last turn's enemy movements and this turn's player movements
	*/
	
	public static final boolean DEBUG_MODE = false;
	
	public static final float INDUSTRY_PANEL_BASE_WIDTH = 340;
	public static final float INDUSTRY_PANEL_BASE_HEIGHT = 190;
	
	public static Logger log = Global.getLogger(MarketMapDrawer.class);
	
	protected float width;
	protected GroundBattleIntel intel;
	protected CustomPanelAPI panel;
	protected MarketMapPanelPlugin panelPlugin;
	
	public static float getIndustryPanelSizeMult() {
		//if (true) return 0.6f;
		
		float screenWidth = Global.getSettings().getScreenWidth();
		if (screenWidth > 1920)
			return 0.9f;
		if (screenWidth >= 1600)
			return 0.7f;
		return 0.6f;
	}
	
	public static float getIndustryPanelWidth() {
		return INDUSTRY_PANEL_BASE_WIDTH * getIndustryPanelSizeMult();
	}
	
	public static float getIndustryImageWidth() {
		return 190 * getIndustryPanelSizeMult();
	}
	
	public static float getIndustryPanelHeight() {
		return INDUSTRY_PANEL_BASE_HEIGHT * getIndustryPanelSizeMult();
	}
	
	public MarketMapDrawer(GroundBattleIntel intel, CustomPanelAPI outer, float width) 
	{
		this.intel = intel;
		this.width = width;
		panelPlugin = new MarketMapPanelPlugin(this);
		panel = outer.createCustomPanel(width, width/2, panelPlugin);
	}
	
	public void init() {
		addIndustryPanels(width);
	}
	
	public void addIndustryPanels(float width) {
		int index = 0;
		float panelWidth = getIndustryPanelWidth();
		float panelHeight = getIndustryPanelHeight();
		
		List<IndustryForBattle> industries = intel.getIndustries();
		List<Rectangle> rects = new ArrayList<>();
		for (IndustryForBattle ifb : industries) {
			if (DEBUG_MODE || ifb.getPosOnMap() == null) {
				rects = new MapLocationGenV2().generateLocs(industries, width);
				//panelPlugin.debugRects = rects;
				//Global.getLogger(this.getClass()).info("lol " + rects.size());
				break;
			}
		}
		
		for (IndustryForBattle ifb : industries) {
			IFBPanelPlugin plugin = new IFBPanelPlugin(ifb);
			float x = ifb.getPosOnMap().x;
			float y = ifb.getPosOnMap().y;
			CustomPanelAPI indPanel = ifb.renderPanelNew(panel, panelWidth, panelHeight, x, y, plugin);
			
			index++;
		}
	}
	
	public CustomPanelAPI getPanel() {
		return panel;
	}
	
	
	public static class MarketMapPanelPlugin extends FramedCustomPanelPlugin {
	
		protected MarketMapDrawer map;
		protected String bg;
		protected List<Rectangle> debugRects;

		public MarketMapPanelPlugin(MarketMapDrawer map) 
		{			
			super(0.25f, map.intel.getFactionForUIColors().getBaseUIColor(), true);

			//this.faction = faction;
			this.map = map;
			this.bg = null;
			MarketAPI market = map.intel.getMarket();
			if (market.getPlanetEntity() != null)
				bg = market.getPlanetEntity().getSpec().getTexture();
		}
		
		@Override
		public void render(float alphaMult) {
			super.render(alphaMult);
			
			if (debugRects != null) {
				for (Rectangle r : debugRects) {
					drawDebugRect(r);
				}
			}
		}
		
		public void drawArrow(GroundUnit unit, IndustryForBattle from, IndustryForBattle to, boolean prevTurn) 
		{
			float x = pos.getX();
			float y = pos.getY();
			Color color = unit.getFaction().getBaseUIColor();
						
			Vector2f vFrom = from.getGraphicalPosOnMap();
			Vector2f vTo = to.getGraphicalPosOnMap();
			float height = map.width/2;
			
			Vector2f col1 = getCollisionPoint(vFrom, vTo, from.getRectangle());
			Vector2f col2 = getCollisionPoint(vFrom, vTo, to.getRectangle());
			
			if (col1 == null || col2 == null)
				return;
			
			// panel coordinates to screen coordinates
			col1.x += x; 
			col1.y = height - col1.y + y;
			col2.x += x; 
			col2.y = height - col2.y + y;
						
			renderArrow(col1, col2, 10, color, 0.4f);
		}
		
		public void drawDebugRect(Rectangle r) {
			float x = r.x + pos.getX();
			float y = r.y + pos.getY();
			GL11.glRectf(x, y, x + r.width, y + r.height);
		}

		@Override
		public void renderBelow(float alphaMult) {
			super.renderBelow(alphaMult);
			
			//if (bg == null) return;

			float x = pos.getX();
			float y = pos.getY();
			float w = pos.getWidth();
			float h = pos.getHeight();

			GL11.glPushMatrix();
			GL11.glDisable(GL11.GL_TEXTURE_2D);
			GL11.glEnable(GL11.GL_LINE_SMOOTH);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			//GL11.glEnable(GL11.GL_LINE_STIPPLE);
			
			String spriteId = this.bg != null? this.bg : map.intel.getMarket().getContainingLocation().getBackgroundTextureFilename();
			
			try {
				Global.getSettings().loadTexture(spriteId);
			} catch (IOException ex) {};
			SpriteAPI bgSprite = Global.getSettings().getSprite(spriteId);
			float drawW = w-4;
			float drawH = h-2;
			if (bg == null) {
				// FIXME: this distorts the background
				// but I have absolutely no idea how renderRegionAtCenter is supposed to work
				bgSprite.setSize(drawW, drawH);
				bgSprite.renderAtCenter(x + w/2, y + h/2);
				
				/*
				bgSprite.renderRegionAtCenter(x + w/2, y + h/2,
							(bgSprite.getTextureWidth()-drawW)/2,
							(bgSprite.getTextureHeight()-drawH)/2,
							drawW, drawH
						);
				*/
				//bgSprite.renderRegion(x, y,0, 0, 512, 512);
			}
			else {
				bgSprite.setSize(drawW, drawH);
				bgSprite.renderAtCenter(x + w/2, y + h/2);
			}
			
			
			for (GroundUnit unit : map.intel.getMovedFromLastTurn().keySet()) {
				IndustryForBattle prev = map.intel.getMovedFromLastTurn().get(unit);
				IndustryForBattle curr = unit.getLocation();
				if (curr != null) continue;
				drawArrow(unit, prev, curr, true);
			}
			for (GroundUnit unit : map.intel.getPlayerData().getUnits()) {
				IndustryForBattle curr = unit.getLocation();
				IndustryForBattle next = unit.getDestination();
				if (curr == null || next == null) continue;
				drawArrow(unit, curr, next, false);
			}
			GL11.glDisable(GL11.GL_LINE_STIPPLE);
			GL11.glPopMatrix();
		}
	}
	
	public static class IFBPanelPlugin extends CustomPanelPluginWithBorder {
		protected static final Color BG_COLOR = new Color(0f, 0f, 0f, 0.5f);
		
		//protected GroundBattleIntel intel;
		protected IndustryForBattle ifb;
		protected boolean isContested;
		protected int interval;
		protected short stippleIndex = 7;
				
		public IFBPanelPlugin(IndustryForBattle ifb) {
			super(getColor(ifb), BG_COLOR);
			this.ifb = ifb;
			isContested = ifb.isContested();
		}
		
		protected static Color getColor(IndustryForBattle ifb) {
			Color color = Misc.getPositiveHighlightColor();
			if (ifb.heldByAttacker != ifb.getIntel().isPlayerAttackerForGUI())
				color = Misc.getNegativeHighlightColor();
			return color;
		}
				
		@Override
		public void render(float alphaMult) {
			if (isContested) {
				//GL11.glEnable(GL11.GL_LINE_STIPPLE);
				GL11.glLineWidth(2);
				//GL11.glLineStipple(2, (short)stippleIndex);
				alphaMult *= 0.5f + 0.5f * Math.sin(interval/60f * Math.PI);
				drawBorder(alphaMult);
				//GL11.glDisable(GL11.GL_LINE_STIPPLE);
			}
			else {
				GL11.glLineWidth(1);
				drawBorder(alphaMult);
			}
		}

		@Override
		public void advance(float amount) {
			interval++;
			if (interval > 60) {
				interval = 0;
				//stippleIndex++;
			}			
		}
	}
	
	/**
	 * Generates a semi-random location on the map for each of the industries.
	 */
	public static class MapLocationGenV2 {
		public List<Rectangle> rects = new ArrayList<>();
		
		public List<Rectangle> generateLocs(List<IndustryForBattle> industries, float mapWidth) 
		{
			// work with a copy of the actual arg, since we'll be modifying it
			industries = new LinkedList<>(industries);
			
			float baseWidth = getIndustryPanelWidth();
			float baseHeight = getIndustryPanelHeight();
			
			int max = industries.size() + Math.max(0, 6 - industries.size()/2);
			for (int i=0; i<max; i++) {
				float width = baseWidth * MathUtils.getRandomNumberInRange(1.1f, 1.5f);
				float height = baseHeight * MathUtils.getRandomNumberInRange(1.1f, 1.3f);
				rects.add(new Rectangle(0, 0, (int)width, (int)height));
			}
			
			// add some extra unusable rectangles to mess up the tesselation
			List<Rectangle> rectsForPack = new ArrayList<>(rects);
			int maxExtra = Math.max(3, 14 - industries.size());
			for (int i=0; i<maxExtra; i++) {
				float width = baseWidth * MathUtils.getRandomNumberInRange(0.3f, 0.5f);
				float height = baseHeight * MathUtils.getRandomNumberInRange(0.3f, 0.5f);
				rectsForPack.add(new Rectangle(0, 0, (int)width, (int)height));
			}
			
			Packer.pack(rectsForPack, Packer.Algorithm.BEST_FIT_DECREASING_HEIGHT, (int)mapWidth);
			
			int offsetX = (int)(mapWidth - getRightmostPoint())/2;
			int offsetY = (int)(mapWidth/2 - getLowestPoint())/2;
			for (Rectangle r : rects) {
				r.x += offsetX;
				r.y += offsetY;
			}				
			
			// Pick random rectangles for our industries
			WeightedRandomPicker<Rectangle> picker = new WeightedRandomPicker<>();
			picker.addAll(rects);
						
			List<Rectangle> toUse = new LinkedList<>();
			for (int i=0; i<industries.size(); i++) {
				toUse.add(picker.pickAndRemove());
			}
			
			// Give the rectangle closest to the center to spaceport?
			// center being the average position of selected rects rather than the map center
			IndustryForBattle spaceport = null;
			for (IndustryForBattle ifb : industries) {
				if (ifb.getIndustry().getSpec().hasTag(Industries.TAG_SPACEPORT)) {
					spaceport = ifb;
					break;
				}
			}
			
			if (spaceport != null) {
				Rectangle best = getClosestToCenter(toUse);
				toUse.remove(best);
				assignPosFromRectangle(spaceport, best, baseWidth, baseHeight);
				industries.remove(spaceport);
			}
			
			// assign the other industries
			for (IndustryForBattle ifb : industries) {
				Rectangle r = toUse.remove(0);
				assignPosFromRectangle(ifb, r, baseWidth, baseHeight);
			}
			
			return rects;
		}
		
		public void assignPosFromRectangle(IndustryForBattle ifb, Rectangle r,
				float baseWidth, float baseHeight) 
		{
			int x = r.x;
			int y = r.y;
			
			// vary the actual position within the available rectangle
			x += Math.random() * (r.width - baseWidth);
			y += Math.random() * (r.height - baseHeight);

			//Global.getLogger(this.getClass()).info(String.format("%s location on map: %s, %s", ifb.getName(), x, y));
			ifb.setPosOnMap(new Vector2f(x, y));
		}
		
		public int getRightmostPoint() {
			int best = 0;
			for (Rectangle r : rects) {
				int curr = r.x + r.width;
				if (curr > best) best = curr;
			}
			return best;
		}
		
		public int getLowestPoint() {
			int best = 0;
			for (Rectangle r : rects) {
				int curr = r.y + r.height;
				if (curr > best) best = curr;
			}
			return best;
		}
		
		public Rectangle getClosestToCenter(List<Rectangle> rects) {
			Vector2f avg = getAveragePosition(rects);
			
			float bestDistSq = Float.MAX_VALUE;
			Rectangle best = null;
			for (Rectangle r : rects) {
				Vector2f center = new Vector2f((float)r.getCenterX(), (float)r.getCenterY());
				float distSq = MathUtils.getDistanceSquared(center, avg);
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = r;
				}
			}
			return best;
		}
		
		public Vector2f getAveragePosition(List<Rectangle> rects) {
			float x = 0, y = 0;
			for (Rectangle r : rects) {
				x += r.getCenterX();
				y += r.getCenterY();
			}
			x /= rects.size();
			y /= rects.size();
			return new Vector2f(x, y);
		}
	}
		
	public static List<Pair<Vector2f, Vector2f>> getRectangleSides(Rectangle rect) {
		Vector2f tl = new Vector2f(rect.x, rect.y);
		Vector2f tr = new Vector2f(rect.x + rect.width, rect.y);
		Vector2f bl = new Vector2f(rect.x, rect.y + rect.height);
		Vector2f br = new Vector2f(rect.x + rect.width, rect.y + rect.height);
		ArrayList<Pair<Vector2f, Vector2f>> result = new ArrayList<>();
		result.add(new Pair<>(tl, tr));
		result.add(new Pair<>(tr, br));
		result.add(new Pair<>(br, bl));
		result.add(new Pair<>(bl, tl));
		
		return result;
	}
	
	// Adapted from LazyLib's CollisionUtils
	public static Vector2f getCollisionPoint(Vector2f lineStart,
                                             Vector2f lineEnd, Rectangle rect)
    {
        Vector2f closestIntersection = null;
		List<Pair<Vector2f, Vector2f>> sides = getRectangleSides(rect);
		
        for (Pair<Vector2f, Vector2f> side : sides)
        {
            Vector2f intersection = CollisionUtils.getCollisionPoint(lineStart, lineEnd, side.one, side.two);
            // Collision = true
            if (intersection != null)
            {
                if (closestIntersection == null)
                {
                    closestIntersection = new Vector2f(intersection);
                }
                else if (MathUtils.getDistanceSquared(lineStart, intersection)
                        < MathUtils.getDistanceSquared(lineStart, closestIntersection))
                {
                    closestIntersection.set(intersection);
                }
            }
        }

        // Null if no segment was hit
        // FIXME: Lines completely within bounds return null (would affect custom fighter weapons)
       return closestIntersection;
    }
	
	// from base game code: https://fractalsoftworks.com/forum/index.php?topic=5061.msg336833#msg336833
	protected static SpriteAPI texture = Global.getSettings().getSprite("graphics/hud/line32x32.png");
	protected static void renderArrow(Vector2f from, Vector2f to, float startWidth, Color color, float maxAlpha) {
		float dist = MathUtils.getDistance(from, to);
		Vector2f dir = Vector2f.sub(from, to, new Vector2f());
		if (dir.x != 0 || dir.y != 0) {
			dir.normalise();
		} else {
			return;
		}
		float arrowHeadLength = Math.min(startWidth * 0.4f * 3f, dist * 0.5f);
		dir.scale(arrowHeadLength);
		Vector2f arrowEnd = Vector2f.add(to, dir, new Vector2f());
		
		// main body of the arrow
		renderFan(from.x, from.y, arrowEnd.x, arrowEnd.y, startWidth, startWidth * 0.4f, color,
				//0f, maxAlpha * 0.5f, maxAlpha);
				maxAlpha * 0.2f, maxAlpha * 0.67f, maxAlpha);
		// arrowhead, tapers to a width of 0
		renderFan(arrowEnd.x, arrowEnd.y, to.x, to.y, startWidth * 0.4f * 3f, 0f, color,
				maxAlpha, maxAlpha, maxAlpha);
	}
	
	
	protected static void renderFan(float x1, float y1, float x2, float y2, float startWidth, float endWidth, Color color, float edge1, float mid, float edge2) {
		//HudRenderUtils.renderFan(texture, x1, y1, x2, y2, startWidth, endWidth, color, edge1, mid, edge2, false);
		edge1 *= 0.4f;
		edge2 *= 0.4f;
		mid *= 0.4f;
		boolean additive = true;
		//additive = false;
		renderFan(texture, x1, y1, x2, y2, startWidth, endWidth, color, edge1, mid, edge2, additive);
		renderFan(texture, x1 + 0.5f, y1 + 0.5f, x2 + 0.5f, y2 + 0.5f, startWidth, endWidth, color, edge1, mid, edge2, additive);
		renderFan(texture, x1 - 0.5f, y1 - 0.5f, x2 - 0.5f, y2 - 0.5f, startWidth, endWidth, color, edge1, mid, edge2, additive);
	}
	
	public static void renderFan(SpriteAPI texture, float x1, float y1, float x2, float y2, 
			float startWidth, float endWidth, Color color, 
			float edge1, float mid, float edge2, boolean additive) {

		GL11.glPushMatrix();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		
		texture.bindTexture();
		
		GL11.glEnable(GL11.GL_BLEND);
		if (additive) {
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		} else {
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		}

		Vector2f v1 = new Vector2f(x2 - x1, y2 - y1);
		v1.normalise();
		v1.set(v1.y, -v1.x);
		Vector2f v2 = new Vector2f(v1);
		v1.scale(startWidth * 0.5f);
		v2.scale(endWidth * 0.5f);

		GL11.glBegin(GL11.GL_TRIANGLE_FAN);
		{
			// center
			GL11.glColor4ub((byte)color.getRed(), (byte)color.getGreen(), (byte)color.getBlue(), (byte)((float)color.getAlpha() * mid));
			GL11.glTexCoord2f(0.5f, 0.5f);
			GL11.glVertex2f((x2 + x1) * 0.5f, (y2 + y1) * 0.5f);
			
			// start
			GL11.glColor4ub((byte)color.getRed(), (byte)color.getGreen(), (byte)color.getBlue(), (byte)((float)color.getAlpha() * edge1));
			GL11.glTexCoord2f(0, 0);
			GL11.glVertex2f(x1 - v1.x, y1 - v1.y);
			GL11.glTexCoord2f(0, 1);
			GL11.glVertex2f(x1 + v1.x, y1 + v1.y);
			
			// end
			GL11.glColor4ub((byte)color.getRed(), (byte)color.getGreen(), (byte)color.getBlue(), (byte)((float)color.getAlpha() * edge2));
			GL11.glTexCoord2f(1, 1);
			GL11.glVertex2f(x2 + v2.x, y2 + v2.y);
			GL11.glTexCoord2f(1, 0);
			GL11.glVertex2f(x2 - v2.x, y2 - v2.y);
			
			// wrap back around to start
			GL11.glColor4ub((byte)color.getRed(), (byte)color.getGreen(), (byte)color.getBlue(), (byte)((float)color.getAlpha() * edge1));
			GL11.glTexCoord2f(0, 0);
			GL11.glVertex2f(x1 - v1.x, y1 - v1.y);
		}
		GL11.glEnd();
		GL11.glPopMatrix();
	}
}
