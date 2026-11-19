package com.storageplanner.core;

/** The 1000 ImageNet class names in SceneNet's order (Keras's imagenet_class_index.json), for the Algorithm Lab. */
public final class ImageNetClasses {
    private ImageNetClasses() {}

    private static String[] names;

    /** Name of class i ("golden retriever", "pizza", ...). */
    public static synchronized String name(int i) {
        if (names == null) names = ALL.split("\\|");
        return i >= 0 && i < names.length ? names[i] : "class " + i;
    }

    private static final String ALL =
        "tench|goldfish|great white shark|tiger shark|hammerhead|electric ray|stingray|cock|hen|ostrich|brambling|goldf" +
        "inch|house finch|junco|indigo bunting|robin|bulbul|jay|magpie|chickadee|water ouzel|kite|bald eagle|vulture|gr" +
        "eat grey owl|European fire salamander|common newt|eft|spotted salamander|axolotl|bullfrog|tree frog|tailed fro" +
        "g|loggerhead|leatherback turtle|mud turtle|terrapin|box turtle|banded gecko|common iguana|American chameleon|w" +
        "hiptail|agama|frilled lizard|alligator lizard|Gila monster|green lizard|African chameleon|Komodo dragon|Africa" +
        "n crocodile|American alligator|triceratops|thunder snake|ringneck snake|hognose snake|green snake|king snake|g" +
        "arter snake|water snake|vine snake|night snake|boa constrictor|rock python|Indian cobra|green mamba|sea snake|" +
        "horned viper|diamondback|sidewinder|trilobite|harvestman|scorpion|black and gold garden spider|barn spider|gar" +
        "den spider|black widow|tarantula|wolf spider|tick|centipede|black grouse|ptarmigan|ruffed grouse|prairie chick" +
        "en|peacock|quail|partridge|African grey|macaw|sulphur-crested cockatoo|lorikeet|coucal|bee eater|hornbill|humm" +
        "ingbird|jacamar|toucan|drake|red-breasted merganser|goose|black swan|tusker|echidna|platypus|wallaby|koala|wom" +
        "bat|jellyfish|sea anemone|brain coral|flatworm|nematode|conch|snail|slug|sea slug|chiton|chambered nautilus|Du" +
        "ngeness crab|rock crab|fiddler crab|king crab|American lobster|spiny lobster|crayfish|hermit crab|isopod|white" +
        " stork|black stork|spoonbill|flamingo|little blue heron|American egret|bittern|crane|limpkin|European gallinul" +
        "e|American coot|bustard|ruddy turnstone|red-backed sandpiper|redshank|dowitcher|oystercatcher|pelican|king pen" +
        "guin|albatross|grey whale|killer whale|dugong|sea lion|Chihuahua|Japanese spaniel|Maltese dog|Pekinese|Shih-Tz" +
        "u|Blenheim spaniel|papillon|toy terrier|Rhodesian ridgeback|Afghan hound|basset|beagle|bloodhound|bluetick|bla" +
        "ck-and-tan coonhound|Walker hound|English foxhound|redbone|borzoi|Irish wolfhound|Italian greyhound|whippet|Ib" +
        "izan hound|Norwegian elkhound|otterhound|Saluki|Scottish deerhound|Weimaraner|Staffordshire bullterrier|Americ" +
        "an Staffordshire terrier|Bedlington terrier|Border terrier|Kerry blue terrier|Irish terrier|Norfolk terrier|No" +
        "rwich terrier|Yorkshire terrier|wire-haired fox terrier|Lakeland terrier|Sealyham terrier|Airedale|cairn|Austr" +
        "alian terrier|Dandie Dinmont|Boston bull|miniature schnauzer|giant schnauzer|standard schnauzer|Scotch terrier" +
        "|Tibetan terrier|silky terrier|soft-coated wheaten terrier|West Highland white terrier|Lhasa|flat-coated retri" +
        "ever|curly-coated retriever|golden retriever|Labrador retriever|Chesapeake Bay retriever|German short-haired p" +
        "ointer|vizsla|English setter|Irish setter|Gordon setter|Brittany spaniel|clumber|English springer|Welsh spring" +
        "er spaniel|cocker spaniel|Sussex spaniel|Irish water spaniel|kuvasz|schipperke|groenendael|malinois|briard|kel" +
        "pie|komondor|Old English sheepdog|Shetland sheepdog|collie|Border collie|Bouvier des Flandres|Rottweiler|Germa" +
        "n shepherd|Doberman|miniature pinscher|Greater Swiss Mountain dog|Bernese mountain dog|Appenzeller|EntleBucher" +
        "|boxer|bull mastiff|Tibetan mastiff|French bulldog|Great Dane|Saint Bernard|Eskimo dog|malamute|Siberian husky" +
        "|dalmatian|affenpinscher|basenji|pug|Leonberg|Newfoundland|Great Pyrenees|Samoyed|Pomeranian|chow|keeshond|Bra" +
        "bancon griffon|Pembroke|Cardigan|toy poodle|miniature poodle|standard poodle|Mexican hairless|timber wolf|whit" +
        "e wolf|red wolf|coyote|dingo|dhole|African hunting dog|hyena|red fox|kit fox|Arctic fox|grey fox|tabby|tiger c" +
        "at|Persian cat|Siamese cat|Egyptian cat|cougar|lynx|leopard|snow leopard|jaguar|lion|tiger|cheetah|brown bear|" +
        "American black bear|ice bear|sloth bear|mongoose|meerkat|tiger beetle|ladybug|ground beetle|long-horned beetle" +
        "|leaf beetle|dung beetle|rhinoceros beetle|weevil|fly|bee|ant|grasshopper|cricket|walking stick|cockroach|mant" +
        "is|cicada|leafhopper|lacewing|dragonfly|damselfly|admiral|ringlet|monarch|cabbage butterfly|sulphur butterfly|" +
        "lycaenid|starfish|sea urchin|sea cucumber|wood rabbit|hare|Angora|hamster|porcupine|fox squirrel|marmot|beaver" +
        "|guinea pig|sorrel|zebra|hog|wild boar|warthog|hippopotamus|ox|water buffalo|bison|ram|bighorn|ibex|hartebeest" +
        "|impala|gazelle|Arabian camel|llama|weasel|mink|polecat|black-footed ferret|otter|skunk|badger|armadillo|three" +
        "-toed sloth|orangutan|gorilla|chimpanzee|gibbon|siamang|guenon|patas|baboon|macaque|langur|colobus|proboscis m" +
        "onkey|marmoset|capuchin|howler monkey|titi|spider monkey|squirrel monkey|Madagascar cat|indri|Indian elephant|" +
        "African elephant|lesser panda|giant panda|barracouta|eel|coho|rock beauty|anemone fish|sturgeon|gar|lionfish|p" +
        "uffer|abacus|abaya|academic gown|accordion|acoustic guitar|aircraft carrier|airliner|airship|altar|ambulance|a" +
        "mphibian|analog clock|apiary|apron|ashcan|assault rifle|backpack|bakery|balance beam|balloon|ballpoint|Band Ai" +
        "d|banjo|bannister|barbell|barber chair|barbershop|barn|barometer|barrel|barrow|baseball|basketball|bassinet|ba" +
        "ssoon|bathing cap|bath towel|bathtub|beach wagon|beacon|beaker|bearskin|beer bottle|beer glass|bell cote|bib|b" +
        "icycle-built-for-two|bikini|binder|binoculars|birdhouse|boathouse|bobsled|bolo tie|bonnet|bookcase|bookshop|bo" +
        "ttlecap|bow|bow tie|brass|brassiere|breakwater|breastplate|broom|bucket|buckle|bulletproof vest|bullet train|b" +
        "utcher shop|cab|caldron|candle|cannon|canoe|can opener|cardigan|car mirror|carousel|carpenter's kit|carton|car" +
        " wheel|cash machine|cassette|cassette player|castle|catamaran|CD player|cello|cellular telephone|chain|chainli" +
        "nk fence|chain mail|chain saw|chest|chiffonier|chime|china cabinet|Christmas stocking|church|cinema|cleaver|cl" +
        "iff dwelling|cloak|clog|cocktail shaker|coffee mug|coffeepot|coil|combination lock|computer keyboard|confectio" +
        "nery|container ship|convertible|corkscrew|cornet|cowboy boot|cowboy hat|cradle|crane|crash helmet|crate|crib|C" +
        "rock Pot|croquet ball|crutch|cuirass|dam|desk|desktop computer|dial telephone|diaper|digital clock|digital wat" +
        "ch|dining table|dishrag|dishwasher|disk brake|dock|dogsled|dome|doormat|drilling platform|drum|drumstick|dumbb" +
        "ell|Dutch oven|electric fan|electric guitar|electric locomotive|entertainment center|envelope|espresso maker|f" +
        "ace powder|feather boa|file|fireboat|fire engine|fire screen|flagpole|flute|folding chair|football helmet|fork" +
        "lift|fountain|fountain pen|four-poster|freight car|French horn|frying pan|fur coat|garbage truck|gasmask|gas p" +
        "ump|goblet|go-kart|golf ball|golfcart|gondola|gong|gown|grand piano|greenhouse|grille|grocery store|guillotine" +
        "|hair slide|hair spray|half track|hammer|hamper|hand blower|hand-held computer|handkerchief|hard disc|harmonic" +
        "a|harp|harvester|hatchet|holster|home theater|honeycomb|hook|hoopskirt|horizontal bar|horse cart|hourglass|iPo" +
        "d|iron|jack-o'-lantern|jean|jeep|jersey|jigsaw puzzle|jinrikisha|joystick|kimono|knee pad|knot|lab coat|ladle|" +
        "lampshade|laptop|lawn mower|lens cap|letter opener|library|lifeboat|lighter|limousine|liner|lipstick|Loafer|lo" +
        "tion|loudspeaker|loupe|lumbermill|magnetic compass|mailbag|mailbox|maillot|maillot|manhole cover|maraca|marimb" +
        "a|mask|matchstick|maypole|maze|measuring cup|medicine chest|megalith|microphone|microwave|military uniform|mil" +
        "k can|minibus|miniskirt|minivan|missile|mitten|mixing bowl|mobile home|Model T|modem|monastery|monitor|moped|m" +
        "ortar|mortarboard|mosque|mosquito net|motor scooter|mountain bike|mountain tent|mouse|mousetrap|moving van|muz" +
        "zle|nail|neck brace|necklace|nipple|notebook|obelisk|oboe|ocarina|odometer|oil filter|organ|oscilloscope|overs" +
        "kirt|oxcart|oxygen mask|packet|paddle|paddlewheel|padlock|paintbrush|pajama|palace|panpipe|paper towel|parachu" +
        "te|parallel bars|park bench|parking meter|passenger car|patio|pay-phone|pedestal|pencil box|pencil sharpener|p" +
        "erfume|Petri dish|photocopier|pick|pickelhaube|picket fence|pickup|pier|piggy bank|pill bottle|pillow|ping-pon" +
        "g ball|pinwheel|pirate|pitcher|plane|planetarium|plastic bag|plate rack|plow|plunger|Polaroid camera|pole|poli" +
        "ce van|poncho|pool table|pop bottle|pot|potter's wheel|power drill|prayer rug|printer|prison|projectile|projec" +
        "tor|puck|punching bag|purse|quill|quilt|racer|racket|radiator|radio|radio telescope|rain barrel|recreational v" +
        "ehicle|reel|reflex camera|refrigerator|remote control|restaurant|revolver|rifle|rocking chair|rotisserie|rubbe" +
        "r eraser|rugby ball|rule|running shoe|safe|safety pin|saltshaker|sandal|sarong|sax|scabbard|scale|school bus|s" +
        "chooner|scoreboard|screen|screw|screwdriver|seat belt|sewing machine|shield|shoe shop|shoji|shopping basket|sh" +
        "opping cart|shovel|shower cap|shower curtain|ski|ski mask|sleeping bag|slide rule|sliding door|slot|snorkel|sn" +
        "owmobile|snowplow|soap dispenser|soccer ball|sock|solar dish|sombrero|soup bowl|space bar|space heater|space s" +
        "huttle|spatula|speedboat|spider web|spindle|sports car|spotlight|stage|steam locomotive|steel arch bridge|stee" +
        "l drum|stethoscope|stole|stone wall|stopwatch|stove|strainer|streetcar|stretcher|studio couch|stupa|submarine|" +
        "suit|sundial|sunglass|sunglasses|sunscreen|suspension bridge|swab|sweatshirt|swimming trunks|swing|switch|syri" +
        "nge|table lamp|tank|tape player|teapot|teddy|television|tennis ball|thatch|theater curtain|thimble|thresher|th" +
        "rone|tile roof|toaster|tobacco shop|toilet seat|torch|totem pole|tow truck|toyshop|tractor|trailer truck|tray|" +
        "trench coat|tricycle|trimaran|tripod|triumphal arch|trolleybus|trombone|tub|turnstile|typewriter keyboard|umbr" +
        "ella|unicycle|upright|vacuum|vase|vault|velvet|vending machine|vestment|viaduct|violin|volleyball|waffle iron|" +
        "wall clock|wallet|wardrobe|warplane|washbasin|washer|water bottle|water jug|water tower|whiskey jug|whistle|wi" +
        "g|window screen|window shade|Windsor tie|wine bottle|wing|wok|wooden spoon|wool|worm fence|wreck|yawl|yurt|web" +
        " site|comic book|crossword puzzle|street sign|traffic light|book jacket|menu|plate|guacamole|consomme|hot pot|" +
        "trifle|ice cream|ice lolly|French loaf|bagel|pretzel|cheeseburger|hotdog|mashed potato|head cabbage|broccoli|c" +
        "auliflower|zucchini|spaghetti squash|acorn squash|butternut squash|cucumber|artichoke|bell pepper|cardoon|mush" +
        "room|Granny Smith|strawberry|orange|lemon|fig|pineapple|banana|jackfruit|custard apple|pomegranate|hay|carbona" +
        "ra|chocolate sauce|dough|meat loaf|pizza|potpie|burrito|red wine|espresso|cup|eggnog|alp|bubble|cliff|coral re" +
        "ef|geyser|lakeside|promontory|sandbar|seashore|valley|volcano|ballplayer|groom|scuba diver|rapeseed|daisy|yell" +
        "ow lady's slipper|corn|acorn|hip|buckeye|coral fungus|agaric|gyromitra|stinkhorn|earthstar|hen-of-the-woods|bo" +
        "lete|ear|toilet tissue";
}
