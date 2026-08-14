package com.affilemanager.app.network

import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class RemoteErrorInfo(
    val title: String,
    val detail: String,
    val suggestion: String,
    val diagnosticCode: String,
)

enum class RemoteOperation(val diagnosticPart: String) {
    CONNECT("CONNECT"),
    LIST("LIST"),
    CREATE_DIRECTORY("CREATE"),
    RENAME("RENAME"),
    DELETE("DELETE"),
}

enum class FtpFailureStage(val diagnosticPart: String) {
    GREETING("GREETING"),
    LOGIN("AUTH"),
    BINARY_MODE("TYPE"),
    LIST("LIST"),
}

class FtpCommandException(
    val stage: FtpFailureStage,
    val replyCode: Int?,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append("FTP ")
        append(stage.diagnosticPart)
        append(" failed")
        replyCode?.let { append(" (").append(it).append(')') }
    },
    cause,
)

object RemoteErrorPresenter {
    fun invalidProfile(problem: String): RemoteErrorInfo = RemoteErrorInfo(
        title = "Jungties duomenys neteisingi",
        detail = problem,
        suggestion = "Paspauskite pieštuką ir pataisykite pažymėtą profilio lauką.",
        diagnosticCode = "PROFILE-INPUT",
    )

    fun present(protocol: NetworkProtocol, operation: RemoteOperation, error: Throwable): RemoteErrorInfo {
        val causes = error.causeChain()
        causes.filterIsInstance<FtpCommandException>().firstOrNull()?.let(::ftpFailure)?.let { return it }
        if (causes.any { it is UnknownHostException }) {
            return RemoteErrorInfo(
                title = "Serverio adresas neteisingas",
                detail = "Android nepavyko rasti nurodyto serverio.",
                suggestion = "Serverio lauke palikite tik IP adresą arba domeną, be prisijungimo duomenų ir ftp://.",
                diagnosticCode = "NET-DNS",
            )
        }
        if (causes.any { it is SocketTimeoutException }) {
            return RemoteErrorInfo(
                title = "Baigėsi ryšio laukimo laikas",
                detail = "Serveris arba jo duomenų kanalas laiku neatsakė.",
                suggestion = "Patikrinkite telefono internetą, prievadą ir serverio pasyvaus FTP prievadus, tada bandykite dar kartą.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-TIMEOUT",
            )
        }
        if (causes.any { it is SSLException }) {
            return RemoteErrorInfo(
                title = "Saugaus ryšio patvirtinti nepavyko",
                detail = "TLS sertifikatas arba saugaus ryšio suderinimas buvo atmestas.",
                suggestion = "Patikrinkite, ar pasirinktas FTPS / HTTPS režimas ir ar serverio sertifikatas galioja.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-TLS",
            )
        }
        if (causes.any { it is FTPConnectionClosedException }) {
            return RemoteErrorInfo(
                title = "FTP serveris uždarė ryšį",
                detail = "Valdymo ryšys buvo uždarytas prieš užbaigiant komandą.",
                suggestion = "Bandykite dar kartą; jei kartojasi, patikrinkite serverio neveiklumo ir ryšio ribas.",
                diagnosticCode = "FTP-${operation.diagnosticPart}-CLOSED",
            )
        }
        if (causes.any { it is NoRouteToHostException || it is ConnectException }) {
            return RemoteErrorInfo(
                title = "Serverio pasiekti nepavyko",
                detail = "Nurodytas prievadas nepriėmė ryšio arba iki serverio nėra tinklo kelio.",
                suggestion = "Patikrinkite IP adresą, prievadą, telefono tinklą ir serverio ugniasienę.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-UNREACHABLE",
            )
        }

        val safeMessage = causes.asSequence()
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .take(2_048)
            .lowercase()
        if (listOf("authentication", "auth fail", "login", "credential", "password", " 401", " 530").any(safeMessage::contains)) {
            return RemoteErrorInfo(
                title = "Prisijungimas atmestas",
                detail = "Serveris nepriėmė pateiktų prisijungimo duomenų.",
                suggestion = "Redaguokite jungtį ir patikrinkite naudotojo vardą, slaptažodį bei pasirinktą protokolą.",
                diagnosticCode = "${protocol.name}-AUTH",
            )
        }
        if (listOf("certificate", "host key", "fingerprint").any(safeMessage::contains)) {
            return RemoteErrorInfo(
                title = "Serverio tapatybė nepatvirtinta",
                detail = "Sertifikatas arba SSH rakto atspaudas neatitiko išsaugotos reikšmės.",
                suggestion = "Patikrinkite serverio tapatybę prieš keisdami išsaugotą sertifikatą ar rakto atspaudą.",
                diagnosticCode = "${protocol.name}-IDENTITY",
            )
        }
        if (listOf("permission denied", "access denied", "forbidden", " 403").any(safeMessage::contains)) {
            return RemoteErrorInfo(
                title = "Serveris neleido atlikti veiksmo",
                detail = "Paskyrai nepakanka teisių pasirinktam katalogui arba veiksmui.",
                suggestion = "Patikrinkite paskyros teises ir pradinį katalogą.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-DENIED",
            )
        }
        if (listOf("no such file", "not found", " 404", " 550").any(safeMessage::contains)) {
            return RemoteErrorInfo(
                title = "Nuotolinis kelias nepasiekiamas",
                detail = "Nurodyto pradinio katalogo arba elemento serveryje nepavyko atverti.",
                suggestion = "Redaguokite jungtį ir patikrinkite pradinį kelią; dažniausiai tinka /.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-PATH",
            )
        }
        if (causes.any { it is SocketException }) {
            return RemoteErrorInfo(
                title = "Tinklo ryšys nutrūko",
                detail = "Telefono ir serverio ryšys nutrūko vykdant komandą.",
                suggestion = "Patikrinkite interneto ryšį ir bandykite dar kartą.",
                diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-SOCKET",
            )
        }

        val type = error.javaClass.simpleName
            .uppercase()
            .filter(Char::isLetterOrDigit)
            .take(24)
            .ifBlank { "UNKNOWN" }
        return RemoteErrorInfo(
            title = if (operation == RemoteOperation.CONNECT) "Prisijungti nepavyko" else "Nuotolinis veiksmas nepavyko",
            detail = "Gauta netikėta ryšio klaida; prisijungimo duomenys saugumo sumetimais nerodomi.",
            suggestion = "Patikrinkite jungties nustatymus ir pateikite diagnostikos kodą, jei klaida kartojasi.",
            diagnosticCode = "${protocol.name}-${operation.diagnosticPart}-$type",
        )
    }

    private fun ftpFailure(error: FtpCommandException): RemoteErrorInfo {
        val reply = error.replyCode?.toString() ?: "NO-REPLY"
        return when (error.stage) {
            FtpFailureStage.LOGIN -> RemoteErrorInfo(
                title = "FTP prisijungimas atmestas",
                detail = "Serveris atmetė prisijungimą (FTP ${error.replyCode ?: "be kodo"}).",
                suggestion = "Patikrinkite naudotojo vardą, slaptažodį ir ar reikia FTP, ar FTPS.",
                diagnosticCode = "FTP-AUTH-$reply",
            )
            FtpFailureStage.LIST -> RemoteErrorInfo(
                title = "FTP katalogo atverti nepavyko",
                detail = "Prisijungta, bet serveris neatvėrė pradinio katalogo (FTP ${error.replyCode ?: "be kodo"}).",
                suggestion = "Patikrinkite pradinį kelią, paskyros teises ir pasyvaus FTP prievadus.",
                diagnosticCode = "FTP-LIST-$reply",
            )
            FtpFailureStage.GREETING -> RemoteErrorInfo(
                title = "FTP serveris atmetė ryšį",
                detail = "Serverio pradinis atsakymas nebuvo sėkmingas (FTP ${error.replyCode ?: "be kodo"}).",
                suggestion = "Patikrinkite prievadą, serverio apkrovą ir ar pasirinktas tinkamas FTP režimas.",
                diagnosticCode = "FTP-GREETING-$reply",
            )
            FtpFailureStage.BINARY_MODE -> RemoteErrorInfo(
                title = "FTP perdavimo režimas nepalaikomas",
                detail = "Serveris neįjungė failams būtino dvejetainio režimo (FTP ${error.replyCode ?: "be kodo"}).",
                suggestion = "Patikrinkite serverio FTP konfigūraciją arba bandykite kitą protokolą.",
                diagnosticCode = "FTP-TYPE-$reply",
            )
        }
    }

    private fun Throwable.causeChain(): List<Throwable> {
        val result = ArrayList<Throwable>(4)
        var current: Throwable? = this
        while (current != null && result.size < 8 && result.none { it === current }) {
            result += current
            current = current.cause
        }
        return result
    }
}
