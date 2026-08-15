package com.affilemanager.app.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTranslatorTest {
    @Test
    fun englishTranslatesStaticAndDynamicInterfaceCopy() {
        assertEquals("Files", UiTranslator.translate("Failai", AppLanguageManager.ENGLISH))
        assertEquals("Selected: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.ENGLISH))
        assertEquals("Copy (4)", UiTranslator.translate("Kopijuoti (4)", AppLanguageManager.ENGLISH))
        assertEquals("Paste (2)", UiTranslator.translate("Įklijuoti (2)", AppLanguageManager.ENGLISH))
        assertEquals("Paste from server", UiTranslator.translate("Įklijuoti iš serverio", AppLanguageManager.ENGLISH))
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
        assertEquals("8.0 GB free of 9.7 GB", UiTranslator.translate("8.0 GB laisva iš 9.7 GB", AppLanguageManager.ENGLISH))
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
        assertEquals("Local file is missing", UiTranslator.translate("Trūksta vietinio failo", AppLanguageManager.ENGLISH))
        assertEquals(
            "Open terminal in this folder",
            UiTranslator.translate("Atidaryti terminalą šiame aplanke", AppLanguageManager.ENGLISH),
        )
        assertEquals("Close terminal?", UiTranslator.translate("Uždaryti terminalą?", AppLanguageManager.ENGLISH))
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
    }

    @Test
    fun lithuanianKeepsOriginalInterfaceCopy() {
        assertEquals("Failai", UiTranslator.translate("Failai", AppLanguageManager.LITHUANIAN))
        assertEquals("Pasirinkta: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.LITHUANIAN))
    }

    @Test
    fun unknownTextSuchAsAFileNameIsNeverChanged() {
        val fileName = "Sąskaita 2026 – final.pdf"
        assertEquals(fileName, UiTranslator.translate(fileName, AppLanguageManager.ENGLISH))
    }
}
