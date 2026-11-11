package com.anant.teslatycoon.rival;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seth Gotiya's mouth. Every line is Hinglish (Hindi in Roman letters mixed with English), silly and harmless; he roasts electric
 * cars, brags about diesel, begs, panics and cries when his plans fail. Replies look at what the player typed.
 * Placeholders: {p} player name, {m} money, {lv} showroom level, {car} newest model, {w} a word the player used.
 */
public final class GotiyaChat {
    private GotiyaChat() {}

    private static final Random RNG = new Random();

    private record Rule(Pattern pattern, String[] lines) {}

    private static Rule rule(String regex, String... lines) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), lines);
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        RULES.add(rule("\\b(hi|hello|hey|hii+|yo|sup|namaste|namaskar|ram ram|salaam)\\b",
                "Oye {p}! Namaste namaste... ab bata, petrol bharwana hai ya Tesla ka charger dhundh raha hai?",
                "Hello hello {p}! Seth Gotiya ki dukaan mein aapka swagat hai... bas bijli wali gaadi ka naam mat lena!",
                "Arre {p}, namaste! Chai piyega? Meri chai mein bhi diesel ka swaad hai... free hai!",
                "Hey {p}! Aaj kal sab electric electric karte hain, par mera engine dhak-dhak karta hai. Dil bhi, diesel bhi!"));
        RULES.add(rule("\\b(tesla|electric|ev|battery|charge|charging|bijli|autopilot|cybertruck|cybercab|roadster|model ?3)\\b",
                "Tesla?! Bhai woh gaadi hai ya mobile phone? Raat ko charging pe laga ke sona padta hai!",
                "Bijli ki gaadi! Mere ghar ka inverter bhi 2 ghante mein haath utha deta hai, tu 500 km ki baat karta hai?",
                "Tesla mein engine ki awaaz hi nahi aati... gaadi hai ya Wi-Fi router? Mujhe toh dhak-dhak chahiye!",
                "Autopilot? Meri gaadi ka autopilot 'Ramu kaka' hai, woh bhi sirf chai ke liye rukta hai!",
                "Electric gaadi mein silencer kahan lagaoge? Bina awaaz ke toh mere kutte bhi nahi darte!",
                "Battery khatam ho gayi toh kya dhakka lagayega? Mere diesel mein toh ek bottle bhar ke chal padta hoon!",
                "Charger lagao, charger lagao... bhai {p}, tumhari zindagi bas extension board ke aas-paas ghoomti hai!",
                "Cybertruck ko dekh ke toh mujhe lagta hai kisi ne gaadi ko origami bana diya! Mera truck dekh, mard ki gaadi!"));
        RULES.add(rule("\\b(petrol|diesel|gotiya|seth|engine|exhaust|mileage|average|pump|dhuaan|dhuan)\\b",
                "Haan haan! Petrol-diesel ki baat karo... asli mazaa toh dhuein mein hai! Kya khushboo hai... pollution ki!",
                "Seth Gotiya ka naam suna hai? Poore sheher ka tank mere paas se bharta hai!",
                "Mileage? Meri gaadi ki mileage utni hi hai jitni meri biwi ki shopping ki limit... koi limit nahi!",
                "Mera engine bolta hai 'dhuk dhuk dhuk', tera Tesla bolta hai 'beep'... pehchaan kaun si badi hai bata!",
                "Diesel zindabad! Jis din petrol sasta hua, us din main Tesla khareed lunga... matlab kabhi nahi!"));
        RULES.add(rule("\\b(money|paisa|paise|rich|cash|income|price|cost|cheap|expensive|rupee|rupay|dollar|crore|lakh|profit|earn|balance)\\b",
                "Paisa? Tere paas sirf ${m} hai aur tu mujhse takkar lega? Mere paas toh kabaadi ka stock zyada hai!",
                "Paise ki baat mat kar {p}! Meri tijori mein itna diesel hai ki Ambani bhi sharma jaye!",
                "Abhi ${m}? Haha! Ek chai ka cup bhi itna mehnga hota hai mere ilaake mein!",
                "Tesla ka daam sunke toh mere dil ki sports-car-wali dhadkan band ho gayi... main toh scooter hi theek hoon!",
                "Paisa hi sab kuch nahi hota {p}... lekin diesel ke bina kuch bhi nahi hota!"));
        RULES.add(rule("\\b(noob|loser|bad|fake|scam|trash|useless|bakwas|bekar|idiot|stupid|ugly|pagal|nalayak|chor|cheat|hacker)\\b",
                "Mujhe {w} bola?! Main Seth Gotiya hoon! Mere daadaji ke zamane se petrol bech raha hoon, tu kal ka launda!",
                "{w}?! Dekh {p}, zubaan sambhal ke! Mere ghode... matlab mere engines gussa ho jayenge!",
                "Haye haye! Mujhe {w} bolta hai! Mere dil pe chhura chala diya... diesel ka chhura!",
                "Tu {w}, tera showroom {w}, tera Tesla bhi {w}! (Acha chal, sorry, main bhi gussa hoon.)",
                "Arre arre {p}, itni neech baatein? Main toh sirf spikes bichhata hoon, gaali nahi deta!"));
        RULES.add(rule("(lol|haha+|hehe+|lmao|lmfao|rofl|xd|\\bhahaha)",
                "Hans le hans le {p}, abhi toh sirf shuruaat hai... mera agla plan padh ke rona mat!",
                "Haha! Tu hans raha hai? Mere spikes bhi hanste hain... jab tera tyre phatta hai!",
                "Oho! Bada khush hai? Khushi mein ek petrol ka pump bhi khareed le!",
                "Lol-wol mat kar, meri dukaan ka sabse sasta item bhi tere Tesla se zyada izzat wala hai!"));
        RULES.add(rule("\\b(help|please|plz|pls|sorry|maaf|mercy|dayaa|daya|stop|ruko|bas)\\b",
                "Maaf kar du? Pehle meri dukaan ka ek kilo diesel khareed, phir sochunga!",
                "Rehem? Gotiya ke dictionary mein 'rehem' ka matlab hai 'rehmat petrol pump'!",
                "Plz plz mat bol {p}... pighal gaya main! Chal theek hai, aaj ek hi sabotage karunga... (Bas ek? Haha jhooth!)",
                "Arre sorry-worry chhod! Yeh jung hai, yeh business hai, yeh Gotiya hai!"));
        RULES.add(rule("\\b(win|won|beat|best|number ?one|no\\.? ?1|champion|king|boss|winner|jeet|jeeta|jeetega)\\b",
                "Jeet? Gotiya ne apni zindagi mein sirf ek hi cheez jeeti hai... lottery mein ek steel ki balti!",
                "Tu number one? Bhai {p}, mere dukaan ke bahar jo kutta baithta hai woh bhi mujhse zyada customers rakhta hai!",
                "Winner winner chicken dinner! Par woh chicken mere dhabe ka hoga, tera showroom ka nahi!",
                "Champion banega tu? Pehle meri Monster Truck ka saamna kar, phir bol!"));
        RULES.add(rule("\\b(race|racing|fast|speed|drift|zoom|vroom|jump|fly|udna|udd|rocket|space ?x)\\b",
                "Race? Mere paas teen paiye wali gaadi hai, aur teen pahiye ki gaadi kabhi nahi haarti... kyunki woh kabhi shuru hi nahi hoti!",
                "Udega? Bhai gaadi udayega toh traffic police ko kaun jawab dega? Main nahi dunga, bata diya!",
                "SpaceX wali gaadi?! Rocket laga ke aasmaan mein jayega toh pump kahan bharayega? Rasta ruk jayega, Gotiya ke bina!",
                "Fast chalane ka matlab hai petrol jaldi khatam... isliye mujhe bahut pasand hai!"));
        RULES.add(rule("\\b(thanks|thank you|thx|shukriya|dhanyavad|dhanyawad|tysm)\\b",
                "Thanks? Mujhe? Haha, galti se bol diya hoga! Chal khair, welcome... par spikes ab bhi bichhenge!",
                "Shukriya? Pehle sabotage toh hone de {p}, phir bolna!",
                "Arre arre, itni tameez? Tu shayad Tesla se zyada polite hai... par phir bhi main tera dushman hoon!"));
        RULES.add(rule("(\\?|\\b(kya|kyun|kyu|kaise|kab|kahan|kaun|why|how|what|when|where|who)\\b)",
                "Sawaal pe sawaal! Main Gotiya hoon, Google nahi hoon bhai... par jawab yahi hai: diesel best hai!",
                "Kya bol raha hai {p}? Mere kaan mein engine ki awaaz aati hai, tujhe sunai nahi deta!",
                "Pata nahi! Aur pata bhi hota toh tujhe nahi batata. Competition hai bhai, competition!",
                "Itne sawaal toh meri biwi bhi nahi poochti! Chal, ek hi jawab: 'Kyunki main Seth Gotiya hoon!'"));
    }

    private static final String[] FALLBACK = {
            "{w}? Haha {p}! Itna sab bol ke bhi tera showroom ${m} pe hi atka hai!",
            "Arre {p}, tu {w} bol raha hai aur main sirf apni diesel ki khushboo soongh raha hoon... mazaa aa gaya!",
            "Tera Tesla level {lv} pe aa gaya toh kya? Mere paas level 100 ka dhuaan hai!",
            "Sun {p}, main tujhe ek baat batata hoon: electric gaadi mein horn bajane se pehle bhi 3 baar permission leni padti hai!",
            "{w}... hmm... interesting. Main iss par apna agla sabotage likh raha hoon. Noting down!",
            "Bhai {p}, tu bohot chatpata baat karta hai! Isi liye toh tere showroom ke bahar meri dukaan hai... jasoosi ke liye!",
            "Mere pass kaam hai, par tu chhota hai yaar... chal baat karte hain: tera showroom kab band ho raha hai?",
            "Gotiya ki dukaan ki chai piyega? Tesla wale ko chai bhi bijli se banani padti hogi... shock lagega!",
            "Dekh {p}, main shaant hoon... par mere andar ka engine garam hai. Dhuaan nikal raha hai!"
    };

    private static final String[] IDLE = {
            "Oye Anant ke showroom wale! Bijli ka bill dekha? Mera diesel tera dil jalata hai!",
            "Aaiye aaiye! Petrol Motors mein aaiye! Aaj ka offer: ek litre petrol pe ek litre hawa FREE!",
            "Kisi ko Tesla khareedni hai toh pehle apna ghar ki bijli connection check kar lo... meri dukaan mein koi connection nahi, sirf bharosa hai!",
            "Mere pump ke bahar line lambi hai... kyunki sabko pata hai, asli gaadi diesel pe chalti hai!",
            "Tesla wale ko dekho, gaadi chalane se pehle phone charge karta hai. Main toh seedha tank bharta hoon!",
            "Suno suno! Jo Tesla khareedega usko mere taraf se ek packet free... mungfali ka! (Dhuaan alag se.)",
            "Gotiya Petrol Motors: jahan har tank mein hai zindagi, aur har kisi ke paas hai bahaana!",
            "Electric gaadi wale ko cold pad gaya hai: unka engine toh hai hi nahi, garmi kahan se aayegi!",
            "Kal raat mujhe sapna aaya... ek Tesla mere pump pe aaya aur bola 'Uncle, thoda diesel dena'... maza aa gaya!",
            "Mere customers ko dekh ke Anant ka showroom jal raha hai! Jal-jal ke pighal raha hai!",
            "Ek Tesla ki keemat mein main poore mohalle ko diesel pila sakta hoon... aur chai bhi!",
            "Dhuaan dhuaan dhuaan! Meri kismat bhi dhuein jaisi hai: upar uthti hai aur sabko dikhti hai!"
    };

    private static final String[] SAB = {
            "",
            "Haha! Maine saari sadak pe KILEIN bichha di! Dekhte hain ab kaunsi Tesla bina tyre ke test drive jaati hai!",
            "Bijli ka main switch mere haath mein hai! Kat gayi bijli, ab chalo andhere mein showroom chalao Anant!",
            "Yeh dekh, concrete ke barricade! Ab dekhta hoon kaise nikalta hai tera Cybertruck... DIESEL ka dumdaar rasta band!",
            "Cybercab ka GPS jam kar diya! Ab apne aap gumega tera robotaxi... Haha, 'Autopilot' ko 'Auto-pagal' bana diya!",
            "BAS! Ab bahut ho gaya! Meri Monster Truck ready hai! Showroom ko mitti mein mila dunga! HAHAHA! BOOM BOOM!"
    };

    /** Boasts while the Monster Truck is charging the showroom (the opening line is SAB[5], these follow). */
    private static final String[] TRUCK = {
            "Monster Truck, aage badh! Kaanch ka showroom toh mere liye bas ek bada sa biscuit hai!",
            "Crunch crunch! Aaj showroom ko chai ke saath khaunga... glass ke biscuit!",
            "Hahaha! Udne wali gaadi se EMP maarega? Pehle meri Monster Truck ke tyre se bach ke dikha, {p}!",
            "Truck ka ek tyre tere poore showroom ke gate se bada hai {p}... dhaaaam!",
            "Daro mat Tesla wale, bas thodi si toda-phodi hai! HAHAHA!",
            "Diesel ki shakti! Dekh mere truck ka dhuaan... aasmaan tak jaata hai, tere rocket se pehle!"
    };

    private static final String[] CRY = {
            "",
            "Mere spikes... mere pyaare spikes... kisne tod diye?! Sniff sniff... Bhaiya mujhe wapis do mere kile!",
            "Solar roof?! Mega Pack?! Mera blackout... meri mehnat... Hai Ram! Mere switch ka kya kasoor tha!",
            "Mera barricade... Cybertruck ne todd diya! Mera 5 saal ka cement... baah baah baah!",
            "Starlink?! Billboard?! Mere saare customers wahan ja rahe hain... Mere paas koi nahi aa raha... Mummy!",
            "NAHI! Mera truck band ho gaya! Meri Monster Truck ki aatma bijli ke jhatke se gayi! Main haar gaya... hoo hoo hoo!"
    };

    private static String fill(String line, String player, long money, int level, String car, String word) {
        return line.replace("{p}", player).replace("{m}", String.format("%,d", money)).replace("{lv}", String.valueOf(level))
                .replace("{car}", car).replace("{w}", word);
    }

    /** A dynamic reply to anything the player typed in chat. */
    public static String reply(String text, String player, long money, int level, String car, int sabotage) {
        String t = text.toLowerCase(Locale.ROOT);
        String word = pickWord(text);
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(t);
            if (m.find()) {
                String w = m.group().isBlank() ? word : m.group().trim().replaceAll("[^\\p{L}\\p{N}]", "");
                if (w.isBlank()) {
                    w = word;
                }
                return fill(r.lines[RNG.nextInt(r.lines.length)], player, money, level, car, w);
            }
        }
        if (sabotage > 0 && RNG.nextInt(3) == 0) {
            return SAB[Math.min(sabotage, SAB.length - 1)];
        }
        return fill(FALLBACK[RNG.nextInt(FALLBACK.length)], player, money, level, car, word);
    }

    public static String idle() {
        return IDLE[RNG.nextInt(IDLE.length)];
    }

    public static String sabotageLine(int sabotage) {
        return SAB[Math.max(1, Math.min(sabotage, SAB.length - 1))];
    }

    /** A fresh boast during the finale; {@code n} walks through the list so two in a row never repeat. */
    public static String truckBoast(String player, int n) {
        return TRUCK[Math.floorMod(n, TRUCK.length)].replace("{p}", player);
    }

    public static String cryLine(int sabotage) {
        return CRY[Math.max(1, Math.min(sabotage, CRY.length - 1))];
    }

    public static String taunt(String player, int level, long money) {
        String[] lines = {
                "Arre {p}, level " + level + " pe pahunch ke itna ud raha hai? Seth Gotiya ke aage kuch nahi!",
                "{p} bhai, ${m} se kya hota hai? Mere ek ghante ka dhuaan isse mehnga hai!",
                "Dekh {p}, tera showroom chamak raha hai... par chamak ke neeche meri sazish bhi tik tik kar rahi hai!"
        };
        return fill(lines[RNG.nextInt(lines.length)], player, money, level, "", "");
    }

    private static String pickWord(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "yeh";
        }
        String w = parts[RNG.nextInt(parts.length)].replaceAll("[^\\p{L}\\p{N}]", "");
        if (w.isBlank()) {
            w = parts[0];
        }
        return w.length() > 18 ? w.substring(0, 18) : w;
    }

    private static final String[] CAUGHT = {
            "arre arre... main toh bas rasta dekh raha tha!! bhaago",
            "kuch nahi dekha maine. kuch nahi. bye",
            "oye peeche mat aa!! main ja raha hoon",
            "bas window shopping kar raha tha yaar, itna gussa kyun",
            "tera showroom dekhne nahi aaya tha... chashma dhundh raha tha apna"};
    private static int caught;

    /** He was spying behind a showroom pillar and the player walked up to him. */
    public static String caughtSpying() {
        return CAUGHT[caught++ % CAUGHT.length];
    }
}
