package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnimalAndLooseSearchTest {
    private fun youtube(s: String): Request.YouTube {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.YouTube)
        return r as Request.YouTube
    }

    private fun history(s: String): HistoryAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.History)
        return (r as Request.History).ask
    }

    private fun sports(s: String): SportsAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Sports)
        return (r as Request.Sports).ask
    }

    private fun animal(s: String): AnimalAsk {
        val r = IntentParser.parse(s)
        assertTrue("\"$s\" -> $r", r is Request.Animal)
        return (r as Request.Animal).ask
    }

    @Test fun looseYouTube() {
        for ((s, q) in listOf(
            "youtube cat videos" to "cat videos",
            "pull up cat videos on youtube" to "cat videos",
            "show me cat videos on youtube" to "cat videos",
            "find me a video about how to tie a tie" to "how to tie a tie",
            "find me a video on how to tie a tie" to "how to tie a tie",
            "any videos on how to fix a flat tire" to "how to fix a flat tire",
            "are there any videos about black holes" to "black holes",
            "is there a youtube video on sourdough" to "sourdough",
            "look up mrbeast on youtube" to "mrbeast",
            "open youtube and search lofi" to "lofi",
            "go on youtube and look up minecraft speedruns" to "minecraft speedruns",
            "can you youtube how to change a tire" to "how to change a tire",
            "search up skateboarding tricks on youtube" to "skateboarding tricks",
            "videos of golden retrievers" to "golden retrievers",
            "golden retriever videos" to "golden retriever",
            "lofi on youtube" to "lofi",
            "i wanna watch cat videos on youtube" to "cat videos",
        )) {
            assertEquals(s, q, youtube(s).query)
        }
        assertTrue(youtube("throw on lofi hip hop on youtube").play)
    }

    @Test fun looseHistory() {
        assertEquals(HistoryAspect.WHEN, history("when'd world war 2 start").aspect)
        assertEquals(HistoryAspect.HOW_LONG, history("how long'd the civil war last").aspect)
        assertEquals(HistoryAspect.CAUSE, history("why'd the vietnam war happen").aspect)
        assertEquals(HistoryAspect.CAUSE, history("what kicked off ww1").aspect)
        assertEquals(HistoryAspect.WHO_WON, history("who came out on top in the korean war").aspect)
        assertEquals(HistoryAspect.WHO_WON, history("who ended up winning the war of 1812").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("whats the deal with the cold war").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("fill me in on the french revolution").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("catch me up on the cuban missile crisis").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("what went down at pearl harbor").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("what was the cold war all about").aspect)
        assertEquals(HistoryAspect.OVERVIEW, history("info on the boston tea party").aspect)
        assertEquals(HistoryAspect.KEY_POINTS, history("gimme the rundown on ww2").aspect)
        assertEquals(HistoryAspect.KEY_POINTS, history("quick rundown of the space race").aspect)
        assertEquals(HistoryAspect.DEATHS, history("how many people died during 9/11").aspect)
        assertEquals(HistoryAspect.DEATHS, history("how many people died in ww2").aspect)
        assertEquals(HistoryAspect.WHERE, history("where'd the battle of gettysburg happen").aspect)
    }

    @Test fun looseSports() {
        val now = LocalDate.now()
        assertEquals(SportsWant.RESULT, sports("how'd the eagles do").want)
        assertEquals(SportsWant.RESULT, sports("howd the eagles do").want)
        assertEquals(SportsWant.RESULT, sports("how d the eagles do").want)
        assertEquals(SportsWant.RESULT, sports("did the birds pull it off").want)
        assertEquals(SportsWant.RESULT, sports("did the knicks get the w").want)
        assertEquals(SportsWant.RESULT, sports("did the knicks take the l last night").want)
        assertEquals(SportsWant.SCORE, sports("yo what's the eagles score").want)
        assertEquals(SportsWant.SCORE, sports("how we looking in the eagles game").want)
        assertEquals(SportsWant.NEXT, sports("when do the sixers play next").want)
        assertEquals(SportsWant.NEXT, sports("when's the next eagles game").want)
        assertEquals(SportsWant.NEXT, sports("who are the eagles playing this week").want)
        assertEquals(now.minusDays(1), sports("eagles score from last night").date)
    }

    @Test fun animalQuestions() {
        for ((s, want, who) in listOf(
            Triple("how long do lions live", AnimalWant.LIFESPAN, "lions"),
            Triple("what's the lifespan of a sea turtle", AnimalWant.LIFESPAN, "sea turtle"),
            Triple("elephant lifespan", AnimalWant.LIFESPAN, "elephant"),
            Triple("how old can a parrot get", AnimalWant.LIFESPAN, "parrot"),
            Triple("what do pandas eat", AnimalWant.DIET, "pandas"),
            Triple("what does a koala eat", AnimalWant.DIET, "koala"),
            Triple("is a bear a carnivore", AnimalWant.DIET, "bear"),
            Triple("are raccoons omnivores", AnimalWant.DIET, "raccoons"),
            Triple("diet of a great white shark", AnimalWant.DIET, "great white shark"),
            Triple("where do penguins live", AnimalWant.HABITAT, "penguins"),
            Triple("what is the habitat of a snow leopard", AnimalWant.HABITAT, "snow leopard"),
            Triple("what environment do axolotls need", AnimalWant.HABITAT, "axolotls"),
            Triple("where are kangaroos found", AnimalWant.HABITAT, "kangaroos"),
            Triple("what do wolves hunt", AnimalWant.PREY, "wolves"),
            Triple("what does an orca prey on", AnimalWant.PREY, "orca"),
            Triple("what animals do lions hunt", AnimalWant.PREY, "lions"),
            Triple("what eats rabbits", AnimalWant.PREDATORS, "rabbits"),
            Triple("what hunts zebras", AnimalWant.PREDATORS, "zebras"),
            Triple("what are the predators of a sea otter", AnimalWant.PREDATORS, "sea otter"),
            Triple("does anything hunt crocodiles", AnimalWant.PREDATORS, "crocodiles"),
            Triple("frog predators", AnimalWant.PREDATORS, "frog"),
            Triple("when was the okapi discovered", AnimalWant.DISCOVERED, "okapi"),
            Triple("who discovered the platypus", AnimalWant.DISCOVERED, "platypus"),
            Triple("when were giant pandas first discovered", AnimalWant.DISCOVERED, "giant pandas"),
            Triple("is the dodo extinct", AnimalWant.EXTINCT, "dodo"),
            Triple("are tasmanian tigers extinct", AnimalWant.EXTINCT, "tasmanian tigers"),
            Triple("how long has the woolly mammoth been extinct", AnimalWant.EXTINCT, "woolly mammoth"),
            Triple("when did the dinosaurs go extinct", AnimalWant.EXTINCT, "dinosaurs"),
            Triple("are pandas endangered", AnimalWant.EXTINCT, "pandas"),
            Triple("tell me about the axolotl animal", AnimalWant.OVERVIEW, "axolotl"),
            Triple("red panda facts", AnimalWant.OVERVIEW, "red panda"),
        )) {
            val a = animal(s)
            assertEquals(s, want, a.want)
            assertEquals(s, who, a.animal)
        }
    }

    @Test fun plantQuestions() {
        for ((s, want, who) in listOf(
            Triple("how long do oak trees live", AnimalWant.LIFESPAN, "oak trees"),
            Triple("how old can a redwood get", AnimalWant.LIFESPAN, "redwood"),
            Triple("where do cacti grow", AnimalWant.HABITAT, "cacti"),
            Triple("where does bamboo grow", AnimalWant.HABITAT, "bamboo"),
            Triple("how much sun does a fern need", AnimalWant.HABITAT, "fern"),
            Triple("what soil do roses like", AnimalWant.HABITAT, "roses"),
            Triple("how often should i water a cactus", AnimalWant.HABITAT, "cactus"),
            Triple("how do i grow tomato plants", AnimalWant.HABITAT, "tomato plants"),
            Triple("monstera care", AnimalWant.HABITAT, "monstera"),
            Triple("how tall do redwoods get", AnimalWant.HEIGHT, "redwoods"),
            Triple("how tall can a sunflower grow", AnimalWant.HEIGHT, "sunflower"),
            Triple("how big do giraffes get", AnimalWant.HEIGHT, "giraffes"),
            Triple("when do cherry blossoms bloom", AnimalWant.BLOOM, "cherry blossoms"),
            Triple("what month do tulips flower", AnimalWant.BLOOM, "tulips"),
            Triple("lavender bloom time", AnimalWant.BLOOM, "lavender"),
            Triple("is foxglove poisonous", AnimalWant.TOXIC, "foxglove"),
            Triple("are lilies toxic to cats", AnimalWant.TOXIC, "lilies"),
            Triple("can you eat dandelions", AnimalWant.TOXIC, "dandelions"),
            Triple("is a box jellyfish venomous", AnimalWant.TOXIC, "box jellyfish"),
            Triple("what eats oak leaves", AnimalWant.PREDATORS, "oak leaves"),
            Triple("what eats sunflowers", AnimalWant.PREDATORS, "sunflowers"),
            Triple("what do venus flytraps eat", AnimalWant.DIET, "venus flytraps"),
            Triple("is the wollemi pine extinct", AnimalWant.EXTINCT, "wollemi pine"),
            Triple("when was the wollemi pine discovered", AnimalWant.DISCOVERED, "wollemi pine"),
            Triple("tell me about the baobab tree", AnimalWant.OVERVIEW, "baobab tree"),
            Triple("sequoia facts", AnimalWant.OVERVIEW, "sequoia"),
        )) {
            val a = animal(s)
            assertEquals(s, want, a.want)
            assertEquals(s, who, a.animal)
        }
        assertTrue(animal("how long do oak trees live").plant)
        assertTrue(animal("when do cherry blossoms bloom").plant)
        assertFalse(animal("how long do lions live").plant)
        assertEquals("poisonous", animal("is foxglove poisonous").diet)
        assertEquals("eat", animal("can you eat dandelions").diet)
        assertEquals("care", animal("how much sun does a fern need").diet)
        assertEquals("cactus", Animals.singular("cacti"))
        assertEquals("moss", Animals.singular("moss"))
        assertEquals("hibiscus", Animals.singular("hibiscus"))
    }

    @Test fun notPlants() {
        for (s in listOf("is the store safe", "is it bad", "is the water safe to drink", "how tall is the eiffel tower", "when does the store open",
                "where does my mom grow up", "how do i grow my business", "is my car dangerous", "tell me about yourself", "how big is texas",
                "what is a mortgage", "when does the new iphone come out", "can my dog come", "is it ok to leave", "what's up with you", "why is the sky blue",
                "tips for saving money", "how to grow my business", "what bugs you", "is the president alive", "how many people are left", "do you like music",
                "what is love", "can my kids eat after 8", "can my cat come inside", "is it ok to eat late", "explain quantum physics", "do you need water",
                "what is eating you", "are there any tickets left", "how many tickets are left")) {
            assertFalse(s, IntentParser.parse(s) is Request.Animal)
        }
    }

    @Test fun bugQuestions() {
        for ((s, want, who, asked) in listOf(
            Quad("do ants bite", AnimalWant.BITE, "ants", "bite"),
            Quad("can a tarantula hurt you", AnimalWant.BITE, "tarantula", "bite"),
            Quad("are wasp stings dangerous", AnimalWant.BITE, "wasp", "sting"),
            Quad("what do i do if a bee stings me", AnimalWant.BITE, "bee", "sting"),
            Quad("do daddy long legs bite", AnimalWant.BITE, "daddy long legs", "bite"),
            Quad("are hornets aggressive", AnimalWant.BITE, "hornets", "bite"),
            Quad("bee sting", AnimalWant.BITE, "bee", "sting"),
            Quad("how do i get rid of ants in my kitchen", AnimalWant.PESTS, "ants", "rid"),
            Quad("how to get rid of fruit flies", AnimalWant.PESTS, "fruit flies", "rid"),
            Quad("how do i keep mosquitoes away", AnimalWant.PESTS, "mosquitoes", "rid"),
            Quad("kill bed bugs", AnimalWant.PESTS, "bed bugs", "rid"),
            Quad("what attracts fruit flies", AnimalWant.PESTS, "fruit flies", "attract"),
            Quad("why are there so many flies in my house", AnimalWant.PESTS, "flies", "attract"),
            Quad("why do i keep seeing roaches", AnimalWant.PESTS, "roaches", "attract"),
            Quad("where are these ants coming from", AnimalWant.PESTS, "ants", "attract"),
            Quad("are ladybugs good for the garden", AnimalWant.PESTS, "ladybugs", "good"),
            Quad("are stink bugs bad", AnimalWant.PESTS, "stink bugs", "good"),
            Quad("what do caterpillars turn into", AnimalWant.LIFECYCLE, "caterpillars", "become"),
            Quad("how long does it take a caterpillar to become a butterfly", AnimalWant.LIFECYCLE, "caterpillar", "become"),
            Quad("life cycle of a mosquito", AnimalWant.LIFECYCLE, "mosquito", null),
            Quad("where do cockroaches lay their eggs", AnimalWant.LIFECYCLE, "cockroaches", null),
            Quad("is a spider an insect", AnimalWant.KIND, "spider", "insect"),
            Quad("is a spider a bug", AnimalWant.KIND, "spider", "bug"),
            Quad("are butterflies insects", AnimalWant.KIND, "butterflies", "insect"),
            Quad("what kind of animal is a woodlouse", AnimalWant.KIND, "woodlouse", null),
            Quad("how many legs does a centipede have", AnimalWant.KIND, "centipede", "legs"),
            Quad("how many eyes does a spider have", AnimalWant.KIND, "spider", "eyes"),
            Quad("how long do bees live", AnimalWant.LIFESPAN, "bees", null),
            Quad("what do ladybugs eat", AnimalWant.DIET, "ladybugs", null),
            Quad("what eats spiders", AnimalWant.PREDATORS, "spiders", null),
            Quad("tell me about fireflies", AnimalWant.OVERVIEW, "fireflies", null),
            Quad("whats a cicada", AnimalWant.OVERVIEW, "cicada", null),
            Quad("are fireflies endangered", AnimalWant.EXTINCT, "fireflies", null),
        )) {
            val a = animal(s)
            assertEquals(s, want, a.want)
            assertEquals(s, who, a.animal)
            assertEquals(s, asked, a.diet)
            assertTrue(s, a.bug)
        }
    }

    @Test fun notBugs() {
        for (s in listOf("how do i get rid of my ex", "how do i get rid of acne", "why are there so many people here", "is a tomato a fruit", "what attracts customers",
                "is the app a bug", "does it bite", "will my boss kill me", "how many legs does a table have", "what do you turn into", "is google an animal",
                "how to get rid of a headache", "kill the lights", "kill spotify")) {
            assertFalse(s, IntentParser.parse(s) is Request.Animal)
        }
    }

    @Test fun seaQuestions() {
        for ((s, want, who, asked) in listOf(
            Quad("how fast can a sailfish swim", AnimalWant.SPEED, "sailfish", null),
            Quad("orca top speed", AnimalWant.SPEED, "orca", null),
            Quad("how deep can a sperm whale dive", AnimalWant.DEPTH, "sperm whale", null),
            Quad("do anglerfish live in the deep sea", AnimalWant.DEPTH, "anglerfish", null),
            Quad("can goldfish live in salt water", AnimalWant.HABITAT, "goldfish", "water"),
            Quad("are clownfish freshwater or saltwater", AnimalWant.HABITAT, "clownfish", "water"),
            Quad("how do whales breathe", AnimalWant.KIND, "whales", "breathe"),
            Quad("how long can a dolphin hold its breath", AnimalWant.KIND, "dolphin", "hold"),
            Quad("how long can a fish survive out of water", AnimalWant.KIND, "fish", "hold"),
            Quad("do sharks attack humans", AnimalWant.BITE, "sharks", "attack"),
            Quad("is it safe to swim with dolphins", AnimalWant.BITE, "dolphins", "attack"),
            Quad("are orcas dangerous", AnimalWant.BITE, "orcas", "attack"),
            Quad("can a jellyfish sting you", AnimalWant.BITE, "jellyfish", "sting"),
            Quad("what does octopus taste like", AnimalWant.TOXIC, "octopus", "eat"),
            Quad("is a whale a fish", AnimalWant.KIND, "whale", "fish"),
            Quad("how many hearts does an octopus have", AnimalWant.KIND, "octopus", "hearts"),
            Quad("do octopuses have bones", AnimalWant.KIND, "octopuses", "have:bones"),
            Quad("do jellyfish have brains", AnimalWant.KIND, "jellyfish", "have:brains"),
            Quad("how long do sea turtles live", AnimalWant.LIFESPAN, "sea turtles", null),
            Quad("what eats jellyfish", AnimalWant.PREDATORS, "jellyfish", null),
            Quad("give me some facts about hammerhead sharks", AnimalWant.OVERVIEW, "hammerhead sharks", null),
        )) {
            val a = animal(s)
            assertEquals(s, want, a.want)
            assertEquals(s, who, a.animal)
            assertEquals(s, asked, a.diet)
            assertTrue(s, a.sea)
        }
        // A black widow being "deadly" is about its bite.
        assertEquals(AnimalWant.BITE, animal("is a black widow deadly").want)
    }

    @Test fun notSea() {
        for (s in listOf("how fast can a car go", "how deep is the ocean", "can i live in salt water", "is it safe to swim today", "how fast is the internet",
                "what is bass", "what are x rays", "is a tomato a fish", "how do i breathe better", "what does chicken taste like", "how deep can you dive")) {
            assertFalse(s, IntentParser.parse(s) is Request.Animal)
        }
    }

    private data class Quad(val s: String, val want: AnimalWant, val who: String, val asked: String?)

    @Test fun loosePlantQuestions() {
        for ((s, want, who) in listOf(
            Triple("what time of year do tulips come up", AnimalWant.BLOOM, "tulips"),
            Triple("when do daffodils pop up", AnimalWant.BLOOM, "daffodils"),
            Triple("do ferns flower", AnimalWant.BLOOM, "ferns"),
            Triple("when are lilacs in bloom", AnimalWant.BLOOM, "lilacs"),
            Triple("can my cat eat basil", AnimalWant.TOXIC, "basil"),
            Triple("are poinsettias bad for cats", AnimalWant.TOXIC, "poinsettias"),
            Triple("will mushrooms kill me", AnimalWant.TOXIC, "mushrooms"),
            Triple("is it ok for dogs to eat grass", AnimalWant.TOXIC, "grass"),
            Triple("does lavender like full sun", AnimalWant.HABITAT, "lavender"),
            Triple("how do you keep a basil plant alive", AnimalWant.HABITAT, "basil"),
            Triple("tips for growing tomatoes", AnimalWant.HABITAT, "tomatoes"),
            Triple("how do i keep my monstera from dying", AnimalWant.HABITAT, "monstera"),
            Triple("why is my snake plant turning yellow", AnimalWant.HABITAT, "snake plant"),
            Triple("what bugs eat roses", AnimalWant.PREDATORS, "roses"),
            Triple("what's eating my tomato plants", AnimalWant.PREDATORS, "tomato plants"),
            Triple("what animals eat acorns", AnimalWant.PREDATORS, "acorns"),
            Triple("whats a baobab", AnimalWant.OVERVIEW, "baobab"),
            Triple("give me some facts about sunflowers", AnimalWant.OVERVIEW, "sunflowers"),
            Triple("what do you know about cactuses", AnimalWant.OVERVIEW, "cactuses"),
            Triple("whats the deal with venus flytraps", AnimalWant.OVERVIEW, "venus flytraps"),
            Triple("how do venus flytraps catch bugs", AnimalWant.DIET, "venus flytraps"),
            Triple("are there any wild ginkgo left", AnimalWant.EXTINCT, "ginkgo"),
            Triple("whats the max height of a redwood", AnimalWant.HEIGHT, "redwood"),
        )) {
            val a = animal(s)
            assertEquals(s, want, a.want)
            assertEquals(s, who, a.animal)
            assertTrue(s, a.plant)
        }
        assertEquals("care", animal("tips for growing tomatoes").diet)
        assertEquals("care", animal("how do i keep my monstera from dying").diet)
    }

    @Test fun notAnimals() {
        for (s in listOf("where does taylor swift live", "what do you eat", "where do my parents live", "how long do batteries last",
                "what's the eagles score", "who discovered penicillin", "when was america discovered", "what eats at my soul", "how long do i have to wait",
                "where do you live", "what should i eat", "is my phone extinct")) {
            assertFalse(s, IntentParser.parse(s) is Request.Animal)
        }
    }

    @Test fun animalReading() {
        assertEquals(listOf("lion", "lions"), Animals.forms("lions").take(2))
        assertEquals("wolf", Animals.singular("wolves"))
        assertEquals("puppy", Animals.singular("puppies"))
        assertEquals("octopus", Animals.singular("octopus"))
        assertEquals("Carnivore", Animals.dietKind("The lion is a carnivore and hunts large ungulates."))
        assertEquals("Herbivore", Animals.dietKind("Giant pandas are herbivores that eat bamboo."))
        assertEquals(1662, Animals.lastSeen("The last widely accepted sighting of a dodo was in 1662."))
        assertEquals("about 4,000 years", Animals.since("The last woolly mammoths died out about 4,000 years ago.", 2026))
        assertEquals("about 66 million years", Animals.since("Tyrannosaurus lived 68 to 66 million years ago.", 2026))
        assertEquals("1758", Animals.yearIn("+1758-00-00T00:00:00Z"))
    }
}
