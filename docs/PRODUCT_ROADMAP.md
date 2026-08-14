# AF File Manager produkto kryptis

Ši atranka pritaikyta Android programai, o ne aklai perkelta iš Windows ar macOS failų tvarkyklių. Prioritetai: duomenų saugumas, greita kasdienė eiga, aiški maža sąsaja ir funkcijos be paskyros, reklamų ar telemetrijos.

## Jau turima bazė

- Vienas / du skydeliai, vietinės ir SAF saugyklos, SD / USB matomos per Android saugyklų API.
- Ribota kopijavimo / perkėlimo eilė, konfliktų politika, pauzė, tęsimas ir atšaukimas.
- Kataloginė šiukšliadėžė, peržiūros, tęstinis PDF, archyvų naršymas ir kūrimas.
- SMB, SFTP, WebDAV, FTP / FTPS, sinchronizavimo plano peržiūra.
- Dublikatų SHA-256 analizė, dideli / seni failai, tušti katalogai.
- Jokios reklamos, privalomos paskyros ar sekimo SDK.

## 0.5.0 – saugus produktyvumas

- Masinis pervadinimas su privalomu `senas → naujas` planu, konfliktais, dviejų fazių vykdymu, viso paketo grąžinimu ir vieno paskutinio paketo `Atšaukti`.
- Paieška šiame aplanke arba visose Android matomose saugyklose pagal vardą / regex, tipą, dydį ir keitimo datų intervalą.
- Paieškos rezultatai kaip virtualus darbo aplankas: kopijuoti, perkelti, šiukšlinė, masinis pervadinimas, išsaugoti filtrus ir rodyti tikroje vietoje.
- Atšaukiami katalogų nuskaitymai; pasenęs lėtesnio katalogo rezultatas negali pakeisti naujesnio vaizdo.

## Įgyvendinta 0.6–0.9 seka

### 0.6 – greitis ir patikimų operacijų žurnalas

Įgyvendinta 0.9.0: laipsniškas katalogų srautas, patvarus planas ir atkūrimas, ribota klaidų ataskaita, pakartojimas ir SHA-256 patikra. Universalus senų operacijų `Undo` sąmoningai nerodomas, jei dabartinės failų būsenos neleidžia įrodyti saugaus grąžinimo.

1. Laipsniškas / puslapiuojamas didelių katalogų rodymas: vardai ir tipai iš karto, metaduomenys vėliau; atskiras 100 000+ elementų paleidimo, RAM ir slinkimo etalonas.
2. Patvarus kopijavimo / perkėlimo planas ir atkūrimas po proceso perkrovimo.
3. Atskiro failo klaida, `Bandyti dar kartą`, `Praleisti ir tęsti`, aiški galutinė klaidų ataskaita.
4. Pasirenkamas kopijos SHA-256 patikrinimas; perkeliant šaltinis šalinamas tik po patikrinimo.
5. Kelių operacijų istorija ir tik saugiai dar galimų veiksmų `Atšaukti`.

### 0.7 – darbo sesijos

Įgyvendinta 0.9.0: nepriklausomos kortelės, jų užrakinimas / dubliavimas / atkūrimas, sesijos istorija, skydelių sukeitimas ir aplankų palyginimas. Mažame liečiamame ekrane vietoj nepatikimo tempimo naudojamas aiškus veiksmas „Kopijuoti į kitą skydelį“ su tuo pačiu konfliktų dialogu.

1. Nepriklausomos kortelės abiejuose skydeliuose, užrakinti / dubliuoti / atkurti uždarytą kortelę.
2. Skydelių sukeitimas, tempimas tarp jų, aplankų palyginimas.
3. Sesijos atkūrimas ir paprastas / išplėstinis režimai.

### 0.8 – telefono ir kompiuterio perdavimas

Įgyvendinta 0.9.0: vieno aplanko HTTP sesija privačiame LAN, vienkartinis kodas, 15–60 min. galiojimas, foreground service, atominiai įkėlimai ir sustabdymas. SFTP serveris bei debesijų OAuth lieka atskiras, vėlesnis grėsmių modelio etapas.

1. Vietinio tinklo žiniatinklio perdavimas tik su vienkartiniu kodu, aiškia IP / prievado būsena ir sustabdymo mygtuku.
2. Android foreground-service būsena, ribotas katalogas, automatinis sesijos galiojimo laikas ir jokio viešo interneto pagal nutylėjimą.
3. Tik po grėsmių modelio – pasirenkamas SFTP serveris ir kelios debesijos paskyros.

### 0.9 – metaduomenys ir analizė

Įgyvendinta 0.9.0: hierarchinės spalvotos žymos, įvertinimai, JSON importas / eksportas, žymomis filtruojamos išmaniosios paieškos, aplankų dydžių bei tipų juostinis „vietos žemėlapis“ ir rankinis dublikatų kopijų pasirinkimas prieš siunčiant į šiukšlinę.

1. Žymos bei išmanieji aplankai su eksportuojamu formatu.
2. Aplankų dydžiai fone ir treemap, nieko netrinant automatiškai.
3. Dublikatų peržiūra su aiškiu originalo / kopijų pasirinkimu.

## Sąmoningai vėliau

- Vietinis AI ir semantinė paieška tik po to, kai operacijų atkūrimas, kortelės ir perdavimo sauga yra užbaigti.
- Root, Shizuku ar ADB nėra bazinė produkto kryptis. Android `Android/data` ir `Android/obb` ribos turi būti rodomos sąžiningai.
- „Portable“ darbalaukio versija netaikoma Android APK; atskira Windows ar Linux programa būtų kitas produktas ir kita architektūra.
