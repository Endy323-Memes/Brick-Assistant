package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterTest {
    private fun ask(s: String): CharacterAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Character)
        return (r as Request.Character).ask
    }

    @Test fun traits() {
        ask("is walter white smart").let { assertEquals("walter white", it.name); assertEquals(CharacterWant.TRAIT, it.want); assertEquals("smart", it.trait) }
        assertEquals("goofy", ask("is patrick star goofy").trait)
        assertEquals("arrogant", ask("was dwight schrute really arrogant").trait)
        assertEquals("narcissistic", ask("is homelander a narcissist").trait)
        assertEquals("stubborn", ask("is hank schrader stubborn").trait)
        ask("is goku powerful in dragon ball").let { assertEquals("powerful", it.trait); assertEquals("goku", it.name); assertEquals("dragon ball", it.work) }
        assertEquals("powerful", ask("how strong is superman").trait)
        assertEquals("smart", ask("how smart is sheldon cooper").trait)
        assertEquals("powerful", ask("does goku have powers").trait)
        assertEquals("narcissistic", ask("is tony stark self centered").trait)
        assertEquals("smart", ask("would you call light yagami a genius").trait)
        assertEquals("smart", ask("is light yagami smart or dumb").trait)
        assertEquals("crazy", ask("do you think the joker is insane").trait)
        assertEquals("kind", ask("is uncle iroh kind").trait)
        assertEquals("cruel", ask("is joffrey cruel").trait)
    }

    @Test fun roles() {
        ask("is walter white the protagonist of breaking bad").let {
            assertEquals(CharacterWant.ROLE, it.want); assertEquals(CharRole.PROTAGONIST, it.role); assertEquals("breaking bad", it.work)
        }
        assertEquals(CharRole.ANTAGONIST, ask("is darth vader the antagonist").role)
        assertEquals(CharRole.VILLAIN, ask("is gus fring a villain").role)
        assertEquals(CharRole.VILLAIN, ask("is zuko a bad guy").role)
        assertEquals(CharRole.HERO, ask("is batman a good guy").role)
        assertEquals(CharRole.MAIN, ask("is jesse pinkman a main character").role)
        assertEquals(CharRole.MAIN, ask("is jesse pinkman one of the main characters").role)
        assertEquals(CharRole.SIDE, ask("is saul goodman a side character in breaking bad").role)
        assertEquals(CharRole.ANTIHERO, ask("is deadpool an antihero").role)
        assertEquals(CharacterWant.WHICH_ROLE, ask("is zuko a good guy or a bad guy").want)
        assertEquals(CharacterWant.WHICH_ROLE, ask("is jesse a main character or a side character").want)
        assertEquals(CharacterWant.WHICH_ROLE, ask("what kind of character is jesse pinkman").want)
    }

    @Test fun personality() {
        for (s in listOf("describe sheldon cooper's personality", "what is homer simpson's personality like", "what kind of person is walter white",
                "what are michael scott's traits", "personality of eren yeager", "what's tony stark's character like", "how would you describe spongebob")) {
            assertEquals(s, CharacterWant.PERSONALITY, ask(s).want)
        }
        assertEquals("sheldon cooper", ask("describe sheldon cooper's personality").name)
    }

    @Test fun everydayQuestionsStayPut() {
        for (s in listOf("is it cold outside", "is the wifi strong", "is my battery weak", "is the light on", "is spotify playing",
                "is the store open", "what is bryan cranston's character in breaking bad", "who's the villain in the dark knight",
                "who is the main character of breaking bad", "is it going to rain", "what's the weather like", "is this song popular",
                "how strong is the wind", "is the knicks game on", "set a timer for 10 minutes", "is spotify slow", "is it cold outside")) {
            val r = IntentParser.parse(s)
            assertFalse("\"$s\" -> $r", r is Request.Character)
        }
        assertNull(Characters.parse("is that true"))
    }

    private val walt = Characters.sections("""Walter White is a fictional character and the main protagonist of the American crime drama television series Breaking Bad. He is a high school chemistry teacher who becomes a ruthless drug lord. Walt fights the arrogant Tuco Salamanca.

== Characterization ==
Walt is highly intelligent and proud, and his pride and ego push him further. He is not lazy. Gilligan described him as stubborn.

== References ==
Walt is goofy.""")
    private val names = listOf("walter", "white")

    @Test fun readingTraits() {
        assertTrue(Characters.evidence(walt, Characters.trait("smart")!!.shows, names).first.isNotEmpty())
        assertTrue(Characters.evidence(walt, Characters.trait("cruel")!!.shows, names).first.isNotEmpty())
        // "Walt fights the arrogant Tuco": Tuco's trait, not Walt's ("proud"/"pride" don't count either).
        assertTrue(Characters.evidence(walt, Characters.trait("arrogant")!!.shows, names).first.none { it.sentence.contains("Tuco") })
        // Negated, and the References section is skipped.
        Characters.evidence(walt, Characters.trait("lazy")!!.shows, names).let { (yes, no) -> assertTrue(yes.isEmpty()); assertTrue(no.isNotEmpty()) }
        assertTrue(Characters.evidence(walt, Characters.trait("goofy")!!.shows, names).first.isEmpty())
        val found = Characters.traitsIn(walt, names).map { it.first.key }
        assertTrue(found.toString(), "smart" in found && "cruel" in found && "stubborn" in found)
    }

    @Test fun readingRoles() {
        val r = Characters.roles(walt, names)
        assertTrue(r.protagonist != null)
        assertNull(r.antagonist)
        assertEquals("the protagonist", r.said())
        val bats = Characters.sections("Batman is a superhero who fights villains such as the Joker. He is one of the main characters of DC Comics.")
        val b = Characters.roles(bats, listOf("batman"))
        assertNull(b.antagonist)
        assertTrue(b.hero != null)
        val joker = Characters.sections("The Joker is a supervillain and the archenemy of Batman.")
        assertTrue(Characters.roles(joker, listOf("joker")).antagonist != null)
    }

    @Test fun places() {
        assertEquals("Breaking Bad", Characters.workOf("Character from Breaking Bad"))
        assertEquals("Breaking Bad", Characters.workOf("fictional character in the television series Breaking Bad"))
        assertEquals(listOf("breakingbad"), Characters.fandomWikis("Breaking Bad").take(1))
        assertEquals("spongebob", Characters.fandomWikis("SpongeBob SquarePants").last())
        val list = "== Main ==\n=== Walter White ===\nWalt is a teacher.\n=== Jesse Pinkman ===\nJesse is a reckless former student.\n== Recurring ==\n=== Saul ===\nSaul is a lawyer."
        assertEquals("Jesse is a reckless former student.", Characters.listSection(list, "jesse").single().text)
        assertEquals("Character", Characters.stripHtml("<aside>box</aside><p>Char<b>acter</b><sup>[1]</sup></p>"))
    }
}
