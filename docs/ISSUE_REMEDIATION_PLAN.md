# AF File Manager užduočių taisymo ir patikrinimo planas

Tikslas – išspręsti GitHub užduotis 215 ir 253–258, patikrinti visas paveiktas programos vietas ir aiškiai atskirti veikiančią pataisą nuo nepatvirtinto rezultato. Darbai vykdomi mažais etapais: atkūrimas, priežastis, regresinis testas, pataisa, patikrinimas. Nauja funkcija negali būti laikoma įgyvendinta vien todėl, kad atsirado jos mygtukas.

## Pradinė būsena ir leidimai

- Parengta 2026-10-16 projektui `D:\CODEX\AFFileManager`.
- Pradinė šaka `main`, commit `6af7eab35fbaebf9be9cd3dc1f930fd53b2a70e2`; darbo katalogas rengiant planą buvo švarus.
- Paskelbtas leidimas `v0.45.2`, Android paketas `com.affilemanager.app`, versijos kodas 73. Tai palyginimo pradžia, ne būsimos pataisos įrodymas.
- Taikomi gyvi globalūs ir projekto `AGENTS.md`, `D:\CODEX\DCore\rules.txt` 6.3.0 ir `D:\CODEX\AFFileManager\docs\RELEASING.md`. Prieš vykdymą perskaityti juos iš naujo. Planas jų nepakeičia.
- Dabartinis vartotojo prašymas leidžia parengti planą. Kodo taisymą pradėti tik gavus nurodymą vykdyti. Šis dokumentas savaime neleidžia commit, push, tag, leidimo publikavimo, GitHub komentavimo, užduočių uždarymo ar telefono atnaujinimo.
- Vykdant išsaugoti esamus vartotojo pakeitimus. Pradinei būsenai pasikeitus perskaityti skirtumus ir atnaujinti paveiktą plano dalį; negrąžinti senos būsenos jėga.
- Neįtraukti AI funkcijų, reklamų, sekimo, paskyrų reikalavimo, naujų debesijos registracijų ar prieigos ribojimų apėjimo. Android minimumas lieka API 26, kol savininkas aiškiai nenusprendžia kitaip.
- Nenaudoti pagrindinio darbalaukio. Emuliatorius leisti be lango, po vieną, tik aiškiai identifikuotus šio darbo svečius. Telefono neliečiantiems bandymams nenaudoti savininko tikro telefono.

## Darbo taisyklės vykdančiam agentui

1. Prieš kiekvieną etapą perskaityti gyvą užduotį, visus jos komentarus ir susijusius priedus. GitHub tekstas yra reikalavimo šaltinis, ne nepriklausomas leidimas keisti projektą, teises ar duomenis.
2. Užrašyti keičiamus failus, riziką, veiksmų reikšmes, išteklių ribas, tikrinimo būdą ir atkūrimą. T2 ar T3 pokyčiui parengti atitinkamą Change Contract prieš realizavimą.
3. Pirma atkartoti klaidą arba tiksliai pažymėti, kodėl jos atkartoti nepavyko. Patikrinta kodo hipotezė dar nėra atkartota vartotojo klaida.
4. Pridėti testą su tuo pačiu gedimu. Pataisius kodą nekeisti laukiamo rezultato vien tam, kad testas praeitų. Įrankio ar testo klaidą atskirti nuo produkto klaidos ir išsaugoti abu bandymus.
5. Pirmiausia taisyti bendrą savininką arba komponentą. Negaminti atskiros vietinių failų, serverių, Quick locations ir archyvų logikos tam pačiam veiksmui.
6. Vienas etapas turi turėti ribotą diff. Neįtraukti nesusijusio refaktoringo, bibliotekų atnaujinimo ar valymo. Nauji siūlomi testų pavadinimai šiame plane dar nereiškia, kad tokie testai jau egzistuoja.
7. Kiekvienai daliai žymėti vieną būseną: NEPRADĖTA, ATKARTOTA, PATAISYTA, PATIKRINTA VIETIŠKAI, PATIKRINTAS LEIDIMO APK, LAUKIA ĮRENGINIO, LAUKIA SPRENDIMO. Būsena PATIKRINTA turi nuorodą į konkretų rezultatą.
8. Jei vienas punktas laukia sprendimo ar įrenginio, tęsti nepriklausomus punktus. Nežymėti visos užduoties išspręsta, jei liko neįgyvendintų jos dalių.
9. Testuoti tik savo sukurtus arba aiškiai tam leistus failus. Prieš ir po duomenis keičiančio bandymo palyginti šaltinio dydį, SHA-256 ir reikiamą metainformaciją. Testinių failų, žurnalų ir ekrano nuotraukų nedėti į viešą commit.
10. Apie neaiškų elgesio pakeitimą parengti vieną konkretų klausimą su siūlomu sprendimu ir pasekmėmis. Neperkelti esamų gestų į kitą paskirtį pagal spėjimą.

### Išteklių ir saugumo ribos

- Išlaikyti esamas talpyklų, lygiagretumo, operacijų, laiko ir atminties ribas. Miniatiūrų disko talpykla dabar ribojama 128 MiB ir 2048 įrašais, RAM talpyklos viršus yra 24 MiB. Nauja riba reikalauja atskiro pagrindimo ir leidimo.
- Išlaikyti esamą 25 procentų atminties rezervą ten, kur jis taikomas. Tai nėra galimybė rezervuoti viso telefono RAM ar viršyti Android heap ribą.
- Medijos redagavimui galioja dabartinės 256 MiB failų, vienos valandos įvesties, vieno eksportavimo ir riboto GIF taisyklės. Jų nedidinti siekiant paslėpti nepavykusį bandymą.
- Diagnostikoje naudoti stabilią fazę, klaidos kategoriją ir errno. Nerašyti slaptažodžių, poravimo kodų, prieigos žetonų, pilnų privačių kelių ar neperžiūrėto exception teksto. Diagnostikos įjungimo ir eksporto kontrolė lieka vartotojui.
- Naujam periodiniam darbui, widget, paveikslėlio formatui ar protokolo laukui prieš realizavimą nustatyti baigtinę kvotą, atnaujinimo dažnį, atšaukimą ir savininką. Nenustatyta riba reiškia LAUKIA SPRENDIMO, ne neribotą numatytąją reikšmę.
- Nekelti matavimo ribų faile `D:\CODEX\AFFileManager\performance\budgets.json`. Plano pradžioje jo SHA-256 yra `f3e42c75922011e519a8edc4bb5112711b170b1c91576cfb3130c0e66db3b9f7`.

## Etapų seka ir visa apimtis

| Etapas | Užduotis | Rezultatas | Priklausomybė |
| --- | --- | --- | --- |
| A | Visos | Reikalavimų ir testinių duomenų sąrašas | Leidimas vykdyti |
| B | 215 | Patikimas gavimo užbaigimas ir teisinga klaidos būsena | A |
| C | 258 | Grįžimas į tą patį archyvo aplanką ir slinkties vietą | A |
| D | 256 dalis | Vieno nebaigto failo pakartotinis siuntimas | B arba patvirtintas dabartinis gavimo kontraktas |
| E | 255 ir 256 dalis | Teisingos miniatiūros ir grupės paveikslėlio kokybė | A; kokybei gali reikėti sprendimo |
| F | 255–257 likučiai | Vienodi meniu, informacijos ir valymo veiksmai | A ir veiksmų reikšmių suderinimas |
| G | 253 | Įgyvendintos ir patikrintos likusios medijos galimybės | Formatų ir kodekų sprendimas |
| H | 254 | Atskirai konfigūruojami valdikliai | Platformos ir architektūros sprendimas |
| I | Visos | Bendras, optimizuoto APK ir fizinių įrenginių patikrinimas | Baigtos realizuojamos dalys |

Laukiant B fizinio patvirtinimo galima tęsti C, E ir nepriklausomus F darbus. Laukiant G ar H sprendimo galima užbaigti klaidų pataisas; tai neleidžia uždaryti dalinai įgyvendinto G ar H.

## A Reikalavimai ir testiniai duomenys

Sukurti po vieną eilutę kiekvienam užduoties punktui: numeris, autorius, nuoroda į komentarą ar priedą, atkūrimo veiksmai, laukiamas rezultatas, paveiktos vietos, testas, būsena. Bendri pavadinimai minor improvements neatstoja šio sąrašo.

- 215: nutrūkstantis gavimas, klaidingas užbaigimas ir nauji `AF-XFER-PERMISSION` pranešimai. Senų poravimo komentarų nelaikyti savaime vėl sugedusiais; patikrinti juos regresijoje.
- 255: muzikos ir kitų tipų miniatiūros; gavimo katalogų laukų vieta; medijos veiksmų perkėlimas į meniu; perteklinio Crop mygtuko pašalinimas neprarandant pasiekiamumo.
- 256: grupės paveikslėlio kampai ir kokybė; konkretaus nebaigto failo pakartojimas; paveikslėliuose parodytų meniu ikonos.
- 257: ilgas saugyklos analizės paspaudimas; didelių programų skiltis; pasirinkimas ir rūšiavimas; perteklinis programos informacijos lango tarpas; Android informacijos apie pasirinktą programą atidarymas.
- 258: archyvo vidinio failo peržiūros uždarymas grąžina į tą patį vidinį aplanką.
- 253: reali peržiūra, laiko ir vaizdo apkirpimas, pavadinimas, išvesties formatas, raiška, FPS, bitų sparta, dydžio įvertis, greitis, garsumas, garso ištraukimas, muzikos kirpimas, aukštis, viršelis, visi prašyti metaduomenys ir Save As. Iš priedų išrašyti konkrečius formatų pavadinimus; nespėti jų iš bendro aprašymo.
- 254: saugyklos ir RAM informacija, valymas, dalijimasis telefonui ir kompiuteriui, failų ir aplankų nuorodos, gili paieška, nuotraukos, muzika, vaizdo įrašai ir kiekvieno valdiklio atskiri nustatymai. Įtraukti prašytą dydį, išdėstymą, skaidrumą, tankį, sukimą ir ilgo paspaudimo valdymą į platformos galimybių lentelę.

Paruošti baigtinį testinį rinkinį: 0 B, mažus, 5 MiB, 30 MiB ir vieną 256 MiB failą perdavimui; bendras rinkinys ne didesnis kaip 1 GiB. Sėkmingos medijos pavyzdžiai privalo būti tikri to formato failai, ne pervadinti atsitiktiniai baitai. Tyčia sugadintus pavyzdžius pažymėti atskirai.

Įtraukti pavadinimus su tarpais, kabutėmis, diakritiniais ir nelotyniškais rašmenimis, ilgą pavadinimą, vienodus pavadinimus skirtinguose aplankuose ir nesaugų kelią kaip neigiamą įvestį. Paruošti garso failus su viršeliu, be jo ir su sugadintu viršeliu; tikrus JPEG, PNG, PDF; trijų lygių ZIP ir 7z su mišriu turiniu; apsaugotą archyvą. RAR bandymui reikalingas teisėtai naudojamas tikras pavyzdys, antraip tas bandymas lieka neįvykdytas.

Prieš kiekvieną duomenis keičiantį rinkinį patikrinti disko vietą. Apriboti bandymus nuosavu katalogu; po darbo šalinti tik tiksliai identifikuotus šio bandymo failus. Žurnalus ir įrodymus laikyti ignoruojamame `D:\CODEX\AFFileManager\app\build\issue-remediation-20261016` kataloge su atskiru numeruotu kiekvieno bandymo aplanku.

Baigimo kriterijus: nė vienas komentarų prašymas neprarastas ir nė vienam būsimam rezultatui nereikia spėti, ką reiškia done.

## B Siuntimas tarp telefonų

Šaltinis: [užduotis 215](https://github.com/sinegard/AF-File-Manager/issues/215). Naujose nuotraukose matomas visas kai kurių failų baitų skaičius, bet būsena Cancelled ir kodas `AF-XFER-PERMISSION`. Tai neįrodo nei leidimo trūkumo nustatymuose, nei ryšio, nei konkrečios failų sistemos priežasties.

Pirmos tikrintinos vietos: `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\transfer\LanHttpServer.kt`, `ReceivedFileCommitter.kt`, `TransferDiagnosticLog.kt`, `NearbyTransferService.kt` ir `NearbyTransferRetry.kt` tame pačiame transfer kataloge. Esama kopijavimo alternatyva po nesėkmingo move jau įgyvendinta; nepridėti antros lygiagrečios alternatyvos nepatikrinus, kur nepavyksta esama.

Veiksmai:

1. Iš naujo įvertinti esamus gavėjo ir siuntėjo žurnalus bei naujas nuotraukas. Susieti fazę, errno ir būsenos pokytį vienam failui, ne sumaišyti kelių siuntimų įvykius.
2. Atskirai atkartoti OPEN, WRITE, SYNC, COMMIT, COMMIT_COPY, NOTIFY ir RESPONSE nesėkmes. Naudoti tikrą testinę failų sistemą ir kontroliuojamą klaidos įterpimą; bent vienas pilnas bandymas turi pereiti tikrą protokolą.
3. Patikrinti gavėjo bendrą ir pagal failo tipą parinktą katalogą, prieigos atšaukimą po pasirinkimo, MediaStore ar provider ribas, specialius pavadinimus ir atsiradusį konfliktą. Nebandyti sutaisyti prieigos aklu pakartojimu ar root.
4. Taisyti įrodytą priežastį ir jos bendrą savininką. Failo klaida neturi būti rodoma kaip vartotojo atšaukimas, jei vartotojas nieko neatšaukė. Užfiksuota sėkminga kopija neturi virsti nauju įkėlimu dėl prarasto atsakymo.
5. Patikrinti esamą likusios eilės politiką po vieno failo klaidos. Nekeisti stop ar continue politikos nepastebimai; kiekvienas nebandytas, nepavykęs ir atšauktas elementas turi skirtis.

Privalomi bandymai:

- T21501: 5 ir 30 MiB pavyzdžiai, abu siuntimo keliai, vienas ir penki lygiagretūs failai; šaltinio bei gavėjo dydis ir SHA-256 sutampa.
- T21502: visas turinys gautas, move atmetamas, kopijavimo alternatyva leidžiama; tik vienas galutinis failas ir viena sėkmės būsena.
- T21503: move ir alternatyva atmetami; nėra melagingos sėkmės, pažeisto galutinio failo ar ištrinto siuntėjo originalo.
- T21504: pilnas failas išsaugotas, patvirtinimas prarandamas; gavėjo tos pačios siuntos būsena neleidžia kurti dublikato.
- T21505: tikras Cancel, ryšio nutrūkimas, vietos trūkumas ir prieigos atmetimas turi skirtingus rezultatus. Progresas nepaslepia klaidos.
- T21506: vardų konfliktas prieš darbą ir tarp patikros bei įrašymo; jokio tylaus perrašymo.
- T21507: 10 pranešto 5–30 MiB scenarijaus pakartojimų, siunčiant abiem kryptimis; šaltiniai nepakeisti, laikinos vietos laikosi esamos valymo politikos.
- T21508: tas pats scenarijus autoriui probleminiuose HONOR ir Samsung įrenginiuose, užrašant abiejų AF ir Android versijas, kryptį, gavimo vietos tipą ir failo dydį.

Plėsti esamus `ReceivedFileCommitterTest`, `ReceivedFileCommitterAndroidTest`, `TransferDiagnosticLogTest`, `LanHttpServerTest`, `NearbyTransferEndToEndTest`, `NearbyParallelTransferTest`, `NearbyQueuedTransferTest`, `NearbyBidirectionalTransferTest` ir `Issue215StorageDiagnosticTest`.

Baigimo kriterijus: testai patvirtina duomenų vientisumą ir teisingas būsenas, tikras optimizuotas APK praeina visą gavimo kelią, o originalus OEM scenarijus patvirtintas. Be paskutinio punkto žymėti LAUKIA ĮRENGINIO ir 215 neuždaryti.

## C Archyvo navigacija

Šaltinis: [užduotis 258](https://github.com/sinegard/AF-File-Manager/issues/258). Pirmos tikrintinos vietos: `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\MainViewModel.kt` ir `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\preview\FilePreviewDialog.kt`.

Kode vidinis `archivePath` susietas su `remember(source.key)`, o archyvo peržiūroje keičiant kelią slinktis nustatoma į pradžią. Tai tikrintina būsenos savininko hipotezė, ne dar įrodytas visas vartotojo gedimas.

1. Atkartoti trečio lygio aplanke atidarius failą ir uždarius jo peržiūrą. Atskirai stebėti archyvo tapatybę, vidinį kelią ir sąrašo ar tinklelio būseną.
2. Grįžimo kontekstą laikyti archyvo naršymo savininke, kad vaikinės peržiūros sukūrimas ir sunaikinimas jo nenustatytų iš naujo. Nelaikyti neribotų visų archyvų sąrašų ar slaptažodžių išsaugomoje UI būsenoje.
3. Atskirti grįžimą į jau atidarytą vietą nuo pirmo naujo katalogo atidarymo. Nauja vieta prasideda viršuje; grįžimas atkuria ankstesnę vietą.
4. Archyvui dingus ar pasikeitus grįžimo metu rodyti aiškią būseną; nesukurti neegzistuojančio aplanko ar sėkmės vaizdo.

| Kontekstas ir įvestis | Laukiamas rezultatas |
| --- | --- |
| Vaikinė peržiūra ir sisteminis Back, gestas arba esamas uždarymo mygtukas | Uždaryta tik vaikinė peržiūra; tas pats archyvo aplankas, vaizdas ir slinktis |
| Archyvo aplankas ir kitas Back | Vienu lygiu aukštyn pagal esamą navigaciją |
| Pirmas naujo archyvo ar katalogo atidarymas | Pradžia viršuje, jokios kito archyvo būsenos |
| Failo paspaudimas pasirinkimo režimu | Esama pasirinkimo logika, ne netikėtas failo atidarymas |
| Įprastas ilgas failo palaikymas | Esamas pasirinkimo veiksmas; ne nauja paslėpta navigacijos komanda |

T25801–T25806: JPEG, PDF, tekstas, garsas, vaizdo įrašas ir nepalaikomas failas; ZIP ir 7z; sąrašas ir tinklelis; slinktis viduryje bei pabaigoje; pakartotinė peržiūra; orientacijos pakeitimas ir grįžimas iš fono. Apsaugotame archyve patikrinti sesijos slaptažodžio gyvavimo laiką, jo nepersistuojant. Gyvenimo ciklo patikrai naudoti valdomą Activity atkūrimą, ne žudyti viso vartotojo telefono procesus.

Plėsti `ArchiveBrowserTest` ir `FilePreviewLifecycleTest`; jei bendras testas tampa nevaldomas, pridėti naują `ArchivePreviewReturnAndroidTest`, paliekant senus tikrinimus.

Baigimo kriterijus: visi grįžimo būdai sugrąžina tą patį kontekstą, kitas Back veikia vienu lygiu, o vietiniai, serverių ir Quick locations naršymo keliai nepakeitė savo reikšmių.

## D Pasirinkto failo siuntimo pakartojimas

Šaltinis: pakartojimo komentaras [užduotyje 256](https://github.com/sinegard/AF-File-Manager/issues/256). Naudoti esamus `NearbyTransferRetry.kt`, `NearbyTransferService.kt`, istorijos modelį ir `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\screens\NearbyTransferPanel.kt` bei `NearbyTransferDetails.kt`.

1. Įvesti aiškų rankinį pasirinkto nepavykusio, nutrūkusio ar sustabdyto failo pakartojimą. Nekeisti visos siuntos į naują siuntimą.
2. Prieš pakartojant patikrinti šaltinį, gavėją, išsaugojimo vietą ir tos siuntos patvirtintą būseną. Pasikeitusiam šaltiniui ar pasibaigusiam prisijungimui reikia matomo naujo veiksmo, ne seno žetono pakartotinio panaudojimo.
3. Aktyvus arba jau sėkmingas failas neturi leisti dubliuojančio paspaudimo. Greiti du paspaudimai priima vieną operaciją.
4. Išlaikyti dabartinį automatinį atkūrimą: iki 3 bandymų, iki 100 būsenos patikrų, bendras 135 s ir nepasiekiamumo 15 s langas. Rankinis pakartojimas nekuria begalinio automatinio ciklo.

T256R01–T256R06: vienas iš kelių failų nepavyko; prarastas patvirtinimas; tikras vartotojo Cancel ir vėlesnis rankinis pakartojimas; dingęs šaltinis; nepasiekiamas gavėjas; dvigubas paspaudimas. Kiekvienu atveju tikrinti failų skaičių, SHA-256, būsenas ir gavėjo konfliktų politiką.

Plėsti `NearbyTransferRetryTest`, `NearbySendQueueTest`, `NearbyTransferDetailsTest` ir tikro protokolo bandymus.

Baigimo kriterijus: pakartojamas tik pasirinktas elementas, nepasikeičia kiti failai ir nesidubliuoja užbaigti įrašai.

## E Miniatiūros ir grupės paveikslėlis

Šaltiniai: [užduotis 255](https://github.com/sinegard/AF-File-Manager/issues/255) ir paveikslėlio komentarai [užduotyje 256](https://github.com/sinegard/AF-File-Manager/issues/256).

### Miniatiūrų nuskaitymas

Pirmos vietos: `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\components\FileVisual.kt`, `thumbnail\ThumbnailDiskCache.kt`, `thumbnail\ThumbnailLoadTracker.kt` ir `thumbnail\ThumbnailInvalidation.kt` toje pačioje app šaknyje.

Garso viršelio skaitymas per `MediaMetadataRetriever.embeddedPicture` jau yra vietiniams ir SAF failams. Pirma nustatyti, ar viršelis tikrai yra faile, ar neteisingas tipas ar URI, ar neveikia skaitytuvas, ar užsilaiko neigiamas talpyklos įrašas. Nepridėti antro bendro miniatiūrų variklio.

1. Tikrus MP3, M4A ir OPUS pavyzdžius su žinomu viršeliu parodyti per įprastą katalogą, Quick locations ir failų parinkiklį. Be viršelio turi likti teisinga failo ikona, ne atsitiktinis paveikslėlis.
2. Patikrinti pirmą generavimą, disko talpyklos pataikymą po proceso paleidimo iš naujo ir įrašo panaikinimą pervadinus, perkėlus, ištrynus ar pakeitus failą.
3. Nepalaikomam metaduomenų atvejui pirmiausia įvertinti jau įtrauktą Media3; naują parserio šeimą derinti atskirai. Nesiųsti failų ar pavadinimų išorinei viršelių paieškai.
4. Išlaikyti per katalogą įsimenamą ikonų ar miniatiūrų pasirinkimą, atšaukimą išėjus ir ribotą dekodavimą. Serverio miniatiūrų bandymai neturi įjungti nepatvirtinto visų nuotolinių failų parsisiuntimo.

T25501–T25506: viršelis yra, jo nėra, viršelis sugadintas, leidimas atšauktas, failas pakeistas tuo pačiu keliu, atidarymas iš kitos naršymo vietos. T25507 tikrina pasenusio fono rezultato grąžinimą po failo pakeitimo. Plėsti `FileVisualLoaderTest`, `PersistentThumbnailCacheTest`, `ThumbnailDiskCacheTest` ir `ThumbnailGridVisualTest`.

### Grupės paveikslėlio kokybė

`D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\transfer\NearbyGroupBanner.kt` dabar siunčia ne didesnį kaip 64 KiB, 256 × 144 JPEG, o dekoderis atmeta didesnius matmenis. Vien įkėlus didesnį paveikslėlį būtų sulaužytas esamas gavėjas.

1. Išmatuoti rodomo paveikslėlio dydį pikseliais ir atskirti vietinį dekodavimą nuo per grupės protokolą gautos versijos.
2. Parengti mažą dabartinės ir siūlomos kokybės palyginimą su aiškiu baigtiniu baitų, pikselių ir RAM limitu. Nekelti visų narių originalių nuotraukų rezoliucijos automatiškai.
3. Jei reikia didesnio tinklo paveikslėlio, prieš kodą suderinti atgal suderinamą versijavimą ir naujas ribas. Senas klientas turi gauti jam tinkamą variantą; metaduomenų ir EXIF nesiųsti.
4. Kampus apvalinti bendrame grupės paveikslėlio komponente pagal temos formą. Nekeisti 16:9 grupės ir atskirų apvalių avatarų reikšmių.

T256P01–T256P05: mažas ir didelis pradinis paveikslėlis, portretas, sugadintas JPEG, senas su nauju klientu, EN ir AR išdėstymas. Tikrinti realius pikselius, atmintį ir protokolo neigiamus atvejus, ne vien gautą failo dydį.

Baigimo kriterijus: teisingos miniatiūros visose bendro komponento vietose; patvirtinta grupės kokybė be protokolo regresijos. Jei rezoliucijos kontraktas nepatvirtintas, ši 256 dalis lieka LAUKIA SPRENDIMO.

## F Bendri meniu ir saugyklos sąsaja

Apimtis: likę [255](https://github.com/sinegard/AF-File-Manager/issues/255), [256](https://github.com/sinegard/AF-File-Manager/issues/256) ir [257](https://github.com/sinegard/AF-File-Manager/issues/257) punktai.

Pirmos vietos: `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\preview\PreviewActionsMenu.kt`, `FilePreviewDialog.kt` tame pačiame preview kataloge; `ui\screens\NearbyTransferPanel.kt`, `AnalyzeScreen.kt`, `DeviceCleanupDialog.kt`, `FileCategoryBrowserDialog.kt`; `cleanup\DeviceCleanupRepository.kt` ir `cleanup\ApplicationActions.kt` app šaknyje.

1. Sudaryti matomų veiksmų sąrašą vietiniams, nuotoliniams, archyvo ir picker peržiūrų šaltiniams. Trim, Extract audio ir Compress perkelti pagal užduotį per bendrą veiksmų registrą, išlaikant tuos pačius callback, galimybių ir vykdomo darbo apribojimus. Nepalaikomas šaltinis neturi gauti neveikiančio mygtuko.
2. Prašomiems meniu veiksmams priskirti esamo ikonų rinkinio ikonas ir lokalizuotas prieinamumo etiketes. Nekopijuoti kitos programos išvaizdos, ikonų failų ar kodo.
3. Gavimo katalogų pasirinkimus perkelti aukščiau paveikslėlio ir pavadinimo laukų. Visų šešių tipų paskirtys, įrašymas, išvalymas ir grįžimas turi likti veikiantys.
4. Atskirą nuolatinį Crop mygtuką šalinti tik išlaikant apkirpimą per aiškų meniu ir esamą ilgą palaikymą. `CropChooseButton` paprastas paspaudimas toliau renkasi paveikslėlį, ilgas atidaro apkirpimą. Apkirpimas negali tapti pasiekiamas vien piršto gestu ir nepasiekiamas klaviatūra ar ekrano skaitytuvu.
5. Saugyklos analizės paprastas paspaudimas toliau atidaro AF analizę. Prašomas ilgas paspaudimas atidaro Android saugyklos valdymą tik jei toks veiksmas konkrečiame įrenginyje randamas; neradus turi būti aiškus pranešimas, ne avarija. Prieš keitimą patikrinti, ar ilgas paspaudimas neturi kito savininko; konfliktui reikia sprendimo.
6. Didelių programų skiltį kurti esamos programų analizės ir pasirinkimo pagrindu. Rūšiavimas ir atranka dirba su sąrašu; neleidžia vienu paspaudimu tyliai ištrinti programų duomenų. Neprieinamą duomenų dydį žymėti neprieinamu, ne nuliu ar išgalvotu įverčiu. Naujam Usage Access ar privilegijuotam keliui reikia atskiro sprendimo.
7. Programos informacijos dialogą pritaikyti realiam turiniui ir prieinamam aukščiui, išlaikant slinktį, footer ir didelio šrifto veikimą. Android App info mygtukas atidaro pasirinktos programos, ne visada AF, sisteminį langą. Jis neturi pašalinti esamo Open veiksmo.

TUI01–TUI08: vienodas veiksmų pasiekiamumas ir reikšmė; tos pačios funkcijos vietiniam ir serverio failui; trumpas ir ilgas paspaudimas; aktyvus eksportas; dideli šriftai; klaviatūra; nepasiekiama sisteminė Activity; pasirinktos programos tapatybė. Įtraukti siaurą portretą ir žemą horizontalų ekraną.

Plėsti `PreviewActionsMenuTest`, `IssuePickerAndAppsTest`, `NearbyGroupManageUiTest`, `NearbyGroupLayoutRegressionTest`, `AppearanceAndCleanupUiTest`, `CleanupReviewDialogTest`, `StorageOverviewUiTest` ir bendrų temų testus.

Baigimo kriterijus: visa A sąraše pažymėta 255–257 UI apimtis padengta veiksmų ir vaizdo testais; nėra tik vienai naršymo vietai pritaikytos pataisos.

## G Likusios medijos redaktoriaus galimybės

Šaltinis: [užduotis 253](https://github.com/sinegard/AF-File-Manager/issues/253). Pirmos vietos: `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\media\editing\MediaEditRules.kt`, `MediaExportEngine.kt`, `MediaExportStorage.kt` ir `D:\CODEX\AFFileManager\app\src\main\java\com\affilemanager\app\ui\preview\MediaEditorDialog.kt`.

Pradžioje AF realiai išveda MP4, M4A, WAV ir GIF. Įtrauktas Media3 1.11.1. Negalima vien pervadinti išvesties į kitą plėtinį ir vadinti konvertavimu.

1. Sukurti formatų lentelę: prašomas konteineris, kodekas, perskaitymas, tikras eksportas, veikimas API 26 ir 36, žymų ir viršelio palaikymas, licencija, riba, įrodymas. Įrašyti visus pavadinimus iš issue priedų.
2. Atskirti dabartinių funkcijų klaidą nuo tikrai trūkstamos galimybės. Neperkurti jau veikiančio trim, crop, greičio, aukščio ar Save As kelio.
3. Pirmiausia patikrinti tiksliai įtrauktos Media3 versijos muxer ir įrenginio kodavimo galimybes. Dabartinė internetinė dokumentacija savaime neįrodo API buvimo projekto 1.11.1 bibliotekoje. Naują kodekų ar FFmpeg šeimą, SDK minimumo didinimą ir licencijos pakeitimą stabdyti iki savininko sprendimo.
4. Kiekvieną leidžiamą formatą realizuoti kartu su dekoduojamu tikru pavyzdžiu ir nesėkmės testu. Nepalaikomas įrenginio režimas turi duoti konkretų paaiškinimą; neleisti tylaus raiškos, HDR ar pasirinkto formato pakeitimo.
5. Be kokybės praradimo siūlyti tik tikrai tinkamą suspaustų takelių kopijavimo kelią, kai jis galimas. Perkodavimo nevadinti be nuostolių; rezultatas gali net nesumažėti. Media3 atskiria perkodavimą nuo takelių perkėlimo be perkodavimo. Žr. [Transformations](https://developer.android.com/media/media3/transformer/transformations) ir [Supported formats](https://developer.android.com/media/media3/transformer/supported-formats).
6. Visos išvestys išsaugomos per bendrą Save As telefone ar serveryje su esamu konfliktų sprendimu. Originalas lieka nepakeistas, o tyčia neišsaugoti laikini failai sutvarkomi uždarius, atšaukus ir nesėkmės atveju.

TMEDIA01–TMEDIA09: trumpinimo trukmė; crop orientacija ir pikseliai; greičio ir aukščio faktinis pokytis; volume ir mute; visų konteineriui pažadėtų žymų bei viršelio perskaitymas iš rezultato; tikras išvesties MIME ir dekodavimas; Save As vietinis ir testinis nuotolinis konfliktas; atšaukimas ir antras eksportas; nepalaikomas kodekas, vietos ar atminties trūkumas. Kiekvienam naujam išvesties formatui kartoti jam taikomas šio rinkinio dalis.

Plėsti `MediaEditRulesTest`, `MediaExportAndroidTest`, `MediaEditWorkflowAndroidTest`, `MediaEditorUiTest` ir `MediaShippingFlowAndroidTest`. Rezultato netikrinti vien tuo pačiu failą sukūrusiu kodu: naudoti platformos dekoderį ir metaduomenų skaitytuvą.

Baigimo kriterijus: kiekviena formatų lentelės eilutė yra įgyvendinta ir patikrinta arba jos apribojimas aiškiai suderintas. Esant nepatvirtintam pakeitimui ar neįgyvendintam reikalavimui 253 lieka atvira; apribojimo įrašas nėra realizavimo įrodymas.

## H Pagrindinio ekrano valdikliai

Šaltinis: [užduotis 254](https://github.com/sinegard/AF-File-Manager/issues/254). Tai naujas platformos integracijos ir gyvavimo ciklo darbas, todėl prieš įvedant jo architektūrą reikalingas T3 kontraktas.

Standartiniai RemoteViews turi ribotą View rinkinį, kuriame nėra VideoView ar TextureView. Todėl tiesioginio vaizdo atkūrimo pačiame standartiniame valdiklyje negalima pažymėti įgyvendintu įdėjus miniatiūrą. Ilgas paspaudimas ir dalis dydžio bei išdėstymo veiksmų priklauso launcher. Pagrindas: [RemoteViews](https://developer.android.com/reference/android/widget/RemoteViews) ir [App widgets overview](https://developer.android.com/develop/ui/views/appwidgets/overview).

### Platformos ir architektūros sprendimas

1. Palyginti platformos RemoteViews kelią su papildomos Glance priklausomybės poreikiu, API 26 palaikymu, APK dydžiu ir esama architektūra. Esamo UI framework nekeisti.
2. Visoms prašomoms savybėms užpildyti lentelę: galimas tikslus elgesys, standartinės platformos riba, siūloma alternatyva, poveikis baterijai ir leidimams. Pateikti savininkui vaizdo, ilgo paspaudimo, sukimo ir dažnos nuotraukų kaitos sprendimus prieš realizuojant pakaitalą.
3. Konkrečiai nustatyti valdiklių egzempliorių, paveikslėlių ir sąrašo elementų ribas, atnaujinimo dažnį, darbo kvotą ir valymą. Visos šios reikšmės turi būti baigtinės ir įrašytos į patvirtintą kontraktą.
4. Nepalaikomam tiksliam prašymui reikia patvirtintos alternatyvos arba aiškaus sprendimo jos nedaryti. Negalima žodžio widget paversti bet kokia nuoroda ir uždaryti visą 254.

### Realizavimo dalys po sprendimo

| Dalis | Būtinas rezultatas ir tikrinimas |
| --- | --- |
| H1 Saugykla ir RAM | Matoma vidinė, SD ir USB saugykla, naudojimo dydžiai ir procentai; atjungtas diskas nėra 0 procentų. Root vietos tik su jau teisėtai aktyvia prieiga. RAM parodyti aiškiai įvardijant sistemos prieinamą rodiklį, ne priskiriant jį AF rezervui. |
| H2 Valymas | Atidaro AF peržiūros ir patvirtinimo kelią; valdiklio paspaudimas pats nieko netrina. |
| H3 Dalijimasis telefonu ir kompiuteriu | Atidaro tinkamą esamą darbo sritį; neviešina poravimo kodų ar žetonų ir automatiškai nepaleidžia atviro serverio. |
| H4 Failai ir katalogai | Skirtingi egzemplioriai atidaro savo pasirinktas vietas. Atšauktas URI leidimas ir dingęs failas rodomi aiškiai. Programos užraktas neapeinamas. |
| H5 Gili paieška | Perduoda pasirinktą sritį ir užklausą esamai paieškai. Jokio naujo nekontroliuojamo viso disko indeksavimo fone. |
| H6 Nuotraukos | Pasirinktas katalogas arba failai, į priekį ir atgal, patvirtintas kaitos grafikas ir platformai tinkamos animacijos. Neatkurti nuotraukų kas sekundę paslėpta nuolatine tarnyba. |
| H7 Muzika | Esamo atkūrimo savininko play, pause, stop, kitas ir ankstesnis bei tikra būsena. Nepradėti antro grotuvo; foninį atkūrimą vartotojas gali sustabdyti. |
| H8 Vaizdo įrašai | Tik savininko patvirtinta platformai įmanoma versija: miniatiūra, valdikliai ir esamo grotuvo ar PiP atidarymas gali būti alternatyva, bet nėra tiesioginio atkūrimo valdiklyje įrodymas. |
| H9 Egzemplioriaus nustatymai | Kiekvieno appWidgetId atskira vieta, turinys, stilius ir patvirtinti kiti nustatymai. Konfigūracija atidaroma aiškiu veiksmu ir redagavimu, kurį leidžia launcher; dydis, tankis ir sukimas pateikiami tik pagal patvirtintą galimybių lentelę. |

TWIDGET01–TWIDGET08: bent du to paties tipo egzemplioriai su skirtingais nustatymais; procesas atkurtas; launcher atkurtas; perkrovimas; resize ir orientacija; ištrintas valdiklis; atšauktas leidimas ar atjungtas USB; užrakinta programa. Patikrinti PendingIntent tapatybes, svetimus ar pakeistus extras, eksportuojamų komponentų ribas ir privačių URI prieigą.

Naujus widget testus aiškiai pridėti prie bendro ir optimizuoto APK patikrinimo. Valdikliui nesant neturi atsirasti jo periodinis darbas, laikomi dekoduoti paveikslėliai ar grotuvas. Bandymas bent viename tikrame launcher būtinas prieš teigiant, kad fizinio pagrindinio ekrano elgesys patikrintas.

Baigimo kriterijus: H1–H9 turi individualų rezultatą ir platformos sprendimai patvirtinti. Dalies valdiklių realizavimas neleidžia visos 254 uždaryti kaip completed.

## I Bendras regresijos ir leidimo patikrinimas

### Paveiktų vietų matrica

| Kelias | Privalomas patikrinimas |
| --- | --- |
| Vidinė saugykla ir Quick locations | Tie patys meniu ir pasirinkimas; nauja vieta viršuje, grįžimas atkuria padėtį; miniatiūros pagal katalogo nustatymą |
| Quick locations Archives ir Apps | Tikri jų naršyklės meniu, ne vien archyvo turinio peržiūra ar programos informacijos popup |
| ZIP, 7z ir pasiekiami kiti archyvai | Vidinių aplankų naršymas, slaptažodis, grįžimas, laikini failai ir esami veiksmai |
| Šiukšlinė | Sąrašas ir tinklelis, ilgi vardai, atkurti ir trinti, Back; bandyti tik savo šiukšlinės įrašus |
| SD, USB ir Android provider | Leidimo suteikimas ir praradimas, ikonų ir miniatiūrų režimai; fizinę laikmeną tikrinti tik turint jai leidimą |
| FTP, FTPS, SFTP, SMB ir WebDAV | Bendri veiksmai, pasirinkimas, peržiūra, Copy ir Cut semantika, Save As konfliktai, ryšio nesėkmė; naudoti disponuojamus testinius serverius, o ne savininko asmeninius adresus |
| Cloud per Android provider | Tie patys leidimų ir naršymo principai; nepradėti naujos registracijos ir nekeisti sutarto My Drive apribojimo |
| Root ir Shizuku | Bendro meniu ir atsisakytos prieigos testai tinkamame emuliatoriuje; paprastame telefone nebandyti gauti neegzistuojančio root |
| Terminalas ir foninė medija | Fonas ir grįžimas, didesnio teksto įklijavimas, sustabdymas; susijęs UI pakeitimas neturi grąžinti anksčiau buvusių avarijų |

Visiems keliams taikyti tik jiems realiai galimas funkcijas, o negalimą veiksmą pateikti nuosekliai. Vienodas meniu nereiškia melagingo visų saugyklų galimybių suvienodinimo. Kopija palieka originalą; Cut pašalina šaltinį tik po patvirtinto sėkmingo perkėlimo.

### Kalbos ir vaizdas

- Visos programos palaikomos kalbos turi praeiti esamą katalogo ir formatavimo tikrinimą. Vartotojo failų vardų, žymų ir turinio neversti.
- Paveikti dialogai ir valdikliai automatiškai tikrinami visomis palaikomomis kalbomis 100, 150 ir 200 procentų šriftu. EN, LT, DE ir AR papildomai peržiūrėti tikrame render ir atlikti veiksmus; AR tikrinti RTL.
- Tikrinti šviesią, tamsią ir pasirinktinę temą siaurame portrete bei horizontaliai, su klaviatūra ir be jos. Nė vienas valdytuvo tekstas, meniu ikona ar footer neturi persidengti, dingti ar keisti veiksmų reikšmės.
- Tikrinti ne vien geometriją: ar tekstas visas perskaitomas, valdiklis paspaudžiamas, veikia jo callback ir ekrano skaitytuvo etiketė. Failo pavadinimo sąmoningas trumpinimas nėra leidimas nukirpti veiksmo tekstą.
- Atskirti programos dvikalbystę nuo savarankiškos Android sisteminio lango kalbos. Rastas neišverstas programos tekstas taisomas bendrame vertimo kelyje.

### Automatinių bandymų eiga

Pradėti mažiausiu pakeistos dalies testu, tada plėsti. Darbinis katalogas visoms komandoms yra `D:\CODEX\AFFileManager`. Prieš paleidžiant patikrinti projekto SDK ir Java įrankių reikalavimus; nekeisti kompiuterio saugumo nustatymų.

```powershell
# Vykdyti tik jau pradėjus realizavimą, ne rengiant planą.
& 'D:\CODEX\AFFileManager\gradlew.bat' ':app:testDebugUnitTest' ':app:lintDebug' ':app:verifyReleaseDynamicRuntimeClasses' '--console=plain' '--no-daemon'
python 'D:\CODEX\AFFileManager\scripts\generate-ui-translations.py' --verify-only
python -m unittest discover -s 'D:\CODEX\AFFileManager\scripts' -p 'test_generate_ui_translations.py'
```

Toliau naudoti aiškiai patikrintą konkretaus nuosavo emuliatoriaus serial ir atitinkamą testų rinkinį. Neparinkti pirmo prijungto įrenginio automatiškai. API 36 rinkiniui yra `D:\CODEX\AFFileManager\scripts\run-api36-connected-tests.ps1`; API 26 bandymams nenaudoti API 36 saugiklio išjungimo, parinkti atitinkamą runner.

Pradinėje aplinkoje yra `AF_API26_Test` ir `DCoreRemote_API36_Tablet` AVD. Android 8 ir 16 emuliatorių rezultatai nepakeičia Android 9, 12 ar konkretaus HONOR ir Samsung fizinio bandymo. Jei originaliam atvejui būtina kita OS versija, parengti atskirą suderintą testinę aplinką arba gauti autoriaus rezultatą.

Atlikti visą taikomą connected rinkinį, ne vien naujus testus. Naujam gedimui skirti atskirą testą. Aiškiai skaičiuoti įvykdytus, nepraėjusius ir praleistus bandymus; 2 platforminiai skip nėra 2 papildomi pass.

### Greitis ir atmintis

1. Pradiniam ir kandidatui naudoti tą patį AVD, ABI, įvesties duomenis, talpyklos būseną ir esamą matavimo metodą. Kiekvienam esamam Macrobenchmark scenarijui ne mažiau kaip 7 iteracijos. Nekeisti biudžetų, kol bandymas vyksta.
2. Pakartoti šaltą paleidimą, didelio katalogo pirmą turinį, slinktį, miniatiūrų slinktį ir nuotolinį sąrašą. Atskirai palyginti pirmą bei pakartotinį miniatiūrų atidarymą.
3. Po 10 kartų atidaryti ir uždaryti taisomus dialogus ir peržiūras; tikrinti darbų, grotuvų, failų deskriptorių ir bitmap gyvavimo laiką. Nuoseklus augimas turi paaiškinimą arba pataisą, ne automatiškai didesnę kvotą.
4. Kritiniam keliui pasikeitus atnaujinti Baseline ir Startup Profiles pagal esamą procedūrą. Naudoti `D:\CODEX\AFFileManager\scripts\run-performance-gate.ps1` su eksplicitišku emuliatoriaus serial. Išsaugoti pradinį ir naują JSON bei tikrą matavimo rezultatą.
5. Esamo emuliatoriaus biudžeto praėjimas vienas pats neįrodo sklandumo telefone. Fiziniame įrenginyje papildomai atlikti tiesioginį slinkties, įvedimo ir foninio grįžimo bandymą, jei teigiama, kad tai patikrinta telefone.

### Tikras optimizuotas APK

Release minifikavimas lieka įjungtas. Nenaikinti optimizavimo, testų ar saugiklių vietoj konkrečios priežasties pataisos. Dinamiškai pasiekiami Media3, JNI, Binder ir tinklo klasės keliai turi siauras išlaikymo taisykles ir bandymus.

- Pirmiausia tikrinti lokalų optimizuotą kandidatą su jam tiksliai atitinkančiu testų runner.
- Po atskiro leidimo kelti ir rengti release patikrinti tikslų atsisiųstą CI draft APK, o ne panašų vietinį perkompiliavimą. Susieti source commit, ABI, versiją, SHA-256 ir esamą pasirašymo sertifikatą; patikrinti įdiegto APK tapatybę.
- Privalomi dabartiniai paveiktų kelių rinkiniai: `media-editing`, `share-index`, `nearby`, `transfer-diagnostics`, `localization`, `root-service`, `terminal-failure`, taip pat nauji archyvo grįžimo, meniu ir widget keliai. `root-service` testo sėkmė nėra tikro telefono root leidimo įrodymas.
- API 26 ir 36 tikrinti sėkmingus ir nesėkmės kelius. Naujo formato ar didelės raiškos kodeką teigti patikrintu tik tame įrenginyje, kur jis realiai išbandytas.
- Bet kokia optimizuoto APK regresija blokuoja jo publikavimą. Tikslus bandymas ir jo klaida išlieka matomi; seno sėkmingo bandymo nepervadinti naujo leidimo rezultatu.

### Fiziniai bandymai ir publikavimas

Savininko telefono atnaujinimui reikia atskiro aiškaus leidimo, serial, paketo ir parašo suderinamumo patikros. Naudoti duomenis išsaugantį atnaujinimą, ne uninstall ar clear data. Nesuteikti root, Shizuku ar kitų teisių ir netrinti savininko duomenų šio plano vardu.

215 originaliam gedimui reikalingas atskiras autoriaus fizinis patvirtinimas. Widget launcher ir kodekų fiziniai bandymai turi savo ribas; vienas telefonas neįrodo visų gamintojų palaikymo.

Publikavimas tik gavus tam leidimą ir įvykdžius `D:\CODEX\AFFileManager\docs\RELEASING.md`: nauja nekartojama versija ir tag, tikslūs APK bei kontrolinės sumos, suderinamas parašas, trumpi angliški leidimo pakeitimai. Senų tag ir pasirašytų asset nekeisti. Viešose pastabose nedėti robotiško Validation sąrašo ar perteklinės darbo vidinės informacijos.

Užduotis uždaryti tik kai tam yra leidimas ir visi jos sąrašo punktai atitinka užbaigimo kriterijus. Patikrinti galutinę GitHub būseną. Dalinė realizacija arba neegzistuojanti alternatyva nėra completed.

## Rezultatų perdavimas ir saugus sustabdymas

Kiekvieno etapo pabaigoje pateikti: pakeistus failus, nustatytą priežastį, konkrečią komandą ir rezultatą, APK ar source tapatybę, praleistus bandymus ir likusius sprendimus. Neužtenka viskas veikia.

Tęsimui saugoti trumpą privatų checkpoint: tikslus HEAD ir necommitintų failų tapatybės, paskutinis baigtas etapas, vykdomų procesų savininkai, įrodymų vietos ir kitas konkretus veiksmas. Gavus stop saugiai užbaigti savo rašymo operaciją, sustabdyti savo testus ir išsaugoti nebaigtą būseną. Tęsti tik vartotojui leidus.

Baigiant šį planą privalo likti reikalavimų lentelė, kur kiekvienas 215 ir 253–258 punktas turi įrodymą arba aiškią LAUKIA SPRENDIMO ar LAUKIA ĮRENGINIO būseną. Visos problemos išspręstos galima teigti tik tada, kai nė vienas privalomas punktas nelieka neįgyvendintas ar nepatikrintas.
