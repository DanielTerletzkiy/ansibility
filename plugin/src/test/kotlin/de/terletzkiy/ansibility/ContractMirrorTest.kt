package de.terletzkiy.ansibility

import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the enums that :semantics mirrors from the frozen api, so the two cannot drift apart
 * (the plugin maps between them by name).
 */
class ContractMirrorTest {
    @Test
    fun varLayerMirrorsVarsLayer() {
        assertEquals(
            VarsLayer.entries.map { Triple(it.name, it.level, it.label) },
            VarLayer.entries.map { Triple(it.name, it.level, it.label) },
        )
    }
}
