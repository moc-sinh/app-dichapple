// EnglishContextWords.kt: from Keyboard/TelexCore/EnglishContextWords.swift (VietTelex, MIT).
// Common English words for the engine's context-based decision; see the Swift original for notes.
package vn.mocsinh.mstrans.core.telex

object EnglishContextWords {
    val words: Set<String> by lazy { parse(WORDS) }
    val restoreOnly: Set<String> by lazy { parse(RESTORE_ONLY) }
    val neutralLoanwords: Set<String> by lazy { parse(NEUTRAL) }
    const val MAX_LENGTH = 12
    val functionWords: Set<String> by lazy { parse(FUNCTION) }
    val contractionTails: Set<String> by lazy { parse(TAILS) }

    /** One rule per line, `word: predecessor predecessor …`. At most 8 rules. */
    val predecessorGates: List<Pair<String, Set<String>>> by lazy {
        GATES.split('\n').mapNotNull { line ->
            val parts = line.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val word = parts[0].trim().split(' ').firstOrNull() ?: return@mapNotNull null
            val preds = parse(parts[1])
            if (preds.isEmpty()) null else word to preds
        }
    }

    fun gateIndex(w: String): Int? {
        for ((i, r) in predecessorGates.withIndex()) if (r.first == w) return i
        return null
    }

    fun predecessorBits(w: String): Int {
        var bits = 0
        for ((i, r) in predecessorGates.withIndex()) if (w in r.second) bits = bits or (1 shl i)
        return bits
    }

    private fun parse(list: String): Set<String> = list.split(' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()

    private const val GATES = """been: have has had having ve"""
    private const val TAILS = """ve re ll"""
    private const val NEUTRAL = """
app apps email mail wifi web blog file files link links game games chat team teams fan fans form
font menu tab tabs click share live stream video videos photo photos camera laptop phone sim online
offline admin mod spam virus download upload update comment comments post posts story feed group
groups page pages shop sale size order ship voucher deal combo view views like likes sub vlog idol
hot top vip pro logo banner code demo test server client account profile password login logout
macos ios windows android linux mac macbook imac iphone ipad ipod airpods apple gmail youtube
facebook messenger instagram tiktok telegram whatsapp viber zalo discord slack zoom chrome safari
firefox edge excel powerpoint outlook onedrive dropbox notion figma canva netflix spotify shopee
lazada tiki grab momo vnpay viettel vinaphone mobifone samsung xiaomi oppo huawei
"""
    private const val FUNCTION = """
i you he she it we they me him her us them my your his its our their mine yours hers ours theirs
this that these those who whom whose which what myself yourself himself herself itself ourselves
themselves is am are was were be been being do does did have has had having will would shall should
can could may might must an the some any every each all both few many much several such other
another of to in on at by for with from into onto over under about after before between through
during without within along across behind beyond upon and or but nor so yet if then than because
while when where why how though although unless until whether since as not there
"""
    private const val RESTORE_ONLY = """
a wow ok okay oh ah aha hey hi hello yay yeah yep nope oops ouch hmm huh haha hehe lol omg wtf bye
sorry thanks thank please welcome congrats cool wonderful awesome amazing
"""
    private const val WORDS = """
i you he she it we they me him her us them my your his its our their mine yours hers ours theirs
this that these those who whom whose which what myself yourself himself herself itself ourselves
themselves is am are was were be been being do does did done doing have has had having will would
shall should can could may might must get gets got go goes going make makes made take takes took
see saw seen know knew want wants need needs like likes use uses used say says said come comes came
think thinks look looks find found give gives tell work works call calls an the some any every each
all both half few many much more most several enough such other another same of to in on at by for
with from into onto up out off over under about after before between through during without within
along across behind beyond upon and or but nor so yet if then than because while when where why how
though although unless until whether since as not here there now just only also too very default
back down new old good great big small little long high low right left next last first one two
three again always never often still even much day time way man men people thing things runs loans
songs sons moms cams lens rays vans bans tins tans dams hams thus air ais ams ans arm asn asp bangs
barn beer beest bens best bins bits bons boost boots born burn cans caps cast cats chair chans
chaos charm chens chest chips choes choir chose cons corn cost cums cups cuts dans days deer deest
dims dist docs doms dons dust ems ens eos est gaps gary gays hair hangs hans harm hats hays heer
heest here hero hist hits hoest hongs horn host hungs inf ins ira ist its keeps kens kits langs
lans laos last lats lays leer leest leos lets lips lisa list loes loest longs loops lose lost lots
luis mais mans mary mats mays meest meets mens mere mias most must nams neer neest neos nest nons
norm nuts oer oes oops owns past pest pets pics queens queest quest taxi teest term test thais
thats theer theest there these tims tips tits toer toes toest toms tops towns turn ums ups uri urw
usa usc vary vast vees veest vias visa vons was
"""
}
