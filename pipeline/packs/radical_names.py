"""Curated English names for the 214 Kangxi radicals and for common components.

`RADICAL_NAMES[n - 1]` names Kangxi radical n. A name applies to every form of the radical: the
Kangxi Radicals block character, the unified ideograph, and positional / simplified forms (any
character whose kRSUnicode residual stroke count is 0, e.g. 氵 85.0, 讠 149'.0).

`COMPONENT_NAMES` names individual characters; it overrides the radical-derived name.
"""

RADICAL_NAMES = """
one|line|dot|slash|second|hook|two|lid|person|legs
enter|eight|down box|cover|ice|table|open box|knife|power|wrap
spoon|right open box|hiding enclosure|ten|divination|seal|cliff|private|right hand|mouth
enclosure|earth|scholar|go|go slowly|evening|big|woman|child|roof
inch|small|lame|corpse|sprout|mountain|river|work|oneself|cloth
dry|short thread|shelter|long stride|two hands|shoot|bow|snout|bristle|step
heart|halberd|door|hand|branch|strike|script|dipper|axe|square
not|sun|say|moon|tree|yawn|stop|death|weapon|do not
compare|fur|clan|steam|water|fire|claw|father|trigrams|split wood
slice|fang|cow|dog|profound|jade|melon|tile|sweet|life
use|field|bolt of cloth|sickness|footsteps|white|skin|dish|eye|spear
arrow|stone|spirit|track|grain|cave|stand|bamboo|rice|silk
jar|net|sheep|feather|old|and|plow|ear|brush|meat
minister|self|arrive|mortar|tongue|oppose|boat|stopping|color|grass
tiger|insect|blood|go|clothes|cover (west)|see|horn|speech|valley
bean|pig|badger|shell|red|run|foot|body|cart|bitter
morning|walk|city|wine|distinguish|village|metal|long|gate|mound
slave|short-tailed bird|rain|blue-green|wrong|face|leather|tanned leather|leek|sound
page (head)|wind|fly|eat|head|fragrant|horse|bone|tall|hair
fight|sacrificial wine|cauldron|ghost|fish|bird|salt|deer|wheat|hemp
yellow|millet|black|embroidery|frog|tripod|drum|rat|nose|even
tooth|dragon|turtle|flute
""".replace("\n", "|").strip("|").split("|")

assert len(RADICAL_NAMES) == 214, len(RADICAL_NAMES)

COMPONENT_NAMES = {
    # positional forms whose name needs the position spelled out
    "阝": "mound (left) / city (right)",
    "⻖": "mound (left form)",
    "⻏": "city (right form)",
    "月": "moon / meat",
    "⺼": "meat (side form)",
    "亻": "person (left form)",
    "𠆢": "person (top form)",
    "氵": "water (left form)",
    "氺": "water (bottom form)",
    "扌": "hand (left form)",
    "龵": "hand (top form)",
    "忄": "heart (left form)",
    "⺗": "heart (bottom form)",
    "㣺": "heart (bottom form)",
    "艹": "grass (top form)",
    "䒑": "grass (top form)",
    "刂": "knife (right form)",
    "⺈": "knife (top form)",
    "𠂊": "knife (top form)",
    "灬": "fire (bottom form)",
    "犭": "dog (left form)",
    "礻": "spirit (left form)",
    "衤": "clothes (left form)",
    "𧘇": "clothes (bottom form)",
    "讠": "speech (left form)",
    "訁": "speech (left form)",
    "饣": "eat (left form)",
    "飠": "eat (left form)",
    "纟": "silk (left form)",
    "糹": "silk (left form)",
    "钅": "metal (left form)",
    "釒": "metal (left form)",
    "辶": "walk (enclosing form)",
    "⻌": "walk (enclosing form)",
    "⻍": "walk (enclosing form)",
    "⻎": "walk (enclosing form)",
    "罒": "net (top form)",
    "⺲": "net (top form)",
    "⺫": "net (top form)",
    "爫": "claw (top form)",
    "⺤": "claw (top form)",
    "攵": "strike (right form)",
    "⺮": "bamboo (top form)",
    "𥫗": "bamboo (top form)",
    "牜": "cow (left form)",
    "⺧": "cow (top form)",
    "𧾷": "foot (left form)",
    "⻊": "foot (left form)",
    "覀": "cover (top form)",
    "⻗": "rain (top form)",
    "⺶": "sheep (top form)",
    "⺷": "sheep (top form)",
    "𦍌": "sheep (top form)",
    "王": "king / jade",
    "⺩": "jade (left form)",
    "丬": "split wood (left form)",
    "⺌": "small (top form)",
    "⺍": "small (top form)",
    "𭕄": "small (top form)",
    "尣": "lame",
    "⺊": "divination (top form)",
    "⺁": "cliff (variant)",
    "⺄": "second (hook form)",
    "乚": "second (hook form)",
    "⺀": "ice (two dots)",
    # common non-radical components
    "丷": "eight (top form)",
    "𠂉": "person (lying form, as in 每)",
    "𠂇": "left hand",
    "𠂆": "cliff (slanted form)",
    "龸": "small over cover (as in 堂)",
    "龶": "life (top form, as in 青)",
    "龷": "together (top form, as in 共)",
    "龹": "rolling hands (as in 卷)",
    "𡗗": "top of 春",
    "亼": "gather (top of 今, 合)",
    "龴": "top of 甬",
    "乂": "cross / mow",
    "㐅": "five (old form)",
    "丆": "top of 不",
    "冃": "hat",
    "𠔉": "top of 卷 (variant)",
    "𤴔": "bolt of cloth (bottom form, as in 定)",
    "𠃌": "bent hook",
    "𠄌": "upward hook",
    "𠃊": "corner",
    "乛": "bend",
    "丄": "above (old form)",
    "丅": "below (old form)",
    "𠀎": "bottom of 冓",
    "龰": "stop (bottom form, as in 走)",
    "𤣩": "jade (left form)",
    "㇀": "rising stroke",
    "㇒": "left-falling stroke",
    "㇏": "right-falling stroke",
    "㇔": "dot stroke",
    "㇐": "horizontal stroke",
    "㇑": "vertical stroke",
    "㇕": "horizontal-then-vertical stroke",
    "㇆": "horizontal-vertical-hook stroke",
    "㇇": "horizontal-then-left-falling stroke",
    "㇖": "horizontal-hook stroke",
    "㇚": "vertical-hook stroke",
    "㇗": "vertical-then-horizontal stroke",
    "㇄": "vertical-bend stroke",
    "㇟": "vertical-bend-hook stroke",
    "㇙": "vertical-rising stroke",
    "㇜": "left-falling-then-rising stroke",
    "㇛": "left-falling-then-dot stroke",
    "㇂": "slanting-hook stroke",
    "㇃": "lying-hook stroke",
    "㇁": "curved-hook stroke",
    "㇉": "vertical-turn-hook stroke",
    "㇈": "horizontal-turn-bend-hook stroke",
    "㇌": "horizontal-left-falling-bend-hook stroke",
    "㇋": "horizontal-turn-turn-left-falling stroke",
    "㇊": "horizontal-turn-rising stroke",
    "㇅": "horizontal-vertical-horizontal stroke",
    "㇍": "horizontal-vertical-bend stroke",
    "㇎": "horizontal-turn-turn-turn stroke",
    "㇡": "horizontal-turn-turn-turn-hook stroke",
    "㇞": "vertical-turn-turn stroke",
    "㇓": "vertical-left-falling stroke",
    "㇝": "rising-then-right-falling stroke",
    "㇘": "vertical-bend-turn stroke",
    "㇠": "horizontal-slanting-bend-hook stroke",
    "㇢": "left-falling-hook stroke",
    "㇣": "circle stroke",
}
