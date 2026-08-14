# Privatumas

Miniatiūros ir failų piktogramos kuriamos pačiame įrenginyje. Failų turinys dėl jų nesiunčiamas į tinklą, o miniatiūrų talpykla nėra rašoma į diską.

„AF File Manager“ neturi reklamų, analitikos, telemetrijos ar sekimo SDK ir nereikalauja paskyros.

Programa lokaliai saugo:

- naudotojo žymas, istoriją, SAF vietas ir sinchronizavimo tvarkaraščius;
- tinklo profilių metaduomenis;
- Android Keystore užšifruotus slaptažodžius ir, jei pateikti, privačius SSH raktus;
- į programos šiukšlinę perkeltus failus ir jų atkūrimo metaduomenis.

Duomenys siunčiami tik į naudotojo aiškiai sukonfigūruotus SMB, SFTP, WebDAV, FTP arba FTPS serverius ir tik atliekant pasirinktą veiksmą ar įjungtą tvarkaraštį. Programos atsarginės kopijos ir perkėlimas į kitą įrenginį išjungti, kad prisijungimai ar šiukšlinės turinys nebūtų netyčia kopijuojami.

Paleidus programą, ne dažniau kaip kas šešias valandas tikrinami viešos `sinegard/AF-File-Manager` GitHub repozitorijos stabilūs leidimai. GitHub gauna įprastą HTTPS užklausą su programos versija `User-Agent` antraštėje. Failų vardai, failų turinys, saugyklos sąrašas ir tinklo profiliai į GitHub nesiunčiami. Naujesnis APK automatiškai siunčiamas tik nematuojamame tinkle; mobiliame ar kitame matuojamame tinkle pirmiausia prašoma naudotojo pasirinkimo.

Pašalinus tinklo profilį pašalinamas ir jo užšifruotas prisijungimo įrašas. Pašalinus SAF vietą failai neliečiami – atšaukiamas tik programos ilgalaikis leidimas. Programos duomenų išvalymas Android nustatymuose pašalina visus vietinius programos nustatymus ir programos šiukšlinę.
