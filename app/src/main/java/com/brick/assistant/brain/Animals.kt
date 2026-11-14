package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** What's asked about an animal or plant. */
enum class AnimalWant { OVERVIEW, LIFESPAN, DIET, HABITAT, PREY, PREDATORS, DISCOVERED, EXTINCT, HEIGHT, BLOOM, TOXIC, BITE, PESTS, LIFECYCLE, KIND, SPEED, DEPTH }

/**
 * An animal or plant question: [animal] as said ("lions", "a sea turtle" -> "sea turtle", "oak trees"); [diet] is the word
 * asked about when there is one ("carnivore" in "is a bear a carnivore", "poisonous", "edible", "care" for "how much sun does
 * a fern need", "sting", "attract", "insect" in "is a spider an insect", "legs"); [plant] when the wording says it's a plant
 * or tree; [bug] when it's a bug, insect, spider or the like; [sea] when it's a fish or other sea creature.
 */
data class AnimalAsk(val animal: String, val want: AnimalWant, val diet: String? = null, val said: String = animal, val plant: Boolean = false, val bug: Boolean = false, val sea: Boolean = false)

/**
 * Animals: how long they live, what they eat, where they live, what they hunt and what hunts them, when the species was
 * described, and whether (and how long ago) it went extinct. Wikidata has the taxon facts (scientific name, the year it
 * was named, conservation status, sometimes lifespan, food and habitat); the Wikipedia article fills in the rest.
 */
object Animals {
    // ------------------------------------------------------------------ understanding the question

    private val LEAD = Regex(
        "^(?:(?:hey |ok |okay |yo )?brick[, ]+)?(?:(?:can you |could you |would you |please |do you know |i want to know |i'd like to know |i wanna know |tell me |" +
            "let me know |quick question |look up |lookup |search (?:for |up )?|google |find out |any idea |so |hey |yo |real quick |i was wondering |i'm curious |im curious )+)?",
    )

    /** Not an animal: people, pronouns and the like ("where do you live", "what do my kids eat"). */
    private val NOT_AN_ANIMAL = Regex("^(?:i|me|you|u|we|us|they|them|he|she|it|people|someone|somebody|anyone|anybody|everyone|everybody|y'all|yall|folks|guys|this|that|these|those|there|here|what|who)$|" +
        "\\b(?:my|your|our|their|his|her|its|mine|yours|i|you|we|me|us|soul|life|time|money|phone|battery|batteries|food|it)\\b")

    /** Common animals, for the loose forms with no question word ("red panda facts"). */
    private val KNOWN = ("aardvark|albatross|alligator|alpaca|anaconda|ant|anteater|antelope|armadillo|axolotl|baboon|badger|bald eagle|barracuda|bat|bear|beaver|bee|beetle|bison|black bear|" +
        "blue whale|boa|bobcat|buffalo|butterfly|camel|capybara|caribou|cat|caterpillar|catfish|centipede|chameleon|cheetah|chicken|chimpanzee|chinchilla|chipmunk|clownfish|cobra|cockroach|" +
        "condor|cougar|cow|coyote|crab|crane|cricket|crocodile|crow|deer|dingo|dinosaur|dodo|dog|dolphin|donkey|dove|dragonfly|duck|eagle|eel|elephant|elk|emu|falcon|ferret|flamingo|fox|frog|" +
        "gecko|gerbil|gibbon|giraffe|goat|goldfish|goose|gorilla|grizzly bear|guinea pig|hamster|hawk|hedgehog|heron|hippo|hippopotamus|honey badger|horse|hummingbird|hyena|iguana|impala|" +
        "jackal|jaguar|jellyfish|kangaroo|kiwi|koala|komodo dragon|lemur|leopard|lion|lizard|llama|lobster|lynx|macaw|manatee|mantis|meerkat|mole|mongoose|monkey|moose|mosquito|moth|mouse|" +
        "narwhal|newt|ocelot|octopus|okapi|opossum|orangutan|orca|ostrich|otter|owl|ox|panda|panther|parrot|peacock|pelican|penguin|pig|pigeon|piranha|platypus|polar bear|porcupine|possum|" +
        "puffin|puma|python|quokka|rabbit|raccoon|rat|rattlesnake|raven|red panda|reindeer|rhino|rhinoceros|salamander|salmon|scorpion|sea lion|sea otter|sea turtle|seahorse|seal|shark|sheep|" +
        "skunk|sloth|slug|snail|snake|snow leopard|sparrow|spider|squid|squirrel|starfish|stingray|swan|tapir|tarantula|tasmanian devil|termite|thylacine|tiger|toad|tortoise|toucan|turkey|turtle|" +
        "vulture|walrus|warthog|wasp|weasel|whale|wildebeest|wolf|wolverine|wombat|woodpecker|woolly mammoth|mammoth|yak|zebra|great white shark|hammerhead shark|tyrannosaurus|t rex|velociraptor|" +
        "triceratops|stegosaurus|sabertooth tiger|saber toothed tiger|megalodon|passenger pigeon|quagga|great auk|steller's sea cow")
    /** Common plants and trees. */
    private const val PLANTS = "oak|maple|pine|spruce|fir|cedar|redwood|sequoia|giant sequoia|birch|willow|elm|ash tree|aspen|cypress|juniper|yew|beech|chestnut|walnut|hickory|sycamore|magnolia|dogwood|" +
        "cherry blossom|cherry tree|apple tree|orange tree|lemon tree|olive tree|fig tree|palm tree|coconut palm|baobab|eucalyptus|banyan|bonsai|bamboo|cactus|saguaro|aloe vera|aloe|agave|succulent|" +
        "fern|moss|ivy|poison ivy|poison oak|kudzu|clover|dandelion|sunflower|rose|tulip|daisy|lily|orchid|lavender|lotus|water lily|daffodil|marigold|peony|hydrangea|hibiscus|jasmine|carnation|" +
        "poppy|iris|violet|lilac|azalea|rhododendron|chrysanthemum|venus flytrap|pitcher plant|sundew|corpse flower|rafflesia|tumbleweed|seaweed|kelp|basil|mint|sage|rosemary|thyme|oregano|" +
        "foxglove|oleander|nightshade|deadly nightshade|hemlock|monstera|pothos|snake plant|spider plant|peace lily|fiddle leaf fig|rubber plant|jade plant|money tree|holly|mistletoe|wisteria|" +
        "honeysuckle|ginkgo|wollemi pine|bristlecone pine|joshua tree|mangrove|kapok|mahogany|teak|sugar maple|weeping willow|douglas fir|christmas tree|blackberry|raspberry|blueberry|strawberry plant|" +
        "tomato plant|pumpkin plant|wheat|corn plant|rice plant|cotton plant|tobacco plant|coffee plant|tea plant|cocoa tree|grass|wildflower|chestnut tree|mushroom|toadstool|fly agaric|death cap|" +
        "poinsettia|begonia|geranium|petunia|zinnia|gardenia|camellia|fuchsia|bougainvillea|snapdragon|crocus|hyacinth|bluebell|buttercup|morning glory|forget me not|" +
        "philodendron|calathea|zz plant|croton|dracaena|ficus|string of pearls|prayer plant|air plant|catnip|tomato|potato plant|pepper plant|cucumber plant|pumpkin vine|grape vine|" +
        "acorn|pinecone|pine cone|avocado tree|mango tree|peach tree|pear tree|plum tree|lime tree|cherry plum|birch tree|oak tree|maple tree|pine tree|weed|dandelion weed|thistle|nettle|stinging nettle"
    /** Common names that land on a list page, mapped to the plant people mean. */
    private val HOUSEPLANTS = mapOf(
        "snake plant" to "Dracaena trifasciata", "spider plant" to "Chlorophytum comosum", "money tree" to "Pachira aquatica", "jade plant" to "Crassula ovata",
        "rubber plant" to "Ficus elastica", "zz plant" to "Zamioculcas", "peace lily" to "Spathiphyllum", "pothos" to "Epipremnum aureum",
        "string of pearls" to "Curio rowleyanus", "air plant" to "Tillandsia", "prayer plant" to "Maranta leuconeura", "fiddle leaf fig" to "Ficus lyrata",
        "monstera" to "Monstera deliciosa", "christmas tree" to "Christmas tree", "mother-in-law's tongue" to "Dracaena trifasciata",
        // Bugs whose search lands on a cartoon or a film first.
        "ladybug" to "Coccinellidae", "lady bug" to "Coccinellidae", "ladybird" to "Coccinellidae", "firefly" to "Lampyridae", "lightning bug" to "Lampyridae",
        "daddy long legs" to "Opiliones", "daddy longlegs" to "Opiliones", "harvestman" to "Opiliones", "roly poly" to "Armadillidiidae", "pill bug" to "Armadillidiidae", "pillbug" to "Armadillidiidae",
        "june bug" to "Phyllophaga", "stink bug" to "Pentatomidae", "lightning bugs" to "Lampyridae", "yellow jacket" to "Yellowjacket", "roach" to "Cockroach",
        "house fly" to "Housefly", "murder hornet" to "Asian giant hornet", "praying mantis" to "Mantis", "bumble bee" to "Bumblebee", "honey bee" to "Honey bee",
        "walking stick" to "Phasmatodea", "stick insect" to "Phasmatodea", "bed bug" to "Bed bug", "bedbug" to "Bed bug", "louse" to "Louse", "head louse" to "Head louse",
        "fruit fly" to "Drosophila melanogaster", "tick" to "Tick", "mite" to "Mite", "fly" to "Fly", "cricket" to "Gryllidae", "bug" to "Hemiptera",
    )
    /** Bugs: insects, spiders and the other creepy-crawlies people call bugs. */
    private const val BUGS = "bug|bugs|insect|arachnid|ant|fire ant|carpenter ant|bullet ant|army ant|leafcutter ant|sugar ant|bee|honeybee|honey bee|bumblebee|bumble bee|carpenter bee|sweat bee|" +
        "wasp|hornet|yellow jacket|yellowjacket|murder hornet|asian giant hornet|paper wasp|mud dauber|cicada killer|velvet ant|beetle|ladybug|lady bug|ladybird|firefly|lightning bug|" +
        "june bug|dung beetle|stag beetle|hercules beetle|rhinoceros beetle|japanese beetle|weevil|boll weevil|scarab|butterfly|monarch butterfly|moth|luna moth|atlas moth|gypsy moth|" +
        "clothes moth|caterpillar|silkworm|fly|house fly|housefly|fruit fly|horse fly|horsefly|gnat|fungus gnat|mosquito|midge|mayfly|dragonfly|damselfly|cricket|grasshopper|locust|" +
        "cicada|katydid|praying mantis|mantis|stick insect|walking stick|leaf insect|cockroach|roach|termite|earwig|silverfish|flea|louse|head louse|bed bug|bedbug|stink bug|aphid|" +
        "whitefly|mealybug|scale insect|thrips|lacewing|assassin bug|kissing bug|water bug|giant water bug|water strider|boxelder bug|spider|black widow|brown recluse|wolf spider|" +
        "jumping spider|tarantula|daddy long legs|daddy longlegs|harvestman|orb weaver|huntsman spider|camel spider|funnel web spider|scorpion|tick|deer tick|mite|dust mite|spider mite|" +
        "chigger|centipede|house centipede|millipede|pill bug|pillbug|roly poly|woodlouse|sowbug|mealworm|maggot|grub|cockchafer|hoverfly|botfly|tsetse fly|sandfly|no-see-um|carpet beetle"
    private val BUG_RX = Regex("^(?:$BUGS)(?:e?s)?$|^(?:lice|head lice|larvae)$|\\b(?:bugs?|insects?|ants?|bees?|beetles?|flies|fly|spiders?|moths?|wasps?|hornets?|mites?|ticks?|roaches|cockroach(?:es)?|termites?|fleas?|gnats?|mosquito(?:e?s)?|crickets?|caterpillars?|butterfl(?:y|ies))$")
    fun isBugWord(name: String) = BUG_RX.containsMatchIn(name) && !isPlantWord(name)
    /** Fish and other sea creatures. */
    private const val SEA = "fish|sea creature|sea animal|shark|great white|great white shark|whale shark|hammerhead|tiger shark|bull shark|mako|mako shark|nurse shark|goblin shark|frilled shark|" +
        "greenland shark|basking shark|lemon shark|blue shark|whale|blue whale|humpback|humpback whale|orca|killer whale|sperm whale|beluga|beluga whale|gray whale|grey whale|fin whale|bowhead whale|" +
        "dolphin|bottlenose dolphin|porpoise|manatee|dugong|seal|elephant seal|leopard seal|harbor seal|sea lion|walrus|sea otter|octopus|blue-ringed octopus|blue ringed octopus|giant pacific octopus|" +
        "squid|giant squid|colossal squid|vampire squid|cuttlefish|nautilus|jellyfish|box jellyfish|moon jellyfish|portuguese man o war|man o war|sea anemone|anemone|coral|sea urchin|starfish|" +
        "sea star|sea cucumber|sand dollar|sea sponge|crab|hermit crab|king crab|snow crab|blue crab|coconut crab|spider crab|lobster|spiny lobster|shrimp|mantis shrimp|prawn|krill|" +
        "barnacle|oyster|clam|giant clam|mussel|scallop|sea snail|conch|sea slug|nudibranch|sea turtle|leatherback|loggerhead|green sea turtle|sea snake|eel|moray|moray eel|electric eel|" +
        "stingray|manta|manta ray|pufferfish|puffer fish|blowfish|fugu|clownfish|angelfish|lionfish|swordfish|marlin|sailfish|tuna|bluefin tuna|salmon|cod|halibut|" +
        "flounder|sardine|anchovy|herring|mackerel|grouper|snapper|red snapper|catfish|carp|koi|goldfish|betta|betta fish|guppy|tetra|neon tetra|trout|largemouth bass|striped bass|sea bass|pike|sturgeon|" +
        "anglerfish|angler fish|lanternfish|sunfish|ocean sunfish|mola|seahorse|sea horse|sea dragon|leafy sea dragon|flying fish|hagfish|lamprey|coelacanth|blobfish|giant isopod|" +
        "horseshoe crab|tilapia|mahi mahi|zebrafish|parrotfish|tang|blue tang|surgeonfish|wrasse|cleaner wrasse|minnow|perch|walleye|arowana|oarfish|viperfish|gulper eel|barreleye|" +
        "stonefish|scorpionfish|triggerfish|remora|plankton|axolotl|sea pig|sea spider|cownose ray|eagle ray|sawfish|hammerhead shark|narwhal|sea lamprey|beluga sturgeon|tarpon|bonefish|tilefish"
    private val SEA_RX = Regex("^(?:$SEA)(?:e?s)?$|^(?:octopuses|octopi|walruses|fishes|anchovies)$|\\b(?:fish|fishes|sharks?|whales?|dolphins?|squids?|octopus(?:es)?|crabs?|lobsters?|shrimps?|eels?|stingrays?|manta rays?|jellyfish|corals?|clams?|oysters?|mussels?|prawns?|porpoises?)$")
    fun isSeaWord(name: String) = SEA_RX.containsMatchIn(name) && !isPlantWord(name) && !isBugWord(name)
    private val KNOWN_RX = Regex("^(?:$KNOWN|$PLANTS|$BUGS|$SEA)(?:e?s)?$|^(?:lice|head lice|larvae|octopuses|octopi|walruses|fishes|anchovies)$")
    /** Says it's a plant: "oak tree", "rose bush", "fern", "cacti". */
    private val PLANT_RX = Regex("\\b(?:tree|trees|plant|plants|flower|flowers|bush|bushes|shrub|shrubs|vine|vines|cacti|cactus|fern|ferns|moss|grass|grasses|weed|weeds|herb|herbs|succulents?|" +
        "seaweed|algae|mushrooms?|fungus|fungi|palm|palms|blossoms?|sapling|saplings|ivy|bonsai|bamboo|orchids?|roses?|tulips?|lil(?:y|ies)|sunflowers?|daisies|daisy|oaks?|maples?|pines?|" +
        "redwoods?|sequoias?|willows?|birch(?:es)?|cedars?|spruces?|firs?|baobabs?|eucalyptus|venus flytraps?|lavender|dandelions?|clover|kelp)\\b|^(?:$PLANTS)(?:e?s)?$")

    fun isPlantWord(name: String) = PLANT_RX.containsMatchIn(name)

    /** "wolves" -> "wolf", "puppies" -> "puppy", "octopus" stays. */
    fun singular(w: String): String {
        val s = w.trim()
        val last = s.substringAfterLast(' ')
        val head = s.dropLast(last.length)
        val one = when {
            last in setOf("mice") -> "mouse"
            last in setOf("geese") -> "goose"
            last in setOf("oxen") -> "ox"
            last in setOf("teeth", "feet") -> last.replace("ee", "oo")
            last in setOf("dinosaurs") -> "dinosaur"
            last == "cacti" -> "cactus"
            last == "fungi" -> "fungus"
            last == "leaves" -> "leaf"
            last == "lice" -> "louse"
            last == "larvae" -> "larva"
            last == "mantises" -> "mantis"
            last == "octopuses" || last == "octopi" -> "octopus"
            last == "walruses" -> "walrus"
            last == "fishes" -> "fish"
            Regex("(?:daddy long ?legs|longlegs)$").containsMatchIn(s) -> last
            Regex("(?:moss|grass|cypress|hibiscus|eucalyptus|iris|lotus|crocus|asparagus|narcissus|cactus|ficus|citrus|hummus|lemongrass)$").containsMatchIn(last) -> last
            Regex("(?:sheep|deer|fish|moose|bison|buffalo|salmon|trout|series|species|octopus|platypus|walrus|hippopotamus|rhinoceros|asparagus|lynx|ibis|mantis|ass|grass|chamois|swiss|bus|axolotl)$").containsMatchIn(last) -> last
            last.endsWith("wolves") -> last.dropLast(3) + "f"
            last.endsWith("lves") || last.endsWith("rves") -> last.dropLast(3) + "f"
            last.endsWith("ies") && last.length > 4 -> last.dropLast(3) + "y"
            Regex("(?:ches|shes|xes|sses|zzes)$").containsMatchIn(last) -> last.dropLast(2)
            last.endsWith("oes") && last.length > 4 -> last.dropLast(2)
            last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is") && last.length > 3 -> last.dropLast(1)
            else -> last
        }
        return head + one
    }

    /** The animal said both ways: "lions" -> ["lion", "lions"]. */
    fun forms(w: String): List<String> = listOf(singular(w), w).distinct()

    private fun clean(raw: String): String {
        var s = raw.lowercase(Locale.US).replace('’', '\'').replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        s = Colloquial.spoken(s).lowercase(Locale.US).replace(LEAD, "").trim()
        // Contractions people say: "what's", "how'd", "where'd".
        s = s.replace(Regex("\\bwhat's\\b"), "what is").replace(Regex("\\bwhats\\b"), "what is").replace(Regex("\\bwhere's\\b"), "where is")
            .replace(Regex("\\b(how|when|where|why|what|who)'d\\b"), "$1 did").replace(Regex("\\bwho's\\b"), "who is")
        return s.replace(Regex("\\s+(?:please|for me|real quick|again|exactly|actually|usually|typically|normally|on average|in the wild|in captivity|naturally)$"), "").trim()
    }

    private const val NAME = "([a-z][a-z' -]{1,40}?)"
    private const val ART = "(?:a |an |the )?"
    private const val DIETS = "carnivores?|herbivores?|omnivores?|insectivores?|piscivores?|scavengers?|vegetarians?|vegans?|meat eaters?|plant eaters?|predators?|carnivorous|herbivorous|omnivorous"

    /** Forms with their want; a form ending in "!" needs a plural or "a/the" ("where do penguins live", not "where does taylor swift live"). */
    private const val DANGER = "poisonous|toxic|venomous|edible|safe to eat|safe to touch|safe|dangerous|deadly|harmful|lethal|safe to have|bad"
    private const val PETS = "(?: (?:to|for|around) (?:cats|dogs|pets|humans|people|kids|children|horses|babies|a cat|a dog|my cat|my dog|toddlers|cows|livestock))?"

    /** Who might eat a plant: "can my cat eat basil". */
    private const val EATERS = "(?:cats?|dogs?|pets?|puppy|puppies|kittens?|kitty|kids?|toddlers?|babies|baby|horses?|rabbits?|bunn(?:y|ies)|hamsters?|guinea pigs?|birds?|parrots?|chickens?|goats?|cows?|tortoises?|turtles?|people|humans|i|you|we)"
    /** Forms ending in "~" are so loose they'd catch anything ("what's a mortgage", "does the store come out"): only for plants and animals Brick knows. */
    private val LOOSE = mutableSetOf<Regex>()

    /** Where the bugs are: "ants in my kitchen", "bed bugs in the bed". */
    private const val WHERE = "(?: (?:in|from|out of|around|on|under|inside) (?:my |the |a |our )?[a-z]+(?: [a-z]+)?)?"
    private const val CLASSES = "insects?|bugs?|arachnids?|spiders?|crustaceans?|mammals?|reptiles?|amphibians?|fish|birds?|mollusks?|molluscs?|animals?"

    private const val WATERS = "fresh ?water|salt ?water|sea ?water|the ocean|oceans|the sea|a lake|lakes|a river|rivers|a pond|ponds|a tank|tanks|an aquarium|aquariums|a fish ?tank|brackish water|tap water"

    private val FORMS: List<Triple<AnimalWant, Boolean, Regex>> = listOf(
        // Speed: "how fast can a sailfish swim", "how fast is a cheetah", "orca top speed".
        AnimalWant.SPEED to "^how fast (?:can|do|does|will|could|would) ${ART}$NAME (?:swim|run|fly|go|move|travel|sprint|get|dive|gallop|crawl)$~",
        AnimalWant.SPEED to "^how fast (?:is|are) ${ART}$NAME$~",
        AnimalWant.SPEED to "^(?:what is )?(?:the )?(?:top |max(?:imum)? |average |swimming |running )?speed (?:of|for) ${ART}$NAME$~",
        AnimalWant.SPEED to "^${ART}$NAME(?:'s|s')? (?:top |max )?speed$~",
        AnimalWant.SPEED to "^(?:is|are) ${ART}$NAME (?:fast|quick|slow|the fastest (?:fish|animal|creature|shark|whale))$~",
        // Depth: "how deep do sharks swim", "how deep can a sperm whale dive", "what depth do anglerfish live at".
        AnimalWant.DEPTH to "^how (?:deep|far down) (?:can|do|does|will|could) ${ART}$NAME (?:swim|dive|go|live|get|survive|be found|hunt)(?: in the (?:ocean|sea))?$~",
        AnimalWant.DEPTH to "^(?:what|which) depths? (?:do|does|can) ${ART}$NAME (?:live|swim|dive|go|stay)(?: at| in| to)?$~",
        AnimalWant.DEPTH to "^(?:do|does) ${ART}$NAME live (?:in the )?(?:deep (?:sea|ocean|water)|deep|shallow water|in shallow water|near the surface|at the bottom)$~",
        AnimalWant.DEPTH to "^${ART}$NAME (?:dive )?depth$~",
        // Fresh or salt water: "can goldfish live in salt water", "are clownfish freshwater or saltwater".
        AnimalWant.HABITAT to "^(?:can|do|does|could|will) ${ART}$NAME (?:live|survive|swim|go) in ($WATERS)$~",
        AnimalWant.HABITAT to "^(?:is|are) ${ART}$NAME (?:a |an )?(fresh ?water|salt ?water|marine|sea ?water)(?: or (?:fresh ?water|salt ?water|marine|sea ?water))?(?: fish| animals?| creatures?| species)?$~",
        AnimalWant.HABITAT to "^(?:do|does) ${ART}$NAME (?:need|like|prefer) (fresh ?water|salt ?water|salty water|sea ?water)$~",
        // Breathing: "how do whales breathe", "can fish breathe air", "how long can a dolphin hold its breath".
        AnimalWant.KIND to "^(?:how )?(?:do|does|can) ${ART}$NAME breathe(?: air| underwater| under water| out of water| on land)?$~",
        AnimalWant.KIND to "^how long (?:can|do|does) ${ART}$NAME (?:hold (?:its|it.s|their|his|her) breath|stay underwater|stay under water|stay under|go without air|go without breathing|survive out of water|be out of water|live out of water|dive for)$~",
        // Attacks: "do sharks attack humans", "is it safe to swim with dolphins", "will a stingray sting you".
        AnimalWant.BITE to "^(?:do|does|will|would|can|could) ${ART}$NAME (attack|eat|hunt|bite|kill) (?:humans|people|swimmers|divers|surfers|boats|you|me|a person|kids)$!",
        AnimalWant.BITE to "^is it (?:safe|dangerous|ok|okay) to (?:swim|dive|snorkel|surf|be in the water) (?:with|near|around|by) ${ART}$NAME$~",
        AnimalWant.BITE to "^(?:how many people|how many humans|how often) (?:do|does) ${ART}$NAME (?:attack|kill|bite)(?: a year| each year| every year| per year)?$~",
        AnimalWant.BITE to "^(?:are|is) ${ART}$NAME (?:dangerous to swim with|man-?eaters|man eating|maneaters|killers)$~",
        // Bites and stings: "do ants bite", "can a tarantula hurt you", "are wasp stings dangerous", "what do i do if a bee stings me".
        AnimalWant.BITE to "^(?:do|does|can|will|would|could) ${ART}$NAME (bite|sting|bite or sting|sting or bite|bite people|sting people)(?: (?:you|people|humans|me|dogs|cats|kids|pets|anyone|hard))?$!",
        // Eating them: "can you eat pufferfish", "is shark safe to eat", "what does octopus taste like".
        AnimalWant.TOXIC to "^what (?:does|do) ${ART}$NAME taste like$~",
        AnimalWant.TOXIC to "^(?:is|are) ${ART}$NAME (?:good|tasty|healthy|ok|okay|safe) (?:to eat|eating)$~",
        AnimalWant.BITE to "^(?:is|are) ${ART}$NAME (bites?|stings?) (?:dangerous|bad|painful|poisonous|venomous|deadly|harmful|serious|itchy|lethal)$",
        AnimalWant.BITE to "^how (?:bad|painful|dangerous|serious|deadly|poisonous|venomous) (?:is|are) ${ART}$NAME (bites?|stings?)$",
        AnimalWant.BITE to "^(?:what happens|what do i do|what should i do|what to do|what do you do) (?:if|when) (?:a |an |the |you get )?$NAME (bites?|stings?|bitten|stung)(?: (?:me|you|someone|by (?:a |an )?\\w+|my \\w+))?$~",
        AnimalWant.BITE to "^(?:can|could|will|would) ${ART}$NAME (?:kill|hurt|harm|injure) (?:you|me|people|humans|a person|kids|dogs|cats|a human|my \\w+)$~",
        AnimalWant.BITE to "^(?:are|is) ${ART}$NAME (?:aggressive|harmless|friendly|dangerous to (?:humans|people|me|you)|scary|a threat)$~",
        AnimalWant.BITE to "^${ART}$NAME (bites?|stings?)$~",
        // Getting rid of them: "how do i get rid of ants in my kitchen", "what attracts fruit flies", "are ladybugs good for the garden".
        AnimalWant.PESTS to "^how (?:do|can|should|would|could) (?:i|you|we|one) (?:get rid of|kill|stop|keep away|keep out|repel|deter|prevent|trap|catch|eliminate|deal with|treat|get (?:the )?\\w* out of) (?:all )?${ART}(?:these |those )?$NAME$WHERE$~",
        AnimalWant.PESTS to "^(?:how to|best way to|the best way to|ways to|tips (?:for|to|on)|easiest way to|fastest way to|quickest way to|help me) (?:get rid of|kill|keep away|keep out|repel|deter|prevent|trap|catch|eliminate|deal with|getting rid of) (?:all )?${ART}(?:these |those )?$NAME$WHERE$~",
        AnimalWant.PESTS to "^(?:get rid of|kill|repel|keep away) (?:all )?${ART}(?:these |those )?$NAME$WHERE$~",
        AnimalWant.PESTS to "^(?:how (?:do|can|should|would|could) (?:i|you|we|one) |how to )?keep ${ART}(?:the )?$NAME (?:away|out|off|out of (?:my |the )?\\w+|off (?:my |the )?\\w+|from biting(?: me)?|from coming (?:in|inside|back))$~",
        AnimalWant.PESTS to "^(?:why (?:are|is) there|why do i have|why do i keep (?:seeing|getting|finding)|why am i seeing|why am i getting) (?:so many |all these |all the |a lot of |lots of )?$NAME$WHERE$~",
        AnimalWant.PESTS to "^where (?:are|do|is) (?:all )?(?:these|the|those) $NAME (?:coming from|come from)$~",
        AnimalWant.PESTS to "^what (?:attracts|draws in|brings|lures|keeps away|repels|deters|kills|gets rid of|scares away|scares off) $NAME$~",
        AnimalWant.PESTS to "^what (?:are|is) $NAME (?:attracted to|drawn to)$~",
        AnimalWant.PESTS to "^(?:are|is) ${ART}$NAME (?:good|bad|beneficial|helpful|useful|pests?|a pest|good bugs?|bad bugs?|good to have|bad to have|good for (?:the |my )?(?:garden|plants|yard|environment|lawn)|bad for (?:the |my )?(?:garden|plants|yard|house|home|lawn|crops))$~",
        // Life cycle: "what do caterpillars turn into", "how long does it take a caterpillar to become a butterfly", "how do ants reproduce".
        AnimalWant.LIFECYCLE to "^what (?:do|does|will) ${ART}$NAME (?:turn into|become|grow into|change into|grow up to be|turn in to|morph into)$~",
        AnimalWant.LIFECYCLE to "^(?:what is )?(?:the )?life ?cycle (?:of|for) ${ART}$NAME$",
        AnimalWant.LIFECYCLE to "^${ART}$NAME(?:'s|s')? life ?cycle$",
        AnimalWant.LIFECYCLE to "^how long (?:does it take|do it take|will it take|till|until)? ?(?:for )?${ART}$NAME (?:to )?(?:turn into|become|change into|grow into|hatch|metamorphose|develop|grow up|mature)(?: (?:a |an )?[a-z]+(?: [a-z]+)?)?$~",
        AnimalWant.LIFECYCLE to "^how (?:do|does) ${ART}$NAME (?:reproduce|breed|mate|lay (?:their |its )?eggs|have babies|make babies|metamorphose|grow up|turn into (?:a |an )?[a-z]+)$~",
        AnimalWant.LIFECYCLE to "^where (?:do|does) ${ART}$NAME lay (?:their |its )?eggs$~",
        AnimalWant.LIFECYCLE to "^(?:do|does) ${ART}$NAME (?:lay eggs|have larvae|go through metamorphosis|metamorphose|have babies)$~",
        AnimalWant.LIFECYCLE to "^how (?:are|is) ${ART}$NAME born$~",
        // What it is: "is a spider an insect", "what kind of animal is a woodlouse", "how many legs does a centipede have".
        AnimalWant.KIND to "^(?:is|are) ${ART}$NAME (?:an? |considered (?:an? )?|really (?:an? )?|actually (?:an? )?|technically (?:an? )?|classified as (?:an? )?)?($CLASSES)$~",
        AnimalWant.KIND to "^what (?:kind|type|sort|class) of (?:animal|creature|bug|insect|thing|critter) (?:is|are) ${ART}$NAME$~",
        AnimalWant.KIND to "^how many (?:legs|eyes|wings|hearts|teeth|body parts|body segments|arms|tentacles|fins|gills|brains) (?:do|does) ${ART}$NAME have$~",
        AnimalWant.KIND to "^(?:do|does) ${ART}$NAME have (?:a |an |any )?(?:bones|skeleton|backbone|spine|teeth|scales|brains?|blood|hearts?|eyes|ears|lungs|gills|fins|legs|wings|venom|feelings|a nose|noses|tongues?)$~",
        AnimalWant.KIND to "^(?:do|does) ${ART}$NAME have (?:wings|six legs|eight legs|eyes)$~",
        // Poisonous, edible: "is foxglove poisonous", "are tulips toxic to cats", "can you eat dandelions".
        AnimalWant.TOXIC to "^(?:is|are) ${ART}$NAME ($DANGER)$PETS$",
        AnimalWant.TOXIC to "^can (?:you|i|we|people|humans|cats|dogs) (?:eat|touch|safely eat) ${ART}$NAME$!",
        // "can you" is often already gone: "eat dandelions" (only for plants and animals Brick knows; see parse).
        AnimalWant.TOXIC to "^(?:eat|safely eat|touch) ${ART}$NAME$",
        AnimalWant.TOXIC to "^(?:is|are) ${ART}$NAME (?:safe )?(?:to eat|to touch)$",
        AnimalWant.TOXIC to "^(?:how )?(?:poisonous|toxic|venomous|deadly|dangerous) (?:is|are) ${ART}$NAME$",
        // Loose: "can my cat eat basil", "will mushrooms kill me", "is it ok for dogs to eat grass", "are poinsettias ok for cats".
        AnimalWant.TOXIC to "^(?:can|could|should|will|do) (?:my |a |the )?$EATERS (?:eat|have|chew on|chew|nibble on|nibble|lick|safely eat|be around) ${ART}$NAME$~",
        AnimalWant.TOXIC to "^(?:will|can|could|would|does|do) ${ART}$NAME (?:kill|hurt|poison|harm|make sick|make (?:me|you|us|my \\w+|a \\w+|\\w+) sick)(?: (?:me|you|us|people|humans|someone|kids|my \\w+|a \\w+|cats|dogs|pets))?$~",
        AnimalWant.TOXIC to "^is it (?:ok|okay|safe|alright|fine|bad|dangerous|harmful) (?:for (?:my |a )?$EATERS )?to (?:eat|touch|have|chew on|chew|nibble on) ${ART}$NAME$~",
        AnimalWant.TOXIC to "^(?:is|are) ${ART}$NAME (?:ok|okay|alright|fine|good|pet[- ]safe|pet friendly|cat[- ]safe|dog[- ]safe|kid[- ]safe)(?: (?:to eat|to touch|to have))?$PETS$~",
        // Height: "how tall do redwoods get", "how big do giraffes get".
        AnimalWant.HEIGHT to "^how (?:tall|big|high|large|long|wide) (?:do|does|can|will|could) ${ART}$NAME (?:get|grow|become|grow to be|get to be|reach)$",
        AnimalWant.HEIGHT to "^(?:what is )?(?:the )?(?:average |typical |max(?:imum)? |full )?(?:height|size|full size|length|wingspan|mature height) (?:of|for) ${ART}$NAME$!",
        AnimalWant.HEIGHT to "^${ART}$NAME(?:'s|s')? (?:height|size|full size|mature height|wingspan)$",
        // Blooming: "when do cherry blossoms bloom", "what month do tulips flower".
        AnimalWant.BLOOM to "^(?:when|what time of year|what month|what season|which month|which season) (?:do|does|will|should|is|are) ${ART}$NAME (?:bloom|flower|blossom|bud|come into bloom|in bloom|blooming|flowering)$",
        // Loose (plant names only; see parse): "when do daffodils pop up", "what time of year do tulips come up", "do ferns flower".
        AnimalWant.BLOOM to "^(?:when|what time of year|what month|what season|which month|which season|what time) (?:do|does|will|should|is|are) ${ART}$NAME (?:pop up|come up|come out|open up|start blooming|start to bloom|start flowering|start to flower|bloom out|show up|turn pink|get flowers|get their flowers|flower out|blooming season|in season)$~",
        AnimalWant.BLOOM to "^(?:do|does|can|will) ${ART}$NAME (?:ever )?(?:flower|bloom|blossom|have flowers|produce flowers|get flowers|grow flowers|make flowers)$~",
        AnimalWant.BLOOM to "^(?:when|what month) (?:is|are) ${ART}$NAME (?:in bloom|in flower|blooming|flowering|in season)$~",
        AnimalWant.BLOOM to "^when is ${ART}$NAME season$",
        AnimalWant.BLOOM to "^${ART}$NAME (?:bloom time|blooming season|flowering season|bloom season|blooming time|flowering time|bloom)$",
        // Care: "how much sun does a fern need", "what soil do roses like", "how do i grow tomatoes", "how often should i water a cactus".
        AnimalWant.HABITAT to "^how much (?:sun|sunlight|light|water|shade|space|room) (?:do|does) ${ART}$NAME (?:need|like|want|get|require|take)$",
        AnimalWant.HABITAT to "^what (?:kind of |type of )?(?:soil|light|sunlight|sun|fertili[sz]er|growing conditions|zone|hardiness zone|ph|dirt) (?:do|does) ${ART}$NAME (?:need|like|prefer|grow in|grow best in|require|want|thrive in|grow)$",
        AnimalWant.HABITAT to "^how (?:often|much) (?:should|do|can) (?:i|you|we) water ${ART}(?:my )?$NAME$",
        AnimalWant.HABITAT to "^how (?:do|should|can) (?:i|you|we) (?:grow|plant|care for|take care of|look after|keep alive) ${ART}(?:my )?$NAME$!",
        AnimalWant.HABITAT to "^${ART}$NAME (?:care|growing conditions|care tips|care guide|growing tips)$",
        // Loose care (plant names only): "does lavender like full sun", "how do you keep a basil plant alive", "tips for growing tomatoes", "why is my monstera dying".
        AnimalWant.HABITAT to "^(?:do|does|can|will) ${ART}$NAME (?:like|need|prefer|want|do well in|tolerate|handle|grow in|survive in|take|love) (?:a lot of |lots of |much |full |direct |partial |bright |indirect |low |some )?(?:sun|sunlight|shade|light|water|humidity|cold|frost|heat|the cold|winter|wet soil|dry soil|sand|clay|acidic soil|fertili[sz]er|pots|indoors)$~",
        AnimalWant.HABITAT to "^how (?:do|can|should|would) (?:i|you|we|one) keep ${ART}$NAME (?:alive|healthy|happy|from dying|thriving|growing|going|looking good)$~",
        AnimalWant.HABITAT to "^(?:any |some |give me )?(?:tips|advice|help|a guide) (?:for|on|with) (?:growing|planting|caring for|taking care of|keeping|looking after|raising)? ?${ART}$NAME$~",
        AnimalWant.HABITAT to "^how to (?:grow|plant|care for|take care of|look after|keep alive|raise|start) ${ART}$NAME$~",
        AnimalWant.HABITAT to "^why (?:is|are) ${ART}$NAME (?:dying|wilting|drooping|turning (?:yellow|brown|black)|getting (?:yellow|brown) leaves|not growing|not blooming|losing (?:its|their) leaves)$~",
        AnimalWant.HABITAT to "^(?:where should i put|where do i put|where's the best place for|where is the best place for) ${ART}$NAME$~",
        AnimalWant.HABITAT to "^(?:where|what climate) (?:do|does|can) ${ART}$NAME (?:grow|grow best|grow naturally|grow wild|thrive)$!",
        // Extinct first: "how long has the dodo been extinct" isn't a lifespan.
        AnimalWant.EXTINCT to "^(?:is|are) ${ART}$NAME (?:extinct|endangered|an endangered species|threatened|a threatened species|going extinct|dying out|gone)$",
        AnimalWant.EXTINCT to "^(?:is|are) ${ART}$NAME (?:still alive|still around|still living|still a thing|still out there)$!",
        AnimalWant.EXTINCT to "^(?:when|what year) (?:did|was|were) ${ART}$NAME (?:go extinct|become extinct|die out|disappear|vanish|wiped out|made extinct|declared extinct|extinct|last seen)$",
        AnimalWant.EXTINCT to "^how long (?:has|have|is|are) ${ART}$NAME (?:been )?(?:extinct|gone|dead|died out)(?: for)?$",
        AnimalWant.EXTINCT to "^how long ago (?:did|was|were) ${ART}$NAME (?:go extinct|become extinct|die out|disappear|alive|around|live|exist)$",
        AnimalWant.EXTINCT to "^(?:what|who) (?:killed|wiped out|made extinct)(?: off)? (?:the |all the )?$NAME$",
        AnimalWant.EXTINCT to "^(?:why|how) did ${ART}$NAME (?:go extinct|become extinct|die out|disappear)$",
        AnimalWant.EXTINCT to "^${ART}$NAME (?:extinction|conservation status|endangered status)$",
        AnimalWant.EXTINCT to "^(?:are|is) there (?:any |still any |still )?(?:wild |more |living )?$NAME (?:left|still around|left in the wild|in the wild|still alive|alive)(?: today| anymore)?$!~",
        AnimalWant.EXTINCT to "^how many $NAME (?:are|is) (?:there )?(?:left|still alive|still around|in the wild|left in the wild)$!~",
        AnimalWant.EXTINCT to "^(?:are|is) ${ART}$NAME (?:dying off|disappearing|at risk|in danger|protected)$~",
        AnimalWant.EXTINCT to "^(?:what is )?(?:the )?(?:conservation status|extinction) of ${ART}$NAME$",
        // Lifespan.
        AnimalWant.LIFESPAN to "^how (?:long|many years) (?:do|does|can|will|would|did) ${ART}$NAME (?:live|survive|stay alive|usually live|typically live|live for|last)$",
        AnimalWant.LIFESPAN to "^how old (?:do|does|can|will) ${ART}$NAME (?:get|live|live to|grow)(?: to be)?$",
        AnimalWant.LIFESPAN to "^(?:what is )?(?:the |a |an )?(?:average |typical |normal |max(?:imum)? )?(?:lifespan|life span|life expectancy|longevity|age) (?:of|for) ${ART}$NAME$",
        AnimalWant.LIFESPAN to "^${ART}$NAME(?:'s|s')? (?:average )?(?:lifespan|life span|life expectancy|longevity)$",
        // Prey: what it hunts.
        AnimalWant.PREY to "^what (?:animals? |creatures? |things? |kinds? of animals? )?(?:do|does) ${ART}$NAME (?:hunt|prey on|prey upon|kill|go after|catch|feed on|hunt for|hunt down)$",
        AnimalWant.PREY to "^what animals? (?:do|does) ${ART}$NAME eat$",
        AnimalWant.PREY to "^(?:what is |what are )?(?:the )?(?:prey|preys) (?:of|for) ${ART}$NAME$",
        AnimalWant.PREY to "^(?:what is |what are )?${ART}$NAME(?:'s|s')? (?:prey|favou?rite prey|main prey|hunting habits|hunting)$",
        AnimalWant.PREY to "^(?:how|what) (?:do|does) ${ART}$NAME hunt$",
        // Predators: what hunts it.
        AnimalWant.PREDATORS to "^what (?:animals? |creatures? |things? )?(?:eats?|hunts?|kills?|preys? on|preys? upon|attacks?|feeds? on) ${ART}$NAME$",
        AnimalWant.PREDATORS to "^what (?:bugs?|insects?|pests?|critters?|birds?|kind of bugs?|kinds of bugs|kind of animals?|kinds of animals) (?:eats?|are eating|is eating|attacks?|feeds? on|chews? on|go after|goes after|damages?|destroys?) ${ART}$NAME$~",
        AnimalWant.PREDATORS to "^(?:what is|who is|what are) (?:eating|chewing on|attacking|munching on|killing) ${ART}$NAME$~",
        AnimalWant.PREDATORS to "^(?:what )?(?:pests|bugs|insects|diseases) (?:attack|eat|go after|bother|affect) ${ART}$NAME$~",
        AnimalWant.PREDATORS to "^(?:who|what) (?:hunts|eats|kills|preys on) ${ART}$NAME$",
        AnimalWant.PREDATORS to "^(?:does|do|can) (?:anything|any animals?|anyone|something) (?:eat|hunt|kill|prey on) ${ART}$NAME$",
        AnimalWant.PREDATORS to "^(?:what are |who are |what is )?(?:the )?(?:natural |main |biggest |only )?(?:predators?|enemies|natural enemies|threats) (?:of|to|for) ${ART}$NAME$",
        AnimalWant.PREDATORS to "^(?:what are )?${ART}$NAME(?:'s|s')? (?:natural |main )?(?:predators?|enemies|natural enemies)$",
        AnimalWant.PREDATORS to "^(?:is|are) ${ART}$NAME (?:hunted|eaten|preyed on) by (?:anything|any animals?|other animals)$",
        AnimalWant.PREDATORS to "^(?:does|do) ${ART}$NAME have (?:any )?(?:natural )?(?:predators?|enemies)$",
        // Diet.
        AnimalWant.DIET to "^(?:is|are) ${ART}$NAME (?:a |an )?($DIETS)$",
        AnimalWant.DIET to "^(?:do|does) ${ART}$NAME eat (?:meat|plants|other animals|bugs|insects|fish|grass)$",
        AnimalWant.DIET to "^what (?:do|does|did|can|will) ${ART}$NAME (?:eat|feed on|survive on|live on|like to eat|munch on|consume)$!",
        AnimalWant.DIET to "^what (?:food|foods) (?:do|does) ${ART}$NAME eat$",
        AnimalWant.DIET to "^how (?:do|does) ${ART}$NAME (?:catch|trap|eat|get|digest|lure|find) (?:bugs|insects|flies|food|prey|their food|its food|their prey|its prey|things|stuff)$",
        AnimalWant.DIET to "^(?:what|how) (?:do|does) ${ART}$NAME (?:feed itself|feed themselves|get (?:its|their) food|get food)$",
        AnimalWant.DIET to "^(?:what is )?(?:the )?(?:diet|food|feeding habits|eating habits) (?:of|for) ${ART}$NAME$",
        AnimalWant.DIET to "^(?:what is )?${ART}$NAME(?:'s|s')? (?:diet|feeding habits|eating habits|food)$",
        // Habitat and needs.
        AnimalWant.HABITAT to "^where (?:do|does|did) ${ART}$NAME (?:live|come from|originate|nest|dwell|reside|make (?:its|their) home|live in the wild)$!",
        AnimalWant.HABITAT to "^where (?:are|is|were) ${ART}$NAME (?:found|located|native to|from|native)$",
        AnimalWant.HABITAT to "^where can (?:you|i|we|one) find ${ART}$NAME in the wild$",
        AnimalWant.HABITAT to "^(?:what is )?(?:the )?(?:natural )?(?:habitat|environment|range|native range|home|biome|ecosystem|climate|environmental needs|living conditions|ideal conditions) (?:of|for) ${ART}$NAME$",
        AnimalWant.HABITAT to "^(?:what is |what are )?${ART}$NAME(?:'s|s')? (?:natural )?(?:habitat|environment|range|native range|environmental needs|needs|living conditions|biome)$",
        AnimalWant.HABITAT to "^what (?:kind of )?(?:environment|climate|habitat|conditions|biome|ecosystem|temperature|water|tank|enclosure|setup) (?:do|does) ${ART}$NAME (?:need|live in|like|prefer|thrive in|require|want)$",
        AnimalWant.HABITAT to "^what (?:does|do) ${ART}$NAME need to (?:live|survive|thrive)$",
        // When the species was found and named.
        AnimalWant.DISCOVERED to "^when (?:was|were) ${ART}$NAME (?:first )?(?:discovered|found|described|identified|named|classified|first seen)$!",
        AnimalWant.DISCOVERED to "^who (?:first )?(?:discovered|found|named|described|classified|identified) ${ART}$NAME$!",
        AnimalWant.DISCOVERED to "^(?:when|what year) did (?:we|people|scientists|humans) (?:first )?(?:discover|find|describe|name) ${ART}$NAME$!",
        AnimalWant.DISCOVERED to "^(?:the )?discovery of ${ART}$NAME$!",
        // Everything.
        AnimalWant.OVERVIEW to "^(?:tell me about|about|what is|what are|info on|facts about|fun facts about|teach me about) ${ART}$NAME (?:animal|animals|species|plant|plants)$",
        AnimalWant.OVERVIEW to "^(?:tell me about|about|info on|facts about|fun facts about|teach me about) ${ART}$NAME$",
        AnimalWant.OVERVIEW to "^${ART}$NAME (?:animal |plant |tree )?(?:facts|fun facts|info|information)$",
        AnimalWant.OVERVIEW to "^(?:facts|fun facts|info) (?:about|on) ${ART}$NAME$",
        // Loose (plant and animal names Brick knows only; see parse): "what's a baobab", "give me some facts about sunflowers".
        AnimalWant.OVERVIEW to "^(?:what is the deal with|what is up with|what is the story with|what do you know about|what is|what are|give me (?:some |a few )?(?:fun |cool |interesting )?(?:facts|info|information) (?:about|on)|" +
            "(?:some|a few|any) (?:fun |cool |interesting )?facts (?:about|on)|anything (?:about|on)|everything (?:about|on)|explain|describe|what is so special about|what is special about|why are|why is) ${ART}$NAME(?: so cool| so special| interesting)?$~",
    ).map { (w, p) ->
        val loose = p.endsWith("~")
        val q = p.removeSuffix("~")
        Triple(w, q.endsWith("!"), Regex(q.removeSuffix("!"))).also { if (loose) LOOSE += it.third }
    }

    private val SENT = Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")

    /** The opening sentence of [text], brackets dropped. */
    fun firstSentence(text: String): String? = SENT.split(text.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim())
        .firstOrNull { it.length > 20 }?.trim()

    /** An animal question, or null. */
    fun parse(raw: String): Request.Animal? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        for ((want, strict, rx) in FORMS) {
            val m = rx.find(s) ?: continue
            val said = m.groupValues[0]
            var name = m.groupValues[1].trim().removePrefix("the ").removePrefix("a ").removePrefix("an ").removePrefix("all the ").trim()
            // "my tomato plants", "my monstera": fine for plants ("my dog", "my kids" aren't asking about the species).
            name.removePrefix("my ").takeIf { name.startsWith("my ") && (isPlantWord(it) || Regex("^(?:$PLANTS)(?:e?s)?(?: plants?| trees?)?$").matches(it)) }?.let { name = it }
            name = name.removeSuffix(" animal").removeSuffix(" species").removeSuffix(" in the wild").let { n -> if (Regex("^(?:$PLANTS)$").matches(n) || n in HOUSEPLANTS) n else n.removeSuffix(" plant") }.trim()
            if (name.isEmpty() || name.split(' ').size > 4 || NOT_AN_ANIMAL.containsMatchIn(name)) continue
            if (History.isEvent(name)) continue
            // "where does taylor swift live" vs "where do penguins live": a plural, "a/the", or an animal Brick knows.
            if (strict) {
                val hadArticle = Regex("^(?:\\w+ ){1,3}(?:a|an|the) ").containsMatchIn(said) && Regex("\\b(?:a|an|the) ${Regex.escape(name)}\\b").containsMatchIn(said)
                val plural = (name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("us")) || Regex("\\b(?:mice|geese|sheep|deer|fish|oxen|cattle|cacti|fungi|bamboo|grass|moss|ivy|kelp|lavender|clover)$").containsMatchIn(name)
                if (!hadArticle && !plural && !KNOWN_RX.matches(name)) continue
            }
            val knownName = KNOWN_RX.matches(name) || KNOWN_RX.matches(singular(name)) || PLANT_RX.containsMatchIn(name) ||
                Regex("^(?:$PLANTS)(?:e?s)?(?: plants?| trees?| bush(?:es)?| flowers?)?$").matches(name) || isBugWord(name) || isSeaWord(name)
            // Plants don't bite, and "is a rose a mammal" isn't a thing.
            if ((want == AnimalWant.BITE || want == AnimalWant.KIND || want == AnimalWant.LIFECYCLE) && isPlantWord(name) && !isBugWord(name)) continue
            if (rx in LOOSE && !knownName && !(want == AnimalWant.TOXIC && Regex("\\b(?:poisonous|toxic|venomous|edible)\\b").containsMatchIn(s))) continue
            // "facts" alone: only for animals and plants Brick knows ("ww2 facts" is history, "taxes info" is a search).
            if (want == AnimalWant.OVERVIEW && !Regex("\\b(?:animal|animals|species|plant|plants|bugs?|insects?)$").containsMatchIn(s) && !knownName) continue
            // "is the store safe", "is it bad": only for animals and plants Brick knows, or plant words.
            if (want == AnimalWant.TOXIC && !strict && !KNOWN_RX.matches(name) && !PLANT_RX.containsMatchIn(name) &&
                !Regex("^(?:poisonous|toxic|venomous|edible)$").matches(m.groupValues.getOrNull(2).orEmpty()) && !Regex("\\b(?:poisonous|toxic|venomous|edible)\\b").containsMatchIn(s)) continue
            if (want == AnimalWant.TOXIC && Regex("^(?:eat|safely eat|touch) ").containsMatchIn(s) && !KNOWN_RX.matches(name) && !PLANT_RX.containsMatchIn(name)) continue
            if (want == AnimalWant.HEIGHT && Regex("^how (?:big|large|long|wide)").containsMatchIn(s) && !KNOWN_RX.matches(name) && !PLANT_RX.containsMatchIn(name) && !name.endsWith("s")) continue
            val asked = when (want) {
                AnimalWant.DIET -> Regex("\\b($DIETS)\\b").find(s)?.groupValues?.get(1)
                AnimalWant.TOXIC -> Regex("\\b($DANGER|eat|touch)\\b").find(s.substring(m.groups[1]!!.range.last + 1).ifBlank { s })?.groupValues?.get(1)
                    ?: Regex("\\b($DANGER)\\b").find(s)?.groupValues?.get(1) ?: "eat"
                AnimalWant.HABITAT -> if (Regex("\\b(?:fresh ?water|salt ?water|salty water|sea ?water|marine|brackish|tap water|lakes?|rivers?|ponds?|tanks?|aquariums?|ocean|oceans|the sea)\\b").containsMatchIn(s.replace(name, ""))) "water"
                    else if (Regex("\\b(?:sun|sunlight|light|water|shade|soil|fertili[sz]er|grow|growing|plant|planting|care|zone|ph|dirt|space|room|alive|dying|healthy|tips|wilting|drooping|yellow|brown|put|frost|humidity|indoors)\\b").containsMatchIn(s.replace(name, ""))) "care" else null
                AnimalWant.BITE -> when {
                    Regex("\\bst(?:ing|ung)").containsMatchIn(s) -> "sting"
                    Regex("\\b(?:attack|eat|hunt|kill|swim|dive|snorkel|surf|man-?eat\\w*|maneaters|killers)\\b").containsMatchIn(s.replace(name, "")) -> "attack"
                    else -> "bite"
                }
                AnimalWant.LIFECYCLE -> if (Regex("\\b(?:turn into|turn in to|become|grow into|change into|grow up to be|morph into)\\b").containsMatchIn(s)) "become" else null
                AnimalWant.PESTS -> when {
                    Regex("\\b(?:attract\\w*|drawn|lures?|why (?:are|is) there|why do i (?:have|keep)|why am i|coming from|come from)\\b").containsMatchIn(s) -> "attract"
                    Regex("\\b(?:good|bad|beneficial|helpful|useful|pests?)$").containsMatchIn(s) || Regex("\\b(?:good|bad) (?:for|to have|bugs?)\\b").containsMatchIn(s) -> "good"
                    else -> "rid"
                }
                AnimalWant.KIND -> Regex("^(?:do|does) .* have (?:a |an |any )?(bones|skeleton|backbone|spine|teeth|scales|brains?|blood|hearts?|eyes|ears|lungs|gills|fins|legs|wings|venom|feelings|a nose|noses|tongues?)$").find(s)?.let { "have:" + it.groupValues[1] }
                    ?: (if (Regex("\\b(?:breathe|breath|underwater|under water|out of water|without air)\\b").containsMatchIn(s)) (if (s.startsWith("how long")) "hold" else "breathe") else null)
                    ?: Regex("\\b(legs|eyes|wings|hearts|teeth|body parts|body segments|arms|tentacles|fins|gills|brains)\\b").find(s)?.groupValues?.get(1)
                    ?: Regex("\\b($CLASSES)$").find(s)?.groupValues?.get(1)?.let { singular(it) }
                else -> null
            }
            val plant = PLANT_RX.containsMatchIn(name) && !isBugWord(name) || want == AnimalWant.BLOOM || asked == "care"
            val bug = !plant && (isBugWord(name) || isBugWord(singular(name)))
            val seaName = !plant && !bug && (isSeaWord(name) || isSeaWord(singular(name)))
            // "What does chicken taste like" is cooking, not the field guide; seafood only.
            if (want == AnimalWant.TOXIC && Regex("\\btaste like$").containsMatchIn(s) && !seaName) continue
            // "Are orcas dangerous", "is a black widow deadly": for sea creatures and bugs that's about attacks and bites, not eating them.
            if (want == AnimalWant.TOXIC && (seaName || bug) && asked in setOf("dangerous", "deadly", "harmful", "lethal", "bad") &&
                !Regex("\\b(?:puffer|blowfish|fugu|eat|eating|edible)\\b").containsMatchIn(s)) {
                return Request.Animal(AnimalAsk(name, AnimalWant.BITE, if (bug) "bite" else "attack", s, false, bug, seaName))
            }
            val sea = !plant && !bug && (isSeaWord(name) || isSeaWord(singular(name)) || asked == "water" && want == AnimalWant.HABITAT || want == AnimalWant.DEPTH)
            return Request.Animal(AnimalAsk(name, want, asked, s, plant, bug, sea))
        }
        return null
    }

    // ------------------------------------------------------------------ reading

    /** "Carnivore" from "The lion is a carnivore...", the first kind of eater the text says. */
    fun dietKind(text: String): String? {
        val kinds = listOf(
            "hypercarnivor" to "Carnivore", "carnivor" to "Carnivore", "herbivor" to "Herbivore", "omnivor" to "Omnivore", "insectivor" to "Insectivore",
            "piscivor" to "Piscivore", "folivor" to "Herbivore", "frugivor" to "Frugivore", "nectarivor" to "Nectarivore", "granivor" to "Herbivore",
            "filter feed" to "Filter feeder", "scaveng" to "Scavenger", "detritivor" to "Detritivore", "planktivor" to "Planktivore",
        )
        val low = text.lowercase(Locale.US)
        return kinds.mapNotNull { (k, v) -> low.indexOf(k).takeIf { it >= 0 }?.let { it to v } }.minByOrNull { it.first }?.second
    }

    /** The year in "The last widely accepted sighting was in 1662", or null. */
    fun lastSeen(s: String, now: Int = LocalDate.now().year): Int? =
        Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b").findAll(s).map { it.value.toInt() }.filter { it <= now }.lastOrNull()

    /** How long ago it died out: "about 66 million years", "about 4,000 years", "about 364 years". */
    fun since(s: String, now: Int = LocalDate.now().year): String? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(million|billion|thousand) years ago", RegexOption.IGNORE_CASE).findAll(s).lastOrNull()?.let {
            return "about ${it.groupValues[1]} ${it.groupValues[2].lowercase(Locale.US)} years"
        }
        Regex("(\\d{1,3}(?:,\\d{3})+|\\d+) years ago", RegexOption.IGNORE_CASE).findAll(s).lastOrNull()?.let {
            return "about ${it.groupValues[1]} years"
        }
        Regex("(\\d+(?:,\\d{3})*) (?:BC|BCE)\\b").find(s)?.let { m ->
            val y = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@let
            return "about ${"%,d".format(Locale.US, y + now)} years"
        }
        val y = lastSeen(s, now) ?: return null
        return "about ${now - y} years"
    }

    /** "1758" from Wikidata's "+1758-00-00T00:00:00Z". */
    fun yearIn(t: String?): String? = t?.removePrefix("+")?.take(4)?.takeIf { it.all(Char::isDigit) }?.trimStart('0')

    private fun mainValue(c: JSONObject?) = c?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
    private fun claimsOf(all: JSONObject, p: String): List<JSONObject> = all.optJSONArray(p)?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("rank") != "deprecated" }
    }.orEmpty()

    private fun itemIds(all: JSONObject, p: String) = claimsOf(all, p).mapNotNull { mainValue(it)?.optJSONObject("value")?.optString("id")?.ifBlank { null } }

    /** A Wikidata length of time ("12 years", "8 months"). */
    private fun span(all: JSONObject, p: String): String? {
        val v = claimsOf(all, p).firstNotNullOfOrNull { mainValue(it)?.optJSONObject("value") } ?: return null
        val n = v.optString("amount").removePrefix("+").toDoubleOrNull() ?: return null
        val unit = when (v.optString("unit").substringAfterLast('/')) {
            "Q577", "Q1092296" -> "year"; "Q5151" -> "month"; "Q23387" -> "week"; "Q573" -> "day"; "Q25235" -> "hour"; else -> "year"
        }
        val num = if (n % 1.0 == 0.0) n.toLong().toString() else "%.1f".format(Locale.US, n)
        return "$num $unit${if (num == "1") "" else "s"}"
    }

    private val TAXON_DESC = Regex("\\b(?:species|subspecies|genus|family|order|clade|animal|mammal|bird|reptile|fish|amphibian|insect|arachnid|spider|crustacean|mollusc|mollusk|" +
        "cephalopod|marsupial|rodent|primate|cetacean|whale|dolphin|shark|ray|snake|lizard|frog|toad|salamander|turtle|tortoise|dinosaur|pterosaur|breed|domesticated|ungulate|carnivoran|" +
        "feline|felid|canid|bear|deer|antelope|bat|beetle|butterfly|moth|ant|bee|wasp|worm|jellyfish|coral|sponge|parrot|raptor|bird of prey|songbird|waterfowl|seabird|pinniped|megafauna|" +
        "plant|plants|tree|trees|shrub|flower|flowering|herb|herbaceous|grass|fern|moss|conifer|succulent|cactus|vine|climber|fungus|mushroom|alga|algae|seaweed|cultivar|palm|perennial|annual|evergreen|deciduous)(?:e?s)?\\b", RegexOption.IGNORE_CASE)
    /** A page about a plant, tree or fungus (by its description). */
    private val PLANT_DESC = Regex("\\b(?:plant|plants|tree|trees|shrub|flower|flowering|herb|herbaceous|grass|fern|moss|conifer|succulent|cactus|vine|climber|fungus|fungi|mushroom|alga|algae|seaweed|" +
        "cultivar|palm|perennial|evergreen|deciduous|genus of (?:flowering )?plants|angiosperm|gymnosperm|bamboo|lichen|orchid|lily|rose|daisy)\\b", RegexOption.IGNORE_CASE)

    private val SIZE = "\\d[\\d,.–-]*\\s*(?:m|metres?|meters?|ft|feet|foot|cm|centimetres?|centimeters?|in|inches|mm|km)\\b(?!/| per)"
    private val HEIGHT_HEAD = Regex("description|morphology|characteristics|anatomy|size|appearance|growth|physical|features|biology", RegexOption.IGNORE_CASE)
    private val HEIGHT_SAID = listOf(
        Regex("\\b(?:grows?|growing|grow up|reach(?:es|ing)?|attains?|tall|height|heights|stands?|measur\\w*|length|long|wingspan)\\b[^.]*\\b$SIZE|\\b$SIZE[^.]*\\b(?:tall|high|in height|long|in length|wingspan)\\b", RegexOption.IGNORE_CASE),
    )
    private val BLOOM_HEAD = Regex("flower|reproduction|description|cultivation|phenology|ecology|life cycle|blossom", RegexOption.IGNORE_CASE)
    // Months only capitalized ("May", not "may be"); seasons either way.
    private const val SEASONS = "(?i:spring|summer|autumn|fall|winter|year-round|all year|midsummer)|January|February|March|April|May|June|July|August|September|October|November|December"
    private val BLOOM_SAID = listOf(
        Regex("\\b(?i:bloom\\w*|blossom\\w*|flowers? (?:in|from|between|during|appear|are produced|open|emerge)|flowering|flowers? (?:early|late)|in flower|inflorescences?)\\b[^.]*\\b(?:$SEASONS)\\b|" +
            "\\b(?:$SEASONS)\\b[^.]*\\b(?i:bloom\\w*|blossom\\w*|flowering|flowers? appear)\\b"),
    )
    /** Plants that never flower (ferns, mosses, conifers): they make spores or cones. */
    private val NO_FLOWERS = Regex("\\b(?:do(?:es)? not (?:produce |bear |have )?(?:flowers|seeds)|non-?flowering|flowerless|reproduc\\w* (?:by|via|through|using) spores|spore-bearing|produce spores|gymnosperms?|conifers?|bear cones)\\b", RegexOption.IGNORE_CASE)
    /** A size that's the whole plant's, not a leaf's or a seed's. */
    private val PART_SIZE = Regex("\\b(?:leaf|leaves|leaflets?|fronds?|flowers?|petals?|seeds?|fruits?|berr(?:y|ies)|cones?|spores?|gametophytes?|prothall\\w*|needles?|pollen|sori|sporangia|roots?|bark|trunk diameter only|millimet\\w+|mm)\\b", RegexOption.IGNORE_CASE)
    private val TOXIC_HEAD = Regex("toxicity|toxic|poison|venom|uses|culinary|safety|human|chemistry|medicin|edib|food", RegexOption.IGNORE_CASE)
    private val TOXIC_SAID = listOf(
        Regex("\\b(?:(?:is|are|highly|mildly|extremely|very|considered|all parts? (?:of the plant )?(?:is|are)) (?:poisonous|toxic|venomous|edible|inedible)|non-?toxic|poisonous to|toxic to|venomous|edible|inedible|safe to eat|" +
            "poisoning|toxicity|fatal|lethal|deadly|dermatitis|irritat\\w+)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:poison\\w*|toxic\\w*|toxin\\w*|venom\\w*|harmful|ingest\\w*|allerg\\w*)\\b", RegexOption.IGNORE_CASE),
    )
    private val SAFE = Regex("\\b(?:non-?toxic|(?:incorrectly|wrongly|mistakenly|erroneously|falsely),? (?:said|thought|believed|considered|reported|claimed)|(?:a |common |popular )?(?:myth|misconception)|not (?:actually |very |particularly |especially )?(?:toxic|poisonous|venomous|harmful|dangerous)|harmless|(?:is|are) edible|safe to eat|safe for|eaten (?:raw|cooked|as a vegetable)|cultivated (?:for food|as a vegetable)|widely eaten)\\b", RegexOption.IGNORE_CASE)
    private val DANGEROUS = Regex("\\b(?:poison\\w*|toxic\\w*|toxins?|venom\\w*|inedible|fatal|lethal|deadly|harmful|dermatitis|irritat\\w+)\\b", RegexOption.IGNORE_CASE)
    private val CARE_SAID = listOf(
        Regex("\\b(?:full sun|partial (?:sun|shade)|part shade|full shade|shade|sunlight|bright light|indirect light|well-drained|drained|moist|soil|watering|watered|drought|hardy|hardiness|frost|zones?|ph)\\b", RegexOption.IGNORE_CASE),
    )
    private val GROWS_SAID = Regex("\\b(?:habitats?|grows? (?:in|on|best|well|wild|naturally|throughout)|growing (?:in|on)|native to|endemic to|found (?:in|throughout|across|on)|ranges? (?:from|across)|distributed|occurs? (?:in|throughout)|" +
        "inhabits?|thrives?|prefers?|requires?|needs?)\\b", RegexOption.IGNORE_CASE)
    /** What eats a plant: grazers, browsers, pests. */
    private val EATEN_SAID = listOf(
        Regex("\\b(?:eaten by|grazed by|browsed by|consumed by|fed (?:up)?on by|attacked by|damaged by|pests? (?:include|such as|of)|herbivores? (?:such as|including|like)|food (?:source )?for|feed (?:on|upon) (?:the |its )?(?:leaves|seeds|fruits?|bark|roots|nectar|flowers|foliage|acorns|nuts|cones))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:pests?|herbivor\\w*|caterpillars? (?:of|feed)|larvae (?:of|feed))\\b", RegexOption.IGNORE_CASE),
    )

    private fun sizeIn(s: String): String? = Regex("\\b(?:up to |over |more than |about |around |roughly |approximately |as much as |between )?\\d[\\d,.]*(?:\\s*(?:–|-|to|and)\\s*\\d[\\d,.]*)?\\s*(?:m|metres?|meters?|ft|feet|foot|cm|centimetres?|centimeters?|in|inches|mm)\\b(?:\\s*\\([^)]*\\))?", RegexOption.IGNORE_CASE)
        .find(s)?.value?.replace(Regex("\\s*\\([^)]*\\)"), "")
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    /** Calendar position of "early April", "summer", etc., so they're said in order. */
    private fun calendarRank(v: String): Double {
        val w = v.lowercase(Locale.US)
        val base = MONTHS.indexOfFirst { w.endsWith(it) }.takeIf { it >= 0 }?.toDouble() ?: when {
            w.endsWith("spring") -> 2.5
            w.endsWith("midsummer") -> 6.0
            w.endsWith("summer") -> 5.5
            w.endsWith("autumn") || w.endsWith("fall") -> 8.5
            w.endsWith("winter") -> 11.5
            else -> -1.0 // year-round
        }
        return base + when { w.startsWith("early") -> -0.3; w.startsWith("late") -> 0.3; else -> 0.0 }
    }
    private fun seasonsIn(s: String): String? = Regex("\\b(?:(?i:early|late|mid-?) )?(?:$SEASONS)\\b").findAll(s).map { it.value }.distinct().take(3).sortedBy { calendarRank(it) }.toList()
        .takeIf { it.isNotEmpty() }?.let { Lookup.listSaid(it, 3) }
    private val EXTINCT_DESC = Regex("\\b(?:extinct|prehistoric|fossil|dinosaur|pterosaur|ice age|pleistocene|cretaceous|jurassic|triassic|permian|cambrian)\\b", RegexOption.IGNORE_CASE)

    private val LIFESPAN_HEAD = Regex("lifespan|life span|longevity|life expectancy|life history|life cycle|reproduction|growth|ageing|aging|mortality|breeding", RegexOption.IGNORE_CASE)
    private val DIET_HEAD = Regex("diet|feeding|food|foraging|hunting|predation|prey|ecology|behaviou?r", RegexOption.IGNORE_CASE)
    private val PREDATOR_HEAD = Regex("predat|threats|enemies|mortality|ecology|interactions|competitors|behaviou?r", RegexOption.IGNORE_CASE)
    private val HABITAT_HEAD = Regex("habitat|distribution|range|ecology|environment|captivity|care|husbandry|cultivation|growing", RegexOption.IGNORE_CASE)
    private val HISTORY_HEAD = Regex("taxonomy|discovery|history|etymology|naming|classification|systematics|research|extinction|decline|status|conservation", RegexOption.IGNORE_CASE)

    // Each is tried strongest first: a sentence that says it outright beats one that only mentions it.
    private const val SPAN = "\\d[\\d,.–-]*\\s*(?:years?|months?|weeks?|decades?)\\b"
    private val LIFESPAN_SAID = listOf(
        Regex("\\b(?:lifespans?|life spans?|life expectancy|longevity)\\b[^.]*\\b$SPAN|\\b$SPAN[^.]*\\b(?:lifespans?|life spans?|life expectancy)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:can|may|typically|usually|generally|normally|often|commonly) (?:live|survive)\\b[^.]*\\b$SPAN|\\blives? (?:for |up to |to |about |around |an average of |on average |as long as |over |more than )?$SPAN|" +
            "\\b(?:lived|live|living) (?:to (?:be |the age of |an age of )?|up to |past )\\d|\\breach(?:es|ed)? (?:an |the )?ages? of\\b|\\boldest (?:known|recorded|individual)\\b[^.]*\\b$SPAN", RegexOption.IGNORE_CASE),
    )
    private val LIFESPAN_AVOID = Regex("\\b(?:ago|excavat\\w*|fossil\\w*|dated|radiocarbon|weigh\\w*|until|independent|wean\\w*|gestation|pregnan\\w*|incubat\\w*|mature|maturity|sexually)\\b", RegexOption.IGNORE_CASE)
    private val DIET_SAID = listOf(
        Regex("\\b(?:diet (?:consists|is|was|includes|included|mainly|mostly|primarily|largely|of)|(?:is|are|was|were) (?:a |an |an? \\w+ |strict |obligate |opportunistic )?(?:carnivor|herbivor|omnivor|insectivor|piscivor|frugivor|folivor)\\w*|" +
            "feeds? (?:on|mainly|mostly|primarily|largely|chiefly|almost)|fed (?:on|mainly)|eats? (?:mainly|mostly|primarily|almost|a variety|a wide|small|large|plants|grass|leaves|fruit|insects|fish|meat)|subsists? on|lives? on a diet)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:carnivor\\w*|herbivor\\w*|omnivor\\w*|insectivor\\w*|piscivor\\w*|frugivor\\w*|folivor\\w*|diet|forag\\w*|graz(?:es?|ing)|brows(?:es?|ing))\\b", RegexOption.IGNORE_CASE),
    )
    /** What hunts the animal, so it isn't read as what the animal hunts ("rabbits are the main prey of red foxes"). */
    private val HUNTED = Regex("\\b(?:prey (?:of|for|to)|preyed (?:up)?on by|eaten by|hunted by|killed by|taken by|predators? (?:of|include|such as|are)|falls? prey)\\b", RegexOption.IGNORE_CASE)
    private val PREY_SAID = listOf(
        Regex("\\b(?:prey (?:on|upon|mainly|mostly|primarily|largely|includes?|consists?|species|items|base)|preys? (?:on|upon|mainly|mostly)|(?:its|their|main|primary|preferred|favou?red|typical) prey|hunts? (?:mainly|mostly|primarily|large|small|\\w+s\\b)|" +
            "diet (?:consists|is composed|includes|mainly|mostly|primarily)|feeds? (?:on|mainly|mostly|primarily|largely|chiefly))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:hunts?|hunting|kills?|ambush\\w*|stalks?)\\b", RegexOption.IGNORE_CASE),
    )
    private val PREDATOR_SAID = listOf(
        Regex("\\b(?:preyed (?:up)?on by|eaten by|hunted by|killed by|taken by|prey (?:of|for|to)|falls? prey to|predators? (?:include|includes|such as|are|of (?:the |adult |young )?\\w+ (?:include|are))|(?:main|natural|chief|primary|only|known|major) predators?|" +
            "no (?:natural|known|real) predators|few (?:natural )?predators|apex predator|natural enemies)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:predators?|predation by|threatened by)\\b", RegexOption.IGNORE_CASE),
    )
    private val HABITAT_SAID = Regex("\\b(?:habitats?|inhabits?|inhabiting|lives? in|living in|found (?:in|throughout|across|on)|native to|endemic to|ranges? (?:from|across|throughout|over)|distributed|occurs? (?:in|throughout)|" +
        "dwell\\w*|prefers?|requires?|needs?|thrives?)\\b", RegexOption.IGNORE_CASE)
    private val DISCOVERY_SAID = Regex("\\b(?:first (?:scientifically |formally |validly )?(?:described|discovered|named|recorded|documented|identified|found|reported|collected)|discovered|described (?:by|in)|" +
        "scientific (?:name|description)|named (?:by|in)|binomial|type specimen|holotype)\\b", RegexOption.IGNORE_CASE)
    private val EXTINCT_SAID = Regex("\\b(?:extinct|extinction|died out|wiped out|last (?:confirmed |known |recorded |widely accepted |reliable |verified |certain )?(?:sighting|seen|specimen|individual|population|record|survivors?)|" +
        "disappeared|million years ago|years ago)\\b", RegexOption.IGNORE_CASE)

    // Bugs.
    private val BUG_DESC = Regex("\\b(?:insects?|arachnids?|spiders?|beetles?|moths?|butterfl\\w+|ants?|bees?|wasps?|hornets?|centipedes?|millipedes?|myriapods?|ticks?|mites?|fly|flies|bugs?|" +
        "woodlice|woodlouse|isopods?|scorpions?|cockroach\\w*|termites?|crickets?|grasshoppers?|harvestm\\w+|opiliones|hemiptera\\w*|coleoptera\\w*|lepidoptera\\w*|hymenoptera\\w*|diptera\\w*|orthoptera\\w*|odonata\\w*|mantis\\w*|lice|louse|fleas?|cicadas?|aphids?|weevils?)\\b", RegexOption.IGNORE_CASE)
    /** Each kind of animal and the words that say it ("family of beetles" is an insect). */
    private val CLASS_WORDS = listOf(
        "arachnid" to "arachnids?|spiders?|scorpions?|ticks?|mites?|harvestm\\w+|opiliones|tarantulas?|araneae",
        "insect" to "insects?|beetles?|moths?|butterfl\\w+|ants?|bees?|wasps?|hornets?|fly|flies|termites?|crickets?|grasshoppers?|cockroach\\w*|hemiptera\\w*|coleoptera\\w*|lepidoptera\\w*|" +
            "hymenoptera\\w*|diptera\\w*|orthoptera\\w*|odonata\\w*|mantis\\w*|lice|louse|fleas?|cicadas?|aphids?|weevils?|true bugs?|dragonfl\\w+|ladybirds?|fireflies|firefly",
        "myriapod" to "centipedes?|millipedes?|myriapods?|chilopoda|diplopoda",
        "crustacean" to "crustaceans?|isopods?|woodlice|woodlouse|crabs?|shrimps?|lobsters?|crayfish|krill|barnacles?|prawns?|copepods?",
        "mammal" to "mammals?|primates?|rodents?|marsupials?|cetaceans?|bats?|whales?|dolphins?|porpoises?|seals?|sea lions?|manatees?|dugongs?|walrus\\w*|otters?|pinnipeds?|sirenians?",
        "bird" to "birds?|parrots?|raptors?|penguins?",
        "reptile" to "reptiles?|snakes?|lizards?|turtles?|tortoises?|crocodil\\w+|alligators?",
        "amphibian" to "amphibians?|frogs?|toads?|salamanders?|newts?",
        "fish" to "fish|fishes|sharks?|rays?|eels?|stingrays?|skates?|salmon\\w*|tuna|cod|chondrichthyes|actinopterygii|ray-finned|teleost\\w*|seahorses?|catfish\\w*|carps?|minnows?",
        "mollusc" to "molluscs?|mollusks?|snails?|slugs?|octopus\\w*|squids?|clams?|cephalopods?|cuttlefish|nautilus\\w*|bivalves?|oysters?|mussels?|scallops?|gastropods?|nudibranchs?|conchs?",
        "echinoderm" to "echinoderms?|starfish|sea stars?|sea urchins?|sea cucumbers?|sand dollars?|asteroidea|echinoidea|brittle stars?",
        "cnidarian" to "cnidarians?|jellyfish|corals?|sea anemones?|anemones?|hydrozoa\\w*|scyphozoa\\w*|medusa\\w*|siphonophores?",
        "sponge" to "sponges?|porifera",
        "worm" to "annelids?|earthworms?|worms?",
    )
    /** "arachnid" from "order of arachnids", the earliest kind the words say. */
    fun classOf(vararg texts: String?): String? {
        for (t in texts.filterNotNull()) {
            CLASS_WORDS.mapNotNull { (k, w) -> Regex("\\b(?:$w)\\b", RegexOption.IGNORE_CASE).find(t)?.let { it.range.first to k } }.minByOrNull { it.first }?.let { return it.second }
        }
        return null
    }
    // Sea life.
    private val SEA_DESC = Regex("\\b(?:marine|aquatic|oceans?|oceanic|sea|fish|fishes|sharks?|whales?|dolphins?|cetaceans?|cephalopods?|octopus\\w*|squids?|crabs?|lobsters?|shrimps?|jellyfish|corals?|seals?|pinnipeds?|rays?|eels?)\\b", RegexOption.IGNORE_CASE)
    private val SPEED_HEAD = Regex("locomotion|swimming|speed|movement|behaviou?r|description|physiology|flight|running|anatomy", RegexOption.IGNORE_CASE)
    private const val SPEED_UNIT = "\\d[\\d,.–-]*\\s*(?:km/h|kilometres per hour|kilometers per hour|km per hour|mph|miles per hour|knots|m/s|metres per second|meters per second)"
    private val SPEED_SAID = listOf(
        Regex("\\b$SPEED_UNIT\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:fastest|top speed|speeds? of|swims? at|can reach|bursts? of speed|slow-moving|slow swimmers?|sluggish)\\b", RegexOption.IGNORE_CASE),
    )
    private const val DEPTH_UNIT = "\\d[\\d,.–-]*\\s*(?:m|metres?|meters?|ft|feet|km|kilometres?|kilometers?)(?!/| per)"
    private val DEPTH_HEAD = Regex("depth|distribution|habitat|ecology|range|diving|behaviou?r|physiology", RegexOption.IGNORE_CASE)
    private val DEPTH_SAID = listOf(
        // "can dive to 2,250 metres" beats "prefers waters over 1,000 metres deep".
        Regex("\\b(?:dives?|diving|dove|descend\\w*|recorded at|found (?:as deep as|down to)|down to)\\b[^.]*\\b$DEPTH_UNIT\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:depths?|deep|dives?|diving|dove|down to|below the surface)\\b[^.]*\\b$DEPTH_UNIT\\b|\\b$DEPTH_UNIT\\b[^.]*\\b(?:deep|depths?|below the surface|down)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:deep(?:-sea| sea| water| waters| ocean)|depths?|abyssal|bathypelagic|mesopelagic|pelagic|benthic|shallow (?:water|waters|coastal|reefs?|seas?)|near the surface|sea ?floor|ocean floor)\\b", RegexOption.IGNORE_CASE),
    )
    private val FRESH = Regex("\\b(?:fresh ?water|lakes?|rivers?|streams?|ponds?|wetlands?)\\b", RegexOption.IGNORE_CASE)
    private val SALT = Regex("\\b(?:marine|salt ?water|sea ?water|oceans?|oceanic|seas?|coral reefs?|reefs?|coastal|coasts?|brackish|estuar\\w+)\\b", RegexOption.IGNORE_CASE)
    private val BREATHE_HEAD = Regex("respiration|breathing|physiology|anatomy|diving|description|biology|gills", RegexOption.IGNORE_CASE)
    private val BREATHE_SAID = listOf(
        Regex("\\b(?:gills?|lungs?|blowholes?|spiracles?)\\b[^.]*\\b(?:breath\\w*|oxygen|air|surface|respir\\w*)\\b|\\b(?:breath\\w*|respir\\w*)\\b[^.]*\\b(?:gills?|lungs?|blowholes?|skin)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:gills?|lungs?|blowholes?|breath\\w*|respir\\w*)\\b", RegexOption.IGNORE_CASE),
    )
    private val HOLD_SAID = listOf(
        Regex("\\b(?:hold (?:its|their) breath|dives? (?:lasting|of|for|can last)|submerged for|stays? (?:underwater|submerged|under)|without (?:breathing|air)|out of (?:the )?water)\\b[^.]*\\d|" +
            "\\b\\d[\\d,.–-]*\\s*(?:minutes?|hours?|seconds?|days?)\\b[^.]*\\b(?:dive|dives|underwater|submerged|breath|out of water|without water)\\b", RegexOption.IGNORE_CASE),
    )
    private fun speedIn(s: String): String? = Regex("\\b(?:up to |about |around |over |more than |as fast as |nearly |almost )?$SPEED_UNIT\\b", RegexOption.IGNORE_CASE).find(s)?.value
    private fun depthIn(s: String): String? = Regex("\\b(?:up to |about |around |over |more than |below |down to |as deep as |nearly |almost |beyond )?$DEPTH_UNIT\\b", RegexOption.IGNORE_CASE).find(s)?.value
    private val LEGS_BY_CLASS = mapOf("insect" to "six legs", "arachnid" to "eight legs", "crustacean" to "fourteen legs")
    private val BITE_HEAD = Regex("bite|sting|venom|human|medical|toxicity|danger|defen|interaction|health|behaviou?r", RegexOption.IGNORE_CASE)
    private val BITE_SAID = listOf(
        Regex("\\b(?:bites?|stings?|stinging|bitten|stung|biting)\\b[^.]*\\b(?:painful|pain|harmless|dangerous|venom\\w*|deadly|fatal|swelling|itch\\w*|allerg\\w*|anaphyla\\w*|rarely|humans?|people|medical|redness|necro\\w*|symptoms?)\\b|" +
            "\\b(?:humans?|people)\\b[^.]*\\b(?:bites?|stings?|bitten|stung)\\b|\\b(?:harmless to humans|not (?:dangerous|aggressive) to humans|venom is)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:bites?|stings?|stinger|venom\\w*|fangs?|chelicerae|bitten|stung)\\b", RegexOption.IGNORE_CASE),
    )
    private val STING_SAID = Regex("\\b(?:stings?|stinger|stung|stinging)\\b", RegexOption.IGNORE_CASE)
    private val HARMLESS = Regex("\\b(?:harmless|do not (?:bite|sting)|does not (?:bite|sting)|cannot (?:bite|sting)|can't (?:bite|sting)|unable to (?:bite|sting|pierce)|no stinger|lacks? (?:a )?stinger|" +
        "non-?venomous|not (?:dangerous|aggressive|venomous|harmful)|lack venom|no venom)\\b", RegexOption.IGNORE_CASE)
    private val IF_BOTHERED = Regex("\\b(?:rarely|seldom|only (?:bite|sting|when|if)|(?:when|if) (?:provoked|threatened|handled|disturbed|cornered|trapped|crushed|squeezed)|in self-defen[cs]e|defensive(?:ly)?|not aggressive)\\b", RegexOption.IGNORE_CASE)
    private val PEST_HEAD = Regex("pest|control|management|human|interaction|infest|relationship|economic|agricult|household|prevention|treatment|ecolog|behaviou?r", RegexOption.IGNORE_CASE)
    private val RID_SAID = listOf(
        // Saying how: "can be controlled with", "treatment involves", "insecticides", "traps".
        Regex("\\b(?:(?:can be|are|is|be) (?:controlled|eliminated|killed|removed|prevented|treated|repelled|deterred|trapped|managed|eradicated)|control (?:methods?|measures?|includes?|involves?|is)|" +
            "(?:treatment|prevention|management|eradication) (?:includes?|involves?|methods?|consists?|requires?|is)|insecticides?|pesticides?|repellents?|traps?|baits?|heat treatment|vacuuming|" +
            "diatomaceous|boric acid|fumigat\\w*|get rid of|sealing (?:cracks|gaps)|hot water|washing|laundering)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:control(?:led)? (?:by|with|using)|repel\\w*|deter(?:s|red|rent|rents|ring)?|eliminat\\w*|exterminat\\w*|removal|prevent\\w*)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:pests?|infest\\w*|nuisance)\\b", RegexOption.IGNORE_CASE),
    )
    private val ATTRACT_SAID = Regex("\\b(?:attracted (?:to|by)|attracts?|drawn to|lured|enter (?:homes|houses|buildings|dwellings)|seek(?:ing)? (?:shelter|food|warmth|water)|overwinter\\w*|come indoors|invade\\w*|" +
        "breed(?:s|ing)? in|feed(?:s|ing)? on (?:fermenting|rotting|ripe|sugary|crumbs)|ripening|fermenting|rotting)\\b", RegexOption.IGNORE_CASE)
    private val GOOD_SAID = listOf(
        Regex("\\b(?:beneficial|pollinat\\w*|biological (?:pest )?control|natural enem\\w*|helpful|eat (?:aphids|pests)|prey on (?:aphids|pests|other insects)|(?:considered|regarded as|serious|major|significant|agricultural|household) (?:an? )?pests?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:pests?|damage\\w*|nuisance|harmless)\\b", RegexOption.IGNORE_CASE),
    )
    private val LIFECYCLE_HEAD = Regex("life ?cycle|development|reproduction|metamorphosis|breeding|growth|biology|mating", RegexOption.IGNORE_CASE)
    private val LIFECYCLE_SAID = listOf(
        Regex("\\b(?:metamorphos\\w*|life ?cycle|pupa\\w*|pupates?|chrysalis|cocoons?|instars?|nymphs?|larva\\w*)\\b[^.]*\\b(?:eggs?|adults?|stages?|days|weeks|months|hatch\\w*|becomes?|emerges?|develops?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:eggs?|hatch\\w*|larva\\w*|nymphs?|metamorphos\\w*|molt\\w*|moult\\w*|pupa\\w*|chrysalis|lays?)\\b", RegexOption.IGNORE_CASE),
    )

    private fun about(s: String, names: List<String>): Boolean {
        val low = s.lowercase(Locale.US)
        return names.any { low.contains(it) } || Regex("^(?:they|it|its|their|these|this species|the species|adults?|males?|females?|young|juveniles?|cubs?|pups?|calves|chicks?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)
    }

    /** The first sentence (from the sections [head] picks first, then the intro, then the rest) that [said] matches. */
    private fun pick(secs: List<Characters.Section>, head: Regex, said: Regex, names: List<String>, also: Regex? = null): String? {
        val ordered = secs.filter { head.containsMatchIn(it.heading) } + secs.filter { it.heading.isEmpty() } + secs.filter { it.heading.isNotEmpty() && !head.containsMatchIn(it.heading) }
        val all = ordered.flatMap { it.sentences }.map { it.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 30..320 && !it.contains('"') && !it.contains('“') }
        return all.firstOrNull { said.containsMatchIn(it) && (also == null || also.containsMatchIn(it)) && about(it, names) }
            ?: all.firstOrNull { said.containsMatchIn(it) && (also == null || also.containsMatchIn(it)) }
    }

    /** [pick] with each of [tiers] in turn (strongest first), skipping sentences [avoid] matches. */
    private fun pick(secs: List<Characters.Section>, head: Regex, tiers: List<Regex>, names: List<String>, avoid: Regex? = null): String? {
        val ordered = secs.filter { head.containsMatchIn(it.heading) } + secs.filter { it.heading.isEmpty() } + secs.filter { it.heading.isNotEmpty() && !head.containsMatchIn(it.heading) }
        val all = ordered.flatMap { it.sentences }.map { it.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 30..320 && !it.contains('"') && !it.contains('“') && (avoid == null || !avoid.containsMatchIn(it)) }
        for (t in tiers) {
            (all.firstOrNull { t.containsMatchIn(it) && about(it, names) } ?: all.firstOrNull { t.containsMatchIn(it) })?.let { return it }
        }
        return null
    }

    /** "10–14 years" from "Lions live 10–14 years in the wild". */
    private const val NUM = "(?:\\d[\\d,.]*|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|a few|several|a couple of)"
    /** "1–2 years" from "Most spiders live for only one to two years, although some tarantulas can live over 20". */
    private fun lifespanIn(s: String): String? = Regex("\\b(?:up to |over |more than |about |around |roughly |approximately |as long as |only )?$NUM(?:\\s*(?:–|-|to)\\s*$NUM)?\\s*(?:years?|months?|weeks?|days?|decades?)\\b", RegexOption.IGNORE_CASE)
        .find(s)?.value?.removePrefix("only ")?.let { v ->
            val words = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
                "eleven" to "11", "twelve" to "12", "fifteen" to "15", "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50")
            v.split(' ').joinToString(" ") { words[it.lowercase(Locale.US)] ?: it }.replace(Regex("(\\d+) to (\\d+)"), "$1–$2")
        }

    /** Everything Brick says and shows about an animal or plant, or null when [AnimalAsk.animal] isn't one. */
    suspend fun find(ask: AnimalAsk): LookupResult? = withContext(Dispatchers.IO) {
        val one = singular(ask.animal)
        val known = KNOWN_RX.matches(ask.animal) || KNOWN_RX.matches(one)
        // A page that's an animal or plant: Wikidata's parent taxon, or a description that says so (for ones Brick knows).
        suspend fun taxon(word: String?): Pair<Lookup.Page, JSONObject?>? {
            // "oak tree" → the genus "oak", not some famous oak tree.
            // Houseplant nicknames whose page is a list of plants: go straight to the one people mean.
            val bare = HOUSEPLANTS[one] ?: if (Regex("^(?:$PLANTS)$").matches(one) && one.contains(' ')) one
                else one.replace(Regex("\\s+(?:trees?|plants?|bush(?:es)?|shrubs?|flowers?)$"), "").ifBlank { one }
            val p = (if (word != null) Lookup.page(bare, emptySet(), listOf(word)) else Lookup.page(bare, emptySet())) ?: return null
            // "Snakeplant may refer to…": a list page, not the plant.
            if (p.description?.let { Regex("(?i)\\b(?:index of|disambiguation|may refer to|topics referred to)\\b").containsMatchIn(it) } == true) return null
            val claims = p.qid?.let { Lookup.claims(it) }
            val isTaxon = claims != null && (claims.has("P171") || claims.has("P225") || claims.has("P105"))
            // Not a taxon (e.g. "Whale", an informal group): the title has to be the animal itself, not "Blue whale penis" or "Shark attack".
            val titled = p.title.substringBefore(" (").lowercase(Locale.US).let { t -> t == bare || forms(bare).any { t.endsWith(it) } || forms(one).any { t.endsWith(it) } }
            return if (isTaxon || (known || ask.plant) && titled && p.description?.let { TAXON_DESC.containsMatchIn(it) } == true) p to claims else null
        }
        val words = when {
            ask.plant -> listOf("plant", null)
            ask.bug -> listOf("insect", "animal", null)
            ask.sea -> listOf("animal", null, "fish")
            else -> listOf("animal", null, "plant")
        }
        var found: Pair<Lookup.Page, JSONObject?>? = null
        for (w in words) { found = taxon(w); if (found != null) break }
        val (p, claims) = found ?: return@withContext null
        val plant = ask.plant || p.description?.let { PLANT_DESC.containsMatchIn(it) && !BUG_DESC.containsMatchIn(it) } == true
        val name = p.title.substringBefore(" (").trim()
        val cls = classOf(p.description, firstSentence(p.extract))
        val bug = !plant && (cls in setOf("insect", "arachnid", "myriapod") || ask.bug && cls in setOf(null, "crustacean"))
        val sea = !plant && !bug && (ask.sea || cls in setOf("fish", "echinoderm", "cnidarian", "sponge") || p.description?.let { SEA_DESC.containsMatchIn(it) } == true)
        val names = (forms(ask.animal) + forms(one) + name.lowercase(Locale.US) + name.lowercase(Locale.US).split(' ').last()).filter { it.length >= 3 }.distinct()
        val text = Characters.wikiText(p.title)
        val secs = text?.let { Characters.sections(it) } ?: listOf(Characters.Section("", p.extract, 2, false))
        val intro = secs.firstOrNull { it.heading.isEmpty() }?.text ?: p.extract

        // Wikidata: the scientific name with the year and who named it, status, lifespan, food, habitat.
        val c = claims ?: JSONObject()
        val taxon = claimsOf(c, "P225").firstOrNull()
        val sci = mainValue(taxon)?.optString("value")?.ifBlank { null }
        val namedYear = yearIn(taxon?.optJSONObject("qualifiers")?.optJSONArray("P574")?.optJSONObject(0)?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time"))
        val authorIds = taxon?.optJSONObject("qualifiers")?.optJSONArray("P405")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") } }.orEmpty()
        val statusIds = itemIds(c, "P141")
        val foodIds = if (plant) emptyList() else itemIds(c, "P1034")
        val habitatIds = itemIds(c, "P2974")
        val labels = Lookup.labels(authorIds + statusIds + foodIds + habitatIds)
        val status = statusIds.firstOrNull()?.let { labels[it] }?.replaceFirstChar { it.uppercase(Locale.US) }
        val authors = authorIds.mapNotNull { labels[it] }
        val foods = foodIds.mapNotNull { labels[it] }
        val habitats = habitatIds.mapNotNull { labels[it] }
        val lifeData = span(c, "P2250")
        val maxLife = span(c, "P4214")

        // The article.
        val lifeSaid = pick(secs, LIFESPAN_HEAD, LIFESPAN_SAID, names, LIFESPAN_AVOID)?.takeIf { lifespanIn(it) != null }
        // A plant's "diet" is only a carnivorous plant's ("venus flytraps eat insects"); its herbivores are what eats it.
        val carnivorousPlant = plant && Regex("\\b(?:carnivorous plant|insectivorous plant|traps? (?:and digests?|insects|prey))\\b", RegexOption.IGNORE_CASE).containsMatchIn(intro)
        val dietSaid = if (plant && !carnivorousPlant) null else pick(secs, DIET_HEAD, DIET_SAID, names, HUNTED)
        val kind = when {
            carnivorousPlant -> "Carnivorous plant"
            plant -> null
            // Bug groups eat everything ("one spider species is a herbivore"): only what a sentence says of the whole group.
            bug -> Regex("\\b(?:are|is) (?:mostly |mainly |generally |largely |almost all |predominantly |primarily |typically |all |usually )?(predators|predatory|carnivor\\w*|herbivor\\w*|omnivor\\w*|insectivor\\w*|scavengers|detritivor\\w*|parasites|parasitic|blood-?feeding|nectarivor\\w*)\\b", RegexOption.IGNORE_CASE)
                .find(listOfNotNull(intro, dietSaid).joinToString(" "))?.groupValues?.get(1)?.let { w ->
                    when {
                        Regex("(?i)predat|carnivor|insectivor").containsMatchIn(w) -> "Carnivore"
                        Regex("(?i)parasit|blood").containsMatchIn(w) -> "Parasite"
                        else -> dietKind(w)
                    }
                }
            else -> dietKind(listOfNotNull(intro, dietSaid).joinToString(" ")) ?: dietKind(text?.take(30_000).orEmpty())
        }
        // Plant eaters (and plants) don't hunt.
        val grazer = kind in setOf("Herbivore", "Frugivore", "Nectarivore", "Detritivore") || plant && !carnivorousPlant
        val preySaid = if (grazer) null else pick(secs, DIET_HEAD, PREY_SAID, names, HUNTED)
        val predatorSaid = if (plant) pick(secs, PREDATOR_HEAD, EATEN_SAID, names, Regex("\\b(?:carcinogen\\w*|toxic\\w*|poison\\w*|cancer|humans?|people)\\b", RegexOption.IGNORE_CASE)) else pick(secs, PREDATOR_HEAD, PREDATOR_SAID, names)
        val habitatAvoid = Regex("\\b(?:longest-lived|oldest|record|years old|lived to|age of|prey|predators?|eats?|diet|fossil\\w*|million years|amber)\\b", RegexOption.IGNORE_CASE)
        val habitatSaid = if (plant) pick(secs, HABITAT_HEAD, listOf(GROWS_SAID, HABITAT_SAID), names, habitatAvoid) else pick(secs, HABITAT_HEAD, listOf(HABITAT_SAID), names, habitatAvoid)
        val careSaid = if (plant || ask.diet == "care") pick(secs, Regex("cultivation|care|growing|horticulture|gardening|ecology|habitat", RegexOption.IGNORE_CASE), CARE_SAID, names, habitatAvoid) else null
        val heightSaid = pick(secs, HEIGHT_HEAD, HEIGHT_SAID, names, Regex("\\b(?:ago|fossil\\w*|record(?:ed)? (?:holder|specimen)|range|ranges|km|kilomet\\w+|miles|elevation|altitude|distribution)\\b", RegexOption.IGNORE_CASE))
            ?.takeUnless { plant && PART_SIZE.containsMatchIn(it) && !Regex("\\b(?:tall|height|grows? (?:to|up to)|trees? (?:can )?reach)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val flowerless = plant && NO_FLOWERS.containsMatchIn(intro + " " + (text?.take(20_000) ?: ""))
        val bloomSaid = if (plant && !flowerless) pick(secs, BLOOM_HEAD, BLOOM_SAID, names) else null
        // A plant "toxic to mosquitos" isn't toxic to you; plants aren't venomous ("an antidote to the basilisk's venom" is folklore).
        val toxicAvoid = if (plant) Regex("\\b(?:mosquito\\w*|insects?|insecticid\\w*|pests?|larvae|bacteria\\w*|fung(?:i|al|icid\\w*)|nematodes?|aphids?|mites?|repell\\w*|" +
            "venom\\w*|antidotes?|myth\\w*|legends?|legendary|folklore|basilisks?|superstitions?|believed)\\b", RegexOption.IGNORE_CASE) else null
        val toxicSaid = if (ask.want == AnimalWant.TOXIC || plant) pick(secs, TOXIC_HEAD, TOXIC_SAID, names, toxicAvoid) else null
        // Bugs: bites and stings, pests, life cycle.
        val stingAsked = ask.diet == "sting"
        // "Do ants bite" wants a bite sentence, not the bullet ant's sting.
        val verb = when (ask.diet) {
            "sting" -> "stings?|stinger|stung|stinging"
            "attack" -> "attacks?|attacked|unprovoked|fatal(?:ities)?|bites?|bitten|dangerous to humans|harmless to humans|threat to humans|man-?eaters?"
            else -> "bites?|bitten|biting"
        }
        val verbTiers = listOf(
            Regex("\\b(?:$verb)\\b[^.]*\\b(?:painful|pain|dangerous|venom\\w*|swelling|allerg\\w*|humans?|people|harmless|rarely|itch\\w*|skin)\\b|\\b(?:humans?|people|skin)\\b[^.]*\\b(?:$verb)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:$verb)\\b", RegexOption.IGNORE_CASE),
        )
        val biteSaid = if (bug || ask.want == AnimalWant.BITE) pick(secs, BITE_HEAD, (if (ask.want == AnimalWant.BITE) verbTiers else emptyList()) + BITE_SAID, names) else null
        // About living with them, not lab work or ecology: the sentence has to mention homes, pests or people too.
        fun inHomes(t: Regex) = Regex("^(?=.*\\b(?:pests?|infest\\w*|homes?|houses?|household|kitchens?|buildings?|dwellings?|indoors|humans?|people|crops?|gardens?|food|garbage|trash|fruit|bedding|beds?|furniture|pets?)\\b).*?" + t.pattern, RegexOption.IGNORE_CASE)
        val pestTiers = when (ask.diet) {
            "attract" -> (listOf(ATTRACT_SAID) + RID_SAID).map { inHomes(it) }
            "good" -> GOOD_SAID
            else -> (RID_SAID + ATTRACT_SAID).map { inHomes(it) }
        }
        // Advice, not anecdotes or lab work: "the founder of a company said…", "behavioral screens to isolate genes".
        val pestAvoid = Regex("\\b(?:he|she|his|her|founder|company|said|claimed|survey\\w*|percent|genes?|genetic\\w*|screens|laborator\\w*|researchers?|scientists?|experiment\\w*|model organism|" +
            "studies|study|history|historically|century|ancient|19\\d\\d|18\\d\\d)\\b|%", RegexOption.IGNORE_CASE)
        val pestSaid = if (bug || ask.want == AnimalWant.PESTS) pick(secs, PEST_HEAD, pestTiers, names, pestAvoid) else null
        // "What do caterpillars turn into": the pupa and what comes out of it, before the general stages.
        val becomeTier = Regex("\\b(?:pupa\\w*|pupates?|chrysalis|chrysalides|cocoons?|metamorphos\\w*|emerges? as|turns? into|becomes? (?:an? )?(?:adult|butterfl\\w+|moth|beetle|fly))\\b", RegexOption.IGNORE_CASE)
        val lifeCycleSaid = if (bug || ask.want == AnimalWant.LIFECYCLE) pick(secs, LIFECYCLE_HEAD, (if (ask.diet == "become") listOf(becomeTier) else emptyList()) + LIFECYCLE_SAID, names,
            // Biology, not culture: "he argued that the metamorphosis ... was a symbol" isn't a life cycle.
            Regex("\\b(?:fossil\\w*|million years|he|she|his|her|argued|wrote|symbol\\w*|proof|metaphor\\w*|philosoph\\w*|religio\\w*|literature|poem|novel|christian\\w*|culture|cultural|art|artists?|myth\\w*)\\b", RegexOption.IGNORE_CASE)) else null
        val legs = Regex("\\b(six|eight|ten|twelve|fourteen|\\d{1,3}|hundreds of|up to \\d+|many) (?:pairs of )?(?:jointed |walking |true )?legs\\b", RegexOption.IGNORE_CASE)
            .find(intro + " " + (text?.take(15_000) ?: ""))?.value?.lowercase(Locale.US) ?: LEGS_BY_CLASS[cls]
        // Sea life: speed, depth, fresh or salt water, breathing.
        val speedSaid = if (sea || ask.want == AnimalWant.SPEED) pick(secs, SPEED_HEAD, SPEED_SAID, names, Regex("\\b(?:boats?|ships?|cars?|vessels?|winds?|currents?)\\b", RegexOption.IGNORE_CASE)) else null
        val depthSaid = if (sea || ask.want == AnimalWant.DEPTH) pick(secs, DEPTH_HEAD, DEPTH_SAID, names, Regex("\\b(?:long|length|wide|tall|weigh\\w*|fossil\\w*|million years|elevation|altitude|mountains?)\\b", RegexOption.IGNORE_CASE)) else null
        val waterText = listOfNotNull(p.description, intro.take(900), habitatSaid).joinToString(" ")
        val fresh = FRESH.containsMatchIn(waterText)
        val salt = SALT.containsMatchIn(waterText)
        val water = when {
            !sea && ask.diet != "water" -> null
            fresh && salt -> "fresh and salt water"
            fresh -> "fresh water"
            salt -> "salt water"
            else -> null
        }
        val breatheSaid = if (ask.diet == "breathe" || ask.diet == "hold") pick(secs, BREATHE_HEAD, (if (ask.diet == "hold") HOLD_SAID else emptyList()) + BREATHE_SAID, names) else null
        val discoverySaid = pick(secs, HISTORY_HEAD, DISCOVERY_SAID, names, Regex("\\b1[5-9]\\d{2}|20\\d{2}\\b"))
        val extinct = status?.contains("extinct", true) == true && !status.contains("wild", true) ||
            (status == null && (p.description?.let { EXTINCT_DESC.containsMatchIn(it) } == true || Regex("\\b(?:is an extinct|are an extinct|(?:lived|existed|died out|went extinct|became extinct)\\b[^.]*\\bmillion years ago)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intro.take(600)) ||
                !plant && Regex("\\b(?:was a|were a)\\b", RegexOption.IGNORE_CASE).containsMatchIn(intro.take(300))))
        val extinctSaid = if (extinct) pick(secs, HISTORY_HEAD, EXTINCT_SAID, names, Regex("\\d")) else null
        val gone = extinctSaid?.let { since(it) } ?: if (extinct) since(intro) else null
        // "The oldest known was 38" is a record, not a lifespan (it goes in OLDEST).
        val record = lifeSaid?.let { Regex("\\b(?:oldest|longest-lived|record)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
        val life = lifeSaid?.takeUnless { record }?.let { lifespanIn(it) } ?: lifeData
        val named = listOfNotNull(namedYear, authors.takeIf { it.isNotEmpty() }?.let { "by " + Lookup.listSaid(it, 2) }).joinToString(" ").ifBlank { null }
        val the = if (Regex("^[A-Z][a-z]").containsMatchIn(name) && name.split(' ').size <= 3) "The ${name.replaceFirstChar { it.lowercase(Locale.US) }}" else name
        // Poisonous or safe, from what the article says.
        val danger: Boolean? = toxicSaid?.let { t ->
            when {
                SAFE.containsMatchIn(t) && !Regex("\\b(?:toxic|poisonous) (?:if|when|unless)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> false
                DANGEROUS.containsMatchIn(t) -> true
                else -> null
            }
        }
        val height = heightSaid?.let { sizeIn(it) }
        val blooms = bloomSaid?.let { seasonsIn(it) }

        fun brief(s: String?) = s?.let { Sports.brief(it, 120) }
        fun an(w: String) = if (w.first().lowercaseChar() in "aeiou") "an" else "a"
        val answer: String? = when (ask.want) {
            AnimalWant.OVERVIEW -> brief(firstSentence(intro))
            AnimalWant.LIFESPAN -> brief(lifeSaid) ?: lifeData?.let { "$the lives about $it." } ?: maxLife?.let { "The oldest known lived $it." }
            AnimalWant.DIET -> {
                val asked = ask.diet?.let { dietKind(it) ?: it }
                val head = if (asked != null && kind != null) {
                    if (kind.contains(asked, true)) "Yes. " else "No, it's ${an(kind)} ${kind.lowercase(Locale.US)}. "
                } else ""
                head + (brief(dietSaid) ?: foods.takeIf { it.isNotEmpty() }?.let { "$the eats ${Lookup.listSaid(it, 3)}." }
                    ?: kind?.let { "$the is ${an(it)} ${it.lowercase(Locale.US)}." }
                    ?: if (plant) "$the is a plant; it makes its own food from sunlight." else "")
            }
            AnimalWant.HABITAT -> (if (ask.diet == "water" && water != null) {
                // "Can goldfish live in salt water" → "No. The goldfish lives in fresh water."
                val wantsFresh = Regex("\\b(?:fresh ?water|lakes?|rivers?|ponds?|tap water)\\b").containsMatchIn(ask.said)
                val wantsSalt = Regex("\\b(?:salt ?water|salty|sea ?water|marine|oceans?|the sea)\\b").containsMatchIn(ask.said)
                val head = when {
                    wantsFresh && !wantsSalt -> if (fresh) "Yes. " else "No. "
                    wantsSalt && !wantsFresh -> if (salt) "Yes. " else "No. "
                    else -> ""
                }
                "$head$the lives in $water."
            } else null)
                ?: (if (ask.diet == "care") brief(careSaid) ?: brief(habitatSaid) else brief(habitatSaid) ?: brief(careSaid))
                ?: habitats.takeIf { it.isNotEmpty() }?.let { "$the ${if (plant) "grows" else "lives"} in ${Lookup.listSaid(it, 3)?.lowercase(Locale.US)}." }
            AnimalWant.PREY -> when {
                plant && grazer -> "$the is a plant, so it doesn't hunt."
                grazer -> "$the is ${an(kind!!)} ${kind.lowercase(Locale.US)}, so it doesn't hunt."
                else -> brief(preySaid) ?: brief(dietSaid)
            }
            AnimalWant.PREDATORS -> brief(predatorSaid) ?: "I couldn't find what ${if (plant) "eats" else "hunts"} the $one."
            AnimalWant.DISCOVERED -> when {
                discoverySaid != null -> brief(discoverySaid)
                named != null -> "$the${sci?.let { ", $it," } ?: ""} was first described in $named."
                else -> null
            }
            AnimalWant.EXTINCT -> when {
                extinct -> "Yes. " + (gone?.let { "$the has been extinct for $it. " } ?: "") + (brief(extinctSaid)?.takeIf { gone == null } ?: "")
                status != null -> "No. $the is listed as ${status.lowercase(Locale.US)}."
                else -> "No, the $one isn't extinct."
            }
            AnimalWant.HEIGHT -> brief(heightSaid) ?: "I couldn't find how ${if (plant) "tall" else "big"} the $one gets."
            AnimalWant.BLOOM -> brief(bloomSaid) ?: if (flowerless) "$the doesn't flower; it reproduces by spores or cones instead." else "I couldn't find when the $one blooms."
            AnimalWant.TOXIC -> {
                val wantsSafe = ask.diet?.let { Regex("^(?:edible|safe.*|eat|touch)$").matches(it) } == true
                val head = when (danger) {
                    null -> ""
                    true -> if (wantsSafe) "No. " else "Yes. "
                    false -> if (wantsSafe) "Yes. " else "No. "
                }
                head + (brief(toxicSaid) ?: "I couldn't find whether the $one is ${ask.diet?.takeIf { it != "eat" } ?: "poisonous"}.")
            }
            AnimalWant.BITE -> {
                val verb = ask.diet ?: "bite"
                val head = when {
                    biteSaid == null -> ""
                    Regex("\\b(?:rarely|seldom|rare|uncommon|very few|only (?:one|two|three|four|five|a few|a handful|a small number))\\b", RegexOption.IGNORE_CASE).containsMatchIn(biteSaid) -> "Rarely. "
                    IF_BOTHERED.containsMatchIn(biteSaid) -> "Only if bothered. "
                    HARMLESS.containsMatchIn(biteSaid) -> "No. "
                    Regex("\\b(?:bites?|stings?|bitten|stung|biting|stinging|venom\\w*)\\b", RegexOption.IGNORE_CASE).containsMatchIn(biteSaid) -> "Yes. "
                    else -> ""
                }
                // A little more room so "…a significant number of fatal attacks" keeps its noun.
                head + (biteSaid?.let { Sports.brief(it, 150) } ?: "I couldn't find whether the $one ${verb}s.")
            }
            AnimalWant.PESTS -> {
                val good = ask.diet == "good" && pestSaid != null
                val askedGood = Regex("\\b(?:good|beneficial|helpful|useful)\\b").containsMatchIn(ask.said)
                val beneficial = good && Regex("\\b(?:beneficial|pollinat\\w*|biological (?:pest )?control|natural enem\\w*|helpful|eat (?:aphids|pests)|prey on)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pestSaid!!)
                val head = when {
                    !good -> ""
                    beneficial -> if (askedGood) "Yes. " else "No. "
                    else -> if (askedGood) "Not really. " else "Yes. "
                }
                head + (brief(pestSaid) ?: "The field guide has no pest tips for ${ask.animal}. Try Web results.")
            }
            AnimalWant.LIFECYCLE -> brief(lifeCycleSaid) ?: "I couldn't find the $one's life cycle."
            // If trimming the sentence lost the number ("…estimated to reach maximum."), say the number instead.
            AnimalWant.SPEED -> brief(speedSaid)?.let { b -> val v = speedSaid?.let { speedIn(it) }; if (v != null && !b.contains(Regex("\\d"))) "$the can reach ${v.replaceFirst(Regex("^(?i)up to "), "")}." else b }
                ?: "I couldn't find how fast the $one is."
            AnimalWant.DEPTH -> brief(depthSaid)?.let { b -> val v = depthSaid?.let { depthIn(it) }; if (v != null && !b.contains(Regex("\\d"))) "$the goes down to ${v.replaceFirst(Regex("^(?i)(?:up to|down to|below) "), "")}." else b }
                ?: "I couldn't find how deep the $one goes."
            AnimalWant.KIND -> {
                val asked = ask.diet
                fun kindSaid(k: String) = "${an(k)} $k"
                when {
                    asked == "breathe" -> when {
                        cls == "fish" -> "$the breathes through gills, taking oxygen from the water."
                        cls == "mammal" && sea -> "$the breathes air with lungs, so it has to come up to the surface."
                        else -> brief(breatheSaid) ?: "I couldn't find how the $one breathes."
                    }
                    asked == "hold" -> brief(breatheSaid) ?: "I couldn't find how long the $one can stay under."
                    asked != null && asked.startsWith("have:") -> {
                        // "Do octopuses have bones" → "No. Octopuses are invertebrates…"
                        val part = asked.removePrefix("have:")
                        val stem = when (part) {
                            "bones", "skeleton", "backbone", "spine" -> "bones?|bony|skeletons?|skeletal|backbones?|spines?|vertebra\\w*|cartilag\\w*"
                            else -> Regex.escape(part.removeSuffix("s")) + "s?"
                        }
                        // Say-so about the part itself first; "invertebrate" alone is a last resort for bones.
                        val tiers = listOf(Regex("\\b(?:no|lacks?|lacking|without|absent|has|have|possess\\w*|made of)\\b[^.]*\\b(?:$stem)\\b|\\b(?:$stem)\\b[^.]*\\b(?:no|lacks?|absent)\\b", RegexOption.IGNORE_CASE),
                            Regex("\\b(?:$stem)\\b", RegexOption.IGNORE_CASE)) +
                            (if (stem.startsWith("bones")) listOf(Regex("\\b(?:are|is an?) invertebrates?\\b", RegexOption.IGNORE_CASE)) else emptyList())
                        val said = pick(secs, Regex("anatomy|description|physiology|morphology|skeleton|biology|characteristics|senses|features", RegexOption.IGNORE_CASE),
                            tiers, names, Regex("\\b(?:fossil\\w*|million years|than other)\\b", RegexOption.IGNORE_CASE))
                        val no = said != null && (Regex("\\b(?:no|lacks?|lacking|without|absent|boneless|invertebrates?|not have|do not|does not)\\b", RegexOption.IGNORE_CASE).containsMatchIn(said) ||
                            stem.startsWith("bones") && Regex("\\bcartilag\\w*", RegexOption.IGNORE_CASE).containsMatchIn(said))
                        said?.let { (if (no) "No. " else "Yes. ") + brief(it) } ?: "I couldn't find whether the $one has $part."
                    }
                    asked != null && Regex("^(?:legs|eyes|wings|hearts|teeth|body parts|body segments|arms|tentacles|fins|gills|brains)$").matches(asked) -> {
                        val count = if (asked == "legs") legs else Regex("\\b(?:\\d{1,3}|two|three|four|five|six|seven|eight|nine|ten|twelve|a pair of|a single|one) (?:pairs of )?(?:compound |simple |long |short )?$asked\\b", RegexOption.IGNORE_CASE)
                            .find(intro + " " + (text?.take(15_000) ?: ""))?.value?.lowercase(Locale.US)
                        count?.let { "$the has $it." } ?: "I couldn't find how many $asked the $one has."
                    }
                    cls == null -> brief(firstSentence(intro))
                    asked == null -> "$the is ${kindSaid(cls)}."
                    else -> {
                        val want = when (asked) { "bug" -> "insect"; "spider" -> "arachnid"; "mollusk" -> "mollusc"; else -> asked }
                        when {
                            want == "animal" -> "Yes. $the is ${kindSaid(cls)}."
                            want == cls -> "Yes. $the is ${kindSaid(cls)}."
                            asked == "bug" && cls in setOf("arachnid", "myriapod", "crustacean") -> "Sort of. People call it a bug, but ${the.replaceFirstChar { it.lowercase(Locale.US) }} is ${kindSaid(cls)}, not an insect."
                            else -> "No. $the is ${kindSaid(cls)}, not ${kindSaid(want)}."
                        }
                    }
                }
            }
        }?.trim()?.ifBlank { null }

        val rows = buildList {
            sci?.let { add("SPECIES" to it) }
            if (bug || sea || ask.want == AnimalWant.KIND) cls?.let { add("CLASS" to it.replaceFirstChar { c -> c.uppercase(Locale.US) }) }
            water?.let { add("WATER" to it.replaceFirstChar { c -> c.uppercase(Locale.US) }) }
            if (sea || ask.want == AnimalWant.SPEED) speedSaid?.let { speedIn(it) }?.let { add("SPEED" to it) }
            if (sea || ask.want == AnimalWant.DEPTH) depthSaid?.let { depthIn(it) }?.let { add("DEPTH" to it) }
            if (bug) legs?.let { add("LEGS" to it.replaceFirstChar { c -> c.uppercase(Locale.US) }) }
            status?.let { add("STATUS" to it) }
            if (extinct) gone?.let { add("EXTINCT" to "$it ago") }
            (life ?: lifeData)?.let { add("LIFESPAN" to it) }
            (maxLife ?: if (record) lifeSaid?.let { lifespanIn(it) } else null)?.takeIf { it != life }?.let { add("OLDEST" to it) }
            height?.let { add((if (plant) "HEIGHT" else "SIZE") to it) }
            blooms?.let { add("BLOOMS" to it) }
            // Only when asked: a passing mention ("some species are toxic") isn't a verdict on the whole plant.
            if (ask.want == AnimalWant.TOXIC) danger?.let { add("TOXIC" to if (it) "Yes" else "No") }
            listOfNotNull(kind, foods.takeIf { it.isNotEmpty() }?.let { Lookup.listSaid(it, 3) }).joinToString(": ").ifBlank { null }?.let { add("DIET" to it) }
            habitats.takeIf { it.isNotEmpty() }?.let { add("HABITAT" to (Lookup.listSaid(it, 3) ?: "")) }
            named?.let { add("NAMED" to it) }
        }
        val notes = if (plant) listOfNotNull(
            lifeSaid?.let { "Lifespan: $it" }, heightSaid?.let { "Height: $it" }, bloomSaid?.let { "Blooms: $it" }, habitatSaid?.let { "Grows: $it" },
            careSaid?.takeIf { it != habitatSaid }?.let { "Needs: $it" }, dietSaid?.let { "Diet: $it" }, predatorSaid?.let { "Eaten by: $it" },
            toxicSaid?.let { "Toxicity: $it" }, discoverySaid?.let { "Discovered: $it" }, extinctSaid?.let { "Extinction: $it" },
        ) else if (sea) listOfNotNull(
            lifeSaid?.let { "Lifespan: $it" }, dietSaid?.let { "Diet: $it" }, preySaid?.takeIf { it != dietSaid }?.let { "Hunts: $it" },
            predatorSaid?.let { "Hunted by: $it" }, habitatSaid?.let { "Habitat: $it" }, depthSaid?.takeIf { it != habitatSaid }?.let { "Depth: $it" },
            speedSaid?.let { "Speed: $it" }, breatheSaid?.let { "Breathing: $it" }, biteSaid?.let { "${if (stingAsked) "Sting" else "Danger"}: $it" },
            toxicSaid?.let { "Eating: $it" }, discoverySaid?.let { "Discovered: $it" }, extinctSaid?.let { "Extinction: $it" },
        ) else if (bug) listOfNotNull(
            lifeSaid?.let { "Lifespan: $it" }, lifeCycleSaid?.let { "Life cycle: $it" }, dietSaid?.let { "Diet: $it" }, preySaid?.takeIf { it != dietSaid }?.let { "Hunts: $it" },
            predatorSaid?.let { "Hunted by: $it" }, habitatSaid?.let { "Habitat: $it" }, biteSaid?.let { "${if (stingAsked) "Sting" else "Bite"}: $it" },
            pestSaid?.takeIf { it != biteSaid }?.let { "${if (ask.diet == "good") "Good or bad" else "Pest"}: $it" }, discoverySaid?.let { "Discovered: $it" }, extinctSaid?.let { "Extinction: $it" },
        ) else listOfNotNull(
            lifeSaid?.let { "Lifespan: $it" }, dietSaid?.let { "Diet: $it" }, preySaid?.takeIf { it != dietSaid }?.let { "Hunts: $it" },
            predatorSaid?.let { "Hunted by: $it" }, habitatSaid?.let { "Habitat: $it" }, heightSaid?.takeIf { ask.want == AnimalWant.HEIGHT }?.let { "Size: $it" },
            toxicSaid?.let { "Toxicity: $it" }, discoverySaid?.let { "Discovered: $it" }, extinctSaid?.let { "Extinction: $it" },
        )
        LookupResult(p.title, p.description, null, firstSentence(intro) ?: p.extract, p.image, p.url, rows, answer,
            listOf("Wikipedia") + if (claims != null) listOf("Wikidata") else emptyList(),
            list = notes, listTitle = "FIELD NOTES", heading = when { plant -> "PLANT"; bug -> "BUG"; sea -> "SEA LIFE"; else -> "ANIMAL" })
    }
}
