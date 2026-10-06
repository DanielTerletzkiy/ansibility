package de.terletzkiy.ansibility.docs

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/** F5.4 keyword hover (M2 acceptance 10: `when:` at golden/roles/haproxy/tasks/main.yml:10) and keyword levels. */
@RequiresInfraFixture
class KeywordDocumentationTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
        copyDocsData()
    }

    private fun keywordTarget(path: String, offset: Int): KeywordDocumentationTarget =
        docTarget(path, offset) as? KeywordDocumentationTarget ?: error("not a keyword target at $path:$offset")

    private val keywordsPage = "${DOCS_11}reference_appendices/playbooks_keywords.html"

    fun testWhenAtHaproxyMainLine10() {
        val target = keywordTarget(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "when", 2))
        val data = documentation(target)
        val text = plain(data.html)
        assertTrue(text, text.startsWith("when : list keyword · Task"))
        assertTrue(text, text.contains("Implicit Jinja expression — no {{ }}"))
        assertTrue(text, text.contains("Conditional expression, determines if an iteration of a task is run or not."))
        assertTrue(text, text.contains("Applies to: Role · Block · Task · Handler · PlaybookInclude"))
        assertEquals("$keywordsPage#task", externalUrl(data))
        assertEquals("when : list · keyword — Conditional expression, determines if an iteration of a task is run or not.", plain(hint(target)!!))
    }

    fun testExplicitKeywordsHaveNoImplicitNote() {
        val text = plain(documentation(keywordTarget(CONFIGURE, at(CONFIGURE, 9, "notify"))).html)
        assertTrue(text, text.startsWith("notify : list keyword · Task"))
        assertFalse(text, text.contains("Implicit Jinja expression"))
        val changedWhen = plain(documentation(keywordTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "changed_when"))).html)
        assertTrue(changedWhen, changedWhen.contains("Implicit Jinja expression"))
    }

    fun testLoopKeywords() {
        val withItems = plain(documentation(keywordTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "with_items"))).html)
        assertTrue(withItems, withItems.startsWith("with_items keyword · Task"))
        assertTrue(withItems, withItems.contains("Loops over the results of the lookup plugin items"))
        val loopVar = keywordTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "loop_var"))
        val data = documentation(loopVar)
        assertTrue(plain(data.html), plain(data.html).startsWith("loop_var : str keyword · loop_control"))
        assertEquals("loop_control keywords live in the task section", "$keywordsPage#task", externalUrl(data))
    }

    fun testLevelsAndSections() {
        assertEquals("$keywordsPage#play", externalUrl(documentation(keywordTarget(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "hosts:")))))
        assertEquals("$keywordsPage#role", externalUrl(documentation(keywordTarget(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "tags: [demo]")))))
        assertEquals("$keywordsPage#block", externalUrl(documentation(keywordTarget(DEMO_TASKS, offsetOf(DEMO_TASKS, "rescue:")))))
        val importPlaybook = documentation(keywordTarget(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "ansible.builtin.import_playbook")))
        assertTrue(plain(importPlaybook.html), plain(importPlaybook.html).contains("The playbook file to import"))
        assertEquals("$keywordsPage#play", externalUrl(importPlaybook))
        val listen = documentation(keywordTarget(HAPROXY_HANDLERS, at(HAPROXY_HANDLERS, 8, "listen")))
        assertTrue(plain(listen.html), plain(listen.html).contains("Templating: Not templated"))
        assertEquals("handlers live in the task section", "$keywordsPage#task", externalUrl(listen))
    }

    fun testUnknownLevelGivesNoTarget() {
        val root = root(DEMO_TASKS)
        assertNull(inBackgroundReadAction {
            KeywordDocumentationTarget.create(project, root, "hosts", de.terletzkiy.ansibility.api.KeywordLevel.TASK)
        })
        assertNotNull(inBackgroundReadAction {
            KeywordDocumentationTarget.create(project, root, "hosts", de.terletzkiy.ansibility.api.KeywordLevel.PLAY)
        })
    }
}
