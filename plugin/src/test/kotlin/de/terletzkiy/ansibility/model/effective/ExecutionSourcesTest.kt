package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.semantics.precedence.VarLayer

/**
 * [ExecutionSources] on a synthetic root: the L2/L12/L13/L14/L18 sources of a play for each running role, in
 * ansible-core's order, the templated `vars_files` markers, `private_role_vars`, `defaults_from`, and the runtime markers
 * for `set_fact`, `register` and `include_vars`.
 */
class ExecutionSourcesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        for ((path, text) in FILES) myFixture.addFileToProject(path, text.trimIndent() + "\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    private fun root(path: String): AnsibleRoot = AnsibleWorkspace.getInstance(project).roots().single { it.dir.path.endsWith("/$path") }

    private fun play(path: String, name: String): PlayRef =
        PlayGraph.getInstance(project).playsOf(myFixture.findFileInTempDir(path)!!).single { it.ref.name == name }.ref

    private fun inputs(running: String?, playName: String = "Web", rootPath: String = SITE) =
        ExecutionSources.getInstance(project).inputs(root(rootPath), play("$rootPath/site.yml", playName), running)

    /** Layer and the role (or the file name) of each source, in engine order. */
    private fun describe(inputs: ExecutionInputs): List<String> = inputs.sources
        .sortedWith(compareBy({ it.layer.level }, { it.order }))
        .map { source ->
            val origin = inputs.origins.getValue(source.originId)
            "${source.layer.name}:${origin.role ?: origin.file.name}"
        }

    private fun line(location: SourceLocation): String =
        "${location.file.name}:${FileDocumentManager.getInstance().getDocument(location.file)!!.getLineNumber(location.offset) + 1}"

    fun testPlayLevelTasksSeeEveryPublicRole() {
        val inputs = inputs(running = null)
        assertEquals(
            listOf(
                "ROLE_DEFAULTS:base", "ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web",
                "PLAY_VARS:site.yml", "VARS_FILES:common.yml",
                "ROLE_VARS:base", "ROLE_VARS:web",
            ),
            describe(inputs),
        )
        assertEquals(listOf("site.yml:9"), inputs.unknownSources.map(::line))
        assertTrue(inputs.sources.all { it.owner == de.terletzkiy.ansibility.semantics.precedence.VarOwner.All })
        assertTrue(inputs.origins.values.all { it.play?.name == "Web" })
    }

    fun testTheRunningRoleIsReappliedLastWithItsEntryVarsAndParams() {
        assertEquals(
            listOf(
                "ROLE_DEFAULTS:base", "ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web",
                "PLAY_VARS:site.yml", "VARS_FILES:common.yml",
                "ROLE_VARS:base", "ROLE_VARS:web", "ROLE_VARS:web",
                "ROLE_PARAMS:web",
            ),
            describe(inputs(running = "web")),
        )
        assertEquals(
            listOf(
                "ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web", "ROLE_DEFAULTS:base",
                "PLAY_VARS:site.yml", "VARS_FILES:common.yml",
                "ROLE_VARS:web", "ROLE_VARS:base",
            ),
            describe(inputs(running = "base")),
        )
    }

    fun testIncludedRolesArePrivateUnlessRunning() {
        val dyn = describe(inputs(running = "dyn"))
        assertEquals(listOf("ROLE_DEFAULTS:base", "ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web", "ROLE_DEFAULTS:dyn"), dyn.filter { it.startsWith("ROLE_DEFAULTS") })
        assertEquals("include params", listOf("ROLE_PARAMS:dyn"), dyn.filter { it.startsWith("ROLE_PARAMS") })
        assertFalse(describe(inputs(running = null)).contains("ROLE_DEFAULTS:dyn"))
    }

    fun testWinnersFollowAnsibleCoresOrder() {
        val context = AnsibleContextServiceImpl.getInstance(project)!!
        val web1 = EvalTarget(HostKey(SITE, "prod", "web1"), play("$SITE/site.yml", "Web"), myFixture.findFileInTempDir(SITE))
        fun winner(name: String, running: String?) = context.explain(web1, name, running).winner?.source
        assertEquals("defaults/main.yml of web", "web", winner("shared", null)?.role)
        assertEquals("the running role wins among defaults", "base", winner("shared", "base")?.role)
        assertEquals("vars_files beat play vars", VarsLayer.VARS_FILES, winner("shared_play", null)?.layer)
        assertEquals(VarsLayer.ROLE_PARAMS, winner("web_param", "web")?.layer)
        assertNull("role params belong to their role's tasks", winner("web_param", "base"))
        assertEquals(VarsLayer.ROLE_VARS, winner("web_entry_var", "web")?.layer)
        assertEquals(VarsLayer.ROLE_PARAMS, winner("dyn_param", "dyn")?.layer)
        assertEquals("1", winner("dyn_default", "dyn")?.preview)
        assertNull(winner("dyn_default", "web"))
    }

    fun testDefaultsFromOfTheRunningInclude() {
        val partial = inputs(running = "web", playName = "Partial")
        val files = partial.sources.filter { it.layer == VarLayer.ROLE_DEFAULTS }.map { partial.origins.getValue(it.originId).file.name }
        assertEquals("defaults_from replaces main for the running role", listOf("main.yml", "other.yml"), files)
        val dep = partial.origins.values.first { it.role == "dep" }
        assertEquals("main.yml", dep.file.name)
    }

    fun testPrivateRoleVarsKeepOnlyTheRunningChain() {
        assertEquals(
            listOf("ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web", "PLAY_VARS:site.yml", "ROLE_VARS:web", "ROLE_VARS:web", "ROLE_PARAMS:web"),
            describe(inputs(running = "web", rootPath = PRIVATE)),
        )
    }

    fun testARunningRoleWithoutAPlayBringsItsDependencies() {
        val inputs = ExecutionSources.getInstance(project).inputs(root(SITE), null, "web")
        assertEquals(listOf("ROLE_DEFAULTS:dep", "ROLE_DEFAULTS:web", "ROLE_VARS:web"), describe(inputs))
        assertEquals(emptyList<Any>(), ExecutionSources.getInstance(project).inputs(root(SITE), null, null).sources)
        assertEquals("a play that does not apply the running role is evaluated play-level", emptyList<String>(), describe(inputs(running = "web", playName = "Elsewhere")))
    }

    fun testRuntimeMarkers() {
        val context = AnsibleContextService.getInstance(project)
        val web1 = EvalTarget(HostKey(SITE, "prod", "web1"), play("$SITE/site.yml", "Web"), myFixture.findFileInTempDir(SITE))
        val fact = context.explain(web1, "runtime_fact").runtimeMarkers.single()
        assertEquals(RuntimeMarkerKind.SET_FACT, fact.kind)
        assertEquals("main.yml:4", line(fact.location))
        assertEquals(RuntimeMarkerKind.REGISTER, context.explain(web1, "id_result").runtimeMarkers.single().kind)
        val included = context.explain(web1, "included_name").runtimeMarkers.single()
        assertEquals(RuntimeMarkerKind.INCLUDE_VARS, included.kind)
        assertEquals("main.yml:2", line(included.location))
        assertEquals("play-level set_fact", RuntimeMarkerKind.SET_FACT, context.explain(web1, "play_fact").runtimeMarkers.single().kind)
        assertEquals(emptyList<Any>(), context.explain(web1, "elsewhere_fact").runtimeMarkers)
        val chain = context.explain(web1, "shared")
        assertEquals(listOf("site.yml:9"), chain.unknownSources.map(::line))
    }

    fun testPlayVarsStatusIsShadowedByVarsFiles() {
        val definition = VarService.getInstance(project).symbol(root(SITE), "shared_play").definitions.single { it.kind == VarDefKind.PLAY_VARS }
        val status = AnsibleContextService.getInstance(project).definitionStatus(definition)
        assertEquals(emptyList<Any>(), status.winsOn)
        assertEquals(listOf("web1", "web2"), status.shadowedOn.keys.map { it.host })
        assertTrue(status.shadowedOn.values.all { it.layer == VarsLayer.VARS_FILES && it.file.name == "common.yml" })

        val playOnly = VarService.getInstance(project).symbol(root(SITE), "play_only").definitions.single()
        assertEquals(listOf("web1", "web2"), AnsibleContextService.getInstance(project).definitionStatus(playOnly).winsOn.map { it.host })
    }

    fun testRoleFilesLoadAsAnsibleLoadsThem() {
        // `_get_dir_vars_files`: sorted per directory level (`a` before `a.yml`), recursive, vars extensions only.
        myFixture.addFileToProject("$SITE/roles/ordered/defaults/main/a/x.yml", "---\nordered_value: x")
        myFixture.addFileToProject("$SITE/roles/ordered/defaults/main/a.yml", "---\nordered_value: a")
        myFixture.addFileToProject("$SITE/roles/ordered/defaults/extra2/a.yml", "---\nordered_from: 1")
        myFixture.addFileToProject("$SITE/roles/ordered/defaults/extra2/notes.txt", "ordered_from: 3")
        myFixture.addFileToProject("$SITE/roles/ordered/defaults/extra2/sub/b.yml", "---\nordered_from: 2")
        myFixture.addFileToProject("$SITE/roles/ordered/tasks/main.yml", "---\n- name: Noop\n  ansible.builtin.debug:\n    msg: hi")
        myFixture.addFileToProject(
            "$SITE/ordered.yml",
            "---\n- name: Ordered\n  hosts: web\n  tasks:\n    - name: Include\n      ansible.builtin.include_role:\n        name: ordered\n        defaults_from: extra2",
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val roleDir = myFixture.findFileInTempDir("$SITE/roles/ordered")!!
        fun defaults(inputs: ExecutionInputs): List<String> = inputs.sources.filter { it.layer == VarLayer.ROLE_DEFAULTS }
            .map { VfsUtilCore.getRelativePath(inputs.origins.getValue(it.originId).file, roleDir)!! }
        val standalone = ExecutionSources.getInstance(project).inputs(root(SITE), null, "ordered")
        assertEquals("a.yml loads last and wins", listOf("defaults/main/a/x.yml", "defaults/main/a.yml"), defaults(standalone))
        val included = ExecutionSources.getInstance(project).inputs(root(SITE), play("$SITE/ordered.yml", "Ordered"), "ordered")
        assertEquals(
            "a defaults_from directory is read recursively, without notes.txt",
            listOf("defaults/extra2/a.yml", "defaults/extra2/sub/b.yml"),
            defaults(included),
        )
    }

    fun testInputsAreCachedUntilAVarsFileChanges() {
        val first = inputs(running = "web")
        assertSame(first, inputs(running = "web"))
        myFixture.addFileToProject("$SITE/roles/web/defaults/main/extra.yml", "---\nx: 1\n")
        assertNotSame(first, inputs(running = "web"))
    }

    private companion object {
        const val SITE = "site"
        const val PRIVATE = "private"

        private const val SITE_YML = """
            ---
            - name: Web
              hosts: web
              vars:
                shared_play: play
                play_only: p
              vars_files:
                - vars/common.yml
                - "vars/{{ env }}.yml"
              roles:
                - base
                - role: web
                  web_param: 42
                  vars:
                    web_entry_var: ev
              tasks:
                - name: Dynamic
                  ansible.builtin.include_role:
                    name: dyn
                  vars:
                    dyn_param: 7
                - name: Play fact
                  ansible.builtin.set_fact:
                    play_fact: true

            - name: Partial
              hosts: web
              tasks:
                - name: Other defaults
                  ansible.builtin.include_role:
                    name: web
                    defaults_from: other.yml

            - name: Elsewhere
              hosts: web
              tasks:
                - name: Not in the Web play
                  ansible.builtin.set_fact:
                    elsewhere_fact: true
        """

        private val ROLES = mapOf(
            "roles/base/defaults/main.yml" to "---\nshared: base\nbase_only: b",
            "roles/base/vars/main.yml" to "---\nbase_var: bv",
            "roles/base/tasks/main.yml" to "---\n- name: Fact\n  ansible.builtin.set_fact:\n    runtime_fact: x\n- name: Reg\n  ansible.builtin.command: id\n  register: id_result",
            "roles/dep/defaults/main.yml" to "---\nshared: dep\ndep_only: d",
            "roles/dep/tasks/main.yml" to "---\n- name: Noop\n  ansible.builtin.debug:\n    msg: hi",
            "roles/web/meta/main.yml" to "---\ndependencies:\n  - role: dep",
            "roles/web/defaults/main.yml" to "---\nshared: web\nweb_port: 80",
            "roles/web/defaults/other.yml" to "---\nweb_port: 8080",
            "roles/web/vars/main.yml" to "---\nweb_var: wv",
            "roles/web/vars/extra.yml" to "---\nincluded_name: i",
            "roles/web/tasks/main.yml" to "---\n- name: Load extra\n  ansible.builtin.include_vars: extra.yml",
            "roles/dyn/defaults/main.yml" to "---\ndyn_default: 1\nshared: dyn",
            "roles/dyn/tasks/main.yml" to "---\n- name: Noop\n  ansible.builtin.debug:\n    msg: dyn",
        )

        private const val HOSTS = "---\nall:\n  hosts:\n    web1:\n    web2:\nweb:\n  hosts:\n    web1:\n    web2:"

        val FILES: Map<String, String> = buildMap {
            put("$SITE/ansible.cfg", "[defaults]\nhash_behaviour = replace")
            put("$SITE/environments/prod/hosts.yml", HOSTS)
            put("$SITE/vars/common.yml", "---\nfrom_vars_files: c\nshared_play: vf")
            put("$SITE/site.yml", SITE_YML)
            for ((path, text) in ROLES) put("$SITE/$path", text)
            put("$PRIVATE/ansible.cfg", "[defaults]\nprivate_role_vars = yes")
            put("$PRIVATE/environments/prod/hosts.yml", HOSTS)
            put("$PRIVATE/site.yml", "---\n- name: Web\n  hosts: web\n  vars:\n    p: 1\n  roles:\n    - base\n    - role: web\n      web_param: 42\n      vars:\n        web_entry_var: ev")
            for ((path, text) in ROLES) put("$PRIVATE/$path", text)
        }
    }
}
