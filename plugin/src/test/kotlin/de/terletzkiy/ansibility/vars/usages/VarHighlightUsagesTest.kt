package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.IdentifierHighlightingComputer
import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.injected.editor.EditorWindow
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.TestActionEvent

/**
 * F1.10 acceptance 4: Highlight Usages in File (Ctrl+Shift+F7) and the caret highlighting mark every use of the
 * variable in the open file, uses in other injections included, with definitions as writes; one handler serves both
 * (`highlightUsagesHandlerFactory`), in the host and in the injected editor.
 */
class VarHighlightUsagesTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
        copyUsagesData("runtime")
    }

    fun testHighlightUsagesInFileFromAnInjectionMarksHostRanges() {
        at(TASKS, 34, "web_port")
        val action = HighlightUsagesAction()
        val event = TestActionEvent.createTestEvent(action, EditorUtil.getEditorDataContext(hostEditor()))
        assertTrue("Ctrl+Shift+F7 runs with the injected editor", event.getData(CommonDataKeys.EDITOR) is EditorWindow)
        ActionUtil.updateAction(action, event)
        ActionUtil.performAction(action, event)
        val marked = hostEditor().markupModel.allHighlighters.map { lineAndText(TASKS, TextRange(it.startOffset, it.endOffset)) }.sorted()
        assertEquals(TASK_USES, marked)
    }

    fun testCaretHighlightingUsesTheSameHandler() {
        at(TASKS, 9, "web_port")
        val handler = highlightHandlerAtCaret()
        assertInstanceOf(handler, VarHighlightUsagesHandler::class.java)
        assertEquals(TASK_USES, handler!!.readUsages.map { lineAndText(TASKS, it) }.sorted())
        assertEmpty("the tasks define no web_port", handler.writeUsages)

        // The computer IdentifierHighlighterPass runs: it asks the custom handler first.
        val editor = hostEditor()
        val file = hostFile()
        val result = inBackgroundReadAction {
            IdentifierHighlightingComputer(file, editor, ProperTextRange.create(file.textRange), editor.caretModel.offset).computeRanges()
        }
        val reads = result.occurrences.filter { it.highlightInfoType == HighlightInfoType.ELEMENT_UNDER_CARET_READ }.map { lineAndText(TASKS, TextRange.create(it.range)) }
        assertEquals(TASK_USES, reads.sorted())
    }

    fun testCaretHighlightingInATemplateAndOnADefinition() {
        at(TEMPLATE, 1, "web_port")
        assertEquals(listOf("1:web_port", "4:web_port"), highlightHandlerAtCaret()!!.readUsages.map { lineAndText(TEMPLATE, it) }.sorted())
        at(DEFAULTS, 3, "web_port")
        val handler = highlightHandlerAtCaret()!!
        assertEquals("the key is a write", listOf("3:web_port"), handler.writeUsages.map { lineAndText(DEFAULTS, it) })
        assertEmpty(handler.readUsages)
    }

    fun testLoopVariablesAndLocalsHighlightTheirOwnScope() {
        at(ALPHA_TASKS, 16, "server.name")
        val loop = highlightHandlerAtCaret()!!
        assertEquals("not the use outside the loop (line 22)", listOf("16:server"), loop.readUsages.map { lineAndText(ALPHA_TASKS, it) })
        assertEquals(listOf("19:server"), loop.writeUsages.map { lineAndText(ALPHA_TASKS, it) })

        at(ALPHA_TEMPLATE, 2, "greeting }}")
        val local = highlightHandlerAtCaret()!!
        assertEquals(listOf("2:greeting", "2:greeting"), local.readUsages.map { lineAndText(ALPHA_TEMPLATE, it) })
        assertEquals(listOf("2:greeting"), local.writeUsages.map { lineAndText(ALPHA_TEMPLATE, it) })
    }

    fun testNoHandlerAwayFromVariables() {
        at(TASKS, 3, "ansible.builtin.stat")
        assertFalse(highlightHandlerAtCaret() is VarHighlightUsagesHandler)
    }

    companion object {
        const val TASKS = VarFindUsagesTest.TASKS
        const val TEMPLATE = VarFindUsagesTest.TEMPLATE
        const val DEFAULTS = VarFindUsagesTest.DEFAULTS
        const val ALPHA_TASKS = VarUsageScopingTest.ALPHA_TASKS
        const val ALPHA_TEMPLATE = VarUsageScopingTest.ALPHA_TEMPLATE

        /** The uses of `web_port` in the role's tasks: `when:`, `assert.that`, the folded shell value. */
        val TASK_USES = listOf("31:web_port", "34:web_port", "9:web_port")
    }
}
