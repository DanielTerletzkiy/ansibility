package de.terletzkiy.ansibility.vault.ui

import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap

/**
 * The marker types that tell OS clipboard managers a copied vault value is a secret (F7.1 "mark as sensitive", F7.13
 * rule 5, spike S-V5). They travel next to the text as extra clipboard formats whose content is a fixed marker, never
 * the value:
 *
 * - **macOS** (nspasteboard.org): `org.nspasteboard.ConcealedType` (a password: managers neither show nor keep it)
 *   and `org.nspasteboard.TransientType` (put there for a moment: managers do not record it);
 * - **Windows**: `ExcludeClipboardContentFromMonitorProcessing` (clipboard monitors skip it),
 *   `CanIncludeInClipboardHistory` = 0 (not in Win+V history) and `CanUploadToCloudClipboard` = 0 (not synced);
 * - **Linux and the BSDs** (KDE Klipper): `x-kde-passwordManagerHint` = `secret`.
 *
 * Each marker gets a private [DataFlavor] mapped to exactly its native name through the JDK's public
 * [SystemFlavorMap] (`setNativesForFlavor`, one direction only, so reading the clipboard never produces these
 * flavors), and the toolkit writes the marker bytes under that name when the IDE puts the value on the system
 * clipboard. It is a hint: a clipboard manager that ignores these conventions may still keep a copy (the texts say
 * so), the IDE's `ide.mac.useNativeClipboard` mode passes on the text only (as may split mode, whose frontend owns
 * the clipboard), and the 30-s clear stays the actual protection.
 */
internal object VaultClipboardHints {
    /** One marker format: its [native] clipboard name, the private flavor that carries it, and its fixed content. */
    class Hint(val native: String, private val content: ByteArray) {
        /** A flavor of its own per marker (flavors compare by MIME subtype and class, so every subtype is distinct). */
        val flavor: DataFlavor = DataFlavor("application/x-ansibility-hint-${native.lowercase().replace(NOT_MIME, "-")}; class=\"[B\"", native)

        /** The marker bytes, a fresh copy for each request. */
        fun content(): ByteArray = content.copyOf()

        override fun toString(): String = "Hint($native)"
    }

    /** The desktop families with a clipboard-manager convention. */
    enum class Desktop { MAC, WINDOWS, UNIX, OTHER }

    private val NOT_MIME = Regex("[^a-z0-9]+")
    private val DWORD_ZERO = byteArrayOf(0, 0, 0, 0)

    /** The hints of the running desktop, registered with the default flavor map on first use. */
    val current: List<Hint> by lazy { forDesktop(localDesktop()).onEach(::register) }

    /** The hints [desktop]'s clipboard managers honour (pure; [current] registers them). */
    fun forDesktop(desktop: Desktop): List<Hint> = when (desktop) {
        Desktop.MAC -> listOf(Hint("org.nspasteboard.ConcealedType", ByteArray(0)), Hint("org.nspasteboard.TransientType", ByteArray(0)))
        Desktop.WINDOWS -> listOf(
            Hint("ExcludeClipboardContentFromMonitorProcessing", DWORD_ZERO),
            Hint("CanIncludeInClipboardHistory", DWORD_ZERO),
            Hint("CanUploadToCloudClipboard", DWORD_ZERO),
        )
        Desktop.UNIX -> listOf(Hint("x-kde-passwordManagerHint", "secret".toByteArray(Charsets.US_ASCII)))
        Desktop.OTHER -> emptyList()
    }

    /** The desktop family of [os]. */
    fun desktopOf(os: OS): Desktop = when (os) {
        OS.macOS -> Desktop.MAC
        OS.Windows -> Desktop.WINDOWS
        OS.Linux, OS.FreeBSD -> Desktop.UNIX
        else -> Desktop.OTHER
    }

    /**
     * The desktop of this JVM: the markers go to the AWT clipboard of this very machine, so the local OS is the right
     * one here.
     */
    @OptIn(LowLevelLocalMachineAccess::class)
    fun localDesktop(): Desktop = desktopOf(OS.CURRENT)

    /** Maps [hint]'s flavor to exactly its native name (idempotent; no reverse mapping, no generated `JAVA_DATAFLAVOR:` native). */
    private fun register(hint: Hint) {
        (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)?.setNativesForFlavor(hint.flavor, arrayOf(hint.native))
    }
}
