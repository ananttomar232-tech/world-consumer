package com.anant.teslatycoon.rival;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seth Gotiya's mind: reads what the player typed (English, Hindi or Hinglish), works out what it is about and answers with one of
 * his voiced lines (tools/gotiya_lines.py). He keeps a little memory: kind words build trust (empathy can turn him into a partner),
 * insults cost it, a question about the current trouble gets a grumpy but true hint.
 */
public final class GotiyaChat {
    private GotiyaChat() {}

    private static final Random RNG = new Random();

    /** What he knows when he answers. beaten: his finale is lost or his dealership was destroyed (he is much easier to reach then). */
    public record Ctx(String player, long money, int level, String car, int sabotage, boolean partner, boolean beaten, int trust) {}

    /** The answer: the line (with its voice clip), the chat text, the change in trust and whether he joins the business now. */
    public record Reply(GotiyaLines.Line line, String text, int trustDelta, boolean join) {}

    private record Intent(String key, Pattern pattern) {}

    private static final List<Intent> INTENTS = new ArrayList<>();

    private static void intent(String key, String regex) {
        INTENTS.add(new Intent(key, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
    }

    // checked in this order: the first match wins
    static {
        intent("help", "\\b(help|hint|tip|guide|stuck|madad|madat|kya karu\\w*|kya kar(e|en|ein)|how (do|can|should) i|what (do|should|can) i|kya kar(u|oon|un)|kaise (hata|theek|fix|kar)\\w*|how to (fix|remove|beat|stop|win))\\b");
        intent("empathy", "\\b(sorry|maaf|maafi|maf|maph\\w*|mav\\w*kar|mafi|kaam kar(te|enge|ege|ein)|saath (mein|me|men) kaam|sath (me|men|mein) kam|milkar|mil ke|forgive|friend|friends|dost|dosti|partner|together|saath|join|respect|izzat|understand|samajh(ta|ti)? hoon|it'?s ok(ay)?|koi baat nahi|don'?t cry|mat ro|ro mat|care|hug|gale|peace|shanti|help you|madad karu\\w*|feel (bad|sorry)|bura laga|we can (work|share|do)|work together|chalo saath|i like you|proud of you|sympathy|empathy|calm down|relax|fikar mat|chinta mat|tension mat|you('| a)re (good|nice|great|kind|a good)|tum (ache|achhe) ho|aap (ache|achhe) ho|let'?s be|truce|sulah|bhai bhai|no hard feelings)\\b");
        intent("insult", "\\b(noob|loser|fake|scam|trash|useless|bakwas|bekar|idiot|stupid|ugly|pagal|nalayak|chor|cheat|hacker|dumb|moron|gadha|ullu|bewakoof|hate you|shut up|chup|kamina)\\b");
        intent("who", "\\b(who are you|who r u|your name|tera naam|tumhara naam|aapka naam|kaun ho|kon ho|introduce)\\b");
        intent("how", "\\b(how are you|how r u|how('| i)s it going|kaise ho|kaisa hai|kese ho|keise ho|kaise hain|kaise hai|kya hal|kya haal|kaisi chal|what'?s up|wassup|sup)\\b");
        intent("why_hate", "\\b(why (do you )?hate|why are you (doing|so)|kyun (karte|kar raha|nafrat)|why (me|tesla)|what did i do|maine kya kiya|problem kya hai)\\b");
        intent("sab_ask", "\\b(spike|spikes|kil(e|ein|en)|barricade|roadblock|blackout|bijli (kyun|kaat)|gps|jam|remove|hatao|hata do|why (did|the) (you )?(block|cut|put))\\b");
        intent("greet", "\\b(hi+|hello|hey|yo|namaste|namaskar|ram ram|salaam|good (morning|evening|afternoon)|gm)\\b");
        intent("bye", "\\b(bye|goodbye|good night|gn|see you|alvida|chalta hoon|cya|tata)\\b");
        intent("thanks", "\\b(thanks|thank you|thx|ty|shukriya|dhanyavad|dhanyawad|tysm)\\b");
        intent("compliment", "\\b(nice|cool|great|awesome|amazing|beautiful|sundar|badhiya|mast|kamaal|wow|good (job|work)|well done|legend|smart)\\b");
        intent("love", "\\b(love|pyaar|pyar|ishq|crush|marry|shaadi)\\b");
        intent("threat", "\\b(destroy|kill|maar|finish you|crush you|beat you|dekh lunga|tod dunga|smash|warn|warning|watch out|i will|main tujhe)\\b");
        intent("laugh", "(lol|haha+|hehe+|lmao|lmfao|rofl|xd|\\bhaha)");
        intent("joke", "\\b(joke|funny|mazak|mazaak|chutkula|make me laugh)\\b");
        intent("cars", "\\b(lambo|lamborghini|mustang|shelby|ferrari|hatchback|supercar|your (car|cars|showroom|shop)|teri (gaadi|dukaan)|gaadi dikha|sell me|buy (a|your)|kitne ki|price of)\\b");
        intent("tesla", "\\b(tesla|electric|ev|battery|charg\\w*|bijli|autopilot|cybertruck|cybercab|roadster|model ?3|robotaxi|solar|elon)\\b");
        intent("petrol", "\\b(petrol|diesel|engine|exhaust|mileage|average|pump|dhuaan|dhuan|fuel|v8|v12|pollution)\\b");
        intent("money", "\\b(money|paisa|paise|rich|cash|income|cost|cheap|expensive|rupee|rupay|dollar|crore|lakh|profit|earn|balance|ameer|garib)\\b");
        intent("race", "\\b(race|racing|fast|speed|drift|zoom|vroom|fly|udna|udd|rocket|space ?x|challenge)\\b");
        intent("food", "\\b(chai|tea|coffee|food|khana|samosa|paratha|bhook|hungry|eat|lunch|dinner|biryani)\\b");
        intent("family", "\\b(family|wife|biwi|beta|son|kids|bachche|ghar|mother|mummy|papa|dadaji|grandfather)\\b");
        intent("sport", "\\b(cricket|football|ipl|match|sport|game of|virat|dhoni)\\b");
        intent("weather", "\\b(weather|mausam|rain|baarish|garmi|sardi|hot|cold|sun)\\b");
        intent("game", "\\b(minecraft|game|mod|server|creeper|villager|block|npc|bot|ai|robot)\\b");
        intent("yes", "^\\s*(yes|yeah|yep|haan|han|ha|ji|ok|okay|sure|theek hai)\\W*$");
        intent("no", "^\\s*(no|nope|nah|nahi|na|never|kabhi nahi)\\W*$");
        intent("question", "(\\?|\\b(kya|kyun|kyu|kaise|kab|kahan|kaun|why|how|what|when|where|who|which|can you|will you|do you|are you)\\b)");
    }

    public static String classify(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (Intent i : INTENTS) {
            if (i.pattern.matcher(t).find()) {
                return i.key;
            }
        }
        return "fallback";
    }

    /** The full answer to a chat line. */
    public static Reply respond(String text, Ctx ctx) {
        String intent = classify(text);
        String key = intent;
        int delta = 0;
        boolean join = false;
        switch (intent) {
            case "help" -> key = "hint_" + Math.max(0, Math.min(5, ctx.sabotage()));
            case "empathy" -> {
                if (ctx.partner()) {
                    key = "partner";
                } else {
                    delta = ctx.beaten() ? 2 : 1;
                    if (text.length() > 30) {
                        delta++;                                      // a real sentence counts for more than "sorry"
                    }
                    int need = ctx.beaten() ? 2 : 5;
                    if (ctx.trust() + delta >= need) {
                        key = "empathy_join";
                        join = true;
                    } else {
                        key = !ctx.beaten() && ctx.trust() == 0 && RNG.nextInt(3) == 0 ? "cold" : "empathy_soft";
                    }
                }
            }
            case "insult", "threat" -> delta = -1;
            case "greet", "how", "fallback", "question" -> {
                if (ctx.partner()) {
                    key = "partner";
                } else if (ctx.beaten() && !"greet".equals(intent) && RNG.nextInt(2) == 0) {
                    key = "destroyed";                               // after his defeat he is not in the mood for jokes
                }
            }
            default -> {
            }
        }
        GotiyaLines.Line line = GotiyaLines.pick(GotiyaLines.has(key) ? key : "fallback");
        return new Reply(line, fill(line.text(), ctx, pickWord(text)), delta, join);
    }

    /** A plain answer (kept for tests and simple callers). */
    public static String reply(String text, String player, long money, int level, String car, int sabotage) {
        return respond(text, new Ctx(player, money, level, car, sabotage, false, false, 0)).text();
    }

    public static String fill(String line, Ctx ctx, String word) {
        return line.replace("{p}", ctx.player()).replace("{m}", String.format(Locale.US, "%,d", ctx.money())).replace("{lv}", String.valueOf(ctx.level()))
                .replace("{car}", ctx.car() == null ? "" : ctx.car()).replace("{w}", word == null ? "yeh" : word);
    }

    public static String idle() {
        return GotiyaLines.pick("idle").text();
    }

    public static String sabotageLine(int sabotage) {
        return GotiyaLines.pick("sab_" + Math.max(1, Math.min(5, sabotage))).text();
    }

    public static String cryLine(int sabotage) {
        return GotiyaLines.pick("cry_" + Math.max(1, Math.min(5, sabotage))).text();
    }

    private static String pickWord(String text) {
        String[] parts = text.trim().split("\\s+");
        String best = "";
        for (String p : parts) {
            String w = p.replaceAll("[^\\p{L}\\p{N}]", "");
            if (w.length() > best.length()) {
                best = w;                                           // the longest word is usually the interesting one
            }
        }
        if (best.isBlank()) {
            best = "yeh";
        }
        return best.length() > 18 ? best.substring(0, 18) : best;
    }

    static Matcher match(String regex, String text) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
    }
}
