package com.affilemanager.app.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTranslatorTest {
    @Test
    fun englishTranslatesStaticAndDynamicInterfaceCopy() {
        assertEquals("Files", UiTranslator.translate("Failai", AppLanguageManager.ENGLISH))
        assertEquals("Selected: 12", UiTranslator.translate("Pasirinkta: 12", AppLanguageManager.ENGLISH))
        assertEquals("Copy (4)", UiTranslator.translate("Kopijuoti (4)", AppLanguageManager.ENGLISH))
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
        assertEquals("Local file is missing", UiTranslator.translate("Trūksta vietinio failo", AppLanguageManager.ENGLISH))
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
