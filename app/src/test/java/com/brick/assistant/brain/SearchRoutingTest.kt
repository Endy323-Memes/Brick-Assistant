package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everyday questions about sports, books, movies, TV, music, plants, trees, bugs and insects, each pinned to the
 * request type that answers it. A new parser that grabs one of these (the way "play some jazz" once went to the Utah
 * Jazz) fails here. "Search" is the general lookup: Wikipedia and Wikidata cards for books, films, shows, albums and people.
 */
class SearchRoutingTest {
    private fun route(q: String) = IntentParser.parse(q)::class.java.simpleName

    private fun check(cases: List<Pair<String, String>>) {
        val wrong = cases.mapNotNull { (q, want) -> route(q).takeIf { it != want }?.let { "\"$q\" went to $it, not $want" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun sports() = check(listOf(
        "who won the super bowl" to "Sports",
        "who won the super bowl in 2020" to "Sports",
        "lakers score" to "Sports",
        "did the yankees win last night" to "Sports",
        "when do the cowboys play next" to "Sports",
        "who won the world series last year" to "Sports",
        "is lebron injured" to "Sports",
        "nba standings" to "Sports",
        "knicks game tonight" to "Sports",
        "how many rings does tom brady have" to "Search",
        "who is patrick mahomes" to "Search",
        "who won the 2022 world cup" to "Sports",
        "premier league table" to "Sports",
        "what time is the chiefs game" to "Sports",
        "who won the stanley cup" to "Sports",
        "f1 standings" to "Search",
        "who won the masters" to "Sports",
        "steph curry stats" to "Search",
        "warriors vs celtics" to "Sports",
        "when is the next ufc fight" to "Search",
    ))

    @Test fun books() = check(listOf(
        "who wrote the great gatsby" to "Search",
        "what is the hobbit about" to "Search",
        "how many pages is dune" to "Search",
        "when was 1984 published" to "Search",
        "who is the author of harry potter" to "Search",
        "what is the latest stephen king book" to "LatestWork",
        "books by brandon sanderson" to "Search",
        "is the hunger games a book" to "Search",
        "what's the next book in the stormlight archive" to "LatestWork",
        "tell me about the book to kill a mockingbird" to "Search",
        "best fantasy books" to "Ranked",
        "summary of pride and prejudice" to "Search",
        "what book series is the wheel of time" to "Search",
        "how many books are in the percy jackson series" to "Search",
        "who wrote the fault in our stars" to "Search",
    ))

    @Test fun movies() = check(listOf(
        "who directed inception" to "Search",
        "what is the movie oppenheimer about" to "Search",
        "when does dune part three come out" to "Search",
        "who is in the cast of interstellar" to "Search",
        "how long is avatar the way of water" to "Search",
        "what is the rating of the dark knight" to "MediaAsk",
        "best horror movies" to "Ranked",
        "movies like interstellar" to "Search",
        "what's the latest marvel movie" to "Latest",
        "where can i watch barbie" to "MediaAsk",
        "is the godfather on netflix" to "MediaAsk",
        "what movies has tom hanks been in" to "Search",
        "how much did avengers endgame make" to "Search",
        "when did titanic come out" to "Search",
        "top rated movies of 2024" to "Ranked",
        "tell me about the movie the matrix" to "Search",
    ))

    @Test fun tv() = check(listOf(
        "how many seasons of breaking bad are there" to "Search",
        "when is the next episode of the last of us" to "Latest",
        "who plays eleven in stranger things" to "Search",
        "what is the office about" to "Search",
        "is severance renewed" to "Search",
        "where can i watch the bear" to "MediaAsk",
        "best tv shows" to "Ranked",
        "when does season 5 of stranger things come out" to "Search",
        "how many episodes are in game of thrones" to "Search",
        "tell me about the show succession" to "Search",
        "what's the newest season of the boys" to "Latest",
        "shows like the office" to "Search",
        "who created the simpsons" to "Search",
        "is friends on netflix" to "MediaAsk",
    ))

    @Test fun music() = check(listOf(
        "who sings blinding lights" to "MediaAsk",
        "what is taylor swift's latest album" to "Search",
        "when did thriller come out" to "Search",
        "songs by the weeknd" to "Search",
        "who is drake" to "Search",
        "what genre is radiohead" to "GameInfo",
        "top songs right now" to "Ranked",
        "how many albums does beyonce have" to "Search",
        "when is taylor swift's next album" to "Search",
        "who wrote bohemian rhapsody" to "Search",
        "tell me about the album abbey road" to "Search",
        "best songs of 2023" to "Ranked",
        "what is kendrick lamar's new album" to "Search",
        "play blinding lights" to "Music",
        "play some jazz" to "Music",
    ))

    @Test fun plants() = check(listOf(
        "how tall do oak trees get" to "Animal",
        "how long do redwoods live" to "Animal",
        "is a monstera toxic to cats" to "Animal",
        "how much water does a snake plant need" to "Animal",
        "what is a venus flytrap" to "Animal",
        "where do cactus grow" to "Animal",
        "is poison ivy dangerous" to "Animal",
        "how old is the oldest tree" to "Search",
        "what does a sunflower need to grow" to "Search",
        "tell me about the baobab tree" to "Animal",
        "are pothos safe for dogs" to "Animal",
        "how much sun does a fiddle leaf fig need" to "Animal",
        "what is a sequoia" to "Animal",
        "is foxglove poisonous" to "Animal",
    ))

    @Test fun trees() = check(listOf(
        "how tall can a maple tree get" to "Animal",
        "what is the tallest tree" to "Animal",
        "how long do pine trees live" to "Animal",
        "where do palm trees grow" to "Animal",
        "what do willow trees look like" to "Search",
        "how fast do birch trees grow" to "Search",
        "tell me about cherry blossom trees" to "Animal",
        "how old can oak trees get" to "Animal",
        "what is a joshua tree" to "Animal",
        "are eucalyptus trees native to australia" to "Search",
    ))

    @Test fun bugs() = check(listOf(
        "is a spider an insect" to "Animal",
        "do bed bugs bite" to "Animal",
        "how long do mosquitoes live" to "Animal",
        "what do ladybugs eat" to "Animal",
        "are stink bugs harmful" to "Animal",
        "how many legs does a centipede have" to "Animal",
        "what is a lightning bug" to "Animal",
        "do cockroaches bite" to "Animal",
        "how long do fireflies live" to "Animal",
        "are brown recluse spiders dangerous" to "Search",
    ))

    @Test fun insects() = check(listOf(
        "how many legs does an insect have" to "Animal",
        "what do bees eat" to "Animal",
        "how long do butterflies live" to "Animal",
        "do ants sleep" to "Search",
        "what is the biggest insect" to "Animal",
        "how do fireflies glow" to "Search",
        "where do praying mantises live" to "Animal",
        "how fast can a dragonfly fly" to "Animal",
        "do wasps sting more than once" to "Search",
        "what do grasshoppers eat" to "Animal",
        "how long does a monarch butterfly live" to "Animal",
        "are hornets dangerous" to "Animal",
        "what is a cicada" to "Animal",
        "how many eyes does a fly have" to "Animal",
    ))

    /** Parsers for gear, gadgets, consoles and games come first in line: none of them may take a media or nature question. */
    @Test fun gearParsersStayOut() {
        val gear = setOf("Peripheral", "Headgear", "Tech", "Platform", "PcBuild", "GameFind")
        for (q in listOf("who wrote the great gatsby", "what is the hobbit about", "who sings blinding lights", "what is a venus flytrap", "how many legs does a centipede have",
                "who directed inception", "how many seasons of breaking bad are there", "is a monstera toxic to cats", "what do bees eat", "what genre is radiohead",
                "tell me about the album abbey road", "what is a joshua tree", "do bed bugs bite", "what's the newest season of the boys", "play some jazz"))
            assertTrue("\"$q\" went to ${route(q)}", route(q) !in gear)
    }

    /** Breaking Bad's card once left Bryan Cranston off: Wikidata had him sixth. Actors the intro names come first. */
    @Test fun castOrder() {
        val f = mapOf("P161" to listOf("Anna Gunn", "Aaron Paul", "Dean Norris", "Betsy Brandt", "Jonathan Banks", "Bryan Cranston"))
        // The intro Brick gets names the leads only by their characters, and the others outright (one with a no-break space).
        val lead = "Breaking Bad is an American crime drama. The series follows Walter White, a chemistry teacher, who works with former student " +
            "Jesse Pinkman. The series also stars Anna Gunn and Dean Norris."
        val roles = "P161:P453" to listOf("Bryan Cranston=Walter White", "Aaron Paul=Jesse Pinkman", "Anna Gunn=Skyler White")
        assertEquals(listOf("Bryan Cranston", "Aaron Paul", "Anna Gunn", "Dean Norris", "Betsy Brandt", "Jonathan Banks"), Lookup.castFirst(f + roles, lead)["P161"])
        // With no characters known, the named actors still come first.
        assertEquals(listOf("Anna Gunn", "Dean Norris", "Aaron Paul", "Betsy Brandt", "Jonathan Banks", "Bryan Cranston"), Lookup.castFirst(f, lead)["P161"])
        assertEquals(f, Lookup.castFirst(f, null))
    }

    /** What the general lookup reads from the wording: the kind of page and the fact asked for. */
    @Test fun lookupWording() {
        fun u(q: String) = Lookup.understand(q).let { "${it.subject}|${it.kind}|${it.aspect}" }
        assertEquals("great gatsby|null|AUTHOR", u("who wrote the great gatsby"))
        assertEquals("harry potter|null|AUTHOR", u("who is the author of harry potter"))
        assertEquals("inception|MOVIE|DIRECTOR", u("who directed inception"))
        assertEquals("breaking bad|TV_SHOW|SEASONS", u("how many seasons of breaking bad are there"))
        assertEquals("avatar the way of water|MOVIE|RUNTIME", u("how long is avatar the way of water"))
        assertEquals("dune|BOOK|RUNTIME", u("how many pages is dune"))
    }
}
