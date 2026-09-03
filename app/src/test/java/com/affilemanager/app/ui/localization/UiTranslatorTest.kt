package com.affilemanager.app.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class UiTranslatorTest {
    @Test
    fun englishTranslatesStaticAndDynamicInterfaceCopy() {
        assertEquals("Files", UiTranslator.translate("Failai", AppLanguageManager.ENGLISH))
        assertEquals("Selected: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.ENGLISH))
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
        assertEquals("Downloads", UiTranslator.translate("Atsisiuntimai", AppLanguageManager.ENGLISH))
        assertEquals("Appearance", UiTranslator.translate("Išvaizda", AppLanguageManager.ENGLISH))
        assertEquals("Dynamic", UiTranslator.translate("Dinaminė", AppLanguageManager.ENGLISH))
        assertEquals("Recent files", UiTranslator.translate("Naujausi failai", AppLanguageManager.ENGLISH))
        assertEquals("Analyze storage", UiTranslator.translate("Analizuoti saugyklą", AppLanguageManager.ENGLISH))
        assertEquals("Display settings", UiTranslator.translate("Rodinio nustatymai", AppLanguageManager.ENGLISH))
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
    fun staticLithuanianRuntimeMessagesHaveEnglishTranslations() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
            ?: error("Main source directory not found")
        val literal = Regex("""\"((?:\\.|[^\"\\])*)\"""")
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
                    Regex("[ĄČĘĖĮŠŲŪŽąčęėįšųūž]").containsMatchIn(text) &&
                    UiTranslator.translate(text, AppLanguageManager.ENGLISH) == text
            }
            .distinct()
            .toList()
        assertTrue("Untranslated Lithuanian runtime messages: $untranslated", untranslated.isEmpty())
    }

    private fun decodeKotlinLiteral(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}
