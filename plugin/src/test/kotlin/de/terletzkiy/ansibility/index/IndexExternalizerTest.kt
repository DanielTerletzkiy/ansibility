package de.terletzkiy.ansibility.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.io.DataExternalizer
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** A platform test only because the externalizers live next to their index IDs, which need the application. */
class IndexExternalizerTest : BasePlatformTestCase() {
    private fun <T> roundTrip(externalizer: DataExternalizer<T>, value: T): T {
        val bytes = ByteArrayOutputStream().also { externalizer.save(DataOutputStream(it), value) }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return externalizer.read(input).also { assertEquals("all bytes consumed", 0, input.available()) }
    }

    fun testVarDefEntriesRoundTrip() {
        val entries = listOf(
            DefEntry(DefSite.SPEC_OPTION, PathHint.ARGUMENT_SPECS, 12, ValueShape.CONTAINER, LiteralType.NONE, entryPoint = "main", contentHash = -5),
            DefEntry(
                DefSite.INVENTORY_INLINE, PathHint.INVENTORY, 70_000, ValueShape.LITERAL, LiteralType.STR, preview = "192.0.2.29 · ünïcode",
                owner = "prod-prod1", ownerIsHost = true, hasDocComment = true, contentHash = 42,
            ),
            DefEntry(DefSite.REGISTER, PathHint.TASKS, 0, ValueShape.CONTAINER, LiteralType.NONE),
        )
        for (site in DefSite.entries) {
            for (shape in ValueShape.entries) {
                val e = DefEntry(site, PathHint.OTHER, 1, shape, LiteralType.BOOL)
                assertEquals(listOf(e), roundTrip(VarDefIndex.EXTERNALIZER, listOf(e)))
            }
        }
        assertEquals(entries, roundTrip(VarDefIndex.EXTERNALIZER, entries))
        assertEquals(emptyList<DefEntry>(), roundTrip(VarDefIndex.EXTERNALIZER, emptyList()))
    }

    fun testVarUseEntriesRoundTrip() {
        val entries = UseContainer.entries.mapIndexed { i, container ->
            UseEntry(i * 1000, container, guarded = i % 2 == 0, guardedByCondition = i == 1, called = i == 2, attrPath = List(i) { "p$it" })
        }
        assertEquals(entries, roundTrip(VarUseIndex.EXTERNALIZER, entries))
        // FU2: the INDIRECT flag and its kind, alone and next to every other flag
        val indirect = listOf(null, JinjaIndirection.HOSTVARS, JinjaIndirection.VARS).flatMap { via ->
            listOf(
                UseEntry(7, UseContainer.YAML_TEMPLATE, guarded = false, guardedByCondition = false, called = false, attrPath = emptyList(), indirect = via),
                UseEntry(70_000, UseContainer.YAML_EXPRESSION, guarded = true, guardedByCondition = true, called = false, attrPath = listOf("a", "0"), indirect = via),
            )
        }
        assertEquals(indirect, roundTrip(VarUseIndex.EXTERNALIZER, indirect))
        assertEquals(listOf(false, false, true, true, true, true), indirect.map { it.isIndirect })
    }

    fun testTaskIndexEntriesRoundTrip() {
        val renders = SrcKind.entries.map { RenderEntry(it.code, it.code + 7, it, "{{ x }}".takeIf { _ -> it.code % 2 == 0 }, "item", listOf("item", "a"), null) }
        assertEquals(renders, roundTrip(TemplateUseIndex.EXTERNALIZER, renders))
        val handlers = listOf(HandlerEntry(3, false, 1), HandlerEntry(40, true, 1))
        assertEquals(handlers, roundTrip(HandlerIndex.EXTERNALIZER, handlers))
        assertEquals(listOf(0, 5, 1 shl 20), roundTrip(ModuleUseIndex.EXTERNALIZER, listOf(0, 5, 1 shl 20)))
        val plays = listOf(
            PlayEntry(
                0, "System", "system,web",
                RoleUseKind.entries.map { PlayRoleUse("r${it.code}", "tasks".takeIf { _ -> it != RoleUseKind.ROLES }, it.code, it) },
                listOf("a"), listOf("vars/common.yml"), null,
            ),
            PlayEntry(90, null, null, emptyList(), emptyList(), emptyList(), "other.yml"),
        )
        assertEquals(plays, roundTrip(PlayIndex.EXTERNALIZER, plays))
    }

    fun testValueOfAnotherFormatIsRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply { writeByte(99); writeByte(0) }
        }.toByteArray()
        assertThrows(IOException::class.java) { VarDefIndex.EXTERNALIZER.read(DataInputStream(ByteArrayInputStream(bytes))) }
    }
}
