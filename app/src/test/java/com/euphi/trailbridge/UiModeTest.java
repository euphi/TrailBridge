package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UiModeTest {

    @Test
    public void osmandIsHiddenInTheGpxModesUnlessConnected() {
        assertTrue(UiMode.OSMAND.showsOsmand(false));
        assertTrue(UiMode.ALL.showsOsmand(false));
        assertFalse(UiMode.GPX_NAV.showsOsmand(false));
        assertFalse(UiMode.SIMULATION.showsOsmand(false));
        assertTrue(UiMode.GPX_NAV.showsOsmand(true));
        assertTrue(UiMode.SIMULATION.showsOsmand(true));
    }

    @Test
    public void routeCardAndStartButton() {
        assertFalse(UiMode.OSMAND.showsRoute());
        assertTrue(UiMode.GPX_NAV.showsRoute());
        assertTrue(UiMode.SIMULATION.showsRoute());
        assertTrue(UiMode.ALL.showsRoute());
        assertTrue(UiMode.GPX_NAV.showsRouteStart());
        assertFalse(UiMode.SIMULATION.showsRouteStart());
    }

    @Test
    public void testPanelPerMode() {
        assertEquals(UiMode.TestPanel.NONE, UiMode.OSMAND.testPanel());
        assertEquals(UiMode.TestPanel.NONE, UiMode.GPX_NAV.testPanel());
        assertEquals(UiMode.TestPanel.ALWAYS, UiMode.SIMULATION.testPanel());
        assertEquals(UiMode.TestPanel.TOGGLE, UiMode.ALL.testPanel());
    }

    @Test
    public void whatSurvivesASwitch() {
        assertFalse(UiMode.OSMAND.keepsRoute(true));
        assertFalse(UiMode.OSMAND.keepsPlayback());
        assertTrue(UiMode.GPX_NAV.keepsRoute(false));
        assertFalse(UiMode.GPX_NAV.keepsPlayback());
        assertTrue(UiMode.SIMULATION.keepsRoute(true));
        assertFalse(UiMode.SIMULATION.keepsRoute(false));   // a real-GPS route would be invisible there
        assertTrue(UiMode.ALL.keepsRoute(false));
        assertTrue(UiMode.ALL.keepsPlayback());
    }

    @Test
    public void keysRoundTripAndUnknownMeansAll() {
        for (UiMode m : UiMode.values()) assertEquals(m, UiMode.fromKey(m.key));
        assertEquals(UiMode.ALL, UiMode.fromKey(null));
        assertEquals(UiMode.ALL, UiMode.fromKey("gibt-es-nicht"));
    }
}
