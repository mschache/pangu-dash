package io.github.mschache.karoopro

import io.github.mschache.karoopro.calc.TargetKind
import io.github.mschache.karoopro.calc.TargetStatus
import io.github.mschache.karoopro.calc.WorkoutState
import io.github.mschache.karoopro.data.Panel
import io.github.mschache.karoopro.data.PanelController
import io.github.mschache.karoopro.data.PanelSetting
import io.github.mschache.karoopro.data.PanelTargets
import io.github.mschache.karoopro.data.Profile
import io.github.mschache.karoopro.data.Settings
import io.github.mschache.karoopro.data.SettingsJson
import io.github.mschache.karoopro.ui.field.CoachContent
import io.github.mschache.karoopro.ui.field.CompactCells
import io.github.mschache.karoopro.ui.field.CompactLayout
import io.github.mschache.karoopro.ui.field.CompactNumber
import io.github.mschache.karoopro.ui.field.CompactText
import io.github.mschache.karoopro.ui.field.DemoSource
import io.github.mschache.karoopro.ui.field.FieldModelBuilder
import io.github.mschache.karoopro.ui.field.Palette
import io.github.mschache.karoopro.ui.field.RideContent
import io.github.mschache.karoopro.ui.field.TileModel
import io.github.mschache.karoopro.ui.field.WeatherContent
import io.github.mschache.karoopro.ui.field.WorkoutContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDeckTest {
    @Test fun panelsFieldStartsWithTheBigNumber() {
        assertEquals(listOf(Panel.NUMBER, Panel.RIDE, Panel.CLIMB), PanelController.deck(listOf(Panel.RIDE, Panel.CLIMB), withNumber = true))
        assertEquals(listOf(Panel.NUMBER), PanelController.deck(emptyList(), withNumber = true))
        // The full field never gets the big number as a panel, and falls back to Ride when everything is hidden.
        assertEquals(listOf(Panel.RIDE), PanelController.deck(emptyList(), withNumber = false))
        assertEquals(listOf(Panel.NUMBER, Panel.LAP), PanelController.deck(listOf(Panel.NUMBER, Panel.LAP), withNumber = true))
    }

    @Test fun bonusActionsFlipTheFieldOnScreen() {
        val full = "TYPE_EXT::karoopro::pro"
        val panels = "TYPE_EXT::karoopro::panels"
        assertEquals(setOf(PanelTargets.PANELS), PanelTargets.forPage(listOf("TYPE_MAP", panels), full, panels))
        assertEquals(setOf(PanelTargets.FULL), PanelTargets.forPage(listOf(full), full, panels))
        assertEquals(setOf(PanelTargets.FULL, PanelTargets.PANELS), PanelTargets.forPage(listOf(full, panels), full, panels))
        // On a page without either, the actions keep flipping the full field as before.
        assertEquals(setOf(PanelTargets.FULL), PanelTargets.forPage(listOf("TYPE_SPEED"), full, panels))
    }

    @Test fun settingsNeverListTheBigNumber() {
        assertFalse(Settings().orderedPanels.any { it.panel == Panel.NUMBER })
        val stored = Settings(panels = listOf(PanelSetting(Panel.NUMBER), PanelSetting(Panel.LAP, enabled = false)))
        val ordered = stored.orderedPanels.map { it.panel }
        assertFalse(Panel.NUMBER in ordered)
        assertEquals(Panel.LAP, ordered.first())
        assertEquals(Panel.PANELS.size, ordered.size)
        // Settings saved before the Panels field existed still load with every panel.
        val old = SettingsJson.decodeFromString(Settings.serializer(), """{"panels":[{"panel":"RIDE"},{"panel":"LAP","enabled":false}]}""")
        assertEquals(Panel.PANELS.toSet(), old.orderedPanels.map { it.panel }.toSet())
    }
}

class CompactLayoutTest {
    @Test fun oneRowOnAMapSlotTwoWhenTall() {
        // 478 x 130 px at 1.875 = 255 x 69 dp: four values in one row.
        assertEquals(CompactLayout(4, 1), CompactLayout.of(4, 255f, 69f))
        assertEquals(CompactLayout(4, 1), CompactLayout.of(8, 255f, 69f))
        // 478 x 210 px = 255 x 112 dp: two rows of four.
        assertEquals(CompactLayout(4, 2), CompactLayout.of(8, 255f, 112f))
        // Half width: two values side by side.
        assertEquals(CompactLayout(2, 1), CompactLayout.of(4, 126f, 69f))
        assertEquals(CompactLayout(2, 1), CompactLayout.of(2, 255f, 112f))
    }
}

class CompactBodyTest {
    private val p = Palette.DARK

    @Test fun panelsBecomeValues() {
        val ride = RideContent(listOf(TileModel("TIME", "1:39:11", "", null, 0), TileModel("DIST", "48.2", "km", null, 0)))
        assertEquals(listOf("TIME" to "1:39:11", "DIST" to "48.2"), (FieldModelBuilder.compactBody(ride, p) as CompactCells).cells.map { it.label to it.value })

        val state = WorkoutState(3, 8, TargetKind.POWER, 280, 295, 310, TargetStatus.EASE, 95, 1200, 145, 300, 72, 0.6f)
        val workout = FieldModelBuilder.compactBody(WorkoutContent(state, "W"), p) as CompactCells
        assertEquals("280–295", workout.cells[0].value)
        assertEquals(p.bad, workout.cells[0].color)
        assertEquals("1:35", workout.cells[2].value)
        assertEquals(0.6f, workout.bar!!, 0f)
        assertEquals("STEP 3/8", FieldModelBuilder.compactTitle(Panel.WORKOUT, WorkoutContent(state, "W")))

        assertEquals("No workout running", (FieldModelBuilder.compactBody(WorkoutContent(null, ""), p) as CompactCells).message)
        val weather = WeatherContent("Waiting for GPS position…", null, "--", "", null, emptyList(), emptyList())
        assertEquals("Waiting for GPS position…", (FieldModelBuilder.compactBody(weather, p) as CompactCells).message)
        val coach = CoachContent("LIVE", false, "just now", null, "Drink now", false, true, "TAP TO CONFIRM")
        assertTrue(FieldModelBuilder.compactBody(coach, p) is CompactText)
    }

    @Test fun descentSlideMirrorsTheClimb() {
        val deck = listOf(Panel.NUMBER) + Panel.PANELS
        val model = FieldModelBuilder.compact(DemoSource(Settings(), Profile.DEFAULT), Panel.DESCENT, deck, 1.875f, interactive = true, systemDark = true)
        assertEquals("DESCENT 1/1", model.title)
        val cells = model.body as CompactCells
        assertEquals(listOf("TO BOTTOM", "DOWN", "AVG LEFT", "GRADE", "SPEED", "BOTTOM IN", "STEEPEST"), cells.cells.map { it.label })
        // Grades read negative on a descent, and the 12 % stretch ahead is flagged.
        assertTrue(cells.cells[2].value.startsWith("-"))
        assertTrue(cells.cells.last().value.startsWith("-12"))
        assertEquals(p.bad, cells.cells.last().color)
        assertTrue(cells.bar!! > 0.2f && cells.bar!! < 0.5f)
    }

    @Test fun everySlideBuildsFromDemoData() {
        val settings = Settings()
        val deck = listOf(Panel.NUMBER) + Panel.PANELS
        for (panel in deck) {
            val model = FieldModelBuilder.compact(DemoSource(settings, Profile.DEFAULT, t = 30), panel, deck, 1.875f, interactive = true, systemDark = true)
            assertEquals(panel, model.panel)
            assertTrue("$panel has a title", model.title.isNotBlank())
        }
        val number = FieldModelBuilder.compact(DemoSource(settings, Profile.DEFAULT), Panel.NUMBER, deck, 1.875f, interactive = true, systemDark = true)
        assertTrue(number.body is CompactNumber)
        assertEquals(settings.bigMetric.label, number.title)
    }
}
