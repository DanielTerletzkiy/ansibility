package de.terletzkiy.ansibility.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * `AllIcons.Nodes.Locked` is the platform's corner overlay (a small lock drawn in the bottom-right of a 16×16 canvas,
 * meant to sit on another node icon), so used on its own it looks small and off-centre next to `AllIcons.Ide.Readwrite`
 * (user report 2026-10-09). The full-size locked icon is `AllIcons.Ide.Readonly`, the pair of `Ide.Readwrite`.
 * Gradle runs the plugin tests in the `plugin` module directory.
 */
class VaultIconsTest {
    private val main: Path = Paths.get("src/main")

    @Test
    fun theCornerOverlayLockIsNeverUsedOnItsOwn() {
        val users = Files.walk(main).use { paths ->
            paths.filter { it.isRegularFile() && it.extension in setOf("kt", "xml") }
                .filter { "AllIcons.Nodes.Locked" in it.readText() }
                .map { main.relativize(it).toString() }
                .toList()
        }
        assertEquals("use AllIcons.Ide.Readonly for a locked state", emptyList<String>(), users)
    }
}
