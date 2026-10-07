package de.terletzkiy.ansibility.run

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLFile

/** The plays and roles of a playbook, the tags that select each, and the tags added for untagged roles. */
class PlaybookPartsTest : BasePlatformTestCase() {
    private val playbook = """
        |---
        |- name: Ping all hosts
        |  hosts: all
        |  gather_facts: true
        |  serial: 1
        |  tasks:
        |    - name: Ping
        |      ansible.builtin.ping:
        |
        |- name: System
        |  hosts: system
        |  become: true
        |  pre_tasks:
        |    - name: Prepare
        |      ansible.builtin.include_role:
        |        name: prepare
        |  roles:
        |    - { role: system, tags: ['system'] }
        |    - role: postfix
        |      vars:
        |        relay: mail.example.test
        |    # the firewall last
        |    - iptables
        |    - { role: debug, tags: [debug, always] }
        |  tasks:
        |    - name: Certificates
        |      import_role:
        |        name: certs
        |      tags: [certs]
        |    - name: Not a role
        |      ansible.builtin.debug: {}
        |
        |- hosts: web
        |  tags: web
        |  roles: [nginx, {role: certs, tags: [certs]}]
        |
        |- name: Other
        |  hosts: db
        |  roles:
        |    - { role: certs, tags: ['system'] }
        |
        |- ansible.builtin.import_playbook: playbook-other.yml
        |""".trimMargin()

    private lateinit var file: YAMLFile

    override fun setUp() {
        super.setUp()
        file = myFixture.configureByText("playbook-site.yml", playbook) as YAMLFile
    }

    private val plays get() = PlaybookParts.plays(file)

    fun testPlaysAndRolesWithTheirTags() {
        assertEquals(listOf("Ping all hosts", "System", "", "Other", "playbook-other.yml"), plays.map { it.name })
        assertEquals(listOf("Ping all hosts", "System", "hosts: web", "Other", "playbook-other.yml"), plays.map { it.label })
        val system = plays[1]
        assertEquals(listOf("prepare", "system", "postfix", "iptables", "debug", "certs"), system.roles.map { it.name })
        assertEquals(listOf(emptyList(), listOf("system"), emptyList(), emptyList(), listOf("debug", "always"), listOf("certs")), system.roles.map { it.tags })
        assertEquals("a scalar tags value is a comma-separated list", listOf("web"), plays[2].tags)
        assertEquals(listOf(1, 1, 0, 0, 0), plays.map { it.untaggedTasks })
    }

    fun testARoleIsSelectedByItsOwnTags() {
        val system = selection(PlaybookTarget.role(1, "System", 1, "system"))
        assertEquals(listOf("system"), system.tags)
        assertEmpty(system.additions)
        assertEquals(listOf("role 'certs' of play 'Other' (tags system)"), system.alsoSelected)

        val certs = selection(PlaybookTarget.role(1, "System", 5, "certs"))
        assertEquals(listOf("certs"), certs.tags)
        assertEquals(listOf("role 'certs' of play 'hosts: web' (tags certs)"), certs.alsoSelected)
    }

    fun testAnUntaggedRoleGetsItsNameAsTag() {
        val iptables = selection(PlaybookTarget.role(1, "System", 3, "iptables"))
        assertEmpty(iptables.tags)
        assertEquals(listOf(TagAddition(PlaybookTarget.role(1, "System", 3, "iptables"), "iptables", "Role 'iptables' gets tags: [iptables]")), iptables.additions)
        assertEquals(listOf("iptables"), iptables.tagsWithAdditions)

        val include = selection(PlaybookTarget.role(1, "System", 0, "prepare"))
        assertEquals("an include_role without apply can get the tag", "prepare", include.additions.single().tag)
    }

    fun testAPlayIsSelectedByItsRolesTagsOrItsOwn() {
        val system = selection(PlaybookTarget.play(1, "System"))
        assertEquals(listOf("system", "debug", "always", "certs"), system.tags)
        assertEquals(listOf("prepare", "postfix", "iptables"), system.additions.map { it.tag })
        assertEquals(listOf("1 untagged task(s) of the play"), system.notSelected)
        assertEquals(listOf("role 'certs' of play 'hosts: web' (tags certs)", "role 'certs' of play 'Other' (tags system)"), system.alsoSelected)

        val web = selection(PlaybookTarget.play(2, ""))
        assertEquals("a play's own tags select all of it", listOf("web"), web.tags)
        assertEmpty(web.additions)

        val ping = selection(PlaybookTarget.play(0, "Ping all hosts"))
        assertEmpty(ping.tags)
        assertEquals(listOf("ping-all-hosts"), ping.additions.map { it.tag })

        val other = selection(PlaybookTarget.play(3, "Other"))
        assertEquals(listOf("system"), other.tags)
        assertEquals(listOf("role 'system' of play 'System' (tags system)"), other.alsoSelected)

        assertNull(PlaybookParts.selection(plays, PlaybookTarget.PLAYBOOK))
        assertNull(PlaybookParts.selection(plays, PlaybookTarget.play(1, "Gone")))
    }

    fun testBecomeIsSeenOnPlaysAndRoleEntries() {
        assertTrue(PlaybookParts.usesBecome(plays, PlaybookTarget.PLAYBOOK))
        assertTrue(PlaybookParts.usesBecome(plays, PlaybookTarget.play(1, "System")))
        assertTrue("a role runs with its play's become", PlaybookParts.usesBecome(plays, PlaybookTarget.role(1, "System", 3, "iptables")))
        assertFalse(PlaybookParts.usesBecome(plays, PlaybookTarget.play(0, "Ping all hosts")))
        assertFalse(PlaybookParts.usesBecome(plays, PlaybookTarget.play(2, "")))
        val own = myFixture.configureByText("playbook-own.yml", "- hosts: all\n  roles:\n    - { role: a, become: yes }\n    - b\n") as YAMLFile
        val ownPlays = PlaybookParts.plays(own)
        assertTrue(PlaybookParts.usesBecome(ownPlays, PlaybookTarget.role(0, "", 0, "a")))
        assertFalse(PlaybookParts.usesBecome(ownPlays, PlaybookTarget.role(0, "", 1, "b")))
        assertTrue("a play with one becoming role", PlaybookParts.usesBecome(ownPlays, PlaybookTarget.play(0, "")))
    }

    fun testTargetsAreFoundByNameAfterEdits() {
        val (play, role) = PlaybookParts.find(file, PlaybookTarget.role(7, "System", 9, "iptables"))!!
        assertEquals("System", play.name)
        assertEquals(3, role!!.index)
        assertNull("an unnamed play is found by index only", PlaybookParts.find(file, PlaybookTarget.play(1, "")))
    }

    fun testEntriesStartAtTheirDashOrFirstLeaf() {
        val markers = PsiTreeUtil.collectElements(file) { it.node.elementType == YAMLTokenTypes.SEQUENCE_MARKER }
        val names = markers.mapNotNull { PlaybookParts.entryAt(it) }.map { if (it is PlaybookPlay) "play:${it.label}" else "role:${(it as PlaybookRole).name}" }
        assertEquals(
            listOf(
                "play:Ping all hosts", "play:System", "role:prepare", "role:system", "role:postfix", "role:iptables", "role:debug", "role:certs",
                "play:hosts: web", "play:Other", "role:certs", "play:playbook-other.yml",
            ),
            names,
        )
        val nginx = PsiTreeUtil.collectElements(file) { it.firstChild == null && it.text == "nginx" }.single()
        assertEquals("nginx", (PlaybookParts.entryAt(nginx) as PlaybookRole).name)
    }

    fun testMissingTagsAreAddedInTheStyleAroundThem() {
        val additions = selection(PlaybookTarget.play(1, "System")).additions + selection(PlaybookTarget.play(0, "Ping all hosts")).additions +
            selection(PlaybookTarget.role(2, "", 0, "nginx")).additions
        assertTrue(PlaybookTagEdits.apply(project, file.virtualFile, additions))
        assertEquals(
            """
            |---
            |- name: Ping all hosts
            |  hosts: all
            |  tags: ['ping-all-hosts']
            |  gather_facts: true
            |  serial: 1
            |  tasks:
            |    - name: Ping
            |      ansible.builtin.ping:
            |
            |- name: System
            |  hosts: system
            |  become: true
            |  pre_tasks:
            |    - name: Prepare
            |      ansible.builtin.include_role:
            |        name: prepare
            |        apply:
            |          tags: ['prepare']
            |      tags: ['prepare']
            |  roles:
            |    - { role: system, tags: ['system'] }
            |    - role: postfix
            |      vars:
            |        relay: mail.example.test
            |      tags: ['postfix']
            |    # the firewall last
            |    - { role: iptables, tags: ['iptables'] }
            |    - { role: debug, tags: [debug, always] }
            |  tasks:
            |    - name: Certificates
            |      import_role:
            |        name: certs
            |      tags: [certs]
            |    - name: Not a role
            |      ansible.builtin.debug: {}
            |
            |- hosts: web
            |  tags: web
            |  roles: [{role: nginx, tags: ['nginx']}, {role: certs, tags: [certs]}]
            |
            |- name: Other
            |  hosts: db
            |  roles:
            |    - { role: certs, tags: ['system'] }
            |
            |- ansible.builtin.import_playbook: playbook-other.yml
            |""".trimMargin(),
            file.text,
        )
        val system = selection(PlaybookTarget.play(1, "System"))
        assertEquals(listOf("prepare", "system", "postfix", "iptables", "debug", "always", "certs"), system.tags)
        assertEmpty(system.additions)
        assertEquals(listOf("ping-all-hosts"), selection(PlaybookTarget.play(0, "Ping all hosts")).tags)
        assertEquals(listOf("nginx"), selection(PlaybookTarget.role(2, "", 0, "nginx")).tags)

        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("one command, undone at once", playbook, file.text)
    }

    fun testBlockStyleNeighboursGetABlockMapping() {
        val block = myFixture.configureByText("playbook-block.yml", "- hosts: all\n  roles:\n    - role: a\n      tags: [a]\n    - b\n") as YAMLFile
        val addition = PlaybookParts.selection(PlaybookParts.plays(block), PlaybookTarget.role(0, "", 1, "b"))!!.additions
        assertTrue(PlaybookTagEdits.apply(project, block.virtualFile, addition))
        assertEquals("- hosts: all\n  roles:\n    - role: a\n      tags: [a]\n    - role: b\n      tags: [b]\n", block.text)
    }

    private fun selection(target: PlaybookTarget): TagSelection = PlaybookParts.selection(plays, target)!!
}
