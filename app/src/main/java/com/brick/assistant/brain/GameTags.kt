package com.brick.assistant.brain

/** Steam's user tags (id:name), most used first, from store.steampowered.com/tagdata/populartags. */
internal object GameTags {
    private const val RAW =
        "492:Indie|19:Action|21:Adventure|597:Casual|4182:Singleplayer|599:Simulation|9:Strategy|122:RPG|3871:2D||" +
        "4191:3D|493:Early Access|113:Free to Play|4166:Atmospheric|4305:Colorful|1742:Story Rich|3859:Multiplayer||" +
        "3834:Exploration|1684:Fantasy|4726:Cute|3964:Pixel Graphics|3839:First-Person|3993:Combat|1664:Puzzle||" +
        "4252:Stylized|1654:Relaxing|4136:Funny|4106:Action-Adventure|1773:Arcade|7481:Controller|6730:PvE|4085:Anime||" +
        "1685:Co-op|1667:Horror|3942:Sci-fi|6426:Choices Matter|4791:Top-Down|701:Sports|1697:Third Person||" +
        "5350:Family Friendly|4004:Retro|128:Massively Multiplayer|1775:PvP|1774:Shooter|4667:Violent||" +
        "7208:Female Protagonist|3843:Online Co-Op|4342:Dark|12095:Sexual Content|4175:Realistic||" +
        "4747:Character Customization|5716:Mystery|6971:Multiple Endings|3968:Physics|699:Racing|7250:Linear||" +
        "1662:Survival|1719:Comedy|1695:Open World|4195:Cartoony|6650:Nudity|1721:Psychological Horror||" +
        "3799:Visual Novel|8945:Resource Management|1716:Roguelike|3959:Roguelite|4345:Gore|4057:Magic|12472:Management||" +
        "1625:Platformer|3810:Sandbox|5379:2D Platformer|1708:Tactical|4172:Medieval|3916:Old School|6815:Hand-drawn||" +
        "1663:FPS|4094:Minimalist|1702:Crafting|1643:Building|4325:Turn-Based Combat|9204:Immersive Sim|4231:Action RPG||" +
        "1741:Turn-Based Strategy|4295:Futuristic|4604:Dark Fantasy|1698:Point & Click|5125:Procedural Generation||" +
        "5608:Emotional|42804:Action Roguelike|4562:Cartoon|1755:Space|4026:Difficult|30358:Nature|6129:Logic||" +
        "560542:Incremental|14139:Turn-Based Tactics|11014:Interactive Fiction|5395:3D Platformer|4947:Romance||" +
        "4486:Choose Your Own Adventure|3978:Survival Horror|87:Utilities|7332:Base Building|7368:Local Multiplayer||" +
        "6691:1990's|9130:Hentai|1710:Surreal|1646:Hack and Slash|4695:Economy|1738:Hidden Object|9551:Dating Sim||" +
        "3854:Lore-Rich|10235:Life Sim|3835:Post-apocalyptic|6276:Inventory Management|4885:Bullet Hell|615955:Idler||" +
        "5154:Score Attack|1720:Dungeon Crawler|1666:Card Game|17305:Strategy RPG|3798:Side Scroller|21978:VR||" +
        "1036:Education|5900:Walking Simulator|17389:Tabletop|4145:Cinematic|5537:Puzzle Platformer||" +
        "42152:Dialogue Heavy|1678:War|97376:Cozy|31275:Text-Based|5186:Psychological|1687:Stealth|4255:Shoot 'Em Up||" +
        "1659:Zombies|8369:Investigation|44868:LGBTQ+|4434:JRPG|3841:Local Co-Op|10808:Supernatural|3987:Historical||" +
        "4975:2.5D|7926:Artificial Intelligence|4064:Thriller|32322:Deckbuilding|84:Design & Illustration|7743:1980s||" +
        "16689:Time Management|10695:Party-Based RPG|1756:Great Soundtrack|5851:Isometric|5923:Dark Humor||" +
        "12057:Tutorial|4236:Loot|4711:Replay Value|5711:Team-Based|6869:Nonlinear|4168:Military|5652:Collectathon||" +
        "4637:Top-Down Shooter|1677:Turn-Based|3814:Third-Person Shooter|9541:Demons|1673:Aliens|21725:Tactical RPG||" +
        "4115:Cyberpunk|5752:Robots|5613:Detective|1645:Tower Defense|255534:Automation|5673:Modern||" +
        "3813:Real Time Tactics|8013:Software|1759:Perma Death|5030:Dystopian |1644:Driving|4400:Abstract||" +
        "1770:Board Game|791774:Card Battler|1676:RTS|4474:CRPG|5547:Arena Shooter|3877:Precision Platformer||" +
        "4328:City Builder|1751:Comic Book|4845:Capitalism|29482:Souls-like|1714:Psychedelic|17894:Cats||" +
        "4508:Co-op Campaign|7178:Party Game|16094:Mythology|4684:Wargame|5363:Destruction|10397:Memes||" +
        "916648:Creature Collector|4840:4 Player Local|4598:Alternate History|7569:Grid-Based Movement|6378:Crime||" +
        "4155:Class-Based|8122:Level Editor|4046:Dragons|13906:Game Development|1734:Fast-Paced|4234:Short||" +
        "1669:Moddable|1084988:Auto Battler|4202:Trading|15045:Flight|4036:Parkour|4158:Beat 'em up||" +
        "872:Animation & Modeling|1621:Music|552282:Wholesome|1628:Metroidvania|19995:Dark Comedy|3920:Cooking||" +
        "15277:Philosophical|8666:Runner|87918:Farming Sim|4736:2D Fighter|1100687:Automobile Sim|1752:Rhythm||" +
        "3878:Competitive|5765:Gun Customization|7702:Narrative|11104:Vehicular Combat|6506:3D Fighter|7948:Soundtrack||" +
        "4608:Swordplay|220585:Colony Sim|5055:eSports|1743:Fighting|5794:Science|6052:Noir|5372:Conspiracy|1754:MMORPG||" +
        "5981:Mining|4559:Quick-Time Events|7432:Lovecraftian|35079:Job Simulator|24003:Word Game|1651:Satire||" +
        "1091588:Roguelike Deckbuilder|4758:Twin Stick Shooter|3952:Gothic|91114:Shop Keeper|16598:Space Sim||" +
        "4364:Grand Strategy|4878:Parody |21006:Underground|9592:Dynamic Narration|9626:Animals|15564:Fishing||" +
        "784:Video Production|353880:Looter Shooter|22602:Agriculture|1027:Audio Production|3934:Immersive||" +
        "10816:Split Screen|13782:Experimental|16250:Gambling|176981:Battle Royale|4835:6DOF|5796:Bullet Time||" +
        "198631:Mystery Dungeon|1693:Classic|4150:World War II|6625:Time Manipulation|1732:Voxel|6915:Martial Arts||" +
        "17770:Asynchronous Multiplayer|10383:Transportation|723991:Bullet Heaven|4821:Mechs|4777:Spectacle fighter||" +
        "1637:Dogs|4102:Combat Racing|1723:Action RTS|1665:Match 3|18594:FMV|5411:Beautiful|620519:Hero Shooter||" +
        "9271:Trading Card Game|1100689:Open World Survival Craft|9157:Underwater|5300:God Game|10679:Time Travel||" +
        "12686:Vampires|1688:Ninja|1681:Pirates|13070:Solitaire|1717:Hex Grid|26921:Political Sim|1777:Steampunk||" +
        "31579:Otome|1023537:Boomer Shooter|5502:Hacking|9564:Hunting|7108:Party|1616:Trains|1674:Typing||" +
        "1445:Software Training|180368:Faith|13276:Tanks|5432:Programming|1670:4X|11123:Mouse Only|6310:Diplomacy||" +
        "8093:Minigames|1718:MOBA|1730:Sokoban|97070:Assassins|14720:Nostalgia|5708:Remake|9803:Snow|1647:Western||" +
        "809:Photo Editing|1671:Superhero|5160:Dinosaurs|1320952:Desktop Companion|1680:Heist|6910:Naval||" +
        "454187:Traditional Roguelike|769306:Escape Room|1239876:Organizing|5179:Cold War|3955:Character Action Game||" +
        "13577:Sailing|4994:Naval Combat|4520:Farming|4137:Transhumanism|8253:Music-Based Procedural Generation||" +
        "13382:Archery|23491:Cleaning|1254546:Football (Soccer)|6041:Horses|42089:Jump Scare|4161:Real-Time||" +
        "4190:Addictive|889937:Decorating|10437:Trivia|4291:Spaceships|1220528:Hobby Sim|5390:Time Attack|7622:Offroad||" +
        "17015:Werewolves|7107:Real-Time with Pause|11333:Villain Protagonist|4242:Episodic|3965:Epic|7423:Sniper||" +
        "7556:Dice|56690:On-Rails Shooter|71389:Spelling|4535:Dwarves|5230:Sequel|1199779:Extraction Shooter||" +
        "1100686:Outbreak Sim|11095:Boss Rush|4184:Chess|745697:Social Deduction|5382:World War I|6702:Mars||" +
        "12190:Boxing|1100688:Medical Sim|1746:Basketball|19780:Submarine|7038:Golf|5348:Mod|25085:Touch-Friendly||" +
        "198913:Motorbike|6054:Elves|150626:Gaming|6621:Pinball|6948:Rome|5727:Baseball|776177:360 Video|760247:Xianxia||" +
        "61357:Electronic Music|123332:Bikes|52406:Cult|25959:Wuxia|47827:Wrestling|856791:Asymmetric VR|11634:Vikings||" +
        "6835:Poker|1753:Skateboarding|6214:Birds|1776:Espionage|4852:Billiards|37376:Falling Blocks||" +
        "189941:Instrumental Music|15954:Silent Protagonist|22955:Mini Golf|1254552:Football (American)|96359:Skating||" +
        "10617:Samurai|337964:Rock Music|19568:Cycling|33572:Mahjong|507423:Foxes|5914:Tennis|21635:Language Learning||" +
        "15868:Motocross|27758:Voice Control|14906:Intentionally Awkward Controls|603297:Hardware|8075:TrackIR||" +
        "324176:Hockey|7328:Bowling|117648:8-bit Music|7309:Skiing|28444:Snowboarding|3796:Based On A Novel||" +
        "17337:Lemmings|129761:ATV|20486:Wolves|323922:Musou|252854:BMX|1352486:Capybaras|46348:Zoo|5407:Benchmark||" +
        "847164:Volleyball|158638:Cricket|49213:Rugby|5941:Reboot"

    /** Tag name to Steam tag id. */
    val ALL: List<Pair<String, Int>> = RAW.split('|').mapNotNull { e -> e.substringAfter(':', "").takeIf { it.isNotBlank() }?.let { it to e.substringBefore(':').toInt() } }
}
