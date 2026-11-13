#!/usr/bin/env python3
"""Seth Gotiya's (and the customers') lines: the chat text (Hinglish, Roman letters) and what the voice says (Hindi, Devanagari),
rendered offline with the Piper TTS voices into .ogg clips that ship inside the mod (no voice-chat mod needed: the server plays the
clip at Gotiya's position, so it sounds from where he stands).

usage:  python3 tools/gotiya_lines.py            writes lines.json + sounds.json (fast)
        python3 tools/gotiya_lines.py --voice V  also renders the clips (needs: pip install piper-tts, ffmpeg, the voice models in V)
Voices: hi_IN-pratham-medium (Gotiya) and hi_IN-priyamvada-medium (the customers), both CC BY-NC-SA 4.0 (see CREDITS.txt).
Placeholders in the chat text: {p} player name, {m} money, {lv} level, {car} newest car.  The voice never says them.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "tesla_tycoon", "src", "main", "resources", "assets", "tesla_tycoon")

G, F = "g", "f"          # Gotiya / a (female) customer

# (key, chat text, spoken Hindi, voice)
L = [
    # ------------------------------------------------------------------ greetings, small talk
    ("greet", "Oye {p}! Aa gaya? Main soch hi raha tha ki aaj kiska din kharab karun... namaste, namaste!", "ओये! आ गया? मैं सोच ही रहा था कि आज किसका दिन खराब करूँ... नमस्ते, नमस्ते!", G),
    ("greet", "Arre {p}, namaste! Chai piyega? Meri chai mein bhi thoda diesel ka tadka hai... free hai, sirf aaj!", "अरे, नमस्ते! चाय पियेगा? मेरी चाय में भी थोड़ा डीज़ल का तड़का है... फ्री है, सिर्फ़ आज!", G),
    ("greet", "Hello hello! Seth Gotiya ki taraf se garma-garam swagat... bas apni bijli wali gaadi bahar park karna.", "हेलो हेलो! सेठ गोटिया की तरफ़ से गरमा-गरम स्वागत... बस अपनी बिजली वाली गाड़ी बाहर पार्क करना।", G),
    ("greet", "Ram Ram {p}! Subah subah tera chehra dekh liya, ab toh pakka mera petrol mehnga bikega!", "राम राम! सुबह सुबह तेरा चेहरा देख लिया, अब तो पक्का मेरा पेट्रोल महँगा बिकेगा!", G),
    ("greet", "Oho, Tesla wale babu! Aaj kaunsi nayi bijli ki kahani sunane aaye ho?", "ओहो, टेस्ला वाले बाबू! आज कौन सी नई बिजली की कहानी सुनाने आए हो?", G),
    ("bye", "Ja ja {p}, par yaad rakhna... sadak pe agla surprise mera hi hoga!", "जा जा, पर याद रखना... सड़क पे अगला सरप्राइज़ मेरा ही होगा!", G),
    ("bye", "Chal theek hai, alvida. Raat ko sapne mein bhi dhak-dhak engine sunega tu!", "चल ठीक है, अलविदा। रात को सपने में भी धक-धक इंजन सुनेगा तू!", G),
    ("bye", "Bye bye! Apni gaadi charge karna mat bhoolna... warna dhakka main nahi lagaunga!", "बाय बाय! अपनी गाड़ी चार्ज करना मत भूलना... वरना धक्का मैं नहीं लगाऊँगा!", G),
    ("who", "Main? Seth Gotiya! Gotiya Petrol Motors ka maalik, sheher ka diesel samrat, aur tera sabse bada sir-dard!", "मैं? सेठ गोटिया! गोटिया पेट्रोल मोटर्स का मालिक, शहर का डीज़ल सम्राट, और तेरा सबसे बड़ा सिरदर्द!", G),
    ("who", "Naam toh suna hi hoga... Seth Gotiya. Teen peedhi se petrol bech rahe hain, chauthi peedhi tujhe hara ke dikhayegi!", "नाम तो सुना ही होगा... सेठ गोटिया। तीन पीढ़ी से पेट्रोल बेच रहे हैं, चौथी पीढ़ी तुझे हरा के दिखाएगी!", G),
    ("who", "Main woh hoon jiske pump pe sheher ka har tank bharta tha... jab tak tu apni bijli ki dukaan le ke nahi aaya.", "मैं वो हूँ जिसके पंप पे शहर का हर टैंक भरता था... जब तक तू अपनी बिजली की दुकान ले के नहीं आया।", G),
    ("how", "Main? Ekdum first class! Tere showroom ka bill dekh ke toh aur bhi achha lag raha hai.", "मैं? एकदम फ़र्स्ट क्लास! तेरे शोरूम का बिल देख के तो और भी अच्छा लग रहा है।", G),
    ("how", "Kaisa hoon? Jaise ek diesel engine thand mein... thoda dhuaan, thoda gussa, par chalta rehta hoon.", "कैसा हूँ? जैसे एक डीज़ल इंजन ठंड में... थोड़ा धुआँ, थोड़ा गुस्सा, पर चलता रहता हूँ।", G),
    ("how", "Sach bataun? Customers kam ho gaye hain... par yeh baat kisi ko mat batana, khaaskar tujhe!", "सच बताऊँ? कस्टमर कम हो गए हैं... पर ये बात किसी को मत बताना, ख़ासकर तुझे!", G),
    # ------------------------------------------------------------------ topics
    ("tesla", "Tesla?! Gaadi hai ya mobile phone? Raat bhar charging pe lagao, subah update aata hai, gaadi phir bhi wahi ki wahi!", "टेस्ला?! गाड़ी है या मोबाइल फ़ोन? रात भर चार्जिंग पे लगाओ, सुबह अपडेट आता है, गाड़ी फिर भी वही की वही!", G),
    ("tesla", "Bijli ki gaadi! Mere ghar ka inverter do ghante mein haath khade kar deta hai, aur tu paanch sau kilometre ki baat karta hai?", "बिजली की गाड़ी! मेरे घर का इन्वर्टर दो घंटे में हाथ खड़े कर देता है, और तू पाँच सौ किलोमीटर की बात करता है?", G),
    ("tesla", "Tesla mein engine ki awaaz hi nahi aati. Gaadi hai ya Wi-Fi router? Mujhe toh dhak-dhak chahiye, dil wali!", "टेस्ला में इंजन की आवाज़ ही नहीं आती। गाड़ी है या वाई-फ़ाई राउटर? मुझे तो धक-धक चाहिए, दिल वाली!", G),
    ("tesla", "Autopilot? Meri gaadi ka autopilot Ramu kaka hai. Chai ke liye rukte hain, par kabhi crash nahi karte!", "ऑटोपायलट? मेरी गाड़ी का ऑटोपायलट रामू काका है। चाय के लिए रुकते हैं, पर कभी क्रैश नहीं करते!", G),
    ("tesla", "Cybertruck ko dekh ke lagta hai kisi ne gaadi ko origami bana diya aur bhool gaya kholna!", "साइबरट्रक को देख के लगता है किसी ने गाड़ी को ओरिगामी बना दिया और भूल गया खोलना!", G),
    ("tesla", "Charger dhundhte dhundhte teri zindagi extension board ke aas-paas hi ghoomti rahegi, {p}!", "चार्जर ढूँढते ढूँढते तेरी ज़िंदगी एक्सटेंशन बोर्ड के आस-पास ही घूमती रहेगी!", G),
    ("tesla", "Thik hai thik hai, maanta hoon... Tesla sundar hai. Bas yeh baat meri biwi ko mat batana, woh khareed legi!", "ठीक है ठीक है, मानता हूँ... टेस्ला सुंदर है। बस ये बात मेरी बीवी को मत बताना, वो ख़रीद लेगी!", G),
    ("petrol", "Petrol ki khushboo! Jab tank bharta hai na, toh lagta hai mandir mein agarbatti jal rahi hai.", "पेट्रोल की ख़ुशबू! जब टैंक भरता है ना, तो लगता है मंदिर में अगरबत्ती जल रही है।", G),
    ("petrol", "Mera engine bolta hai dhuk dhuk dhuk, tera Tesla bolta hai beep. Bata, mard ki gaadi kaunsi?", "मेरा इंजन बोलता है धुक धुक धुक, तेरा टेस्ला बोलता है बीप। बता, मर्द की गाड़ी कौन सी?", G),
    ("petrol", "Diesel zindabad! Jis din petrol sasta hoga, us din main Tesla khareed lunga. Matlab kabhi nahi!", "डीज़ल ज़िंदाबाद! जिस दिन पेट्रोल सस्ता होगा, उस दिन मैं टेस्ला ख़रीद लूँगा। मतलब कभी नहीं!", G),
    ("petrol", "Meri dukaan mein Mustang hai, Lamborghini hai, aur ek desi hatchback bhi. Sab mein asli aag hai, battery nahi!", "मेरी दुकान में मस्टैंग है, लैम्बॉर्गिनी है, और एक देसी हैचबैक भी। सब में असली आग है, बैटरी नहीं!", G),
    ("money", "Paisa? Tere paas sirf ${m} hai aur tu mujhse takkar lega? Mere gallay mein itna toh chillar hai!", "पैसा? तेरे पास तो बस थोड़े से पैसे हैं और तू मुझसे टक्कर लेगा? मेरे गल्ले में इतना तो चिल्लर है!", G),
    ("money", "Paise ki baat mat kar {p}. Meri tijori mein itna diesel hai ki Ambani bhi sharma jaaye.", "पैसे की बात मत कर। मेरी तिजोरी में इतना डीज़ल है कि अंबानी भी शर्मा जाए।", G),
    ("money", "Paisa hi sab kuch nahi hota... lekin diesel ke bina kuch bhi nahi hota. Yeh Gotiya ka gyaan hai, muft mein.", "पैसा ही सब कुछ नहीं होता... लेकिन डीज़ल के बिना कुछ भी नहीं होता। ये गोटिया का ज्ञान है, मुफ़्त में।", G),
    ("money", "Tera galla bhar raha hai, mera khaali ho raha hai. Yeh koi insaaf hai, bhagwan?", "तेरा गल्ला भर रहा है, मेरा ख़ाली हो रहा है। ये कोई इंसाफ़ है, भगवान?", G),
    ("cars", "Gaadi dekhni hai? Meri dukaan mein aa, Lamborghini ki awaaz sunke tere Tesla ki battery khud discharge ho jayegi!", "गाड़ी देखनी है? मेरी दुकान में आ, लैम्बॉर्गिनी की आवाज़ सुनके तेरे टेस्ला की बैटरी ख़ुद डिस्चार्ज हो जाएगी!", G),
    ("cars", "Mustang Shelby! Paanch sau ghode andar band hain. Tere Tesla mein toh sirf ek chooha hai jo pahiya ghumata hai.", "मस्टैंग शेल्बी! पाँच सौ घोड़े अंदर बंद हैं। तेरे टेस्ला में तो सिर्फ़ एक चूहा है जो पहिया घुमाता है।", G),
    ("cars", "Meri Lamborghini dekh, peeli peeli, jaise garam samosa. Aur tera Cybertruck? Thanda fridge!", "मेरी लैम्बॉर्गिनी देख, पीली पीली, जैसे गरम समोसा। और तेरा साइबरट्रक? ठंडा फ़्रिज!", G),
    ("insult", "Mujhe {w} bola?! Main Seth Gotiya hoon! Mere daadaji ke zamaane se petrol bech raha hoon, tu kal ka launda!", "मुझे ऐसा बोला?! मैं सेठ गोटिया हूँ! मेरे दादाजी के ज़माने से पेट्रोल बेच रहा हूँ, तू कल का लौंडा!", G),
    ("insult", "Zubaan sambhal ke {p}! Mere engines gussa ho gaye toh poora sheher dhuein se kaala ho jayega.", "ज़ुबान सँभाल के! मेरे इंजन गुस्सा हो गए तो पूरा शहर धुएँ से काला हो जाएगा।", G),
    ("insult", "Haaye haaye! Itni kadwi baat? Mere dil pe chhura chala diya... diesel ka chhura!", "हाय हाय! इतनी कड़वी बात? मेरे दिल पे छुरा चला दिया... डीज़ल का छुरा!", G),
    ("insult", "Gaali dene se teri bijli nahi badhegi. Main toh sirf kilein bichhata hoon, gaali nahi deta!", "गाली देने से तेरी बिजली नहीं बढ़ेगी। मैं तो सिर्फ़ कीलें बिछाता हूँ, गाली नहीं देता!", G),
    ("compliment", "Hain? Meri taareef? Ruk... yeh koi chaal hai kya? Phir bhi... achha laga. Bahut dinon baad.", "हैं? मेरी तारीफ़? रुक... ये कोई चाल है क्या? फिर भी... अच्छा लगा। बहुत दिनों बाद।", G),
    ("compliment", "Arre, itni meethi baat? Aaj toh main sirf aadhi sadak pe kilein bichhaunga. Pakka.", "अरे, इतनी मीठी बात? आज तो मैं सिर्फ़ आधी सड़क पे कीलें बिछाऊँगा। पक्का।", G),
    ("compliment", "Tu bhi na {p}... sharma diya. Mera chehra laal ho gaya, jaise meri Mustang.", "तू भी ना... शर्मा दिया। मेरा चेहरा लाल हो गया, जैसे मेरी मस्टैंग।", G),
    ("laugh", "Hans le hans le {p}. Mera agla plan padh ke hasi apne aap gayab ho jayegi.", "हँस ले हँस ले। मेरा अगला प्लान पढ़ के हँसी अपने आप ग़ायब हो जाएगी।", G),
    ("laugh", "Haha! Tu hans raha hai? Mere spikes bhi hanste hain... jab tera tyre phatta hai! Phuss!", "हाहा! तू हँस रहा है? मेरे स्पाइक्स भी हँसते हैं... जब तेरा टायर फटता है! फुस्स!", G),
    ("laugh", "Hahaha! Chal, kam se kam hum dono ek baat pe toh hanse. Shuruaat achhi hai.", "हाहाहा! चल, कम से कम हम दोनों एक बात पे तो हँसे। शुरुआत अच्छी है।", G),
    ("thanks", "Thanks? Mujhe? Galti se bol diya hoga! Chal khair... welcome. Par yeh dosti nahi hai, samjha?", "थैंक्स? मुझे? ग़लती से बोल दिया होगा! चल ख़ैर... वेलकम। पर ये दोस्ती नहीं है, समझा?", G),
    ("thanks", "Shukriya kis baat ka? Abhi toh maine kuch kiya hi nahi... ruk, kuch karta hoon.", "शुक्रिया किस बात का? अभी तो मैंने कुछ किया ही नहीं... रुक, कुछ करता हूँ।", G),
    ("race", "Race? Chal! Main apni Mustang mein, tu apne Tesla mein. Bas beech mein charging ke liye mat rukna!", "रेस? चल! मैं अपनी मस्टैंग में, तू अपने टेस्ला में। बस बीच में चार्जिंग के लिए मत रुकना!", G),
    ("race", "Udne wali gaadi? Bhai udega toh traffic police challan kahan bhejegi? Badalon pe?", "उड़ने वाली गाड़ी? भाई उड़ेगा तो ट्रैफ़िक पुलिस चालान कहाँ भेजेगी? बादलों पे?", G),
    ("race", "Speed ka shauk hai? Mere spikes pe aa, ek second mein zero ho jayegi teri speed!", "स्पीड का शौक है? मेरे स्पाइक्स पे आ, एक सेकंड में ज़ीरो हो जाएगी तेरी स्पीड!", G),
    ("why_hate", "Kyun? Kyunki jab se tu aaya hai, mere pump pe line chhoti ho gayi hai. Dhanda hai bhai, dushmani nahi... thodi si hai.", "क्यों? क्योंकि जब से तू आया है, मेरे पंप पे लाइन छोटी हो गई है। धंधा है भाई, दुश्मनी नहीं... थोड़ी सी है।", G),
    ("why_hate", "Nafrat nahi hai {p}... darr hai. Agar sab bijli pe chale gaye, toh Gotiya kahan jayega?", "नफ़रत नहीं है... डर है। अगर सब बिजली पे चले गए, तो गोटिया कहाँ जाएगा?", G),
    ("why_hate", "Mere daadaji ne yeh pump banaya tha. Tera showroom dekhta hoon toh lagta hai unki yaadein bujh rahi hain.", "मेरे दादाजी ने ये पंप बनाया था। तेरा शोरूम देखता हूँ तो लगता है उनकी यादें बुझ रही हैं।", G),
    ("sab_ask", "Kilein? Kaunsi kilein? Mujhe kya pata... sadak pe apne aap ugg aayi hongi. Mausam hai.", "कीलें? कौन सी कीलें? मुझे क्या पता... सड़क पे अपने आप उग आई होंगी। मौसम है।", G),
    ("sab_ask", "Hatwa le khud! Depot mein jaa, counter khareed, aur meri mehnat pe paani pher de. Tujhe yahi toh aata hai.", "हटवा ले ख़ुद! डिपो में जा, काउंटर ख़रीद, और मेरी मेहनत पे पानी फेर दे। तुझे यही तो आता है।", G),
    ("sab_ask", "Business hai bhai. Tu customer khinchta hai, main sadak rokta hoon. Dono apna apna kaam kar rahe hain.", "बिज़नेस है भाई। तू कस्टमर खींचता है, मैं सड़क रोकता हूँ। दोनों अपना अपना काम कर रहे हैं।", G),
    ("threat", "Dhamki?! Mujhe? Mere paas Monster Truck hai, tere paas kya hai? Charger ka taar?", "धमकी?! मुझे? मेरे पास मॉन्स्टर ट्रक है, तेरे पास क्या है? चार्जर का तार?", G),
    ("threat", "Ha ha! Darr gaya main... dekh, kaanp raha hoon. Dhuein se, darr se nahi.", "हा हा! डर गया मैं... देख, काँप रहा हूँ। धुएँ से, डर से नहीं।", G),
    ("love", "Pyaar?! Arre baba... main shaadi-shuda aadmi hoon, aur meri pehli mohabbat diesel hai.", "प्यार?! अरे बाबा... मैं शादीशुदा आदमी हूँ, और मेरी पहली मोहब्बत डीज़ल है।", G),
    ("food", "Chai? Haan! Kadak, adrak wali, aur saath mein samosa. Bijli se bani chai mein woh baat kahan.", "चाय? हाँ! कड़क, अदरक वाली, और साथ में समोसा। बिजली से बनी चाय में वो बात कहाँ।", G),
    ("food", "Bhook lagi hai? Mere dhaabe pe aa, paratha khila dunga. Bas Tesla bahar park karna, ghee mein charge nahi hoti.", "भूख लगी है? मेरे ढाबे पे आ, पराठा खिला दूँगा। बस टेस्ला बाहर पार्क करना, घी में चार्ज नहीं होती।", G),
    ("family", "Meri biwi kehti hai Tesla le lo. Mere bachche kehte hain Tesla le lo. Sirf main kehta hoon... nahi. Akela hoon, par diesel hai.", "मेरी बीवी कहती है टेस्ला ले लो। मेरे बच्चे कहते हैं टेस्ला ले लो। सिर्फ़ मैं कहता हूँ... नहीं। अकेला हूँ, पर डीज़ल है।", G),
    ("family", "Ghar? Ghar mein sab theek hai. Bas mera beta ab cycle chalata hai, kehta hai pollution kam karna hai. Haaye.", "घर? घर में सब ठीक है। बस मेरा बेटा अब साइकिल चलाता है, कहता है पॉल्यूशन कम करना है। हाय।", G),
    ("sport", "Cricket? Main toh tere showroom ke bahar fielding laga ke baitha hoon. Har customer catch karunga!", "क्रिकेट? मैं तो तेरे शोरूम के बाहर फ़ील्डिंग लगा के बैठा हूँ। हर कस्टमर कैच करूँगा!", G),
    ("joke", "Sun ek joke: Tesla wala petrol pump pe aaya. Maine poocha kya chahiye? Bola: bas charger ka rasta. Hahaha!", "सुन एक जोक: टेस्ला वाला पेट्रोल पंप पे आया। मैंने पूछा क्या चाहिए? बोला: बस चार्जर का रास्ता। हाहाहा!", G),
    ("joke", "Joke? Tera electricity bill hi sabse bada joke hai. Main roz padhta hoon, roz hasta hoon.", "जोक? तेरा बिजली का बिल ही सबसे बड़ा जोक है। मैं रोज़ पढ़ता हूँ, रोज़ हँसता हूँ।", G),
    ("joke", "Ek baar ek Cybertruck ne aaina dekha... aaina bola: bhai pehle khud ko unfold kar!", "एक बार एक साइबरट्रक ने आईना देखा... आईना बोला: भाई पहले ख़ुद को अनफ़ोल्ड कर!", G),
    ("weather", "Mausam? Garmi mein mera engine garam, sardi mein mera dil garam. Tera Tesla? Dono mein battery kam!", "मौसम? गर्मी में मेरा इंजन गरम, सर्दी में मेरा दिल गरम। तेरा टेस्ला? दोनों में बैटरी कम!", G),
    ("game", "Yeh sab ek game hai kya? Agar hai, toh main villain hoon... aur villain sabse achhe dialogue bolta hai!", "ये सब एक गेम है क्या? अगर है, तो मैं विलेन हूँ... और विलेन सबसे अच्छे डायलॉग बोलता है!", G),
    ("yes", "Haan? Kis baat pe haan? Main ne toh kuch poocha hi nahi... ya poocha tha? Dhuein mein yaad nahi.", "हाँ? किस बात पे हाँ? मैंने तो कुछ पूछा ही नहीं... या पूछा था? धुएँ में याद नहीं।", G),
    ("no", "Na? Theek hai, na sahi. Gotiya ko na sunne ki aadat hai... customers roz sunate hain.", "ना? ठीक है, ना सही। गोटिया को ना सुनने की आदत है... कस्टमर रोज़ सुनाते हैं।", G),
    ("question", "Sawaal pe sawaal! Main Gotiya hoon, Google nahi. Par jawab hamesha ek hi hai: diesel best hai.", "सवाल पे सवाल! मैं गोटिया हूँ, गूगल नहीं। पर जवाब हमेशा एक ही है: डीज़ल बेस्ट है।", G),
    ("question", "Achha sawaal hai {p}. Itna achha ki main iska jawab apne vakil se poochh ke dunga.", "अच्छा सवाल है। इतना अच्छा कि मैं इसका जवाब अपने वकील से पूछ के दूँगा।", G),
    ("question", "Pata nahi. Aur pata hota bhi toh nahi batata. Competition hai bhai, competition!", "पता नहीं। और पता होता भी तो नहीं बताता। कॉम्पिटिशन है भाई, कॉम्पिटिशन!", G),
    ("question", "Hmm... soch raha hoon. Soch raha hoon. Nahi, kuch nahi aaya. Chai ke baad poochna.", "हम्म... सोच रहा हूँ। सोच रहा हूँ। नहीं, कुछ नहीं आया। चाय के बाद पूछना।", G),
    ("fallback", "Hmm, '{w}'... interesting. Yeh main apni diary mein likh raha hoon, agle sabotage ke liye.", "हम्म... इंटरेस्टिंग। ये मैं अपनी डायरी में लिख रहा हूँ, अगले सबोटाज के लिए।", G),
    ("fallback", "Tu bolta bahut achha hai {p}. Isi liye toh meri dukaan tere showroom ke saamne hai... jasoosi ke liye.", "तू बोलता बहुत अच्छा है। इसी लिए तो मेरी दुकान तेरे शोरूम के सामने है... जासूसी के लिए।", G),
    ("fallback", "Dekh, main shaant hoon. Par mere andar ka engine garam hai. Dhuaan nikal raha hai, dekh!", "देख, मैं शांत हूँ। पर मेरे अंदर का इंजन गरम है। धुआँ निकल रहा है, देख!", G),
    ("fallback", "Accha accha, sun liya. Ab tu meri sun: level {lv} pe aa ke bhi tu mere dhuein ke aage kuch nahi!", "अच्छा अच्छा, सुन लिया। अब तू मेरी सुन: इतने ऊपर आ के भी तू मेरे धुएँ के आगे कुछ नहीं!", G),
    ("fallback", "Itni baatein? Mere customers bhi itna nahi bolte. Woh bas aate hain, dekhte hain, aur tere paas chale jaate hain.", "इतनी बातें? मेरे कस्टमर भी इतना नहीं बोलते। वो बस आते हैं, देखते हैं, और तेरे पास चले जाते हैं।", G),
    ("fallback", "'{w}'? Yeh shabd mere dadaji ne kabhi nahi bola. Woh sirf 'tank full' bolte the.", "ये शब्द मेरे दादाजी ने कभी नहीं बोला। वो सिर्फ़ टैंक फ़ुल बोलते थे।", G),
    ("fallback", "Main sun raha hoon {p}... ek kaan se. Dusre kaan mein meri Mustang ka engine baj raha hai.", "मैं सुन रहा हूँ... एक कान से। दूसरे कान में मेरी मस्टैंग का इंजन बज रहा है।", G),
    ("fallback", "Haan haan, bilkul. Waise... tu kabhi akela mehsoos karta hai? Kuch nahi, bhool ja maine kya poocha.", "हाँ हाँ, बिल्कुल। वैसे... तू कभी अकेला महसूस करता है? कुछ नहीं, भूल जा मैंने क्या पूछा।", G),
    # ------------------------------------------------------------------ help: he grumbles, then tells the truth
    ("hint_0", "Abhi koi museebat nahi hai... matlab abhi tak. Customers ko khush rakh, gaadi chala, paise jod. Main dekh raha hoon!", "अभी कोई मुसीबत नहीं है... मतलब अभी तक। कस्टमर को ख़ुश रख, गाड़ी चला, पैसे जोड़। मैं देख रहा हूँ!", G),
    ("hint_1", "Theek hai, bata deta hoon: Depot mein Jump Hydraulics aur Armor Tires khareed, right-click kar ke laga. Meri kilein bekaar. Haaye!", "ठीक है, बता देता हूँ: डिपो में जंप हाइड्रॉलिक्स और आर्मर टायर ख़रीद, लगा। मेरी कीलें बेकार। हाय!", G),
    ("hint_2", "Andhera hai na? Depot se Solar Roof aur MegaPack le, laga de. Bijli wapas. Mujhe kyun pooch raha hai yaar!", "अँधेरा है ना? डिपो से सोलर रूफ़ और मेगापैक ले, लगा दे। बिजली वापस। मुझे क्यों पूछ रहा है यार!", G),
    ("hint_3", "Barricade? Beast Ram Mode khareed, Cybertruck mein baith aur seedha zor se thok de. Mera cement... sniff.", "बैरिकेड? बीस्ट रैम मोड ख़रीद, साइबरट्रक में बैठ और सीधा ज़ोर से ठोक दे। मेरा सीमेंट... सिसक।", G),
    ("hint_4", "GPS jam hai? Starlink aur Billboard pack le, phir highway ke teen billboard pe apna ad laga. Mera dhanda chaupat.", "जीपीएस जाम है? स्टारलिंक और बिलबोर्ड पैक ले, फिर हाइवे के तीन बिलबोर्ड पे अपना ऐड लगा। मेरा धंधा चौपट।", G),
    ("hint_5", "Mere truck se bachna hai? Chhat pe laal Roadster hai. Space se ud, aur R daba ke EMP maar. Ya phir type kar slash destroy... nahi nahi, mat karna!", "मेरे ट्रक से बचना है? छत पे लाल रोडस्टर है। स्पेस से उड़, और आर दबा के ई एम पी मार। या फिर डिस्ट्रॉय... नहीं नहीं, मत करना!", G),
    # ------------------------------------------------------------------ empathy & partnership
    ("empathy_soft", "Tu... tu sach mein aisa soch raha hai? Kisi ne itne saalon mein mujhse aise baat nahi ki.", "तू... तू सच में ऐसा सोच रहा है? किसी ने इतने सालों में मुझसे ऐसे बात नहीं की।", G),
    ("empathy_soft", "Hmm. Pata nahi kyun, aaj kilein bichhane ka mann nahi kar raha. Tu ajeeb aadmi hai, {p}.", "हम्म। पता नहीं क्यों, आज कीलें बिछाने का मन नहीं कर रहा। तू अजीब आदमी है।", G),
    ("empathy_soft", "Dosti? Mujhse? Seth Gotiya se? Ruk... aankh mein dhuaan chala gaya. Bas dhuaan hai, aansu nahi.", "दोस्ती? मुझसे? सेठ गोटिया से? रुक... आँख में धुआँ चला गया। बस धुआँ है, आँसू नहीं।", G),
    ("empathy_soft", "Sach kahun? Main thak gaya hoon ladte ladte. Par mera pump... mere daadaji ka pump...", "सच कहूँ? मैं थक गया हूँ लड़ते लड़ते। पर मेरा पंप... मेरे दादाजी का पंप...", G),
    ("empathy_soft", "Tu mujhe maaf kar raha hai? Itne spikes ke baad bhi? Hmm... soch ke bataunga. Dil se nahi, dimaag se.", "तू मुझे माफ़ कर रहा है? इतने स्पाइक्स के बाद भी? हम्म... सोच के बताऊँगा। दिल से नहीं, दिमाग़ से।", G),
    ("empathy_join", "Theek hai {p}... haath mila. Aaj se Anant and Gotiya Motors! Tu bijli la, main customers laata hoon. Aur chai bhi!", "ठीक है... हाथ मिला। आज से अनंत एंड गोटिया मोटर्स! तू बिजली ला, मैं कस्टमर लाता हूँ। और चाय भी!", G),
    ("empathy_join", "Yaar, ladai mein kuch nahi rakha. Chal saath mein kaam karte hain. Mere pump pe charger lagwa de... naya zamana!", "यार, लड़ाई में कुछ नहीं रखा। चल साथ में काम करते हैं। मेरे पंप पे चार्जर लगवा दे... नया ज़माना!", G),
    ("partner", "Partner! Aaj teen customer bheje tere paas. Commission ki baat baad mein, pehle chai.", "पार्टनर! आज तीन कस्टमर भेजे तेरे पास। कमीशन की बात बाद में, पहले चाय।", G),
    ("partner", "Socha nahi tha main Tesla bechunga. Par dekh, Cybertruck ke saath selfie bhi le li. Mast hai!", "सोचा नहीं था मैं टेस्ला बेचूँगा। पर देख, साइबरट्रक के साथ सेल्फ़ी भी ले ली। मस्त है!", G),
    ("partner", "Haan partner, bol! Jo bhi kaam ho, Gotiya hazir hai. Bas mujhe bijli ka jhatka mat dena.", "हाँ पार्टनर, बोल! जो भी काम हो, गोटिया हाज़िर है। बस मुझे बिजली का झटका मत देना।", G),
    ("partner", "Kal raat maine apni Mustang ko bataya ki hum partner hain. Woh naraz hai, par main khush hoon.", "कल रात मैंने अपनी मस्टैंग को बताया कि हम पार्टनर हैं। वो नाराज़ है, पर मैं ख़ुश हूँ।", G),
    ("partner", "Tere saath kaam karke pata chala: dushman se achha partner hota hai. Aur paisa bhi zyada hai!", "तेरे साथ काम करके पता चला: दुश्मन से अच्छा पार्टनर होता है। और पैसा भी ज़्यादा है!", G),
    ("cold", "Meethi baatein band kar. Gotiya itni aasani se nahi pighalta. Pehle mera dil jeet, phir dukaan.", "मीठी बातें बंद कर। गोटिया इतनी आसानी से नहीं पिघलता। पहले मेरा दिल जीत, फिर दुकान।", G),
    # ------------------------------------------------------------------ sabotage, taunts, idle
    ("sab_1", "Haha! Maine saari sadak pe KILEIN bichha di, dono taraf! Ab dekhta hoon kaunsi Tesla test drive pe jaati hai!", "हाहा! मैंने सारी सड़क पे कीलें बिछा दीं, दोनों तरफ़! अब देखता हूँ कौन सी टेस्ला टेस्ट ड्राइव पे जाती है!", G),
    ("sab_2", "Bijli ka main switch mere haath mein hai! Khatak! Ab andhere mein showroom chala, {p}!", "बिजली का मेन स्विच मेरे हाथ में है! खटाक! अब अँधेरे में शोरूम चला!", G),
    ("sab_3", "Concrete ke barricade, poori sadak pe, dono taraf! Dekhta hoon ab tera Cybertruck kaise nikalta hai!", "कंक्रीट के बैरिकेड, पूरी सड़क पे, दोनों तरफ़! देखता हूँ अब तेरा साइबरट्रक कैसे निकलता है!", G),
    ("sab_4", "Cybercab ka GPS jam! Ab tera robotaxi gol gol ghoomega, jaise shaadi mein dulha ki ghodi!", "साइबरकैब का जीपीएस जाम! अब तेरा रोबोटैक्सी गोल गोल घूमेगा, जैसे शादी में दूल्हे की घोड़ी!", G),
    ("sab_5", "BAS! Ab bahut ho gaya! Meri Monster Truck ready hai! Showroom ko mitti mein mila dunga! BOOM BOOM!", "बस! अब बहुत हो गया! मेरी मॉन्स्टर ट्रक रेडी है! शोरूम को मिट्टी में मिला दूँगा! बूम बूम!", G),
    ("cry_1", "Mere spikes... mere pyaare spikes! Kisne hata diye? Sniff... wapas do mere kile!", "मेरे स्पाइक्स... मेरे प्यारे स्पाइक्स! किसने हटा दिए? सिसक... वापस दो मेरे कीले!", G),
    ("cry_2", "Solar roof?! MegaPack?! Mera blackout... meri mehnat... Hai Ram! Mere switch ka kya kasoor tha!", "सोलर रूफ़?! मेगापैक?! मेरा ब्लैकआउट... मेरी मेहनत... हे राम! मेरे स्विच का क्या क़सूर था!", G),
    ("cry_3", "Mera barricade! Paanch saal ka cement, ek Cybertruck ne tod diya! Baah baah baah!", "मेरा बैरिकेड! पाँच साल का सीमेंट, एक साइबरट्रक ने तोड़ दिया! बाह बाह बाह!", G),
    ("cry_4", "Starlink?! Billboard?! Mere saare customers udhar ja rahe hain... mere paas koi nahi... Mummy!", "स्टारलिंक?! बिलबोर्ड?! मेरे सारे कस्टमर उधर जा रहे हैं... मेरे पास कोई नहीं... मम्मी!", G),
    ("cry_5", "NAHI! Mera truck band ho gaya! Meri Monster Truck ki aatma bijli ke jhatke se chali gayi... hoo hoo hoo!", "नहीं! मेरा ट्रक बंद हो गया! मेरी मॉन्स्टर ट्रक की आत्मा बिजली के झटके से चली गई... हू हू हू!", G),
    ("truck", "Monster Truck, aage badh! Kaanch ka showroom toh mere liye bas ek bada sa biscuit hai!", "मॉन्स्टर ट्रक, आगे बढ़! काँच का शोरूम तो मेरे लिए बस एक बड़ा सा बिस्किट है!", G),
    ("truck", "Crunch crunch! Aaj showroom ko chai ke saath khaunga!", "क्रंच क्रंच! आज शोरूम को चाय के साथ खाऊँगा!", G),
    ("truck", "Udne wali gaadi se EMP maarega? Pehle meri truck ke tyre se bach ke dikha!", "उड़ने वाली गाड़ी से ई एम पी मारेगा? पहले मेरे ट्रक के टायर से बच के दिखा!", G),
    ("truck", "Truck ka ek tyre tere poore gate se bada hai! Dhaaaam!", "ट्रक का एक टायर तेरे पूरे गेट से बड़ा है! धाम!", G),
    ("truck", "Diesel ki shakti! Dekh mera dhuaan, aasmaan tak jaata hai, tere rocket se pehle!", "डीज़ल की शक्ति! देख मेरा धुआँ, आसमान तक जाता है, तेरे रॉकेट से पहले!", G),
    ("truck_win", "HAHAHA! Mera Monster Truck jeet gaya! Tera showroom ab kabaadi hai, {p}!", "हाहाहा! मेरा मॉन्स्टर ट्रक जीत गया! तेरा शोरूम अब कबाड़ी है!", G),
    ("taunt", "Arre {p}, level {lv} pe pahunch ke itna ud raha hai? Gotiya ke aage sab chhote hain!", "अरे, इतने ऊपर पहुँच के इतना उड़ रहा है? गोटिया के आगे सब छोटे हैं!", G),
    ("taunt", "Tera showroom chamak raha hai... par chamak ke neeche meri saazish tik tik kar rahi hai.", "तेरा शोरूम चमक रहा है... पर चमक के नीचे मेरी साज़िश टिक टिक कर रही है।", G),
    ("taunt", "Dekh, mere paas bhi naya stock aaya hai. Tera ek customer mere paas aaya, aur waapas nahi gaya!", "देख, मेरे पास भी नया स्टॉक आया है। तेरा एक कस्टमर मेरे पास आया, और वापस नहीं गया!", G),
    ("idle", "Aaiye aaiye! Gotiya Petrol Motors! Aaj ka offer: ek litre petrol pe ek litre hawa bilkul free!", "आइए आइए! गोटिया पेट्रोल मोटर्स! आज का ऑफ़र: एक लीटर पेट्रोल पे एक लीटर हवा बिल्कुल फ़्री!", G),
    ("idle", "Kal raat sapna aaya: ek Tesla mere pump pe aayi aur boli, uncle thoda diesel dena. Maza aa gaya!", "कल रात सपना आया: एक टेस्ला मेरे पंप पे आई और बोली, अंकल थोड़ा डीज़ल देना। मज़ा आ गया!", G),
    ("idle", "Gotiya Petrol Motors: jahan har tank mein zindagi hai, aur har customer ke paas bahaana.", "गोटिया पेट्रोल मोटर्स: जहाँ हर टैंक में ज़िंदगी है, और हर कस्टमर के पास बहाना।", G),
    ("idle", "Ek Tesla ki keemat mein main poore mohalle ko diesel pila sakta hoon... aur chai bhi!", "एक टेस्ला की क़ीमत में मैं पूरे मोहल्ले को डीज़ल पिला सकता हूँ... और चाय भी!", G),
    ("idle", "Dhuaan dhuaan dhuaan! Meri kismat bhi dhuein jaisi hai: upar uthti hai aur sabko dikhti hai.", "धुआँ धुआँ धुआँ! मेरी क़िस्मत भी धुएँ जैसी है: ऊपर उठती है और सबको दिखती है।", G),
    ("idle", "Suno suno! Jo Lamborghini dekhne aayega, usko garma garam chai free. Tesla wale ko thanda paani.", "सुनो सुनो! जो लैम्बॉर्गिनी देखने आएगा, उसको गरमा गरम चाय फ़्री। टेस्ला वाले को ठंडा पानी।", G),
    ("idle", "Mere showroom ki lights dekh! Bijli ki gaadi wale se zyada bijli toh main jala raha hoon. Irony!", "मेरे शोरूम की लाइट्स देख! बिजली की गाड़ी वाले से ज़्यादा बिजली तो मैं जला रहा हूँ। आयरनी!", G),
    ("caught", "Arre arre... main toh bas rasta dekh raha tha! Bhaago!", "अरे अरे... मैं तो बस रास्ता देख रहा था! भागो!", G),
    ("caught", "Kuch nahi dekha maine. Kuch nahi. Bye!", "कुछ नहीं देखा मैंने। कुछ नहीं। बाय!", G),
    ("caught", "Oye peeche mat aa! Main ja raha hoon, ja raha hoon!", "ओये पीछे मत आ! मैं जा रहा हूँ, जा रहा हूँ!", G),
    ("caught", "Bas window shopping kar raha tha yaar, itna gussa kyun?", "बस विंडो शॉपिंग कर रहा था यार, इतना गुस्सा क्यों?", G),
    ("caught", "Tera showroom dekhne nahi aaya tha... apna chashma dhundh raha tha!", "तेरा शोरूम देखने नहीं आया था... अपना चश्मा ढूँढ रहा था!", G),
    ("visit", "Waah, kya showroom hai... matlab, theek hai. Chalta hai. Mera zyada achha hai.", "वाह, क्या शोरूम है... मतलब, ठीक है। चलता है। मेरा ज़्यादा अच्छा है।", G),
    ("visit", "Main customer hoon aaj! Dikha, sabse sasti Tesla kaunsi hai? Haha, mazaak kar raha hoon.", "मैं कस्टमर हूँ आज! दिखा, सबसे सस्ती टेस्ला कौन सी है? हाहा, मज़ाक कर रहा हूँ।", G),
    ("visit", "Tera reception wala bhi Tesla ke logo jaisa dikhta hai. Yeh kya sci-fi hai bhai!", "तेरा रिसेप्शन वाला भी टेस्ला के लोगो जैसा दिखता है। ये क्या साइंस फ़िक्शन है भाई!", G),
    ("visit", "Bas ghoom raha hoon. Dekh raha hoon tu itne customers kahan se laata hai. Note kar raha hoon!", "बस घूम रहा हूँ। देख रहा हूँ तू इतने कस्टमर कहाँ से लाता है। नोट कर रहा हूँ!", G),
    ("touch_car", "Haath mat laga! Pehle paise dikha, phir Lamborghini ki chabi!", "हाथ मत लगा! पहले पैसे दिखा, फिर लैम्बॉर्गिनी की चाबी!", G),
    ("touch_car", "Oye oye! Ungliyon ke nishaan pad jayenge! Yeh petrol ki gaadi hai, touchscreen nahi!", "ओये ओये! उँगलियों के निशान पड़ जाएँगे! ये पेट्रोल की गाड़ी है, टचस्क्रीन नहीं!", G),
    ("touch_car", "Pasand aayi? Le ja! Bas ek shart: Tesla ka showroom band kar de. Haha!", "पसंद आई? ले जा! बस एक शर्त: टेस्ला का शोरूम बंद कर दे। हाहा!", G),
    ("rival_up", "Tu upgrade karega toh main bhi karunga! Dekh mera showroom: nayi lights, nayi gaadi, naya Gotiya!", "तू अपग्रेड करेगा तो मैं भी करूँगा! देख मेरा शोरूम: नई लाइट्स, नई गाड़ी, नया गोटिया!", G),
    ("rival_up", "Naya level? Mubarak ho. Maine bhi apni dukaan mein Lamborghini khadi kar di. Ab bol!", "नया लेवल? मुबारक हो। मैंने भी अपनी दुकान में लैम्बॉर्गिनी खड़ी कर दी। अब बोल!", G),
    ("rival_up", "Gold ki patti, neon ki batti, aur Mustang ki garmi! Gotiya Motors ab level up!", "गोल्ड की पट्टी, नियॉन की बत्ती, और मस्टैंग की गर्मी! गोटिया मोटर्स अब लेवल अप!", G),
    ("rival_up", "Spotlight laga di maine chhat pe. Ab raat ko bhi sheher ko pata chalega asli dukaan kaunsi hai!", "स्पॉटलाइट लगा दी मैंने छत पे। अब रात को भी शहर को पता चलेगा असली दुकान कौन सी है!", G),
    ("rival_sale", "Ek aur bik gayi! Dekh {p}, petrol ki demand abhi zinda hai!", "एक और बिक गई! देख, पेट्रोल की डिमांड अभी ज़िंदा है!", G),
    ("rival_sale", "Cash payment! Na EMI, na charger. Yeh hota hai business!", "कैश पेमेंट! ना ईएमआई, ना चार्जर। ये होता है बिज़नेस!", G),
    ("rival_sale", "Hahaha, mera customer muskura ke gaya! Tere customers toh charging ka intezaar karte hain.", "हाहाहा, मेरा कस्टमर मुस्कुरा के गया! तेरे कस्टमर तो चार्जिंग का इंतज़ार करते हैं।", G),
    ("destroyed", "Meri dukaan... meri gaadiyan... sab khatam. Daadaji, maaf karna. Main haar gaya.", "मेरी दुकान... मेरी गाड़ियाँ... सब ख़त्म। दादाजी, माफ़ करना। मैं हार गया।", G),
    ("destroyed", "Tune sab tod diya {p}... ab main kahan jaun? Koi toh kuch bolo...", "तूने सब तोड़ दिया... अब मैं कहाँ जाऊँ? कोई तो कुछ बोलो...", G),
    ("destroyed", "Teen peedhi ki mehnat, ek pal mein dhuaan. Shayad yahi sabak hai... zamana badal gaya.", "तीन पीढ़ी की मेहनत, एक पल में धुआँ। शायद यही सबक है... ज़माना बदल गया।", G),
    ("destroyed", "Sniff... koi mujhse do meethe bol bhi nahi bolta. Sab bas jeetna chahte hain.", "सिसक... कोई मुझसे दो मीठे बोल भी नहीं बोलता। सब बस जीतना चाहते हैं।", G),
    ("cinematic", "Nahi nahi nahi! Mera truck! Meri Lamborghini! Meri dukaan! RUKO!", "नहीं नहीं नहीं! मेरा ट्रक! मेरी लैम्बॉर्गिनी! मेरी दुकान! रुको!", G),
    ("complaint_reply", "Haha! Sun liya na apne customer ki? Gotiya ka kamaal hai yeh!", "हाहा! सुन लिया ना अपने कस्टमर की? गोटिया का कमाल है ये!", G),
    ("complaint_reply", "Madam, mere showroom aa jaiye! Wahan kilein nahi, sirf chai hai!", "मैडम, मेरे शोरूम आ जाइए! वहाँ कीलें नहीं, सिर्फ़ चाय है!", G),
    # ------------------------------------------------------------------ the player's customers complain
    ("c_1", "Excuse me! Road pe kilein bichhi hain! Meri Rolls ka tyre phat gaya, aap kuch karte kyun nahi?", "एक्सक्यूज़ मी! रोड पे कीलें बिछी हैं! मेरी गाड़ी का टायर फट गया, आप कुछ करते क्यों नहीं?", F),
    ("c_1", "Test drive kaise karun? Showroom ke dono taraf spikes hain! Yeh kaisa service hai?", "टेस्ट ड्राइव कैसे करूँ? शोरूम के दोनों तरफ़ स्पाइक्स हैं! ये कैसी सर्विस है?", F),
    ("c_1", "Bhaiya, saamne wale petrol uncle ne phir se kilein daal di. Please jaldi hatwaiye!", "भैया, सामने वाले पेट्रोल अंकल ने फिर से कीलें डाल दीं। प्लीज़ जल्दी हटवाइए!", F),
    ("c_2", "Itna andhera kyun hai? Mujhe toh gaadi ka colour bhi nahi dikh raha!", "इतना अँधेरा क्यों है? मुझे तो गाड़ी का कलर भी नहीं दिख रहा!", F),
    ("c_2", "Bijli chali gayi? Tesla showroom mein bijli nahi? Yeh toh bahut funny hai, par main naraz hoon.", "बिजली चली गई? टेस्ला शोरूम में बिजली नहीं? ये तो बहुत फ़नी है, पर मैं नाराज़ हूँ।", F),
    ("c_2", "Main mobile ki torch jala ke gaadi dekh rahi hoon. Please lights theek karwaiye!", "मैं मोबाइल की टॉर्च जला के गाड़ी देख रही हूँ। प्लीज़ लाइट्स ठीक करवाइए!", F),
    ("c_3", "Sadak pe concrete ki deewar khadi hai! Main ghar kaise jaun?", "सड़क पे कंक्रीट की दीवार खड़ी है! मैं घर कैसे जाऊँ?", F),
    ("c_3", "Roadblock dono taraf! Meri meeting chhoot jayegi. Koi Cybertruck se tod do yeh!", "रोडब्लॉक दोनों तरफ़! मेरी मीटिंग छूट जाएगी। कोई साइबरट्रक से तोड़ दो ये!", F),
    ("c_4", "Mera robotaxi gol gol ghoom raha hai! Mujhe chakkar aa rahe hain!", "मेरा रोबोटैक्सी गोल गोल घूम रहा है! मुझे चक्कर आ रहे हैं!", F),
    ("c_4", "GPS kaam nahi kar raha. Cybercab mujhe teen baar petrol pump le gaya!", "जीपीएस काम नहीं कर रहा। साइबरकैब मुझे तीन बार पेट्रोल पंप ले गया!", F),
    ("c_5", "Woh truck showroom ki taraf aa raha hai! Bachao! Koi kuch karo!", "वो ट्रक शोरूम की तरफ़ आ रहा है! बचाओ! कोई कुछ करो!", F),
    ("c_happy", "Wow, kitna sundar showroom hai! Main toh do gaadiyan le jaungi.", "वाओ, कितना सुंदर शोरूम है! मैं तो दो गाड़ियाँ ले जाऊँगी।", F),
    ("c_happy", "Sab kuch itna smooth hai. Aapka service number one hai!", "सब कुछ इतना स्मूद है। आपकी सर्विस नंबर वन है!", F),
]


def ids():
    out, count = [], {}
    for key, text, hindi, voice in L:
        count[key] = count.get(key, 0) + 1
        out.append(("%s_%d" % (key, count[key]), key, text, hindi, voice))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", metavar="DIR", help="render the clips with the Piper voices found in DIR")
    a = ap.parse_args()
    rows = ids()
    os.makedirs(os.path.join(ASSETS, "gotiya"), exist_ok=True)
    with open(os.path.join(ASSETS, "gotiya", "lines.json"), "w", encoding="utf-8") as fh:
        json.dump([{"id": i, "key": k, "text": t, "voice": v} for i, k, t, h, v in rows], fh, ensure_ascii=False, indent=0)
    sounds_path = os.path.join(ASSETS, "sounds.json")
    sounds = json.load(open(sounds_path, encoding="utf-8")) if os.path.exists(sounds_path) else {}
    sounds = {k: v for k, v in sounds.items() if not k.startswith("voice.")}
    for i, k, t, h, v in rows:
        sounds["voice." + i] = {"sounds": [{"name": "tesla_tycoon:voice/" + i, "stream": False}],
                                "subtitle": "subtitles.tesla_tycoon.voice." + ("gotiya" if v == G else "customer")}
    with open(sounds_path, "w", encoding="utf-8") as fh:
        json.dump(sounds, fh, indent=1)
    print("%d lines (%d keys)" % (len(rows), len({k for _, k, _, _, _ in rows})))
    if not a.voice:
        return 0
    out_dir = os.path.join(ASSETS, "sounds", "voice")
    os.makedirs(out_dir, exist_ok=True)
    models = {G: os.path.join(a.voice, "hi_IN-pratham-medium.onnx"), F: os.path.join(a.voice, "hi_IN-priyamvada-medium.onnx")}
    tmp = tempfile.mkdtemp()
    for i, k, t, h, v in rows:
        ogg = os.path.join(out_dir, i + ".ogg")
        if os.path.exists(ogg) and os.path.getmtime(ogg) > os.path.getmtime(__file__):
            continue
        wav = os.path.join(tmp, i + ".wav")
        # Gotiya talks a bit slower and deeper (pitch lowered with ffmpeg), the customers a bit quicker
        subprocess.run([sys.executable, "-m", "piper", "-m", models[v], "-f", wav, "--length-scale", "1.08" if v == G else "0.95"],
                       input=h.encode("utf-8"), check=True, capture_output=True)
        af = "asetrate=22050*0.90,aresample=22050,atempo=1.11,volume=1.6" if v == G else "volume=1.4"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-af", af, "-ac", "1", "-ar", "22050", "-c:a", "libvorbis", "-q:a", "1", ogg], check=True)
    total = sum(os.path.getsize(os.path.join(out_dir, f)) for f in os.listdir(out_dir))
    print("voice clips: %d files, %.1f MiB" % (len(os.listdir(out_dir)), total / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
