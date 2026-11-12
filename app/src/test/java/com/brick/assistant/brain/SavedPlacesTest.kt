package com.brick.assistant.brain

import com.brick.assistant.data.Places
import com.brick.assistant.data.SavedPlace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Saved places: "take me home" goes to the address saved as Home. */
class SavedPlacesTest {
    @Before
    fun setUp() = Places.setForTest(
        listOf(
            SavedPlace("1", "Home", "12 Main St, Springfield"),
            SavedPlace("2", "Work", "1 Office Park"),
            SavedPlace("3", "Mom's house", "5 Elm Rd"),
        ),
    )

    @After
    fun tearDown() = Places.setForTest(emptyList())

    @Test
    fun goingToSavedPlaces() {
        assertEquals(Request.Navigate("Home", true), IntentParser.parse("take me home"))
        assertEquals(Request.Navigate("Home", true), IntentParser.parse("go home"))
        assertEquals(Request.Navigate("Home", true), IntentParser.parse("let's head home"))
        assertEquals(Request.Navigate("Home", true), IntentParser.parse("how do I get home"))
        assertEquals(Request.Navigate("Work", true), IntentParser.parse("directions to work"))
        assertEquals(Request.Navigate("Work", true), IntentParser.parse("navigate to my work please"))
        assertEquals(Request.Navigate("Mom's house", true), IntentParser.parse("take me to moms house"))
        assertEquals(Request.Navigate("Work", false), IntentParser.parse("show work on the map"))
        // The Battery directions gesture: just the name.
        assertEquals("Home", Places.find((Topic.DIRECTIONS.request("home") as Request.Navigate).destination)?.name)
    }

    @Test
    fun loosePhrasing() {
        val home = Request.Navigate("Home", true)
        val work = Request.Navigate("Work", true)
        val mom = Request.Navigate("Mom's house", true)
        assertEquals(home, IntentParser.parse("I'm heading home"))
        assertEquals(home, IntentParser.parse("I need to head back home"))
        assertEquals(home, IntentParser.parse("Can you get me home real quick"))
        assertEquals(home, IntentParser.parse("time to go home"))
        assertEquals(home, IntentParser.parse("how far am I from home"))
        assertEquals(home, IntentParser.parse("what's the eta to home"))
        assertEquals(work, IntentParser.parse("what's the fastest way to work"))
        assertEquals(work, IntentParser.parse("best route to work"))
        assertEquals(work, IntentParser.parse("how long will it take to get to work"))
        assertEquals(work, IntentParser.parse("I'm driving to work"))
        assertEquals(mom, IntentParser.parse("bring me over to mom's house"))
        assertEquals(mom, IntentParser.parse("let's drive to my moms house"))
        assertEquals(Request.Navigate("Mom's house", false), IntentParser.parse("where is mom's house on the map"))
    }

    @Test
    fun looseLeavesOtherRequestsAlone() {
        val nav = { s: String -> (IntentParser.parse(s) as? Request.Navigate)?.destination }
        for (s in listOf(
            "text mom I'm heading home", "remind me to go home at 5", "play take me home country roads",
            "I'm going to work out", "does this work", "what's the weather at home", "call home",
        )) assertFalse(s, nav(s) == "Home" || nav(s) == "Work" || nav(s) == "Mom's house")
    }

    @Test
    fun otherRequestsUnchanged() {
        assertFalse(IntentParser.parse("go to the store") == Request.Navigate("Home", true))
        assertTrue(IntentParser.parse("play home by edward sharpe") is Request.Music)
        assertNull(Places.find("gym"))
    }

    @Test
    fun saveRenameAndImport() {
        assertEquals("Give the place a name.", Places.save(" ", "x"))
        assertNull(Places.save("Gym", "99 Fit Ave"))
        assertEquals("99 Fit Ave", Places.find("the gym")?.address)
        // Same name again replaces the address.
        assertNull(Places.save("gym", "100 Fit Ave"))
        assertEquals(4, Places.all.value.size)
        // Renaming onto another place's name is refused.
        val gym = Places.find("gym")!!
        assertEquals("You already have a place called \"Home\".", Places.save("home", "x", gym.id))
        val json = Places.exportJson()
        Places.setForTest(emptyList())
        val read = Places.read(json)!!
        assertEquals(4, Places.import(read))
        assertEquals("100 Fit Ave", Places.find("gym")?.address)
        assertNull(Places.read("not json"))
    }
}
