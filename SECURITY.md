# Saugumo modelis

## Paslaptys

- Tinklo slaptažodžiai ir privatūs SSH raktai prieš rašymą šifruojami AES-GCM raktu iš `AndroidKeyStore`.
- Paslapčių tekstiniai ir baitų masyvai po naudojimo išvalomi ten, kur JVM / Android API tai leidžia.
- Failų miniatiūros kuriamos vietoje ir laikomos tik iki 24 MiB ribotoje proceso atminties LRU talpykloje; nuolatinė miniatiūrų duomenų bazė ar foninis visos saugyklos indeksavimas nekuriami.
- SFTP serverio raktas tikrinamas pagal SHA-256 atspaudą. TOFU galimas tik aiškiai pasirinkus; pirmas raktas įrašomas, o vėliau pasikeitęs raktas blokuojamas.
- WebDAV leidžiamas tik per HTTPS. FTPS įjungia sertifikato grandinės ir galinio taško vardo tikrinimą. Paprastas FTP sąmoningai lieka nesaugus protokolas ir neturėtų būti naudojamas nepatikimame tinkle.

## Failų vientisumas

- Kopijavimas, atsisiuntimas, šifravimas, teksto keitimas ir išpakavimas naudoja laikinus failus; galutinis vardas pakeičiamas tik sėkmingai užbaigus.
- Kur įmanoma, tikrinamas įrašytas dydis. Dublikatams naudojamas SHA-256.
- Archyvų keliai kanonizuojami ir negali išeiti už paskirties. Ribojamas įrašų skaičius, gylis, vieno failo ir bendras išplėstas dydis; TAR nuorodos atmetamos.
- Nuotolinės sesijos serializuotos vienu `Mutex`, kad vienas klientas nebūtų naudojamas lygiagrečiai nesaugiais būdais.

## Destruktyvūs veiksmai

- Vietiniai failai pagal nutylėjimą siunčiami į programos šiukšlinę.
- Galutinis vietinis, SAF ir nuotolinis trynimas turi atskirą patvirtinimą.
- Sinchronizavimo variklis neturi tylaus trynimo veiksmo. Fone konfliktai sustabdo vykdymą ir įrašo būseną.
- APK diegimą patvirtina Android. Root / Shizuku veiksmai nėra automatiškai vykdomi.
- Atnaujintojas priima tik šios viešos GitHub repozitorijos stabilų leidimą, tikrina HTTPS adresą, APK vardą, dydį, GitHub SHA-256, paketo ID, didesnį `versionCode` ir tą patį pasirašymo sertifikatą. Tik tada atveriamas Android diegimo langas.

## Ribos

- Android sistemos ir gamintojo apribojimai yra viršesni už programą.
- Release APK pasirašomas atskiru platinimo raktu per GitHub Actions paslaptis. Privatus raktas ir jo slaptažodžiai repozitorijoje nesaugomi; jų praradimas neleistų pasirašyti suderinamų atnaujinimų.
- Prieš produkcinį naudojimą kiekvienas tikras tinklo serverio tipas turi būti patikrintas su savininko infrastruktūra ir sertifikatų / SSH atspaudų politika.
