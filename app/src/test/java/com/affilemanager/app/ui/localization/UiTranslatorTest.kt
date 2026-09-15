package com.affilemanager.app.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class UiTranslatorTest {
    @Test
    fun englishTranslatesStaticAndDynamicInterfaceCopy() {
        assertEquals("Files", UiTranslator.translate("Failai", AppLanguageManager.ENGLISH))
        assertEquals("Videos", UiTranslator.translate("Vaizdo įrašai", AppLanguageManager.ENGLISH))
        assertEquals("Selected: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.ENGLISH))
        assertEquals("Will create: backup", UiTranslator.translate("Bus sukurta: backup", AppLanguageManager.ENGLISH))
        assertEquals("Sending to Test phone", UiTranslator.translate("Siunčiama į Test phone", AppLanguageManager.ENGLISH))
        assertEquals("Receiver: Test phone", UiTranslator.translate("Gavėjas: Test phone", AppLanguageManager.ENGLISH))
        assertEquals("Ready to send: 7", UiTranslator.translate("Paruošta siųsti: 7", AppLanguageManager.ENGLISH))
        assertEquals(
            "Selected archive entries extracted to /Download/sample",
            UiTranslator.translate("Pasirinkti archyvo įrašai išpakuoti į /Download/sample", AppLanguageManager.ENGLISH),
        )
        assertEquals("Copy (4)", UiTranslator.translate("Kopijuoti (4)", AppLanguageManager.ENGLISH))
        assertEquals("Paste (2)", UiTranslator.translate("Įklijuoti (2)", AppLanguageManager.ENGLISH))
        assertEquals("Paste from server", UiTranslator.translate("Įklijuoti iš serverio", AppLanguageManager.ENGLISH))
        assertEquals("Paste as 1 line", UiTranslator.translate("Įklijuoti kaip 1 eilutę", AppLanguageManager.ENGLISH))
        assertEquals("Paste multiline text?", UiTranslator.translate("Įklijuoti kelių eilučių tekstą?", AppLanguageManager.ENGLISH))
        assertEquals("Choose from phone", UiTranslator.translate("Pasirinkti iš telefono", AppLanguageManager.ENGLISH))
        assertEquals("Delete selected items?", UiTranslator.translate("Ištrinti pasirinktus elementus?", AppLanguageManager.ENGLISH))
        assertEquals(
            "3 items will be deleted from the remote server without using the local trash.",
            UiTranslator.translate(
                "3 elementai bus ištrinti nuotoliniame serveryje be vietinės šiukšlinės.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "3 items will be copied from the server to /storage/emulated/0/Download. Existing names will not be overwritten.",
            UiTranslator.translate(
                "Iš serverio bus nukopijuota 3 elementų į /storage/emulated/0/Download. Esami vardai nebus perrašyti.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "Copied from server to clipboard: 3",
            UiTranslator.translate("Nukopijuota iš serverio į iškarpinę: 3", AppLanguageManager.ENGLISH),
        )
        assertEquals("PDF page 3", UiTranslator.translate("PDF puslapis 3", AppLanguageManager.ENGLISH))
        assertEquals("Sign PDF", UiTranslator.translate("Pasirašyti PDF", AppLanguageManager.ENGLISH))
        assertEquals(
            "This is a visible handwritten mark, not a qualified cryptographic electronic signature.",
            UiTranslator.translate(
                "Tai matomas ranka pieštas žymuo, o ne kvalifikuotas kriptografinis elektroninis parašas.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals("Downloads", UiTranslator.translate("Atsisiuntimai", AppLanguageManager.ENGLISH))
        assertEquals("Appearance", UiTranslator.translate("Išvaizda", AppLanguageManager.ENGLISH))
        assertEquals("Dynamic", UiTranslator.translate("Dinaminė", AppLanguageManager.ENGLISH))
        assertEquals("Recent files", UiTranslator.translate("Naujausi failai", AppLanguageManager.ENGLISH))
        assertEquals("Analyze storage", UiTranslator.translate("Analizuoti saugyklą", AppLanguageManager.ENGLISH))
        assertEquals("Display settings", UiTranslator.translate("Rodinio nustatymai", AppLanguageManager.ENGLISH))
        assertEquals("Filter", UiTranslator.translate("Filtras", AppLanguageManager.ENGLISH))
        assertEquals("All files", UiTranslator.translate("Visi failai", AppLanguageManager.ENGLISH))
        assertEquals("Grid columns", UiTranslator.translate("Tinklelio stulpeliai", AppLanguageManager.ENGLISH))
        assertEquals("8.0 GB free of 9.7 GB", UiTranslator.translate("8.0 GB laisva iš 9.7 GB", AppLanguageManager.ENGLISH))
        assertEquals("74% used", UiTranslator.translate("74% užimta", AppLanguageManager.ENGLISH))
        assertEquals(
            "3 items · restore them or empty the trash",
            UiTranslator.translate("3 elementų · galima atkurti arba išvalyti viską", AppLanguageManager.ENGLISH),
        )
        assertEquals("Advanced filters · 2", UiTranslator.translate("Išplėstiniai filtrai · 2", AppLanguageManager.ENGLISH))
        assertEquals("Every 24 hr · Wi-Fi/Ethernet only", UiTranslator.translate("Kas 24 val. · tik Wi‑Fi/Ethernet", AppLanguageManager.ENGLISH))
        assertEquals("Create remote folder", UiTranslator.translate("Kurti nuotolinį aplanką", AppLanguageManager.ENGLISH))
        assertEquals("New connection", UiTranslator.translate("Nauja jungtis", AppLanguageManager.ENGLISH))
        assertEquals("Edit with another app", UiTranslator.translate("Redaguoti su kita programa", AppLanguageManager.ENGLISH))
        assertEquals("Save as", UiTranslator.translate("Išsaugoti kaip", AppLanguageManager.ENGLISH))
        assertEquals("Find and replace", UiTranslator.translate("Rasti ir pakeisti", AppLanguageManager.ENGLISH))
        assertEquals("Undo", UiTranslator.translate("Anuliuoti", AppLanguageManager.ENGLISH))
        assertEquals("Text editor", UiTranslator.translate("Teksto redaktorius", AppLanguageManager.ENGLISH))
        assertEquals(
            "Replaced one match",
            UiTranslator.translate("Pakeistas vienas atitikmuo", AppLanguageManager.ENGLISH),
        )
        assertEquals("Ln 12, Col 8", UiTranslator.translate("Eil. 12, stulp. 8", AppLanguageManager.ENGLISH))
        assertEquals("Replaced: 4", UiTranslator.translate("Pakeista: 4", AppLanguageManager.ENGLISH))
        assertEquals(
            "The destination file already exists",
            UiTranslator.translate("Paskirties failas jau yra", AppLanguageManager.ENGLISH),
        )
        assertEquals("Your edited copy", UiTranslator.translate("Jūsų redaguojama kopija", AppLanguageManager.ENGLISH))
        assertEquals(
            "The temporary editing copy could not be removed",
            UiTranslator.translate("Laikinos redagavimo kopijos pašalinti nepavyko", AppLanguageManager.ENGLISH),
        )
        assertEquals("Saved as /notes.txt", UiTranslator.translate("Išsaugota kaip /notes.txt", AppLanguageManager.ENGLISH))
        assertEquals("Add to clipboard", UiTranslator.translate("Įtraukti į iškarpinę", AppLanguageManager.ENGLISH))
        assertEquals(
            "Added to clipboard: 2 · total: 5",
            UiTranslator.translate("Į iškarpinę įtraukta: 2 · iš viso: 5", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Reconnect to Office NAS before saving to the original server",
            UiTranslator.translate(
                "Prieš išsaugodami pradiniame serveryje vėl prisijunkite prie Office NAS",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "Reconnect to Office NAS before merging",
            UiTranslator.translate("Prieš sujungdami vėl prisijunkite prie Office NAS", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Conflicts marked in the editor: 2. Resolve the markers and save.",
            UiTranslator.translate(
                "Redaktoriuje pažymėta konfliktų: 2. Išspręskite žymeklius ir išsaugokite.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "Added 2 · 5 total",
            UiTranslator.translate("Pridėta 2 · iš viso 5", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Added from this server: 3 · 8 total",
            UiTranslator.translate("Iš šio serverio pridėta: 3 · iš viso 8", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Copy-set limit reached: 500 items",
            UiTranslator.translate("Pasiekta kopijavimo rinkinio riba: 500 elementų", AppLanguageManager.ENGLISH),
        )
        assertEquals("Local file is missing", UiTranslator.translate("Trūksta vietinio failo", AppLanguageManager.ENGLISH))
        assertEquals(
            "Open terminal in this folder",
            UiTranslator.translate("Atidaryti terminalą šiame aplanke", AppLanguageManager.ENGLISH),
        )
        assertEquals("Close terminal?", UiTranslator.translate("Uždaryti terminalą?", AppLanguageManager.ENGLISH))
        assertEquals("Unchanged", UiTranslator.translate("Nekeisti", AppLanguageManager.ENGLISH))
        assertEquals("Folder unavailable", UiTranslator.translate("Aplankas nepasiekiamas", AppLanguageManager.ENGLISH))
        assertEquals(
            "Could not open the phone terminal",
            UiTranslator.translate("Telefono terminalo atidaryti nepavyko", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "What to do: Open another phone folder and try again.",
            UiTranslator.translate(
                "Ką daryti: Atverkite kitą telefono aplanką ir bandykite dar kartą.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "Failed: The network profile was removed",
            UiTranslator.translate("Nepavyko: Tinklo profilis pašalintas", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Review automation plan",
            UiTranslator.translate("Peržiūrėti automatikos planą", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Estimated write: 1.2 GB",
            UiTranslator.translate("Numatoma įrašyti: 1.2 GB", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "4 more destination actions",
            UiTranslator.translate("Dar 4 paskirčių veiksmų", AppLanguageManager.ENGLISH),
        )
        assertEquals("Timeline", UiTranslator.translate("Istorija", AppLanguageManager.ENGLISH))
        assertEquals("Automation", UiTranslator.translate("Automatika", AppLanguageManager.ENGLISH))
        assertEquals("Required destination", UiTranslator.translate("Privaloma paskirtis", AppLanguageManager.ENGLISH))
        assertEquals("Every 6 hours", UiTranslator.translate("Kas 6 val.", AppLanguageManager.ENGLISH))
        assertEquals(
            "Paste to many (4)",
            UiTranslator.translate("Įklijuoti į kelias vietas (4)", AppLanguageManager.ENGLISH),
        )
        assertEquals("Scanning files", UiTranslator.translate("Nuskaitomi failai", AppLanguageManager.ENGLISH))
        assertEquals(
            "Files: 12 · folders: 3 · 4 MB",
            UiTranslator.translate("Failai: 12 · aplankai: 3 · 4 MB", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Possible duplicates: 7 files · 9 MB",
            UiTranslator.translate("Galimi dublikatai: 7 failų · 9 MB", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "Checksums verified: 5",
            UiTranslator.translate("Patikrintos kontrolinės sumos: 5", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "The list is loaded in pages (up to 5000 items); one transfer can include up to 1000 files or folders. Touch and hold a media file to preview it.",
            UiTranslator.translate(
                "Sąrašas įkeliamas puslapiais (iki 5000 elementų); vienu siuntimu galima pasirinkti iki 1000 failų ar aplankų. Ilgiau palaikykite medijos failą, kad jį peržiūrėtumėte.",
                AppLanguageManager.ENGLISH,
            ),
        )
        assertEquals(
            "Plan: 3 actions · 12 MB · 1 conflicts",
            UiTranslator.translate("Planas: 3 veiksmų · 12 MB · konfliktų 1", AppLanguageManager.ENGLISH),
        )
        assertEquals(
            "In copy set: 4",
            UiTranslator.translate("Kopijavimo rinkinyje: 4", AppLanguageManager.ENGLISH),
        )
    }

    @Test
    fun lithuanianKeepsOriginalInterfaceCopy() {
        assertEquals("Failai", UiTranslator.translate("Failai", AppLanguageManager.LITHUANIAN))
        assertEquals("Aplanko turinys", UiTranslator.translate("Aplanko turinys", AppLanguageManager.LITHUANIAN))
        assertEquals("Material mėlyna", UiTranslator.translate("Material mėlyna", AppLanguageManager.LITHUANIAN))
        assertEquals("Pasirinkta: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.LITHUANIAN))
        assertEquals("Paprastas tekstas", UiTranslator.translate("Plain text", AppLanguageManager.LITHUANIAN))
        assertEquals(
            "Failas per didelis redaguoti",
            UiTranslator.translate("File is too large to edit", AppLanguageManager.LITHUANIAN),
        )
        assertEquals(
            "Šaltinis nepasiekiamas: report.txt (IOException)",
            UiTranslator.translate("Source unavailable: report.txt (IOException)", AppLanguageManager.LITHUANIAN),
        )
        assertEquals(
            "Įspėjimas: Nepavyko patvirtinti laisvos vietos privačioje laikinojoje saugykloje",
            UiTranslator.translate(
                "Įspėjimas: Private staging space could not be confirmed",
                AppLanguageManager.LITHUANIAN,
            ),
        )
        assertEquals(
            "Baigta su 3 klaidomis",
            UiTranslator.translate("Completed with 3 errors", AppLanguageManager.LITHUANIAN),
        )
    }

    @Test
    fun unknownTextSuchAsAFileNameIsNeverChanged() {
        val fileName = "Sąskaita 2026 – final.pdf"
        assertEquals(fileName, UiTranslator.translate(fileName, AppLanguageManager.ENGLISH))
    }

    @Test
    fun metadataAdvancedAccessAndCleanupCopyAreTranslated() {
        assertEquals("Manufacturer", UiTranslator.translate("Gamintojas", AppLanguageManager.ENGLISH))
        assertEquals("Model", UiTranslator.translate("Modelis", AppLanguageManager.ENGLISH))
        assertEquals("Taken", UiTranslator.translate("Fotografuota", AppLanguageManager.ENGLISH))
        assertEquals("Orientation", UiTranslator.translate("Orientacija", AppLanguageManager.ENGLISH))
        assertEquals("Writable", UiTranslator.translate("Įrašomas", AppLanguageManager.ENGLISH))
        assertEquals("Protected Android files", UiTranslator.translate("Apsaugoti Android failai", AppLanguageManager.ENGLISH))
        assertEquals("Safe cleanup review", UiTranslator.translate("Saugaus valymo peržiūra", AppLanguageManager.ENGLISH))
        assertEquals("Folder contents", UiTranslator.translate("Aplanko turinys", AppLanguageManager.ENGLISH))
        assertEquals("Calculating folder sizes…", UiTranslator.translate("Skaičiuojami aplankų dydžiai…", AppLanguageManager.ENGLISH))
        assertEquals("Material blue", UiTranslator.translate("Material mėlyna", AppLanguageManager.ENGLISH))
        assertEquals("At least 4 files", UiTranslator.translate("Bent 4 failų", AppLanguageManager.ENGLISH))
        assertEquals("Similar-photo group 4", UiTranslator.translate("Panašių nuotraukų grupė 4", AppLanguageManager.ENGLISH))
        assertEquals("Connected · UID 2000", UiTranslator.translate("Prisijungta · UID 2000", AppLanguageManager.ENGLISH))
        assertEquals(
            "Reconnect to Office NAS before deleting",
            UiTranslator.translate("Prieš trindami vėl prisijunkite prie Office NAS", AppLanguageManager.ENGLISH),
        )
    }

    @Test
    fun everyStaticMarkedUiLiteralHasAnEnglishEntry() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex("""(?:LText|uiText)\(\s*\"((?:\\.|[^\"\\])*)\"""")
        val missing = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                literal.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) -> '$' !in text && !UiTranslator.hasEnglishEntry(text) }
            .distinct()
            .toList()
        assertTrue("Missing English UI entries: $missing", missing.isEmpty())
    }

    @Test
    fun everyStaticUiTitleAndDialogActionHasAnEnglishEntry() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex(
            """\b(?:title|subtitle|cancelLabel|confirmLabel)\s*=\s*\"((?:\\.|[^\"\\])*)\"""",
        )
        val deliberatelyLanguageNeutral = setOf("AF File Manager", "SHA-256")
        val missing = File(sourceRoot, "com/affilemanager/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                literal.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) ->
                '$' !in text && text !in deliberatelyLanguageNeutral && !UiTranslator.hasEnglishEntry(text)
            }
            .distinct()
            .toList()
        assertTrue("Missing English title/action entries: $missing", missing.isEmpty())
    }

    @Test
    fun staticComposeTextDoesNotBypassInterfaceLocalization() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex("""\bText\(\s*(?:text\s*=\s*)?\"((?:\\.|[^\"\\])*)\"""")
        val deliberatelyLanguageNeutral = setOf("Alt", "Ctrl", "SHA-256")
        val bypassed = File(sourceRoot, "com/affilemanager/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                literal.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) -> '$' !in text && text !in deliberatelyLanguageNeutral }
            .distinct()
            .toList()
        assertTrue("Static Compose Text bypasses localization: $bypassed", bypassed.isEmpty())
    }

    @Test
    fun dynamicWhenBranchUiCopyIsRegisteredInsteadOfSilentlyLeakingASecondLanguage() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val outputLiteral = Regex("""->\s*\"((?:\\.|[^\"\\\r\n])*)\"""")
        val languageNeutral = setOf(
            "AF File Manager", "APK", "C / C++ / C#", "JSON", "Java", "JavaScript", "Kotlin",
            "Markdown", "Plain text", "Python", "Root", "SHA-256", "SQL", "Shell", "Shizuku",
            "TOML / INI", "TypeScript", "XML / HTML", "YAML", "Aura", "Catppuccin", "Tokyo", "Yin Yang",
        )
        val unresolved = File(sourceRoot, "com/affilemanager/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "UiTranslator.kt" }
            .flatMap { file ->
                outputLiteral.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) ->
                text.isNotBlank() && '$' !in text && text !in languageNeutral &&
                    (text.any { it.isWhitespace() } || text.first().isUpperCase() ||
                        Regex("[ĄČĘĖĮŠŲŪŽąčęėįšųūž]").containsMatchIn(text)) &&
                    !text.matches(Regex("[A-Z0-9_-]+")) &&
                    !UiTranslator.hasKnownUiEntry(text) &&
                    UiTranslator.translate(text, AppLanguageManager.ENGLISH) == text
            }
            .distinct()
            .toList()
        assertTrue("Unregistered dynamic UI branch copy: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun everyStaticLiteralInsideLocalizedCallBranchesIsRegistered() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val neutral = setOf(
            "", "AF File Manager", "APK", "Ctrl", "Alt", "Root", "SHA-256", "Shizuku",
            "Aura", "Catppuccin", "Tokyo", "Yin Yang",
        )
        val unresolved = File(sourceRoot, "com/affilemanager/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "UiTranslator.kt" }
            .flatMap { file ->
                sequenceOf("LText", "uiText").flatMap { call ->
                    localizedCallBodies(file.readText(), call).flatMap { body ->
                        kotlinStringLiterals(body).map { literal -> file.name to literal }
                    }
                }
            }
            .filter { (_, text) ->
                '$' !in text && text == text.trim() && '}' !in text &&
                    !text.matches(Regex("[a-z0-9_-]+")) && text !in neutral &&
                    !UiTranslator.hasKnownUiEntry(text) &&
                    UiTranslator.translate(text, AppLanguageManager.ENGLISH) == text
            }
            .distinct()
            .toList()
        assertTrue("Unregistered localized call branch copy: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun everyInterpolatedLocalizedUiLiteralHasAnEnglishPattern() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val deliberatelyLanguageNeutral = setOf(
            "7 / 7", "7 ↔ 7", "7 %", "AF File Manager 7", "SHA-256  7", "SSH 7…",
        )
        val unresolved = File(sourceRoot, "com/affilemanager/app/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "UiTranslator.kt" }
            .flatMap { file ->
                sequenceOf("LText", "uiText").flatMap { call ->
                    localizedCallBodies(file.readText(), call).flatMap { body ->
                        sampledInterpolatedKotlinStrings(body).map { sample -> file.name to sample }
                    }
                }
            }
            .filter { (_, sample) ->
                sample.any(Char::isLetter) && sample !in deliberatelyLanguageNeutral &&
                    UiTranslator.translate(sample, AppLanguageManager.ENGLISH) == sample
            }
            .distinct()
            .toList()
        assertTrue("Unregistered interpolated UI copy: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun staticLithuanianRuntimeMessagesHaveEnglishTranslations() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex("""\"((?:\\.|[^\"\\])*)\"""")
        val commonLithuanianWords = Regex(
            """(?iu)(?:^|[^\p{L}])(?:failas|failai|failo|failų|aplankas|aplankai|aplanko|aplankų|katalogas|katalogo|kataloge|pasirinktas|pasirinkite|pasirinkti|netinkamas|netinkama|netinkami|negalima|nepavyko|nepasiekiamas|nepasiekiama|serveris|serverio|siuntimas|siuntimo|operacija|operacijos|kelias|kelio|vardas|vardo|saugykla|saugyklos|leidimas|leidimo|ryšys|ryšio|pranešimas|perdavimas|perdavimo|įrašas|įrašo|šaltinis|šaltinio|paskirtis|paskirties|atšaukti|atšauktas|pasiekta|viršija|tuščias|trūksta|nėra|rodoma|rodyti|sukurti|sustabdyti|pradėti|baigta|klaida|klaidos|dydis|elementas|elementai|elementų)(?:$|[^\p{L}])""",
        )
        val untranslated = sourceRoot.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.name !in setOf("UiTranslator.kt", "RuntimeMessageTranslations.kt", "AppLanguageManager.kt")
            }
            .flatMap { file ->
                literal.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) ->
                '$' !in text && text != "Lietuvių" &&
                    (Regex("[ĄČĘĖĮŠŲŪŽąčęėįšųūž]").containsMatchIn(text) ||
                        commonLithuanianWords.containsMatchIn(text)) &&
                    UiTranslator.translate(text, AppLanguageManager.ENGLISH) == text
            }
            .distinct()
            .toList()
        assertTrue("Untranslated Lithuanian runtime messages: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun interpolatedLithuanianRuntimeMessagesHaveEnglishTranslations() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val lithuanianCharacters = Regex("[ĄČĘĖĮŠŲŪŽąčęėįšųūž]")
        val commonLithuanianWords = Regex(
            """(?iu)(?:^|[^\p{L}])(?:failas|failai|failo|failų|aplankas|aplankai|aplanko|aplankų|katalogas|katalogo|kataloge|pasirinktas|pasirinkite|pasirinkti|netinkamas|netinkama|netinkami|negalima|nepavyko|nepasiekiamas|nepasiekiama|serveris|serverio|siuntimas|siuntimo|operacija|operacijos|kelias|kelio|vardas|vardo|saugykla|saugyklos|leidimas|leidimo|ryšys|ryšio|pranešimas|perdavimas|perdavimo|įrašas|įrašo|šaltinis|šaltinio|paskirtis|paskirties|atšaukti|atšauktas|pasiekta|viršija|tuščias|trūksta|nėra|rodoma|rodyti|sukurti|sustabdyti|pradėti|baigta|klaida|klaidos|dydis|elementas|elementai|elementų)(?:$|[^\p{L}])""",
        )
        val untranslated = sourceRoot.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.name !in setOf("UiTranslator.kt", "RuntimeMessageTranslations.kt", "AppLanguageManager.kt")
            }
            .flatMap { file ->
                sampledInterpolatedKotlinStrings(file.readText()).map { sample -> file.name to sample }
            }
            .filter { (_, sample) ->
                (lithuanianCharacters.containsMatchIn(sample) || commonLithuanianWords.containsMatchIn(sample)) &&
                    UiTranslator.translate(sample, AppLanguageManager.ENGLISH) == sample
            }
            .distinct()
            .toList()
        assertTrue("Untranslated interpolated Lithuanian runtime messages: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun staticEnglishRuntimeMessagesHaveLithuanianTranslations() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex("""\"((?:\\.|[^\"\\])*)\"""")
        val runtimeEnglish = Regex(
            """(?iu)(?:^|[^\p{L}])(?:invalid|failed|failure|unavailable|unsupported|cannot|could not|must|too many|too large|not found|does not|is not|was not|already exists|already running|requires|missing|error|limit exceeded|is closed|is empty)(?:$|[^\p{L}])""",
        )
        val protocolResponseSources = setOf("LanFtpServer.kt", "LanHttpServer.kt", "LanWebDavServer.kt")
        val untranslated = sourceRoot.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.name !in setOf("UiTranslator.kt", "RuntimeMessageTranslations.kt", "AppLanguageManager.kt")
            }
            .flatMap { file ->
                literal.findAll(file.readText()).map { match -> file.name to decodeKotlinLiteral(match.groupValues[1]) }
            }
            .filter { (_, text) ->
                '$' !in text && text == text.trim() && text.firstOrNull()?.isUpperCase() == true &&
                    !text.matches(Regex("[A-Z0-9_-]+")) && !text.matches(Regex("[a-z0-9_-]+")) &&
                    runtimeEnglish.containsMatchIn(text) &&
                    UiTranslator.translate(text, AppLanguageManager.LITHUANIAN) == text
            }
            .filterNot { (fileName, _) -> fileName in protocolResponseSources }
            .distinct()
            .toList()
        assertTrue("Untranslated English runtime messages: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun interpolatedEnglishRuntimeMessagesHaveLithuanianTranslations() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val runtimeEnglish = Regex(
            """(?iu)(?:^|[^\p{L}])(?:invalid|failed|failure|unavailable|unsupported|cannot|could not|must|too many|too large|not found|does not|is not|was not|already exists|already running|requires|missing|error|limit exceeded|is closed|is empty)(?:$|[^\p{L}])""",
        )
        val protocolResponseSources = setOf("LanFtpServer.kt", "LanHttpServer.kt", "LanWebDavServer.kt")
        val untranslated = sourceRoot.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    it.name !in setOf(
                        "UiTranslator.kt", "UiTranslationCatalog.kt", "RuntimeMessageTranslations.kt", "AppLanguageManager.kt",
                    ) &&
                    it.name !in protocolResponseSources
            }
            .flatMap { file ->
                sampledInterpolatedKotlinStrings(file.readText()).map { sample -> file.name to sample }
            }
            .filter { (_, sample) ->
                sample == sample.trim() && sample.firstOrNull()?.isUpperCase() == true &&
                    runtimeEnglish.containsMatchIn(sample) &&
                    UiTranslator.translate(sample, AppLanguageManager.LITHUANIAN) == sample
            }
            .distinct()
            .toList()
        assertTrue("Untranslated interpolated English runtime messages: $untranslated", untranslated.isEmpty())
    }

    private fun decodeKotlinLiteral(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")

    private fun kotlinStringLiterals(source: String): Sequence<String> {
        val literal = Regex("""\"((?:\\.|[^\"\\\r\n])*)\"""")
        return literal.findAll(source).map { decodeKotlinLiteral(it.groupValues[1]) }
    }

    /**
     * Produces runnable examples from Kotlin string templates without trying to evaluate source
     * expressions. Braced expressions may themselves contain quoted strings, so a regular
     * expression is not sufficient for this audit.
     */
    private fun sampledInterpolatedKotlinStrings(source: String): Sequence<String> = sequence {
        var position = 0
        while (position < source.length) {
            if (source[position] != '"' || source.startsWith("\"\"\"", position)) {
                position += 1
                continue
            }
            var cursor = position + 1
            val sample = StringBuilder()
            var interpolated = false
            var complete = false
            while (cursor < source.length) {
                when {
                    source[cursor] == '\\' && cursor + 1 < source.length -> {
                        sample.append(source[cursor]).append(source[cursor + 1])
                        cursor += 2
                    }
                    source[cursor] == '"' -> {
                        complete = true
                        cursor += 1
                        break
                    }
                    source[cursor] == '$' && cursor + 1 < source.length && source[cursor + 1] == '{' -> {
                        val interpolationEnd = bracedInterpolationEnd(source, cursor + 2)
                        if (interpolationEnd < 0) break
                        val expression = source.substring(cursor + 2, interpolationEnd - 1)
                        sample.append(if ("\"Taip\"" in expression) "Taip" else "7")
                        interpolated = true
                        cursor = interpolationEnd
                    }
                    source[cursor] == '$' && cursor + 1 < source.length &&
                        (source[cursor + 1].isLetter() || source[cursor + 1] == '_') -> {
                        cursor += 2
                        while (cursor < source.length &&
                            (source[cursor].isLetterOrDigit() || source[cursor] == '_')
                        ) cursor += 1
                        sample.append('7')
                        interpolated = true
                    }
                    else -> {
                        sample.append(source[cursor])
                        cursor += 1
                    }
                }
            }
            if (complete && interpolated) yield(decodeKotlinLiteral(sample.toString()))
            position = maxOf(cursor, position + 1)
        }
    }

    private fun bracedInterpolationEnd(source: String, contentStart: Int): Int {
        var cursor = contentStart
        var depth = 1
        var quote: Char? = null
        var escaped = false
        while (cursor < source.length) {
            val character = source[cursor]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
            } else {
                when (character) {
                    '"', '\'' -> quote = character
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return cursor + 1
                    }
                }
            }
            cursor += 1
        }
        return -1
    }

    private fun localizedCallBodies(source: String, callName: String): Sequence<String> = sequence {
        val marker = "$callName("
        var searchFrom = 0
        while (true) {
            val callStart = source.indexOf(marker, searchFrom)
            if (callStart < 0) break
            val contentStart = callStart + marker.length
            var cursor = contentStart
            var depth = 1
            var quoted = false
            var escaped = false
            while (cursor < source.length && depth > 0) {
                val character = source[cursor]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '(' -> depth += 1
                    ')' -> depth -= 1
                }
                cursor += 1
            }
            if (depth == 0) yield(source.substring(contentStart, cursor - 1))
            searchFrom = maxOf(cursor, contentStart)
        }
    }
}
