package com.brick.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** What's being asked about a gadget: the whole spec sheet, or one part of it. */
enum class TechWant { OVERVIEW, SPECS, BATTERY, CHARGING, DISPLAY, REFRESH, CHIP, MEMORY, CAMERA, UNLOCK, SPEAKERS, PORT, DESIGN, TERM }

/**
 * A tech question: one device ("what is the s26 ultra", "pixel 9a battery life"), two devices compared
 * ("difference between the iphone 11 and the pixel 9a"), or a tech word ("what is a gpu", "cpu vs gpu").
 * [devices] holds the device names (or the [TechWant.TERM] keys) as said.
 */
data class TechAsk(val devices: List<String>, val want: TechWant, val said: String) {
    val compare get() = devices.size > 1
}

object Tech {
    private val us = Locale.US
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ tech words

    /** A tech word: its article, the page whose picture shows the real thing, what it does and why it matters. */
    class Term(val title: String, val imagePage: String, val does: String, val matters: String,
        /** Side-by-side numbers for comparing kinds ("ddr4 vs ddr5"); [name] when the article's title isn't what people call it. */
        val specs: List<TermSpec> = emptyList(), val name: String? = null)

    /** One spec of a kind of part: [value] and [tag] make it a bar ("faster standard speeds"); [lower] when smaller wins. */
    class TermSpec(val label: String, val text: String, val value: Double? = null, val tag: String? = null, val lower: Boolean = false)

    private fun s(label: String, text: String, value: Double? = null, tag: String? = null, lower: Boolean = false) = TermSpec(label, text, value, tag, lower)

    /** Buying-guide sections for the main parts, by article title. */
    private val GUIDES: Map<String, List<Pair<String, List<String>>>> = mapOf(
        "Graphics processing unit" to listOf(
            "KEY SPECS" to listOf("Shader cores (CUDA cores / stream processors): raw drawing power.", "VRAM: 8 GB is tight now; 12-16 GB for 1440p; 16 GB+ for 4K and AI.",
                "Memory bandwidth and bus width: feeds the cores at high resolutions.", "Power (TBP): sets the PSU you need; 5090-class cards draw 575 W.",
                "Features: ray tracing cores, DLSS / FSR / XeSS upscaling and frame generation."),
            "TYPES" to listOf("Dedicated: a separate card with its own VRAM (Nvidia GeForce, AMD Radeon, Intel Arc).", "Integrated: built into the CPU, shares system RAM; fine for video and light games."),
            "HOW TO CHOOSE" to listOf("Match it to your monitor: 1080p, 1440p or 4K and its refresh rate.", "Check the PSU wattage and connector, and that the card fits the case."),
        ),
        "Central processing unit" to listOf(
            "KEY SPECS" to listOf("Cores and threads: multitasking, rendering and streaming.", "Boost clock (GHz): single-core speed, which drives game frame rates.",
                "Cache: AMD's X3D chips add a big L3 cache that lifts gaming.", "TDP: heat and power; sets the cooler you need.", "Socket: must match the motherboard (AM5, LGA 1700, LGA 1851)."),
            "TYPES" to listOf("Desktop: AMD Ryzen, Intel Core / Core Ultra.", "Laptop and phone chips: power-efficient versions, often a system on a chip.", "Workstation and server: Threadripper, Xeon, EPYC with many more cores."),
            "HOW TO CHOOSE" to listOf("Gaming: 6-8 fast cores, ideally an X3D chip.", "Editing and 3D: more cores.", "Pick the platform: AM5 has upgrades ahead; check the board and RAM type."),
        ),
        "Random-access memory" to listOf(
            "KEY SPECS" to listOf("Capacity: 16 GB minimum, 32 GB for gaming and creative work, 64 GB+ for heavy workloads.", "Speed (MT/s) and CAS latency: true latency = CL x 2000 / speed.",
                "Generation: DDR4 or DDR5; the board decides, and they don't mix.", "Profile: turn on XMP (Intel) or EXPO (AMD) in the BIOS to get the rated speed."),
            "TYPES" to listOf("Desktop sticks: DIMM.", "Laptops: SO-DIMM, or soldered LPDDR.", "Servers: ECC registered memory."),
            "HOW TO CHOOSE" to listOf("Buy a matched kit of 2 sticks for dual channel.", "Sweet spots: DDR5-6000 CL30 for AM5, DDR5-6400+ for Intel; DDR4-3200/3600 CL16 for older platforms."),
        ),
        "Solid-state drive" to listOf(
            "KEY SPECS" to listOf("Interface: SATA (~550 MB/s) or NVMe over PCIe 3.0 / 4.0 / 5.0 (~3,500 / 7,000 / 14,000 MB/s).", "DRAM cache: steadier speed for big transfers.",
                "NAND: TLC lasts longer and stays fast; QLC is cheaper per TB.", "Endurance (TBW): how much you can write over its life."),
            "TYPES" to listOf("M.2 NVMe: the stick that screws into the board.", "2.5 in SATA: the older laptop-drive shape.", "External: USB or Thunderbolt."),
            "HOW TO CHOOSE" to listOf("Boot drive and games: a 1-2 TB PCIe 4.0 NVMe with DRAM is the sweet spot.", "PCIe 5.0 drives need a 5.0 slot and good cooling."),
        ),
        "NVM Express" to listOf(
            "KEY SPECS" to listOf("PCIe generation and lanes: 4.0 x4 tops out near 7,900 MB/s; 5.0 x4 near 15,800 MB/s.", "Random reads (IOPS): what makes apps feel quick.", "DRAM cache and NAND type."),
            "HOW TO CHOOSE" to listOf("Check the board's M.2 slots: length (2280), PCIe generation, and whether a slot disables SATA ports."),
        ),
        "Motherboard" to listOf(
            "KEY SPECS" to listOf("Socket: matches the CPU (AM5, AM4, LGA 1700, LGA 1851).", "Chipset: overclocking, PCIe speeds, number of USB and M.2 ports.",
                "Memory: DDR4 or DDR5, and how many slots.", "VRM: power delivery for high-end CPUs.", "Ports: USB-C, USB4/Thunderbolt, Wi-Fi 7, 2.5G or 5G Ethernet."),
            "TYPES" to listOf("ATX: full size, most slots.", "Micro-ATX: shorter, up to 4 slots.", "Mini-ITX: tiny, 1 slot, 2 RAM slots."),
            "HOW TO CHOOSE" to listOf("Pick the CPU first, then a chipset with the features you'll use.", "Count your M.2 drives and USB devices, and check the case size."),
        ),
        "Power supply unit (computer)" to listOf(
            "KEY SPECS" to listOf("Wattage: CPU + GPU power plus 30-40% headroom.", "80 Plus rating: Bronze, Gold, Platinum, Titanium efficiency.",
                "Modular cables: only the cables you need.", "ATX 3.x with a 12V-2x6 plug for new graphics cards."),
            "TYPES" to listOf("ATX: standard desktop size.", "SFX / SFX-L: small form factor cases."),
            "HOW TO CHOOSE" to listOf("650 W for a mid-range build, 850 W for an RTX 5080-class card, 1000 W+ for an RTX 5090.", "Buy from a well-reviewed series; a cheap PSU can damage parts."),
        ),
    )

    private val TERMS: List<Pair<Regex, Term>> = listOf(
        "gpus?|graphics (?:processing )?units?|graphics cards?|video cards?" to Term("Graphics processing unit", "Graphics card",
            "A GPU runs thousands of small calculations at the same time. It draws every frame you see: games, video, the phone's interface, and it speeds up photo and video editing.",
            "Because it works in parallel, the GPU also trains and runs AI models, which is why GPUs power modern games, AI chatbots, self-driving research and data centers."),
        "cpus?|central processing units?|processors?" to Term("Central processing unit", "Central processing unit",
            "The CPU is the general-purpose brain: it runs the operating system and apps, one instruction stream per core, deciding what every other part does.",
            "Every computer, phone, car and smart device has one; its speed and number of cores decide how fast apps open and how well a device multitasks."),
        "ram|memory|random access memory" to Term("Random-access memory", "Random-access memory",
            "RAM holds the apps and data in use right now so the processor can reach them in nanoseconds. It's wiped when the power goes off.",
            "More RAM means more apps and browser tabs stay open without reloading, and it matters more and more for on-device AI."),
        "vram|video memory|video ram" to Term("Video random-access memory", "Graphics card",
            "VRAM is the GPU's own fast memory: it holds textures, frames and models the graphics chip is working on.",
            "Running out of VRAM makes games stutter at high settings and limits how big an AI model a graphics card can run."),
        "ssds?|solid state drives?" to Term("Solid-state drive", "Solid-state drive",
            "An SSD stores files on flash memory chips with no moving parts, so it reads and writes far faster than a spinning hard drive.",
            "SSDs are why modern computers and consoles boot in seconds and games load quickly; they're also tougher and quieter.",
            listOf(s("TOP SPEED", "~550 (SATA) to 14,900 MB/s (NVMe)", 7000.0, "faster transfers"), s("ACCESS", "~0.02-0.1 ms", 0.05, "quicker access", true),
                s("COST/TB", "~\$50-90", 65.0, "cheaper per terabyte", true), s("MAX SIZE", "8 TB (consumer)", 8.0, "bigger drives"),
                s("PARTS", "No moving parts"), s("NOISE", "Silent"), s("DROPS", "Shock resistant")), "SSD"),
        "hdds?|hard drives?|hard disk drives?|hard disks?" to Term("Hard disk drive", "Hard disk drive",
            "A hard drive stores data magnetically on spinning platters read by a moving arm.",
            "They're slower than SSDs but still the cheapest way to store huge amounts of data, so backups and data centers use them.",
            listOf(s("TOP SPEED", "~150-280 MB/s", 250.0, "faster transfers"), s("ACCESS", "~5-10 ms", 8.0, "quicker access", true),
                s("COST/TB", "~\$15-20", 18.0, "cheaper per terabyte", true), s("MAX SIZE", "30+ TB", 30.0, "bigger drives"),
                s("PARTS", "Spinning platters and a moving arm"), s("NOISE", "Audible spin and seek"), s("DROPS", "Fragile while running")), "HDD"),
        "motherboards?|mainboards?|logic boards?" to Term("Motherboard", "Motherboard",
            "The motherboard is the main circuit board: it connects the processor, memory, storage, graphics and ports and carries power and data between them.",
            "It decides what parts a computer can use and how fast they can talk to each other."),
        "socs?|systems? on (?:a )?chips?|chipsets?" to Term("System on a chip", "System on a chip",
            "A system on a chip puts the CPU, GPU, AI engine, modem, image processor and memory controller on one piece of silicon.",
            "Every phone and most tablets and laptops run on one; it's what makes small devices fast and power efficient."),
        "npus?|neural processing units?|neural engines?|ai chips?|ai accelerators?" to Term("Neural processing unit", "Neural processing unit",
            "An NPU is a chip block built for the math behind AI: it runs voice recognition, photo processing and language models quickly on very little power.",
            "It lets phones and laptops run AI features on the device, which is faster and more private than sending everything to the cloud."),
        "psus?|power supply units?|power suppl(?:y|ies)" to Term("Power supply unit (computer)", "Power supply unit (computer)",
            "A PSU turns wall power into the steady low voltages a computer's parts need.",
            "A good one keeps parts safe and stable; a weak one can crash or damage a powerful PC."),
        "oled|oled screens?|oled displays?" to Term("OLED", "OLED",
            "In an OLED screen every pixel makes its own light, so black pixels switch off completely.",
            "That gives perfect blacks, high contrast, thin and bendable screens, and saves battery on dark content."),
        "amoled|super amoled" to Term("AMOLED", "AMOLED",
            "AMOLED is an OLED screen driven by a thin-film transistor layer that switches each pixel quickly.",
            "It's the screen type on most flagship phones: deep blacks, bright colors and fast refresh rates."),
        "lcd|lcd screens?|liquid crystal displays?|ips|ips lcd" to Term("Liquid-crystal display", "Liquid-crystal display",
            "An LCD shines a backlight through liquid crystals that twist to let light through or block it for each pixel.",
            "LCDs are cheap and long-lasting, which is why budget phones, monitors and TVs use them, though blacks look gray next to OLED."),
        "refresh rates?|hz|hertz|high refresh rate|120 ?hz|90 ?hz" to Term("Refresh rate", "Refresh rate",
            "The refresh rate is how many times a second the screen redraws: 60 Hz is 60 times, 120 Hz is twice that.",
            "Higher rates make scrolling, games and animations look smoother; adaptive (LTPO) screens drop the rate when nothing moves to save battery."),
        "usb c|usb type c|type c" to Term("USB-C", "USB-C",
            "USB-C is the small reversible plug that carries power, data and video over one cable.",
            "It's now the standard port for phones, laptops and accessories, so one charger works for nearly everything."),
        "thunderbolt|thunderbolt [345]" to Term("Thunderbolt (interface)", "Thunderbolt (interface)",
            "Thunderbolt runs over a USB-C plug but carries up to 40-120 Gbps of data, displays and PCIe devices.",
            "It lets one cable drive monitors, docks and fast external drives from a laptop."),
        "lightning|lightning port|lightning cable" to Term("Lightning (connector)", "Lightning (connector)",
            "Lightning is Apple's small reversible connector used on iPhones from the iPhone 5 to the iPhone 14, at USB 2.0 speed (480 Mbps).",
            "Apple replaced it with USB-C on the iPhone 15 and later, partly because of EU rules for a common charger."),
        "5g" to Term("5G", "5G",
            "5G is the fifth generation of mobile networks: faster data, lower delay and room for many more devices than 4G.",
            "It makes streaming, video calls and cloud gaming work better on the go and connects cars, factories and smart cities."),
        "nfc|near field communication" to Term("Near-field communication", "Near-field communication",
            "NFC is a very short-range radio link, a few centimeters, between a phone and a reader or tag.",
            "It's what makes tap-to-pay, transit cards, digital car keys and quick pairing work."),
        "wi ?fi|wifi|wi ?fi [67]|wi ?fi 6e" to Term("Wi-Fi", "Wi-Fi",
            "Wi-Fi is wireless networking that links devices to a router and the internet using radio waves.",
            "Newer versions (Wi-Fi 6, 6E, 7) are faster and handle crowded homes with many devices better."),
        "bluetooth" to Term("Bluetooth", "Bluetooth",
            "Bluetooth is a short-range wireless link for headphones, watches, keyboards, cars and other accessories.",
            "It's the reason wireless earbuds and smartwatches can pair with a phone in seconds."),
        // Headphones and VR (the gear itself is Headgear's).
        "anc|active noise cancell?(?:ing|ation)|noise cancell?(?:ing|ation)|noise cancell?ing headphones" to Term("Active noise control", "Active noise control",
            "Noise cancelling uses microphones to hear outside sound and plays the opposite sound wave to cancel it, mostly low hums like engines and air conditioning.",
            "It makes flights, trains and offices quieter, so you can listen at a lower, safer volume.", name = "Active noise cancelling (ANC)"),
        "open[- ]?back(?: headphones)?|closed[- ]?back(?: headphones)?|open[- ]?back (?:vs|or|versus) closed[- ]?back(?: headphones)?" to Term("Headphones", "Headphones",
            "Open-back headphones have grilles on the cups that let air and sound pass through; closed-back headphones seal the cups.",
            "Open-back sounds more spacious and natural but leaks sound and blocks nothing; closed-back isolates you, for commutes, offices and recording.",
            name = "Open-back vs closed-back headphones"),
        "planar(?: magnetic)?(?: drivers?| headphones)?" to Term("Headphones", "Headphones",
            "A planar magnetic driver moves a thin, flat film with magnets on both sides, instead of a cone pushed from the middle.",
            "The whole film moves evenly, giving low distortion and detailed sound; the drivers are bigger and heavier, so the headphones are too.",
            name = "Planar magnetic drivers"),
        "ldac" to Term("LDAC (codec)", "LDAC (codec)",
            "LDAC is Sony's Bluetooth codec that sends up to 990 kbps, about three times what standard Bluetooth audio uses.",
            "It gets wireless headphones closer to CD quality; Android phones support it, iPhones don't."),
        "aptx(?: adaptive| hd| lossless)?" to Term("aptX", "aptX",
            "aptX is Qualcomm's family of Bluetooth audio codecs; aptX Adaptive changes its bitrate to the connection, and aptX Lossless can carry CD-quality audio.",
            "Android phones with Snapdragon chips support it for better wireless sound and lower delay; iPhones use AAC instead."),
        "iems?|in[- ]?ear monitors?" to Term("In-ear monitor", "In-ear monitor",
            "In-ear monitors are earphones that sit deep in the ear canal and seal it, first made for musicians to hear themselves on stage.",
            "They block outside sound well and sound detailed; many have detachable cables and several drivers per ear."),
        "spatial audio|3d audio|virtual surround(?: sound)?" to Term("3D audio effect", "3D audio effect",
            "Spatial audio processes sound so it seems to come from all around you, in front, behind and above, through ordinary headphones.",
            "With head tracking, the sound stays put when you turn your head, like real speakers in a room; it helps movies and games feel immersive."),
        "pc ?vr|pc vr headsets?|tethered vr" to Term("Virtual reality headset", "Virtual reality headset",
            "PC VR is virtual reality powered by a gaming PC: the headset is a screen and sensors, and the PC renders the game, over a cable or streamed over Wi-Fi.",
            "A PC can run bigger, better-looking VR games than a standalone headset's phone-class chip, through SteamVR.", name = "PC VR"),
        "standalone vr|standalone (?:vr )?headsets?|all[- ]in[- ]one vr" to Term("Virtual reality headset", "Virtual reality headset",
            "A standalone VR headset has its own chip, storage and battery, so it runs games and apps on its own with no PC or console.",
            "No wires and nothing else to buy makes it the easiest way into VR; many, like the Quest 3, can also connect to a PC for PC VR.", name = "Standalone VR"),
        "6 ?dof|six degrees of freedom|3 ?dof" to Term("Six degrees of freedom", "Six degrees of freedom",
            "6DoF tracking follows your head and hands as they move (up, down, left, right, forward, back) and turn; 3DoF only follows turning.",
            "With 6DoF you can walk, lean and duck in VR; every modern VR headset has it."),
        "inside[- ]?out tracking|outside[- ]?in tracking|positional tracking" to Term("Positional tracking", "Positional tracking",
            "Inside-out tracking uses cameras on the headset to work out where it is; outside-in uses base stations or sensors placed around the room.",
            "Inside-out needs no setup; base stations (like SteamVR Lighthouse) track more precisely, even behind your back."),
        "passthrough|video passthrough|color passthrough|mixed reality" to Term("Mixed reality", "Mixed reality",
            "Passthrough shows the real room on a VR headset's screens using its outside cameras; mixed reality places virtual things into that view.",
            "You can see your room, desk and people without taking the headset off, and play games built around your space."),
        "foveated rendering|eye[- ]tracked foveated rendering" to Term("Foveated rendering", "Foveated rendering",
            "Foveated rendering draws only the spot you're looking at in full detail and the rest of the view in less, using eye tracking.",
            "It saves a lot of processing power, so headsets like the PS VR2 and Vision Pro can show sharper images."),
        "lidar|lidar scanner|lidar sensor" to Term("Lidar", "Lidar",
            "Lidar measures distance by firing laser pulses and timing how long they take to bounce back.",
            "Phones use it for faster focus in the dark and AR; cars and robots use it to see in 3D."),
        "face id" to Term("Face ID", "Face ID",
            "Face ID is Apple's 3D face unlock: the TrueDepth camera's dot projector casts over 30,000 infrared dots, a flood illuminator lights your face in the dark, and an infrared camera reads the dot pattern into a depth map.",
            "The Secure Enclave compares that map with the one saved at setup, so a flat photo can't fool it; Apple puts the chance of a stranger unlocking it at about 1 in 1,000,000."),
        "touch id" to Term("Touch ID", "Touch ID",
            "Touch ID is Apple's fingerprint reader, built into the home button, top button or keyboard.",
            "It unlocks the device and approves payments without typing a password."),
        "ultrasonic fingerprint(?: sensors?| readers?| scanners?)?" to Term("Fingerprint scanner", "Fingerprint scanner",
            "An ultrasonic fingerprint reader sends sound pulses through the screen glass and maps the ridges of your finger in 3D.",
            "It works with wet or dirty fingers and is harder to fool than an optical reader."),
        "fingerprint (?:sensors?|readers?|scanners?)|in display fingerprint|under display fingerprint" to Term("Fingerprint scanner", "Fingerprint scanner",
            "A fingerprint reader captures your fingerprint with light (optical), electric charge (capacitive) or sound (ultrasonic) and matches it to the one you saved.",
            "It's a quick and secure way to unlock a device and approve payments."),
        "ray tracing" to Term("Ray tracing (graphics)", "Ray tracing (graphics)",
            "Ray tracing draws light by following the paths of light rays as they bounce around a scene.",
            "It gives games realistic reflections, shadows and lighting; modern GPUs have special cores to do it in real time."),
        "dlss|deep learning super sampling" to Term("Deep Learning Super Sampling", "Graphics card",
            "DLSS is Nvidia's AI upscaling: the game renders at a lower resolution and an AI model fills in the detail.",
            "It gives much higher frame rates with little loss in image quality."),
        "hdmi" to Term("HDMI", "HDMI",
            "HDMI is the cable standard for sending video and sound from consoles, computers and streamers to TVs and monitors.",
            "HDMI 2.1 carries 4K at 120 Hz and 8K, which modern consoles and TVs need."),
        "displayport" to Term("DisplayPort", "DisplayPort",
            "DisplayPort is a video connection mostly used for computer monitors.",
            "It carries high resolutions and refresh rates for gaming monitors and can daisy-chain screens."),
        "nvme|nvme ssds?|nvme drives?|m ?2|m 2 ssds?" to Term("NVM Express", "Solid-state drive",
            "NVMe is the way fast SSDs talk to the computer, straight over PCIe lanes instead of an old hard-drive interface.",
            "NVMe drives are several times faster than SATA SSDs, which speeds up loading and big file copies.",
            listOf(s("TOP SPEED", "~3,500 to 14,900 MB/s", 7000.0, "faster transfers"), s("ACCESS", "~0.02-0.05 ms", 0.03, "quicker access", true), s("SLOT", "M.2 slot (PCIe)"),
                s("QUEUES", "64K queues, 64K commands each"), s("COST/TB", "~\$50-90", 65.0, "cheaper per terabyte", true)), "NVMe SSD"),
        "esim|esims|e sim" to Term("ESIM", "ESIM",
            "An eSIM is a SIM card built into the phone that you set up by downloading a profile instead of inserting a card.",
            "You can switch carriers or add a travel plan without a store visit, and phones can hold several."),
        "wireless charging|qi|qi2|qi charging|magsafe" to Term("Qi (standard)", "Qi (standard)",
            "Wireless charging passes power through a magnetic field between a coil in the pad and a coil in the phone.",
            "Qi is the common standard, so one pad charges most phones and earbuds; Qi2 and MagSafe add magnets to line them up."),
        "fast charging|quick charge|usb power delivery|usb pd" to Term("USB Power Delivery", "USB-C",
            "Fast charging raises the voltage or current the charger sends while the phone manages heat, usually over USB Power Delivery.",
            "It can fill half a battery in 20-30 minutes, though top speed drops as the battery fills."),
        "mah|milliamp hours?|milliampere hours?" to Term("Ampere hour", "Lithium-ion battery",
            "mAh (milliamp-hours) is battery capacity: how much charge it holds.",
            "More mAh usually means longer battery life, but screen, chip efficiency and software matter as much."),
        "lithium ion batter(?:y|ies)|li ion batter(?:y|ies)" to Term("Lithium-ion battery", "Lithium-ion battery",
            "A lithium-ion battery stores energy by moving lithium ions between two electrodes as it charges and drains.",
            "It's light and dense enough to power phones, laptops and electric cars, which is why nearly all of them use it."),
        "megapixels?|mp" to Term("Pixel", "Image sensor",
            "A megapixel is a million pixels; it's how much detail a camera sensor captures.",
            "More megapixels allow bigger prints and crops, but sensor size and processing matter more for photo quality."),
        "image sensors?|camera sensors?|cmos sensors?" to Term("Image sensor", "Image sensor",
            "An image sensor turns light into an electrical signal, pixel by pixel, to make a photo.",
            "A bigger sensor gathers more light, which means less noise and better photos at night."),
        "ois|optical image stabili[sz]ation|image stabili[sz]ation" to Term("Image stabilization", "Image stabilization",
            "Image stabilization moves the lens or sensor to cancel out hand shake.",
            "It keeps night photos sharp and videos steady."),
        "telephoto(?: lens| camera)?|periscope(?: lens| camera)?" to Term("Telephoto lens", "Telephoto lens",
            "A telephoto camera zooms optically, with lenses, instead of cropping the picture.",
            "Periscope designs fold the light sideways so phones get 5x or more zoom without a thick lens."),
        "clock speed|ghz|gigahertz|clock rate" to Term("Clock rate", "Central processing unit",
            "Clock speed is how many cycles a processor runs each second; 3 GHz is three billion.",
            "Higher clocks help, but cores, architecture and cache decide real speed as much."),
        "cores?|cpu cores?|multi core|multicore" to Term("Multi-core processor", "Central processing unit",
            "A core is one processing unit inside a CPU; a multi-core chip runs several tasks at once.",
            "More cores help with multitasking, video editing and games that use them."),
        "nanometers?|nm|nm process|\\d nm|process node|transistors?" to Term("Semiconductor device fabrication", "System on a chip",
            "The process node (like 3 nm) describes how finely a chip's transistors are made; smaller nodes fit more transistors.",
            "Newer nodes make chips faster and more power efficient, which means better battery life."),
        "routers?|wi ?fi routers?" to Term("Router (computing)", "Router (computing)",
            "A router directs traffic between your devices and the internet and runs your home Wi-Fi.",
            "Its range and Wi-Fi version decide how fast and stable every device in the house is."),
        "modems?" to Term("Modem", "Modem",
            "A modem converts the signal from your internet provider (cable, fiber, DSL, cellular) into data your router or device can use.",
            "Without it there's no connection; phones have a cellular modem built into their chip."),
        "hdr|high dynamic range" to Term("High-dynamic-range television", "High-dynamic-range television",
            "HDR shows brighter highlights, deeper shadows and more colors than standard video.",
            "It makes movies and games look more lifelike on screens bright enough to show it."),
        "ip68|ip67|ip rating|ip ratings|water resistance|waterproof rating" to Term("IP code", "IP code",
            "An IP rating says how well a device keeps out dust (first digit) and water (second digit); IP68 is dust-tight and survives submersion.",
            "It tells you whether a phone survives rain, spills or a drop in the pool."),
        "heat ?sinks?|cooling|thermal paste|vapor chamber" to Term("Heat sink", "Heat sink",
            "A heat sink or vapor chamber pulls heat away from a chip and spreads it out so it can escape.",
            "Without it, chips slow down to protect themselves (thermal throttling)."),
        "tpus?|tensor processing units?" to Term("Tensor Processing Unit", "Tensor Processing Unit",
            "A TPU is Google's chip built only for the math of neural networks.",
            "Google uses them to train and run its AI models in its data centers."),
        "chromebooks?" to Term("Chromebook", "Chromebook",
            "A Chromebook is a laptop running Google's ChromeOS, built around the Chrome browser and web apps.",
            "They're cheap, simple, start fast and get updates automatically, which is why schools use them."),
        // Kinds of parts, with numbers to put side by side.
        "ddr5|ddr5 ram|ddr5 memory" to Term("DDR5 SDRAM", "DIMM",
            "DDR5 is the current memory standard for desktops and laptops, with higher speeds and two independent channels on every stick.",
            "It feeds today's many-core CPUs and integrated graphics; AM5 and Intel's newest platforms require it.",
            listOf(s("SPEED", "4800-6400 MT/s standard, 8000+ tuned", 6400.0, "faster standard speeds"), s("VOLTAGE", "1.1 V", 1.1, "lower voltage", true),
                s("PER STICK", "up to 64 GB (consumer)", 64.0, "bigger sticks"), s("CHANNELS", "2 x 32-bit per stick"), s("ON-DIE ECC", "Yes"), s("POWER", "On the stick (PMIC)"),
                s("RELEASED", "2020"), s("FITS", "AM5, Intel 12th gen+")), "DDR5"),
        "ddr4|ddr4 ram|ddr4 memory" to Term("DDR4 SDRAM", "DIMM",
            "DDR4 was the memory standard from 2014 to about 2022: one 64-bit channel per stick.",
            "It's still cheap and fast enough for AM4 and many Intel builds.",
            listOf(s("SPEED", "2133-3200 MT/s standard, ~4000 tuned", 3200.0, "faster standard speeds"), s("VOLTAGE", "1.2 V", 1.2, "lower voltage", true),
                s("PER STICK", "up to 32 GB (consumer)", 32.0, "bigger sticks"), s("CHANNELS", "1 x 64-bit per stick"), s("ON-DIE ECC", "No"), s("POWER", "From the motherboard"),
                s("RELEASED", "2014"), s("FITS", "AM4, Intel 6th-14th gen")), "DDR4"),
        "ddr3|ddr3 ram|ddr3 memory" to Term("DDR3 SDRAM", "DIMM",
            "DDR3 is the older memory standard from 2007, in PCs until about 2015.",
            "Only old platforms use it; it can't go in DDR4 or DDR5 slots.",
            listOf(s("SPEED", "800-2133 MT/s", 1600.0, "faster standard speeds"), s("VOLTAGE", "1.5 V (1.35 V low-voltage)", 1.5, "lower voltage", true),
                s("PER STICK", "up to 8 GB (consumer)", 8.0, "bigger sticks"), s("RELEASED", "2007"), s("FITS", "LGA 1155/1150, AM3")), "DDR3"),
        "pcie|pci express|pci e" to Term("PCI Express", "PCI Express",
            "PCIe is the high-speed link between the CPU and graphics cards, SSDs and other expansion cards, built from lanes (x1, x4, x16).",
            "Each new generation doubles the speed per lane, so faster SSDs and GPUs need a matching slot."),
        "pcie ?3(?:\\.0| 0)?|pci express 3(?:\\.0| 0)?|pcie gen ?3|gen ?3" to Term("PCI Express", "PCI Express",
            "PCIe 3.0 (2010) runs 8 GT/s per lane, about 1 GB/s.", "Fine for SATA-class and older NVMe drives; current GPUs lose little in a 3.0 x16 slot.",
            listOf(s("PER LANE", "~1 GB/s", 0.985, "more bandwidth"), s("SSD (X4)", "~3.9 GB/s", 3.9, "faster SSD slots"), s("GPU (X16)", "~15.8 GB/s", 15.8, "a faster graphics slot"),
                s("RATE", "8 GT/s"), s("RELEASED", "2010")), "PCIe 3.0"),
        "pcie ?4(?:\\.0| 0)?|pci express 4(?:\\.0| 0)?|pcie gen ?4|gen ?4" to Term("PCI Express", "PCI Express",
            "PCIe 4.0 (2017, in PCs from 2019) doubles 3.0 to 16 GT/s per lane, about 2 GB/s.", "It's the standard for today's fast NVMe drives (~7,000 MB/s) and graphics cards.",
            listOf(s("PER LANE", "~2 GB/s", 1.97, "more bandwidth"), s("SSD (X4)", "~7.9 GB/s", 7.9, "faster SSD slots"), s("GPU (X16)", "~31.5 GB/s", 31.5, "a faster graphics slot"),
                s("RATE", "16 GT/s"), s("RELEASED", "2017")), "PCIe 4.0"),
        "pcie ?5(?:\\.0| 0)?|pci express 5(?:\\.0| 0)?|pcie gen ?5|gen ?5" to Term("PCI Express", "PCI Express",
            "PCIe 5.0 (2019, in PCs from 2021) doubles 4.0 to 32 GT/s per lane, about 4 GB/s.", "It enables ~14,000 MB/s SSDs and the newest graphics cards; those drives run hot.",
            listOf(s("PER LANE", "~3.9 GB/s", 3.94, "more bandwidth"), s("SSD (X4)", "~15.8 GB/s", 15.8, "faster SSD slots"), s("GPU (X16)", "~63 GB/s", 63.0, "a faster graphics slot"),
                s("RATE", "32 GT/s"), s("RELEASED", "2019")), "PCIe 5.0"),
        "sata|sata ssds?|sata drives?" to Term("Serial ATA", "Solid-state drive",
            "SATA is the older storage connection: a data cable (or M.2 SATA) limited to about 600 MB/s.", "SATA SSDs are cheap upgrades for old PCs and bulk storage, and still far faster than hard drives.",
            listOf(s("TOP SPEED", "~550 MB/s", 550.0, "faster transfers"), s("ACCESS", "~0.1 ms", 0.1, "quicker access", true), s("SLOT", "SATA cable or M.2 (SATA)"),
                s("QUEUES", "1 queue, 32 commands"), s("COST/TB", "~\$50-70", 60.0, "cheaper per terabyte", true)), "SATA SSD"),
        "atx|atx motherboards?|atx boards?|full atx" to Term("ATX", "Motherboard",
            "ATX is the standard full-size motherboard, 305 x 244 mm.", "Most slots and ports; it needs a mid-tower case or bigger.",
            listOf(s("SIZE", "305 x 244 mm", 305.0, null), s("PCIE SLOTS", "up to 7", 7.0, "more expansion slots"), s("RAM SLOTS", "usually 4", 4.0, "more RAM slots"), s("CASES", "Mid and full towers")), "ATX"),
        "micro atx|matx|m atx|microatx|micro atx motherboards?|matx motherboards?" to Term("MicroATX", "Motherboard",
            "Micro-ATX is a shorter board, 244 x 244 mm.", "Fewer slots but cheaper, and it fits smaller cases.",
            listOf(s("SIZE", "244 x 244 mm", 244.0, null), s("PCIE SLOTS", "up to 4", 4.0, "more expansion slots"), s("RAM SLOTS", "2-4", 4.0, "more RAM slots"), s("CASES", "Micro towers and up")), "Micro-ATX"),
        "mini itx|itx|mini itx motherboards?|itx motherboards?" to Term("Mini-ITX", "Motherboard",
            "Mini-ITX is a tiny 170 x 170 mm board with one expansion slot.", "It powers small form factor PCs that fit on a desk or in a bag.",
            listOf(s("SIZE", "170 x 170 mm", 170.0, null), s("PCIE SLOTS", "1", 1.0, "more expansion slots"), s("RAM SLOTS", "2", 2.0, "more RAM slots"), s("CASES", "Small form factor")), "Mini-ITX"),
        "80 plus|80 plus ratings?|psu ratings?|efficiency ratings?" to Term("80 Plus", "Power supply unit (computer)",
            "80 Plus certifies how efficiently a power supply turns wall power into PC power: Bronze, Silver, Gold, Platinum, Titanium.",
            "Higher ratings waste less power as heat, so the PC runs cooler, quieter and a little cheaper."),
        "80 plus bronze(?: psus?| power suppl(?:y|ies))?|bronze(?: rated)? (?:psus?|power suppl(?:y|ies)|rating)" to plus("Bronze", 82, 85, 82),
        "80 plus silver(?: psus?| power suppl(?:y|ies))?|silver(?: rated)? (?:psus?|power suppl(?:y|ies)|rating)" to plus("Silver", 85, 88, 85),
        "80 plus gold(?: psus?| power suppl(?:y|ies))?|gold(?: rated)? (?:psus?|power suppl(?:y|ies)|rating)" to plus("Gold", 87, 90, 87),
        "80 plus platinum(?: psus?| power suppl(?:y|ies))?|platinum(?: rated)? (?:psus?|power suppl(?:y|ies)|rating)" to plus("Platinum", 90, 92, 89),
        "80 plus titanium(?: psus?| power suppl(?:y|ies))?|titanium(?: rated)? (?:psus?|power suppl(?:y|ies)|rating)" to plus("Titanium", 92, 94, 90),
        "tdp|thermal design power" to Term("Thermal design power", "Heat sink",
            "TDP is the heat, in watts, a chip is designed to give off under sustained load.", "It tells you how big a cooler you need; real power draw can go above it when boosting."),
        "cas latency|cl|ram latency|memory latency|timings|ram timings" to Term("CAS latency", "DIMM",
            "CAS latency (CL) is how many clock cycles memory waits before sending data.", "True latency in nanoseconds is CL x 2000 / speed, so DDR5-6000 CL30 is 10 ns."),
        "xmp|expo|extreme memory profile|ram profile" to Term("Serial presence detect", "DIMM",
            "XMP (Intel) and EXPO (AMD) are overclocking profiles stored on the RAM stick.", "Without turning one on in the BIOS, memory runs at a slow default speed.", name = "XMP / EXPO"),
        "modular psus?|modular power suppl(?:y|ies)|fully modular|semi modular" to Term("Power supply unit (computer)", "Power supply unit (computer)",
            "A modular power supply lets you plug in only the cables you need; semi-modular has the main cables fixed.", "Fewer cables means a tidier case and better airflow.", name = "Modular power supply"),
        "integrated graphics|igpu|integrated gpu|onboard graphics" to Term("Graphics processing unit", "Central processing unit",
            "Integrated graphics are a GPU built into the CPU that shares system RAM.", "They handle video, office work and light games without a graphics card, and save power.", name = "Integrated graphics"),
    ).map { (p, t) -> r("^(?:$p)$") to t }

    /** An 80 Plus level: efficiency at 20 / 50 / 100% load, and the heat wasted at a 500 W load. */
    private fun plus(level: String, low: Int, mid: Int, full: Int): Term {
        val waste = (500 / (mid / 100.0) - 500).roundToInt()
        return Term("80 Plus", "Power supply unit (computer)",
            "80 Plus $level power supplies are at least $low% efficient at 20% load, $mid% at half load and $full% at full load (115 V).",
            "At a 500 W load a $level unit wastes about $waste W as heat.",
            listOf(s("AT 50%", "$mid%", mid.toDouble(), "better efficiency"), s("AT 20%", "$low%", low.toDouble(), "better light-load efficiency"),
                s("AT 100%", "$full%", full.toDouble(), "better full-load efficiency"), s("HEAT 500W", "~$waste W wasted", waste.toDouble(), "less wasted heat", true)), "80 Plus $level")
    }

    /** The tech word [s] names, if Brick knows it. */
    fun term(s: String): Term? = TERMS.firstOrNull { it.first.matches(s.trim()) }?.second

    // ------------------------------------------------------------------ devices

    private const val BRAND = "samsung|google|apple|sony|microsoft|nintendo|nvidia|amd|intel|valve|meta|lenovo|dell|hp|asus|razer|amazon|oculus"
    private const val ANCHOR = "iphone|ipad|ipod|macbook|imac|mac (?:mini|studio|pro)|apple watch|airpods|vision pro|homepod|" +
        "galaxy (?:s|z|a|m|note|tab|watch|buds|ring|book|fold|flip|xcover)|galaxy(?= ?(?:s|z|a|m)\\d)|s\\d{1,2}(?:\\+|(?![a-z0-9]))|z ?(?:fold|flip)|" +
        "pixel(?= ?\\d)|pixel (?:fold|tablet|watch|buds|slate)|" +
        "oneplus|nothing phone|cmf phone|xiaomi|redmi|poco [fxm]|oppo (?:find|reno)|vivo x|realme|huawei (?:mate|pura|p)|honor (?:magic|\\d)|motorola|moto ?[a-z](?= |$)|razr|xperia|zenfone|rog (?:phone|ally)|fairphone|" +
        "surface (?:pro|laptop|go|book|duo|studio)|thinkpad|legion go|xps|alienware|razer blade|framework laptop|zenbook|" +
        "playstation|ps ?[1-5]|ps vita|psp|xbox|nintendo switch|switch (?:2|oled|lite)|steam deck|meta quest|quest [1-3]|" +
        "kindle|echo (?:dot|show|pop)|chromecast|fitbit|garmin|gopro|dji|" +
        "geforce|rtx ?\\d{3,4}|gtx ?\\d{3,4}|radeon|rx ?\\d{3,4}|arc [ab]\\d{3}|ryzen|threadripper|core (?:i[3579]|ultra)|snapdragon|exynos|dimensity|tensor g\\d|a\\d{2} (?:bionic|pro)|apple m\\d|m\\d (?:pro|max|ultra|chip)|raspberry pi|" + PcParts.ANCHORS
    private const val MODEL = "(?<=-)[a-z]|\\d{3,5}[a-z]{1,2}\\d[a-z]{1,2}|\\d{1,4}[a-z]{0,3}|gb|tb|wifi|gaming|rgb|tomahawk|mortar|aorus|hero|extreme|apex|carbon|taichi|ax|d[45]|psu|ssd|nvme|[a-z]{1,2}\\d{1,4}[a-z]?|pro|max|ultra|plus|\\+|mini|fe|xl|se|lite|edge|air|neo|fold|flip|series|x|s|gen|\\d(?:st|nd|rd|th)|generation|ti|super|xtx?|oled|slim|digital|edition|ally|deck|go|5g|classic|duo|elite|chip"
    private val DEVICE = r("(?<![a-z0-9])(?:(?:$BRAND) )?(?:$ANCHOR)(?:(?: |-)?(?:$MODEL)(?![a-z0-9]))*(?:\\+)?")

    /** The device names in [s], in order. */
    fun devicesIn(s: String): List<String> = DEVICE.findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() && !PcParts.isBareType(it) }.toList()

    /** True when all of [s] is a device name. */
    fun isDevice(s: String): Boolean = DEVICE.matchEntire(s.trim()) != null

    private val SPECIAL = mapOf(
        "iphone" to "iPhone", "ipad" to "iPad", "ipod" to "iPod", "imac" to "iMac", "macbook" to "MacBook", "airpods" to "AirPods",
        "homepod" to "HomePod", "oneplus" to "OnePlus", "xbox" to "Xbox", "thinkpad" to "ThinkPad", "playstation" to "PlayStation",
        "gopro" to "GoPro", "geforce" to "GeForce", "rog" to "ROG", "xps" to "XPS", "dji" to "DJI", "cmf" to "CMF", "se" to "SE",
        "fe" to "FE", "xl" to "XL", "xt" to "XT", "xtx" to "XTX", "ti" to "Ti", "oled" to "OLED", "rtx" to "RTX", "gtx" to "GTX",
        "rx" to "RX", "amd" to "AMD", "hp" to "HP", "5g" to "5G", "zenbook" to "Zenbook", "razr" to "Razr", "realme" to "Realme",
        "psp" to "PlayStation Portable",
    )

    /** "s26 ultra" -> "Samsung Galaxy S26 Ultra", "ps5" -> "PlayStation 5": the name Wikipedia uses. */
    fun canonical(said: String): String {
        var s = said.lowercase(us).trim().replace(Regex("\\s+"), " ")
        s = s.replace(Regex("^(?:samsung|google|apple|sony|microsoft|valve|oculus) "), "")
        s = s.replace(Regex("^ps ?([1-5])\\b"), "playstation $1").replace(Regex("^ps vita\\b"), "playstation vita")
        s = s.replace(Regex("\\bz ?(fold|flip)"), "z $1")
        var prefix = ""
        when {
            Regex("^galaxy ").containsMatchIn(s) -> prefix = "Samsung "
            Regex("^(?:s\\d{1,2}|z (?:fold|flip))").containsMatchIn(s) -> prefix = "Samsung Galaxy "
            Regex("^(?:switch (?:2|oled|lite))").containsMatchIn(s) -> prefix = "Nintendo "
            Regex("^quest \\d").containsMatchIn(s) -> prefix = "Meta "
            Regex("^(?:nvidia )?(?:rtx|gtx) ").containsMatchIn(s) || Regex("^(?:nvidia )?(?:rtx|gtx)\\d").containsMatchIn(s) -> { s = s.removePrefix("nvidia "); prefix = "GeForce " }
            Regex("^(?:amd )?rx ?\\d").containsMatchIn(s) -> { s = s.removePrefix("amd "); prefix = "Radeon " }
            Regex("^(?:a\\d{2} (?:bionic|pro)|m\\d)").containsMatchIn(s) -> prefix = "Apple "
        }
        s = s.replace(Regex("^(rtx|gtx|rx)(\\d)"), "$1 $2").replace(Regex(" chip$"), "")
        val words = s.split(' ').filter { it.isNotEmpty() }.map { w ->
            SPECIAL[w] ?: when {
                Regex("^[a-z]{1,2}\\d+\\+?$").matches(w) -> w.uppercase(us)
                Regex("^\\d+[a-z]+$").matches(w) -> w
                else -> w.replaceFirstChar { it.titlecase(us) }
            }
        }
        return (prefix + words.joinToString(" ")).trim()
    }

    // ------------------------------------------------------------------ parsing

    private val NOT_TECH = r("\\b(?:price|prices|cost|costs|how much (?:is|are|does|do|was)|cheapest|cheap|deals?|on sale|sale|buy|purchase|order|trade in|preorder|pre order|where (?:can|do) i get|in stock|find my|update|reset|restart|open|launch|play|turn (?:on|off)|connect|pair|call|text|lyrics|song|songs|album|movie|film|show|episode|stock price|shares)\\b")
    private val QUESTION = r("^(?:what(?:'s| is| are| was| were)?|whats|which|how|tell me|show me|give me|explain|describe|info|information|details|is|are|does|do|did|can|could|should|would|will|compare|i want to know|i'd like to know|what about|look up|search)\\b")

    private val WANTS: List<Pair<TechWant, Regex>> = listOf(
        TechWant.UNLOCK to "unlock|unlocks|unlocking|fingerprint|face id|face unlock|touch id|biometric\\w*|face recognition",
        TechWant.CHARGING to "charg(?:e|es|ing|er)|watts?|wattage|magsafe|qi2?",
        TechWant.BATTERY to "battery|batteries|mah|battery life|how long (?:does|do|will|would) (?:the |it |its |a )?.*(?:last|go)|last (?:all|a full) day|screen on time|sot",
        TechWant.REFRESH to "refresh(?: rate)?|hz|hertz|smooth(?:ness)?|fps|ltpo|promotion",
        TechWant.DISPLAY to "screen|screens|display|displays|resolution|ppi|pixel density|nits|brightness|how big",
        TechWant.CHIP to "chip|chips|chipset|processor|cpu|gpu|soc|how fast|performance|benchmark\\w*|powerful",
        TechWant.MEMORY to "ram|memory|storage|how much space|gb|tb|gigabytes?",
        TechWant.CAMERA to "cameras?|megapixels?|mp|lens(?:es)?|zoom|telephoto|selfie|photos?|pictures?|video recording",
        TechWant.SPEAKERS to "speakers?|sound|audio|stereo|loud|dolby|atmos|headphone jack",
        TechWant.PORT to "usb|ports?|lightning|type c|thunderbolt|transfer speeds?|data speeds?|connector",
        TechWant.DESIGN to "weigh|weight|weighs|heavy|dimensions|size|thick|thickness|water ?proof|water resistant|water resistance|ip\\d\\d|durable|build|materials?|titanium",
        TechWant.SPECS to "specs?|specifications?|spec sheet|hardware|stats|features?|tech specs|full specs|everything",
    ).map { (w, p) -> w to r("\\b(?:$p)\\b") }

    private fun want(rest: String): TechWant = WANTS.firstOrNull { it.second.containsMatchIn(rest) }?.first ?: TechWant.OVERVIEW

    private val COMPARE_CUE = r("\\b(?:vs|versus|or|and|difference|differences|differ|compare|compared|comparison|better|worse|than|against|same as)\\b")

    private val TERM_FORMS = listOf(
        r("^(?:what(?:'s| is| are)|whats|define|explain|describe|meaning of|definition of|tell me about|what does|what do|how does|how do|why (?:is|are|do|does))(?: an?| the)? (.+?)(?: do| does| work| works| mean| stand for| important| matter| used for| for| in (?:a |the )?(?:computer|phone|pc|laptop)s?)*$"),
        r("^(.+?) (?:meaning|definition|explained)$"),
    )

    /** A tech question, or null. */
    fun parse(raw: String): Request.Tech? {
        var s = raw.lowercase(us).replace(Regex("[?!,.]+(?!\\d)"), " ").replace("’", "'").replace(Regex("\\s+"), " ").trim()
        // "the s26 ultra's battery" -> "the s26 ultra battery"; "what's" stays.
        s = s.replace(Regex("\\b(?!(?:what|that|it|who|where|how|when|there|here|let|he|she)'s)([a-z0-9+]+)'s\\b"), "$1").replace(Regex("^(?:hey |ok |okay )?brick "), "").trim()
        if (s.isEmpty() || s.length > 160) return null
        termCompare(s)?.let { return it }
        val devices = devicesIn(s).distinctBy { canonical(it) }
        if (devices.isEmpty()) {
            // "explain what an ssd is", "can you tell me what a gpu does", "i want to know what ram is".
            val q = s.replace(Regex("^(?:can you |could you |please |i want to know |i'd like to know |do you know )*(?:explain|tell me|describe|know)?(?: to me)? ?(?=what|how|why)"), "")
                .replace(Regex("^(what (?:an?|the) .+?) (?:is|are)$"), "$1").replace(Regex("^what (?=an? |the )"), "what is ")
            for (rx in TERM_FORMS) {
                val m = rx.matchEntire(q) ?: continue
                val name = m.groupValues[1].trim().removePrefix("an ").removePrefix("a ").removePrefix("the ")
                if (term(name) != null) return Request.Tech(TechAsk(listOf(name), TechWant.TERM, s))
            }
            return null
        }
        if (Regex("\\bmy\\b|\\bthis phone\\b|\\byour\\b").containsMatchIn(s) && devices.size < 2) return null
        if (NOT_TECH.containsMatchIn(s)) return null
        // "is hades on steam deck", "games for the switch 2": where a game runs, not the hardware.
        if (devices.size == 1 && want(s.replace(devices[0], " ")) == TechWant.OVERVIEW &&
            Regex("\\b(?:on|for|with)(?: the)? ${Regex.escape(devices[0])}").containsMatchIn(s)) return null
        var rest = s
        devices.forEach { rest = rest.replace(it, " ") }
        rest = rest.replace(Regex("\\s+"), " ").trim()
        if (devices.size >= 2) {
            if (!COMPARE_CUE.containsMatchIn(rest)) return null
            return Request.Tech(TechAsk(devices.take(2), want(rest).let { if (it == TechWant.OVERVIEW) TechWant.SPECS else it }, s))
        }
        val w = want(rest)
        // A bare name ("iphone 11") or a question about one ("what is the s26 ultra", "s26 ultra battery").
        val leftover = rest.replace(Regex("\\b(?:the|a|an|new|latest|google|samsung|apple)\\b"), "").trim()
        if (w == TechWant.OVERVIEW && leftover.isNotEmpty() && !QUESTION.containsMatchIn(s) &&
            !Regex("^(?:about|info|information|details|review|reviews|overview|good|worth it|any good)$").matches(leftover)) return null
        return Request.Tech(TechAsk(devices.take(1), w, s))
    }

    /** "cpu vs gpu", "what's the difference between ram and storage". */
    private fun termCompare(s: String): Request.Tech? {
        val m = r("^(?:what(?:'s| is| are)(?: the)? (?:main |key |real )?differences? between|difference between|compare|explain) (?:an? |the )?(.+?) (?:and|vs|versus|with|to) (?:an? |the )?(.+)$").matchEntire(s)
            ?: r("^(?:which is better|which one is better|which is faster|should i get|should i buy|should i use|is it better to get|is it better to use)?(?: an?| the)? ?(.+?) (?:vs|versus|or|compared to) (?:an? |the )?(.+?)(?: difference| which is better| which is faster)?$").matchEntire(s) ?: return null
        var a = m.groupValues[1].trim(); var b = m.groupValues[2].trim()
        if (isDevice(a) || isDevice(b)) return null
        // "gold or platinum psu", "ddr4 or ddr5 ram": the shared noun belongs to both sides.
        if (term(a) == null && b.contains(' ')) term("$a ${b.substringAfterLast(' ')}")?.let { a = "$a ${b.substringAfterLast(' ')}" }
        if (term(b) == null && a.contains(' ')) term("$b ${a.substringAfterLast(' ')}")?.let { b = "$b ${a.substringAfterLast(' ')}" }
        // "pcie 4.0 vs 5.0": the shared prefix too.
        if (term(b) == null && a.contains(' ')) term("${a.substringBeforeLast(' ')} $b")?.let { b = "${a.substringBeforeLast(' ')} $b" }
        if (term(a) == null || term(b) == null) return null
        return Request.Tech(TechAsk(listOf(a, b), TechWant.TERM, s))
    }

    // ------------------------------------------------------------------ reading Wikipedia

    /** One article's infobox ("Battery" -> "S26: 4300 mAh ¦ S26 Ultra: 5000 mAh") in order. */
    fun infobox(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val body = html.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<sup[^>]*>.*?</sup>", RegexOption.DOT_MATCHES_ALL), "")
        val row = Regex("<th[^>]*class=\"[^\"]*infobox-label[^\"]*\"[^>]*>(.*?)</th>\\s*<td[^>]*class=\"[^\"]*infobox-data[^\"]*\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        for (tr in body.split("</tr>")) {
            val m = row.find(tr) ?: continue
            val label = plain(m.groupValues[1]).replace(" ¦ ", " ").trim()
            val value = plain(m.groupValues[2])
            if (label.isNotEmpty() && value.isNotEmpty()) out += label to value
        }
        return out
    }

    private fun plain(h: String): String = h
        .replace(Regex("<br\\s*/?>|</li>|</p>|</div>", RegexOption.IGNORE_CASE), " ¦ ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&#8201;", " ").replace("&thinsp;", " ")
        .replace("&amp;", "&").replace("&times;", "×").replace("&#215;", "×").replace("&ndash;", "–").replace("&#8211;", "–")
        .replace("&mdash;", "—").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[ \\t\\r\\n\\u00a0\\u2009]+"), " ")
        .replace(Regex("(?:\\s*¦\\s*)+"), " ¦ ")
        .trim().removePrefix("¦").removeSuffix("¦").trim()

    /** A model asked about: its number ("s26", "11", "9a") and variant words ("ultra"; "pro", "max"). */
    data class Model(val base: String, val variant: Set<String>) {
        /** Finds this model's name in text ("S26 Ultra", "Galaxy S26+"), not its siblings. */
        val inText: Regex by lazy {
            val v = variant.sorted().joinToString("") { w -> if (w == "plus") " ?(?:\\+|plus)" else " ?$w" }
            val tail = if (variant.isEmpty()) "(?! ?(?:\\+|plus|ultra|fe|edge|pro|max|mini|xl|lite|e\\b|a\\b))" else "(?![a-z0-9])"
            Regex("(?<![a-z0-9])${Regex.escape(base)}$v$tail", RegexOption.IGNORE_CASE)
        }
        val label get() = (listOf(base.uppercase(us)) + variant.map { if (it == "plus") "+" else it.replaceFirstChar { c -> c.titlecase(us) } }).joinToString(" ").replace(" +", "+")
    }

    private val VARIANTS = setOf("ultra", "plus", "fe", "pro", "max", "mini", "xl", "edge", "lite", "se", "air", "slim", "oled", "ti", "super", "xt", "xtx")

    fun model(name: String): Model? {
        val t = name.lowercase(us).replace("+", " plus").split(' ').filter { it.isNotEmpty() }
        val i = t.indexOfFirst { Regex("^[a-z]{0,2}\\d{1,4}[a-z]{0,2}$").matches(it) }
        if (i < 0) return null
        return Model(t[i], t.drop(i + 1).filter { it in VARIANTS }.toSet())
    }

    /**
     * The part of an infobox value about [m] when the article covers a lineup:
     * "S26: 4300 mAh ¦ S26 Ultra: 5000 mAh" -> "5000 mAh". Values without model labels come back whole.
     */
    fun forModel(value: String, m: Model?): String {
        if (m == null) return value
        val segs = value.split(" ¦ ")
        data class Group(val label: String?, val parts: MutableList<String>)
        val groups = ArrayList<Group>()
        val head = Regex("^([^:]{1,80}):\\s*(.*)$")
        fun modelLabel(l: String) = Regex("(?<![a-z0-9])(?:${Regex.escape(m.base)}|all)(?![0-9])", RegexOption.IGNORE_CASE).containsMatchIn(l) ||
            Regex("^(?:galaxy |iphone |pixel )?(?:${VARIANTS.joinToString("|")}|\\+|plus|base|standard)(?: models?)?$", RegexOption.IGNORE_CASE).matches(l.trim())
        for (seg in segs) {
            val h = head.find(seg)
            if (h != null && modelLabel(h.groupValues[1])) groups += Group(h.groupValues[1], mutableListOf<String>().apply { if (h.groupValues[2].isNotBlank()) add(h.groupValues[2].trim()) })
            else if (groups.isEmpty()) groups += Group(null, mutableListOf(seg)) else groups.last().parts += seg
        }
        if (groups.none { it.label != null }) return value
        val picked = groups.filter { g -> g.label == null || labelHas(g.label, m) }
        val text = picked.flatMap { it.parts }.filter { it.isNotBlank() }.joinToString(" ¦ ")
        return text.ifBlank { value }
    }

    /** Whether a lineup label ("S26 & S26+ (US, China) & S26 Ultra", "All except S26 FE") includes [m]. */
    fun labelHas(label: String, m: Model): Boolean {
        val l = label.lowercase(us).replace(Regex("\\([^)]*\\)"), " ")
        val (yes, except) = l.split(Regex("\\bexcept\\b"), 2).let { it[0] to it.getOrNull(1) }
        fun items(s: String) = s.split(Regex("&|,|/|\\band\\b")).map { it.trim() }.filter { it.isNotEmpty() }
        fun fits(item: String): Boolean {
            if (Regex("^all(?: models)?$").matches(item)) return true
            val t = item.replace("+", " plus").replace(Regex("\\b(?:galaxy|samsung|apple|iphone|google|pixel|models?)\\b"), " ")
                .split(' ').filter { it.isNotEmpty() }
            val hasBase = m.base in t
            val vs = t.filter { it in VARIANTS }.toSet()
            val numbers = t.filter { Regex("\\d").containsMatchIn(it) }
            if (numbers.isNotEmpty() && !hasBase) return false
            return vs == m.variant
        }
        val inYes = items(yes).any { fits(it) }
        val inExcept = except?.let { e -> items(e).any { fits(it) } } ?: false
        return inYes && !inExcept
    }

    private val SENT = Regex("(?<=[.!?])\\s+(?=[A-Z\"“])")
    private fun sentences(text: String) = SENT.split(text.replace(Regex("^=+.*=+$", RegexOption.MULTILINE), " ").replace(Regex("[\\s\\u00a0\\u202f\\u2009]+"), " ").trim())
        .map { it.trim() }.filter { it.length in 15..500 }

    /** The sentences about [m] first, then (when the article is only about it, or no sibling is named) the rest. */
    private fun about(sents: List<String>, m: Model?, single: Boolean): List<String> {
        if (m == null) return sents
        val mine = sents.filter { m.inText.containsMatchIn(it) }
        val sibling = Regex("(?<![a-z0-9])${Regex.escape(m.base)} ?(?:\\+|plus|ultra|fe|edge|pro|max|mini|xl|lite)", RegexOption.IGNORE_CASE)
        val rest = sents.filter { it !in mine && (single || !sibling.containsMatchIn(it) || m.variant.isEmpty()) }
        return mine + rest
    }

    // ------------------------------------------------------------------ spec reading (pure, tested)

    /** Everything Brick read off one device's page. */
    data class Specs(
        val name: String, val page: String, val description: String?, val intro: String, val image: String?, val url: String,
        val rows: List<Pair<String, String>>, val text: String?, val model: Model?, val single: Boolean,
        val fullRows: List<Pair<String, String>> = rows,
    ) {
        private val sents by lazy { about(sentences(text.orEmpty()), model, single) }
        fun row(vararg labels: String): String? = labels.firstNotNullOfOrNull { l -> rows.firstOrNull { it.first.equals(l, true) }?.second }
            ?: labels.firstNotNullOfOrNull { l -> rows.firstOrNull { it.first.lowercase(us).startsWith(l.lowercase(us)) }?.second }
        fun said(rx: Regex): String? = sents.firstOrNull { rx.containsMatchIn(it) }
        /** Like [said], but from where this model is named ("...the S26+ has 6.7 in and the S26 Ultra has 6.9 in" -> "S26 Ultra has 6.9 in"). */
        fun saidHere(rx: Regex): String? = said(rx)?.let { s -> model?.inText?.find(s)?.let { m -> s.substring(m.range.first).takeIf { rx.containsMatchIn(it) } } ?: s }
        val isPhone get() = Regex("\\b(?:smartphones?|phones?|phablets?|iphones?|tablets?|ipads?)\\b", RegexOption.IGNORE_CASE).containsMatchIn("$description $name ${intro.take(300)}") ||
            row("Battery")?.contains("mAh") == true && row("Display", "Screen") != null
        val isApple get() = Regex("\\b(?:apple|iphone|ipad|macbook|ios)\\b", RegexOption.IGNORE_CASE).containsMatchIn("$name $description")

        val year: Int? get() = (row("First released", "Release date", "Released", "Introduced", "Launch date", "Availability") ?: intro)
            .let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
        val mah: Int? get() = (row("Battery") ?: saidHere(r("\\d[,\\d]{2,5} ?mah")))?.let { Regex("(\\d[\\d,]{2,5}) ?mAh", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val wh: Double? get() = row("Battery", "Power")?.let { Regex("(\\d{1,3}(?:\\.\\d+)?) ?W[·⋅]?h", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

        val displayText: String? get() = row("Display", "Screen", "Main display")
            ?: saidHere(r("\\b(?:display|screen)\\b.*\\d(?:\\.\\d+)? ?(?:in\\b|inch|-inch|″|\")|\\d(?:\\.\\d+)? ?(?:in\\b|inch|-inch|″)[^.]*\\b(?:display|screen|amoled|oled|lcd)\\b"))
        val screenSize: String? get() = (displayText ?: "").let { Regex("(\\d{1,2}(?:\\.\\d{1,2})?) ?(?:-?in(?:ch(?:es)?)?\\b|″|\")", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val panel: String? get() = (displayText ?: "").let { Regex("\\b(dynamic amoled(?: 2x)?|super amoled(?: plus)?|super retina(?: xdr)?|liquid retina(?: xdr)?|ltpo(?: \\d)? oled|p-?oled|actua|amoled|oled|mini-?led|micro-?led|ips lcd|ips|lcd|e ink|retina)\\b", RegexOption.IGNORE_CASE).find(it)?.value }
        val resolution: String? get() = (displayText ?: "").let { d ->
            Regex("(\\d{3,4}) ?[x×] ?(\\d{3,4})").find(d)?.let { "${it.groupValues[1]} x ${it.groupValues[2]}" }
                ?: saidHere(r("\\d{3,4} ?[x×] ?\\d{3,4}"))?.let { Regex("(\\d{3,4}) ?[x×] ?(\\d{3,4})").find(it)?.let { m -> "${m.groupValues[1]} x ${m.groupValues[2]}" } }
        }
        val ppi: String? get() = (displayText ?: "").let { Regex("(\\d{3}) ?ppi", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val hz: Pair<Int, Int?>? get() {
            val src = listOfNotNull(displayText, row("Refresh rate"), saidHere(r("\\d{2,3} ?hz"))).joinToString(" ")
            val all = Regex("(\\d{1,3}) ?(?:–|-|to) ?(\\d{2,3}) ?hz|(\\d{2,3}) ?hz", RegexOption.IGNORE_CASE).findAll(src).toList()
            if (all.isEmpty()) return null
            val range = all.firstOrNull { it.groupValues[2].isNotEmpty() }
            val top = all.maxOf { (it.groupValues[2].ifEmpty { it.groupValues[3] }).toIntOrNull() ?: 0 }
            return top to range?.groupValues?.get(1)?.toIntOrNull()
        }

        val chip: String? get() = row("System-on-chip", "System on chip", "SoC", "Chipset", "Processor", "CPU")?.let { tidy(it, 110) }
        val gpu: String? get() = row("GPU", "Graphics")?.let { tidy(it, 90) }
        val ram: String? get() = row("Memory", "RAM")?.let { tidy(it, 90) }
        val storage: String? get() = row("Storage")?.let { tidy(it, 90) }

        val rearCamera: String? get() = row("Rear camera", "Main camera", "Camera", "Primary camera")?.let { cameraLine(it) }
            ?: saidHere(r("\\b\\d{1,3} ?MP\\b.*\\b(?:wide|ultra|telephoto|periscope|main)"))?.let { cameraLine(it) }
        val frontCamera: String? get() = row("Front camera", "Selfie camera", "Secondary camera")?.let { cameraLine(it) }
        val mainMp: Int? get() = (row("Rear camera", "Main camera", "Camera") ?: rearCamera ?: "").let { c -> Regex("(\\d{1,3}(?:\\.\\d)?) ?MP", RegexOption.IGNORE_CASE).findAll(c).mapNotNull { it.groupValues[1].toDoubleOrNull()?.toInt() }.maxOrNull() }

        val chargingText: String? get() = listOfNotNull(row("Charging"), row("Battery")?.takeIf { Regex("\\d ?W\\b|charg", RegexOption.IGNORE_CASE).containsMatchIn(it) },
            row("Power")).joinToString(" ¦ ").ifBlank { null } ?: saidHere(r("\\d{2,3} ?W\\b.*charg|charg.*\\d{2,3} ?W\\b"))
        /** Wired, wireless and reverse wireless watts. */
        val watts: Triple<Double?, Double?, Double?> get() {
            val t = chargingText ?: return Triple(null, null, null)
            var wired: Double? = null; var wireless: Double? = null; var reverse: Double? = null
            val w = Regex("(\\d{1,3}(?:\\.\\d)?) ?W(?![a-z·⋅])", RegexOption.IGNORE_CASE)
            for (seg in t.split(Regex(" ¦ |;|, (?=\\d)| and (?=\\d)"))) {
                val v = w.find(seg)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                when {
                    Regex("reverse|powershare|share", RegexOption.IGNORE_CASE).containsMatchIn(seg) -> reverse = maxOf(reverse ?: 0.0, v)
                    Regex("wireless|qi|magsafe", RegexOption.IGNORE_CASE).containsMatchIn(seg) -> wireless = maxOf(wireless ?: 0.0, v)
                    else -> wired = maxOf(wired ?: 0.0, v)
                }
            }
            // "Fast charging up to 23W 7.5W": the smaller second number is wireless.
            if (wireless == null && wired != null) {
                val nums = w.findAll(t).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
                if (nums.size == 2 && nums[1] < nums[0] && !Regex("wireless|qi", RegexOption.IGNORE_CASE).containsMatchIn(t)) { wired = nums[0]; wireless = nums[1] }
            }
            return Triple(wired, wireless, reverse)
        }

        val portText: String? get() {
            val src = listOfNotNull(row("Connectivity"), row("Connector", "Connectors"), row("Ports", "I/O", "Input/output"), row("Other"), row("Data inputs")).joinToString(" ¦ ")
            return USB.find(src)?.let { m -> src } ?: said(USB)
        }
        val usb: String? get() = portText?.let { t -> USB.findAll(t).map { it.value }.toList() }?.let { pick ->
            if (pick.isEmpty()) null else pick.distinctBy { it.lowercase(us).replace(Regex("[- ]"), "") }.joinToString(", ") { prettyUsb(it) }
        }

        val unlock: List<String> get() {
            val src = listOfNotNull(row("Sensors"), row("Other"), row("Data inputs"), row("Biometrics", "Security")).joinToString(" ¦ ")
            // Sentences naming a sibling count when they carve out an exception ("...except the S26 FE, which...").
            val lineup = sentences(text.orEmpty()).filter { it !in sents && Regex("\\bexcept\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val joined = (listOf(src) + (sents + lineup).filter { UNLOCK_WORDS.containsMatchIn(it) }.take(8)).joinToString(" ")
            val asked = if (model != null) {
                // "...an ultrasonic sensor, except the S26 FE which uses an optical sensor": read the part about this model.
                val ex = Regex("except (?:for )?(?:the )?[^.]*?" + model.inText.pattern + "[^.]*", RegexOption.IGNORE_CASE).find(joined)
                if (ex != null) ex.value else joined.replace(Regex(",? except (?:for )?(?:the )?[^.,;]+", RegexOption.IGNORE_CASE), "")
            } else joined
            val out = ArrayList<String>()
            fun has(p: String) = r(p).containsMatchIn(asked)
            if (has("face id|truedepth")) out += "Face ID (TrueDepth 3D face scan)"
            when {
                has("ultrasonic") -> out += "Ultrasonic under-display fingerprint reader"
                has("optical (?:in|under)[- ]?(?:display|screen)|(?:in|under)[- ]?(?:display|screen) (?:optical )?fingerprint|optical (?:fingerprint )?sensor") -> out += "Optical under-display fingerprint reader"
                has("(?:side[- ]mounted|power (?:button|key)|side (?:button|key))[^.]{0,40}fingerprint|fingerprint[^.]{0,40}(?:power (?:button|key)|side[- ]mounted)") -> out += "Fingerprint reader in the power button"
                has("touch id") -> out += "Touch ID fingerprint reader"
                has("rear[- ]mounted fingerprint|fingerprint (?:sensor|reader|scanner) on the back") -> out += "Rear fingerprint reader"
                has("fingerprint") && !has("face id") -> out += "Fingerprint reader"
            }
            if (has("face unlock|facial recognition|face recognition") && out.none { it.startsWith("Face ID") }) out += "Face unlock (camera)"
            return out
        }

        val speakers: String? get() = row("Sound", "Audio", "Speakers", "Speaker")?.let { tidy(it, 90) }
            ?: said(r("\\b(?:stereo speakers?|dual speakers?|speakers?)\\b"))?.let { s -> Regex("(stereo|dual|mono|quad)[^.,;]{0,30}speakers?|speakers?[^.,;]{0,30}(?:dolby atmos|stereo)", RegexOption.IGNORE_CASE).find(s)?.value?.replaceFirstChar { it.titlecase(us) } }
        val speakerQuality: String? get() = said(r("\\b(?:speakers?|audio|sound)\\b[^.]*\\b(?:loud|louder|clear|clearer|rich|bass|tinny|quality|full|balanced|praised|improved|criticized|distort\\w*|excellent|good|great|impressive)\\b|\\b(?:loud|louder|clear|clearer|rich|bass|tinny|balanced|praised|improved|excellent|impressive)\\b[^.]*\\b(?:speakers?|audio)\\b"))

        /** "Original: ¦ Android 16 with One UI 8.5 ¦ Current: ..." -> "Android 16 with One UI 8.5". */
        val os: String? get() = row("Operating system", "OS")?.let { v ->
            v.split(" ¦ ").map { it.replace(Regex("^(?:original|current|launch|at launch|initial) *: *", RegexOption.IGNORE_CASE), "").trim() }
                .firstOrNull { it.length > 2 && !it.endsWith(":") }?.let { tidy(it, 70) }
        }
        val weight: String? get() = row("Mass", "Weight")?.let { Regex("\\d+(?:\\.\\d+)? ?(?:g|kg|oz|lb)\\b", RegexOption.IGNORE_CASE).find(it)?.value }
        val water: String? get() = (listOfNotNull(row("Water resistance"), row("Other"), row("Dimensions")).joinToString(" ") + " " + (said(r("\\bIP6\\d\\b")) ?: ""))
            .let { Regex("\\bIP[56X][78X]\\b").find(it)?.value }
        val pen: Boolean get() = Regex("\\bS Pen\\b|Apple Pencil|stylus", RegexOption.IGNORE_CASE).containsMatchIn(listOfNotNull(row("Data inputs"), row("Input"), row("Other")).joinToString(" "))
    }

    private val USB = r("usb[- ]?c(?: \\d(?:\\.\\d)?(?: gen \\d(?:x\\d)?)?)?|usb type[- ]?c(?: \\d(?:\\.\\d)?(?: gen \\d(?:x\\d)?)?)?|usb4(?: v?2(?:\\.0)?)?|thunderbolt(?: \\d)?|lightning|micro[- ]?usb(?: 2\\.0)?|usb \\d\\.\\d(?: gen \\d(?:x\\d)?)?|usb 2\\.0|usb 3")
    private val UNLOCK_WORDS = r("fingerprint|face id|truedepth|face unlock|facial recognition|touch id|ultrasonic")

    /** "USB-C 3.2 Gen 2" -> "USB-C, USB 3.2 Gen 2 (10 Gbps)". */
    fun prettyUsb(raw: String): String {
        val l = raw.lowercase(us).replace(Regex("\\s+"), " ")
        val kind = when {
            l.startsWith("lightning") -> "Lightning"
            l.contains("thunderbolt") -> "Thunderbolt" + (Regex("\\d").find(l)?.let { " ${it.value}" } ?: "")
            l.contains("micro") -> "micro-USB"
            l.contains("usb4") -> "USB4"
            Regex("usb[- ]?(?:type[- ]?)?c").containsMatchIn(l) -> "USB-C"
            else -> "USB"
        }
        val ver = Regex("(\\d\\.\\d)(?: gen (\\dx\\d|\\d))?").find(l.replace(Regex("^.*?usb(?:[- ]?(?:type[- ]?)?c)? ?"), ""))
        val verText = ver?.let { "USB ${it.groupValues[1]}" + (it.groupValues[2].takeIf { g -> g.isNotEmpty() }?.let { g -> " Gen $g" } ?: "") }
        val speed = usbSpeed(l)
        return listOfNotNull(kind, verText?.takeIf { kind != "USB" }).joinToString(", ").let { if (kind == "USB") verText ?: "USB" else it } +
            (speed?.let { " ($it)" } ?: "")
    }

    /** The top data speed a USB port name implies, or null when it doesn't say. */
    fun usbSpeed(name: String): String? {
        val l = name.lowercase(us)
        return when {
            l.contains("thunderbolt 5") -> "80-120 Gbps"
            l.contains("thunderbolt") || l.contains("usb4") -> "40 Gbps"
            l.contains("lightning") -> "480 Mbps, USB 2.0 speed"
            Regex("gen 2x2").containsMatchIn(l) -> "20 Gbps"
            Regex("3\\.[12] gen 2|3\\.1(?! gen 1)\\b(?! gen)").containsMatchIn(l) && Regex("gen 2").containsMatchIn(l) -> "10 Gbps"
            Regex("3\\.[012](?: gen 1)?").containsMatchIn(l) && Regex("gen 1|3\\.0").containsMatchIn(l) -> "5 Gbps"
            Regex("3\\.2").containsMatchIn(l) -> "5-20 Gbps"
            Regex("3\\.1").containsMatchIn(l) -> "5-10 Gbps"
            Regex("\\busb 3\\b").containsMatchIn(l) -> "5-10 Gbps"
            Regex("2\\.0|micro").containsMatchIn(l) -> "480 Mbps"
            else -> null
        }
    }

    /** A spec value trimmed for the card: repeats and brackets dropped. */
    private fun tidy(v: String, max: Int): String {
        var t = v.replace(Regex("\\s*\\[[^]]*]"), "").split(" ¦ ").map { it.trim().trimEnd(',', ';') }.filter { it.isNotEmpty() }.distinct().joinToString("; ")
        if (t.length > max) t = t.substring(0, t.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max).trimEnd(',', ';', ' ') + "..."
        return t
    }

    /** "12 MP wide (26 mm) f/1.8 ... 12 MP ultra-wide" -> "12 MP wide, 12 MP ultrawide". */
    fun cameraLine(v: String): String {
        val hits = Regex("(\\d{1,3}(?:\\.\\d)?) ?MP\\b([^,;¦]{0,60})", RegexOption.IGNORE_CASE).findAll(v).map { m ->
            val lens = Regex("ultra[- ]?wide|wide[- ]angle|wide|periscope|telephoto|macro|depth|main|monochrome|tof|selfie|front", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.value?.lowercase(us)
                ?.replace(Regex("ultra[- ]?wide"), "ultrawide")?.replace("wide-angle", "wide")?.replace("wide angle", "wide")
            val zoom = Regex("(\\d(?:\\.\\d)?)\\s?[x×]", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.groupValues?.get(1)
            listOfNotNull("${m.groupValues[1]} MP", zoom?.let { "${it}x" }, lens).joinToString(" ")
        }.toList().distinct()
        return if (hits.isNotEmpty()) hits.joinToString(", ") else tidy(v, 100)
    }

    // ------------------------------------------------------------------ battery life

    /**
     * About how long a full charge lasts with light, medium and heavy use. An estimate from capacity:
     * phones average roughly 120 / 230 / 480 mA by use; iPhones get about 25% more per mAh.
     */
    fun batteryLife(mah: Int, apple: Boolean): Triple<Int, Int, Int> {
        val f = if (apple) 1.25 else 1.0
        return Triple((mah * f / 120).roundToInt(), (mah * f / 230).roundToInt(), (mah * f / 480).roundToInt())
    }

    private fun hours(h: Int): String = when {
        h >= 36 -> "about ${"%.1f".format(us, h / 24.0).removeSuffix(".0")} days (~$h h)"
        h >= 20 -> "about $h hours, a full day"
        else -> "about $h hours"
    }

    // ------------------------------------------------------------------ fetching

    private fun wiki(url: String) = Http.getJsonCached(url)

    /** A summary's title as Wikipedia shows it ("iPhone 11", not "IPhone 11"). */
    private fun shown(sum: org.json.JSONObject): String? = sum.optString("displaytitle").replace(Regex("<[^>]+>"), "").trim().ifBlank { null }
        ?: sum.optString("title").ifBlank { null }

    private suspend fun summaryOf(title: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
        wiki("https://en.wikipedia.org/api/rest_v1/page/summary/${Http.enc(title.replace(' ', '_'))}")
            ?.takeIf { it.optString("type") != "disambiguation" && it.optString("extract").isNotBlank() }
    }

    private suspend fun infoboxOf(title: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val html = wiki("https://en.wikipedia.org/w/api.php?action=parse&format=json&prop=text&section=0&redirects=1&page=${Http.enc(title)}")
            ?.optJSONObject("parse")?.optJSONObject("text")?.optString("*") ?: return@withContext emptyList()
        infobox(html)
    }

    /** One device's page, infobox and article text, read for the model asked. */
    suspend fun specs(said: String): Specs? = coroutineScope {
        val name = canonical(said)
        var sum = summaryOf(name)
        if (sum == null || !TECH_PAGE.containsMatchIn(sum.optString("description") + " " + sum.optString("extract").take(400))) {
            val p = Lookup.page(name, emptySet(), emptyList(), claimsAhead = false)
            sum = p?.let { summaryOf(it.title) } ?: sum
        }
        sum ?: return@coroutineScope null
        val page = shown(sum) ?: name
        // The infobox and the article text load together (two requests, within Wikimedia's limits).
        val boxAhead = async { infoboxOf(page) }
        val text = withContext(Dispatchers.IO) { Characters.wikiText(page) }
        val full = boxAhead.await()
        val m = model(name)
        val norm = { s: String -> s.lowercase(us).replace(Regex("[^a-z0-9+]"), "") }
        val single = norm(page) == norm(name) || m == null || m.variant.isEmpty() && !Regex("\\bseries\\b|\\blineup\\b", RegexOption.IGNORE_CASE).containsMatchIn(page)
        val rows = if (single) full else full.map { (k, v) -> k to forModel(v, m) }
        val url = sum.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")?.ifBlank { null }
            ?: "https://en.wikipedia.org/wiki/${Http.enc(page.replace(' ', '_'))}"
        Specs(if (single) page else name, page, sum.optString("description").ifBlank { null }, sum.optString("extract"),
            sum.optJSONObject("originalimage")?.optString("source")?.ifBlank { null } ?: sum.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null },
            url, rows, text, m, single, full)
    }

    private val TECH_PAGE = r("\\b(?:smartphone|phone|phablet|tablet|laptop|notebook|computer|console|handheld|graphics|gpu|processor|cpu|chip|soc|system on|microarchitecture|smartwatch|watch|earbuds|headphones|headset|device|camera|drone|e-reader|speaker|streaming|television|monitor|series of|line of|model|brand|electronics)\\b")

    // ------------------------------------------------------------------ answering

    suspend fun find(ask: TechAsk): LookupResult? = withContext(Dispatchers.IO) {
        when {
            ask.want == TechWant.TERM && ask.compare -> terms(ask)
            ask.want == TechWant.TERM -> term(ask)
            ask.devices.all { Laptops.match(it) != null } -> Laptops.find(ask) ?: if (ask.compare) compare(ask) else device(ask)
            ask.devices.any { PcParts.kind(it) != null } -> if (ask.compare) compareParts(ask) else part(ask)
            ask.compare -> compare(ask)
            else -> device(ask)
        }
    }

    /** A CPU, graphics card, SSD, memory kit, motherboard or power supply. */
    private suspend fun part(ask: TechAsk): LookupResult? {
        val p = PcParts.find(ask.devices.first()) ?: return null
        return LookupResult(p.name, p.description, null, "", p.image, p.url, p.rows, p.answer, sources = listOf(p.source),
            heading = p.kind.label.uppercase(us), more = p.notes)
    }

    /** Two parts head to head, one at a time (Wikimedia's request limits). */
    private suspend fun compareParts(ask: TechAsk): LookupResult? {
        val a = PcParts.find(ask.devices[0]) ?: return null
        val b = PcParts.find(ask.devices[1]) ?: return null
        val v = PcParts.versus(a, b)
        // "which has more vram": that spec's result; otherwise the overall verdict.
        val focus = PART_FOCUS.firstOrNull { it.first.containsMatchIn(ask.said) }?.second
        val bar = focus?.let { f -> v.bars.firstOrNull { it.label == f && it.winner != null } }
        val said = bar?.let { x ->
            val (w, l) = if (x.winner == 0) a.name to b.name else b.name to a.name
            val (ws, ls) = if (x.winner == 0) x.showA to x.showB else x.showB to x.showA
            "The $w has ${x.tag}: $ws vs $ls for the $l."
        } ?: v.verdict
        val notes = (a.notes + b.notes).distinctBy { it.first }.take(if (a.kind == b.kind) 1 else 2)
        return LookupResult("${a.name} vs ${b.name}", null, null, "", a.image ?: b.image, a.url, emptyList(), said,
            sources = listOf(a.source, b.source).distinct(), heading = "VERSUS", links = listOf(b.name to b.url), more = notes, versus = v)
    }

    private val PART_FOCUS = listOf(
        r("\\bvram\\b|video memory") to "VRAM", r("\\bcores?\\b") to "CORES", r("\\bthreads?\\b") to "THREADS",
        r("clock|ghz|faster single") to "BOOST", r("\\bcache\\b") to "CACHE", r("power|watt|tdp|efficien") to "POWER",
        r("bandwidth") to "BANDWID", r("\\bread\\b|\\bload") to "READ", r("\\bwrite\\b") to "WRITE",
        r("latency") to "TRUE LAT", r("\\bcheaper|price|cost") to "PRICE",
    )

    private suspend fun term(ask: TechAsk): LookupResult? {
        val t = term(ask.devices.first()) ?: return null
        val sum = summaryOf(t.title) ?: return null
        val img = if (t.imagePage == t.title) sum else summaryOf(t.imagePage)
        val image = img?.optJSONObject("originalimage")?.optString("source")?.ifBlank { null }
            ?: img?.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
            ?: sum.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
        val first = Animals.firstSentence(sum.optString("extract")) ?: sum.optString("extract")
        val title = t.name ?: shown(sum) ?: t.title
        // A named kind ("DDR5", "80 Plus Gold") is explained by its own lines, not the article's opening.
        val lead = if (t.name != null) emptyList() else listOf("WHAT IT IS" to listOf(Sports.brief(first, 240)))
        val more = lead + listOf("WHAT IT DOES" to listOf(t.does), "WHY IT MATTERS" to listOf(t.matters)) + GUIDES[t.title].orEmpty()
            .let { g -> if (t.name != null && t.title == "Power supply unit (computer)") emptyList() else g }
        return LookupResult(title, sum.optString("description").ifBlank { null }?.takeIf { t.name == null }, null, "", image,
            sum.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "https://en.wikipedia.org/wiki/${Http.enc(t.title.replace(' ', '_'))}",
            t.specs.map { it.label to it.text }, t.does.substringBefore(". ").trimEnd('.') + ".", sources = listOf("Wikipedia"),
            heading = "TECH", more = more)
    }

    /** A spec's number for its bar: "6,400 MT/s", "$65", "0.03 ms". */
    private fun brief(t: TermSpec): String {
        val v = t.value ?: return t.text.take(10)
        val unit = Regex("MT/s|GB/s|MB/s|ms|GB|TB|mm|V|W|%").find(t.text)?.value
        val n = if (v % 1.0 == 0.0) "%,d".format(us, v.toLong()) else v.toString()
        return when {
            t.text.contains("$") -> "$$n"
            unit == "%" -> "$n%"
            unit != null -> "$n $unit"
            else -> n
        }
    }

    private suspend fun terms(ask: TechAsk): LookupResult? {
        val (a, b) = ask.devices.map { term(it) }
        if (a == null || b == null) return null
        // Kinds with numbers ("ddr4 vs ddr5", "nvme vs sata", "gold vs platinum psu"): the VERSUS card.
        if (a.specs.isNotEmpty() && b.specs.isNotEmpty()) {
            val na = a.name ?: a.title; val nb = b.name ?: b.title
            val labels = (a.specs.map { it.label } + b.specs.map { it.label }).distinct()
            val bars = ArrayList<Versus.Bar>(); val words = ArrayList<Versus.Words>()
            for (l in labels) {
                val x = a.specs.firstOrNull { it.label == l }; val y = b.specs.firstOrNull { it.label == l }
                val p = x ?: y ?: continue
                if (x?.value != null && y?.value != null) bars += Versus.Bar(l, x.value, y.value, brief(x), brief(y), p.tag, p.lower)
                else words += Versus.Words(l, x?.text, y?.text)
            }
            // Bars show the short number; the full wording goes underneath.
            for (l in bars.map { it.label }) {
                val x = a.specs.first { it.label == l }; val y = b.specs.first { it.label == l }
                if (x.text.length > 10 || y.text.length > 10) words += Versus.Words(l, x.text, y.text)
            }
            val v = Versus(na, nb, bars, words, verdict(na, nb, bars))
            val img = summaryOf(a.imagePage)
            return LookupResult("$na vs $nb", null, null, "", img?.optJSONObject("thumbnail")?.optString("source"),
                "https://en.wikipedia.org/wiki/${Http.enc(a.title.replace(' ', '_'))}", emptyList(), v.verdict, sources = listOf("Wikipedia"),
                links = listOf(nb to "https://en.wikipedia.org/wiki/${Http.enc(b.title.replace(' ', '_'))}"),
                heading = "VERSUS", more = listOf(na.uppercase(us) to listOf(a.does), nb.uppercase(us) to listOf(b.does)), versus = v)
        }
        val sa = summaryOf(a.title); val sb = summaryOf(b.title)
        val img = summaryOf(a.imagePage)
        fun one(s: org.json.JSONObject?) = s?.optString("extract")?.let { Animals.firstSentence(it) }?.let { Sports.brief(it, 200) }
        val na = sa?.let { shown(it) } ?: a.title; val nb = sb?.let { shown(it) } ?: b.title
        return LookupResult("$na vs $nb", null, null, "", img?.optJSONObject("thumbnail")?.optString("source"),
            "https://en.wikipedia.org/wiki/${Http.enc(a.title.replace(' ', '_'))}", emptyList(),
            a.does.substringBefore(". ").trimEnd('.') + ". " + b.does.substringBefore(". ").trimEnd('.') + ".", sources = listOf("Wikipedia"), heading = "COMPARE",
            more = listOf(na.uppercase(us) to listOfNotNull(one(sa), a.does, a.matters), nb.uppercase(us) to listOfNotNull(one(sb), b.does, b.matters)))
    }

    /** The card rows for one device, in a fixed order. */
    fun rows(s: Specs): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun add(k: String, v: String?) { if (!v.isNullOrBlank()) out += k to v }
        add("RELEASE", s.year?.toString())
        add("OS", s.os)
        add("CHIP", s.chip)
        add("GPU", s.gpu?.takeIf { s.chip == null || !s.isPhone })
        add("RAM", s.ram)
        add("STORAGE", s.storage)
        val hz = s.hz
        val screen = listOfNotNull(s.screenSize?.let { "$it in" }, s.panel).joinToString(" ").ifBlank { null }
            ?: s.displayText?.let { tidy(it, 90) }
        add("SCREEN", screen)
        add("RES", listOfNotNull(s.resolution, s.ppi?.let { "$it ppi" }).joinToString(", ").ifBlank { if (screen != null) "Not listed" else null })
        add("REFRESH", when {
            hz == null -> if (screen != null && s.isPhone) "Not listed (likely 60 Hz)" else null
            hz.second != null && hz.second!! < hz.first -> "${hz.second}-${hz.first} Hz adaptive"
            else -> "${hz.first} Hz"
        })
        if (s.mah != null) add("BATTERY", "%,d mAh".format(us, s.mah)) else add("BATTERY", s.wh?.let { "$it Wh" } ?: s.row("Battery")?.let { tidy(it, 80) })
        val (wired, wireless, reverse) = s.watts
        add("CHARGE", listOfNotNull(wired?.let { "${fmt(it)} W wired" }, wireless?.let { "${fmt(it)} W wireless" }, reverse?.let { "${fmt(it)} W reverse" })
            .joinToString(", ").ifBlank { s.chargingText?.let { tidy(it, 80) } })
        add("CAMERA", s.rearCamera)
        add("SELFIE", s.frontCamera)
        add("UNLOCK", s.unlock.joinToString(", ").ifBlank { null })
        add("SPEAKER", s.speakers)
        add("USB", s.usb)
        add("WATER", s.water)
        add("WEIGHT", s.weight)
        // Consoles, graphics cards, laptops: the rest of their own spec rows.
        if (!s.isPhone) {
            val generic = listOf("CPU" to "CPU", "Graphics" to "GPU", "Memory" to "RAM", "Power" to "POWER", "Connectivity" to "CONNECT",
                "Online services" to "ONLINE", "Architecture" to "ARCH", "Fabrication process" to "PROCESS", "Transistors" to "TRANSIS", "Units sold" to "SOLD",
                "Introductory price" to "LAUNCH", "Manufacturer" to "MAKER", "Developer" to "MAKER", "Controller input" to "INPUT", "Media" to "MEDIA")
            for ((label, key) in generic) if (out.none { it.first == key }) s.row(label)?.let { add(key, tidy(it, 100)) }
        }
        return out
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    /** What sets this model apart from the rest of its lineup ("Biggest battery: 5,000 mAh vs 4,300-4,900 mAh"). */
    fun standouts(s: Specs): List<String> {
        val m = s.model ?: return emptyList()
        if (s.single) return emptyList()
        val out = ArrayList<String>()
        fun numbers(row: String, unit: String): Pair<Double?, List<Double>>? {
            val v = s.fullRows.firstOrNull { it.first.equals(row, true) }?.second ?: return null
            val segs = v.split(" ¦ ")
            val mine = ArrayList<Double>(); val others = ArrayList<Double>()
            var current: Boolean? = null
            val rx = Regex("(\\d[\\d,]*(?:\\.\\d+)?) ?$unit\\b", RegexOption.IGNORE_CASE)
            for (seg in segs) {
                val h = Regex("^([^:]{1,80}):\\s*(.*)$").find(seg)
                val body = if (h != null && Regex("(?<![a-z0-9])${Regex.escape(m.base)}", RegexOption.IGNORE_CASE).containsMatchIn(h.groupValues[1])) {
                    current = labelHas(h.groupValues[1], m); h.groupValues[2]
                } else seg
                if (Regex("wireless|reverse", RegexOption.IGNORE_CASE).containsMatchIn(body) && unit == "W") continue
                val n = rx.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                when (current) { true -> mine += n; false -> others += n; null -> {} }
            }
            return mine.maxOrNull() to others
        }
        fun note(row: String, unit: String, what: String, show: (Double) -> String) {
            val (mine, others) = numbers(row, unit) ?: return
            if (mine != null && others.isNotEmpty() && mine > others.max()) out += "$what: ${show(mine)} (others ${show(others.min())}${if (others.max() != others.min()) "-" + show(others.max()) else ""})"
        }
        note("Battery", "mAh", "Biggest battery in the lineup") { "%,d mAh".format(us, it.toInt()) }
        note("Charging", "W", "Fastest wired charging") { "${fmt(it)} W" }
        note("Memory", "GB", "Most RAM") { "${fmt(it)} GB" }
        // Rows that name only this model ("S Pen (S26 Ultra)").
        for ((k, v) in s.fullRows) {
            val only = Regex("([^¦;,]+?)\\s*\\(([^)]*)\\)").findAll(v).firstOrNull { mm -> labelHas(mm.groupValues[2], m) && !labelHas(mm.groupValues[2], Model(m.base, emptySet())) }
            if (only != null) out += "${only.groupValues[1].trim()} (${k.lowercase(us)}), only on the ${m.label}"
        }
        // The article's own words: "The S26 Ultra features...", "...exclusive to the Ultra".
        // Sentences about this model ("The S26 Ultra features..."), not lineup history that names it in passing.
        val sents = sentences(s.text.orEmpty()).filter { (m.inText.find(it)?.range?.first ?: 999) < 40 && !Regex("\\brespectively\\b|\\bannounced\\b|\\bsucceed", RegexOption.IGNORE_CASE).containsMatchIn(it) && r("\\b(?:features?|introduc\\w+|first|only|exclusive\\w*|unique|adds?|added|new|larger|bigger|faster|upgraded|titanium|privacy display|s pen|periscope|built[- ]in)\\b").containsMatchIn(it) }
        out += sents.take(3).map { Sports.brief(it, 160) }
        return out.distinct().take(5)
    }

    /** What reviewers said: the article's reception sentences. */
    fun knownFor(s: Specs): List<String> {
        val text = s.text ?: return emptyList()
        val rec = Characters.sections(text).filter { Regex("reception|reviews?|critical|response", RegexOption.IGNORE_CASE).containsMatchIn(it.heading) }
        val sents = rec.flatMap { sentences(it.text) }.let { all -> about(all, s.model, s.single) }
            .filter { !Regex("\\b(?:sold|sales|units|shipments)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) || it.length < 200 }
        val picked = sents.filter { s.model == null || s.single || s.model.inText.containsMatchIn(it) }.ifEmpty { sents }.take(3)
        return picked.map { Sports.brief(it, 180) }
    }

    private fun spoken(s: Specs) = s.name.replace(Regex("^Samsung (?=Galaxy)"), "").replace(Regex("^Google (?=Pixel)"), "")

    private suspend fun device(ask: TechAsk): LookupResult? {
        val s = specs(ask.devices.first()) ?: return null
        val facts = rows(s)
        val n = spoken(s)
        val more = ArrayList<Pair<String, List<String>>>()
        val life = s.mah?.takeIf { s.isPhone }?.let { batteryLife(it, s.isApple) }
        if (life != null) more += "BATTERY LIFE (ESTIMATE)" to listOf("Light use: ${hours(life.first)}", "Medium use: ${hours(life.second)}", "Heavy use: ${hours(life.third)}")
        val unlock = s.unlock
        val how = unlockHow(unlock)
        if (how.isNotEmpty()) more += "HOW IT UNLOCKS" to how
        s.speakerQuality?.let { more += "SPEAKERS" to listOf(Sports.brief(it, 180)) }
        s.usb?.let { u -> if (!u.contains("Gbps") && !u.contains("Mbps")) more += "USB SPEED" to listOf("$u: the data speed isn't listed on Wikipedia.") }
        val known = knownFor(s)
        val stand = standouts(s)
        val variant = s.model?.variant?.firstOrNull { it in setOf("ultra", "pro", "max", "plus", "fe", "edge", "xl", "mini") }
        val listTitle = when {
            stand.isNotEmpty() && variant != null -> "WHAT MAKES IT ${if (variant == "plus") "PLUS" else variant.uppercase(us)}"
            else -> "KNOWN FOR"
        }
        val list = stand.ifEmpty { known }
        if (stand.isNotEmpty() && known.isNotEmpty()) more += "KNOWN FOR" to known

        val (wired, wireless, _) = s.watts
        val hz = s.hz
        // Short spoken answers: the card carries the details.
        val answer = when (ask.want) {
            TechWant.BATTERY -> s.mah?.let { mah -> "The $n has a ${"%,d".format(us, mah)} mAh battery" + (life?.let { ", about ${it.second} hours of medium use." } ?: ".") }
            TechWant.CHARGING -> if (wired != null || wireless != null) "The $n charges at up to ${listOfNotNull(wired?.let { "${fmt(it)} watts wired" }, wireless?.let { "${fmt(it)} wireless" }).joinToString(" and ")}." else null
            TechWant.DISPLAY -> listOfNotNull(s.screenSize?.let { "$it inch" }, hz?.let { "${it.first} hertz" }).takeIf { it.isNotEmpty() }?.let { "The $n has a ${it.joinToString(", ")} screen." }
            TechWant.REFRESH -> hz?.let { if (it.second != null && it.second!! < it.first) "${it.second} to ${it.first} hertz, adaptive." else "${it.first} hertz." }?.let { "The $n refreshes at $it" }
                ?: if (s.isPhone) "No refresh rate listed for the $n, so likely 60 hertz." else null
            TechWant.CHIP -> s.chip?.let { "The $n runs the ${chipName(it)}." }
            TechWant.MEMORY -> listOfNotNull(s.ram?.let { firstGb(it)?.let { g -> "$g GB of RAM" } }, s.storage?.let { "up to ${maxStorage(it)}" }).joinToString(", ").ifBlank { null }?.let { "The $n has $it." }
            TechWant.CAMERA -> s.mainMp?.let { mp -> "The $n has a $mp megapixel main camera" + (s.rearCamera?.split(", ")?.size?.takeIf { it > 1 }?.let { c -> " and ${c - 1} more on the back." } ?: ".") }
            TechWant.UNLOCK -> unlock.firstOrNull()?.let { "The $n unlocks with ${lowerFirst(it).substringBefore(" (")}." }
            TechWant.SPEAKERS -> s.speakers?.let { "The $n has ${lowerFirst(it.substringBefore(";"))}." }
            TechWant.PORT -> s.usb?.let { "The $n uses ${it.substringBefore(",")}" + (Regex("\\(([^)]*)\\)").find(it)?.let { m -> ", ${m.groupValues[1].substringBefore(",")}." } ?: ".") }
            TechWant.DESIGN -> listOfNotNull(s.weight?.let { "weighs $it" }, s.water?.let { "is $it rated" }).joinToString(" and ").ifBlank { null }?.let { "The $n $it." }
            else -> null
        } ?: overview(s, n, hz)
        return LookupResult(s.name, s.description ?: if (!s.single) "Part of the ${s.page} lineup" else null, null, "", s.image, s.url, facts,
            answer, sources = listOf("Wikipedia"), list = list, listTitle = if (list.isEmpty()) null else listTitle, heading = "TECH", more = more)
    }

    /** "Qualcomm Snapdragon 8 Elite Gen 5 (3 nm); ..." -> "Snapdragon 8 Elite Gen 5". */
    private fun chipName(c: String) = c.substringBefore(";").substringBefore(" ¦").substringBefore(" (").replace(Regex("^(?:Qualcomm|Samsung|Google|Apple|MediaTek) "), "").trim()
    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase(us) } else s
    private fun firstGb(s: String) = Regex("(\\d+) ?GB", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull()
    /** The most storage a value lists, in GB (1 TB = 1024). */
    private fun storageGb(s: String): Int? = Regex("(\\d+) ?(GB|TB)\\b|(\\d+)(?=,| or)", RegexOption.IGNORE_CASE).findAll(s).mapNotNull { m ->
        val n = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return@mapNotNull null
        if (m.groupValues[2].equals("TB", true)) n * 1024 else n
    }.maxOrNull()
    private fun maxStorage(s: String) = storageGb(s)?.let { if (it >= 1024) "${it / 1024} TB" else "$it GB" } ?: s

    /** One line: what it is, then its headline specs. */
    private fun overview(s: Specs, n: String, hz: Pair<Int, Int?>?): String {
        val bits = listOfNotNull(s.chip?.let { chipName(it) }, s.mah?.let { "${"%,d".format(us, it)} mAh" },
            listOfNotNull(s.screenSize?.let { "$it inch" }, hz?.let { "${it.first} hertz" }).takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { "$it screen" })
        if (bits.size >= 2) return "The $n: ${bits.joinToString(", ")}."
        return Animals.firstSentence(s.intro)?.let { Sports.brief(it, 140) } ?: "Here's the $n."
    }

    /** How each unlock method works. */
    fun unlockHow(unlock: List<String>): List<String> = unlock.mapNotNull { u ->
        when {
            u.startsWith("Face ID") -> "Face ID: the TrueDepth camera's dot projector casts over 30,000 infrared dots to map your face in 3D, a flood illuminator lights it in the dark, and an infrared camera reads the pattern. The Secure Enclave matches it to your saved map, so photos and masks don't fool it (about a 1 in 1,000,000 false match)."
            u.startsWith("Ultrasonic") -> "Ultrasonic reader: sends sound pulses through the glass to map your fingerprint's ridges in 3D. It works with wet or greasy fingers and is hard to fool."
            u.startsWith("Optical") -> "Optical reader: lights your finger through the screen and takes a 2D picture of it. Fast, but less secure than ultrasonic and fussier with wet fingers."
            u.contains("power button") -> "Power-button reader: a capacitive sensor in the side key, so your thumb unlocks it as you press it. Quick and reliable."
            u.startsWith("Touch ID") -> "Touch ID: a capacitive sensor reads your fingerprint's ridges through a sapphire cover, and the Secure Enclave checks the match."
            u.startsWith("Rear") -> "Rear reader: a capacitive sensor on the back where your finger rests."
            u.startsWith("Face unlock") -> "Face unlock: uses the front camera's 2D image. Convenient, but on most phones not secure enough for payments."
            else -> null
        }
    }

    private suspend fun compare(ask: TechAsk): LookupResult? {
        // One device at a time, so Brick stays within Wikimedia's request limits.
        val a = specs(ask.devices[0]) ?: return null
        val b = specs(ask.devices[1]) ?: return null
        val v = versus(a, b)
        val more = ArrayList<Pair<String, List<String>>>()
        val how = unlockHow((a.unlock + b.unlock).distinct())
        if (how.isNotEmpty()) more += "HOW THEY UNLOCK" to how
        // A question about one part ("which has the better camera") gets that difference; otherwise the verdict.
        val focused = if (ask.want == TechWant.SPECS || ask.want == TechWant.OVERVIEW) null
            else differences(a, b, short(a), short(b), ask.want).firstOrNull()
        return LookupResult("${a.name} vs ${b.name}", null, null, "", a.image, a.url, emptyList(),
            focused ?: v.verdict, sources = listOf("Wikipedia"), heading = "VERSUS", links = listOf(b.name to b.url), more = more, versus = v)
    }

    /** The head-to-head card: numbers as bars, the rest side by side, and who wins more. */
    fun versus(a: Specs, b: Specs): Versus {
        val na = short(a); val nb = short(b)
        fun n(x: Number?) = x?.toDouble()
        val lifeA = a.mah?.takeIf { a.isPhone }?.let { batteryLife(it, a.isApple).second }
        val lifeB = b.mah?.takeIf { b.isPhone }?.let { batteryLife(it, b.isApple).second }
        val (wa, wla, _) = a.watts
        val (wb, wlb, _) = b.watts
        val ga = a.ram?.let { firstGb(it) }; val gb = b.ram?.let { firstGb(it) }
        val sa = a.storage?.let { storageGb(it) }; val sb = b.storage?.let { storageGb(it) }
        fun gbText(g: Int?) = g?.let { if (it >= 1024) "${it / 1024} TB" else "$it GB" }
        val bars = listOf(
            Versus.Bar("BATTERY", n(a.mah), n(b.mah), a.mah?.let { "%,d mAh".format(us, it) }, b.mah?.let { "%,d mAh".format(us, it) }, "a bigger battery"),
            Versus.Bar("DAY USE (EST.)", n(lifeA), n(lifeB), lifeA?.let { "~$it h" }, lifeB?.let { "~$it h" }, null),
            Versus.Bar("SCREEN", a.screenSize?.toDoubleOrNull(), b.screenSize?.toDoubleOrNull(), a.screenSize?.let { "$it in" }, b.screenSize?.let { "$it in" }, null),
            // A phone with no refresh rate listed is a standard 60 Hz screen.
            Versus.Bar("REFRESH", n(hzOf(a)), n(hzOf(b)), hzOf(a)?.let { (if (a.hz == null) "~" else "") + "$it Hz" }, hzOf(b)?.let { (if (b.hz == null) "~" else "") + "$it Hz" }, "a smoother screen"),
            Versus.Bar("CHARGING", wa, wb, wa?.let { "${fmt(it)} W" }, wb?.let { "${fmt(it)} W" }, "faster charging"),
            Versus.Bar("WIRELESS", wla, wlb, wla?.let { "${fmt(it)} W" }, wlb?.let { "${fmt(it)} W" }, "faster wireless charging"),
            Versus.Bar("CAMERA", n(a.mainMp), n(b.mainMp), a.mainMp?.let { "$it MP" }, b.mainMp?.let { "$it MP" }, "more camera megapixels"),
            Versus.Bar("RAM", n(ga), n(gb), gbText(ga), gbText(gb), "more RAM"),
            Versus.Bar("STORAGE", n(sa), n(sb), gbText(sa), gbText(sb), "more storage"),
        ).filter { it.a != null || it.b != null }
        val words = listOf(
            Versus.Words("CHIP", a.chip?.let { chipName(it) }, b.chip?.let { chipName(it) }),
            Versus.Words("UNLOCK", a.unlock.firstOrNull()?.substringBefore(" ("), b.unlock.firstOrNull()?.substringBefore(" (")),
            Versus.Words("USB", a.usb, b.usb),
            Versus.Words("OS", a.os, b.os),
            Versus.Words("SPEAKERS", a.speakers?.substringBefore(";"), b.speakers?.substringBefore(";")),
        ).filter { it.a != null || it.b != null }
        return Versus(listOfNotNull(na, a.year?.let { "($it)" }).joinToString(" "), listOfNotNull(nb, b.year?.let { "($it)" }).joinToString(" "), bars, words, verdict(na, nb, bars))
    }

    private fun hzOf(s: Specs) = s.hz?.first ?: if (s.isPhone && s.displayText != null) 60 else null

    /** "The Pixel 9a wins 5 of 6, with a bigger battery and a smoother screen." */
    fun verdict(na: String, nb: String, bars: List<Versus.Bar>): String {
        val scored = bars.filter { it.tag != null && it.winner != null }
        if (scored.isEmpty()) return "Here's how the $na and $nb line up."
        val aw = scored.filter { it.winner == 0 }; val bw = scored.filter { it.winner == 1 }
        if (aw.size == bw.size) return "It's even: ${aw.size} each. The $na has ${aw.firstOrNull()?.tag ?: "nothing extra"}; the $nb has ${bw.firstOrNull()?.tag ?: "nothing extra"}."
        val (w, wins) = if (aw.size > bw.size) na to aw else nb to bw
        val why = wins.take(2).mapNotNull { it.tag }.joinToString(" and ")
        // "B650 motherboards win", "the Pixel 9a wins".
        val last = w.substringAfterLast(' ')
        val plural = last.length > 3 && last.endsWith("s") && !last.endsWith("ss") && last[0].isLowerCase() && last != "series"
        return "The $w ${if (plural) "win" else "wins"} ${wins.size} of ${scored.size}, with $why."
    }

    private fun short(s: Specs) = s.name.replace(Regex("^(?:Samsung |Google |Apple |Sony |Microsoft |Nintendo )"), "")

    /** The differences worth saying, biggest first. */
    fun differences(a: Specs, b: Specs, na: String, nb: String, want: TechWant): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        fun num(x: Int?, y: Int?, priority: Int, say: (String, String, Int, Int) -> String) {
            if (x == null || y == null || x == y) return
            out += priority to if (x > y) say(na, nb, x, y) else say(nb, na, y, x)
        }
        num(a.year, b.year, if (want == TechWant.SPECS || want == TechWant.OVERVIEW) 9 else 2) { w, l, x, y -> "The $w is newer ($x vs $y)." }
        num(a.mah, b.mah, if (want == TechWant.BATTERY) 20 else 8) { w, l, x, y -> "The $w has the bigger battery: ${"%,d".format(us, x)} vs ${"%,d".format(us, y)} mAh${if (a.isApple != b.isApple) " (iPhones get more out of each mAh, so the gap is smaller in use)" else ""}." }
        num(a.hz?.first, b.hz?.first, if (want == TechWant.REFRESH || want == TechWant.DISPLAY) 20 else 7) { w, l, x, y -> "The $w's screen is smoother: $x Hz vs $y Hz." }
        if (a.isPhone && b.isPhone && (a.hz == null) != (b.hz == null)) {
            val (w, x, l) = if (a.hz != null) Triple(na, a.hz!!.first, nb) else Triple(nb, b.hz!!.first, na)
            if (x > 60) out += 7 to "The $w's screen runs at $x Hz; the $l's is a standard 60 Hz."
        }
        num(a.watts.first?.toInt(), b.watts.first?.toInt(), if (want == TechWant.CHARGING) 20 else 6) { w, l, x, y -> "The $w charges faster: $x W vs $y W wired." }
        num(a.mainMp, b.mainMp, if (want == TechWant.CAMERA) 20 else 5) { w, l, x, y -> "The $w's main camera has more megapixels: $x MP vs $y MP." }
        val sa = a.screenSize?.toDoubleOrNull(); val sb = b.screenSize?.toDoubleOrNull()
        if (sa != null && sb != null && sa != sb) out += (if (want == TechWant.DISPLAY) 19 else 4) to (if (sa > sb) "The $na has the bigger screen: $sa vs $sb inches." else "The $nb has the bigger screen: $sb vs $sa inches.")
        val ua = a.unlock.firstOrNull(); val ub = b.unlock.firstOrNull()
        if (ua != null && ub != null && ua != ub) out += (if (want == TechWant.UNLOCK) 20 else 3) to "The $na unlocks with ${ua.replaceFirstChar { it.lowercase(us) }.replace("face ID", "Face ID").replace("touch ID", "Touch ID")}; the $nb uses ${ub.replaceFirstChar { it.lowercase(us) }.replace("face ID", "Face ID").replace("touch ID", "Touch ID")}."
        val pa = a.usb; val pb = b.usb
        if (pa != null && pb != null && pa != pb) out += (if (want == TechWant.PORT) 20 else 2) to "Ports: the $na has $pa; the $nb has $pb."
        val ca = a.chip?.substringBefore(";")?.substringBefore(" ¦"); val cb = b.chip?.substringBefore(";")?.substringBefore(" ¦")
        if (ca != null && cb != null && ca != cb) out += (if (want == TechWant.CHIP) 20 else 1) to "The $na runs the $ca; the $nb runs the $cb."
        return out.sortedByDescending { it.first }.map { it.second }
    }
}

/** A head-to-head of two devices: [bars] are numbers drawn side by side (the bigger one wins), [words] the rest. */
data class Versus(val a: String, val b: String, val bars: List<Bar>, val words: List<Words>, val verdict: String) {
    /** [tag] says what winning means ("bigger battery"); null when bigger isn't better (screen size) or it's an estimate. */
    data class Bar(val label: String, val a: Double?, val b: Double?, val showA: String?, val showB: String?, val tag: String?, val lower: Boolean = false) {
        /** 0 when A wins (bigger, or smaller when [lower]), 1 when B does, null on a tie or a missing value. */
        val winner: Int? get() = if (a == null || b == null || a == b) null else if ((a > b) != lower) 0 else 1
    }
    data class Words(val label: String, val a: String?, val b: String?)
}
