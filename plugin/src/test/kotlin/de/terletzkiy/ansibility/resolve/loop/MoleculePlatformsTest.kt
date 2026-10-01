package de.terletzkiy.ansibility.resolve.loop

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionTestCase
import de.terletzkiy.ansibility.semantics.schema.OptionType

/** 🟣 X77 [MoleculePlatforms]: molecule Dockerfile templates type `item` as a molecule platform. */
class MoleculePlatformsTest : JinjaInjectionTestCase() {

    private fun line(path: String, offset: Int): Int = StringUtil.offsetToLineNumber(VfsUtilCore.loadText(vf(path)), offset) + 1

    fun testHaproxyDockerfileItemIsThePlatform() {
        copyInfra("golden/roles/haproxy")
        val dockerfile = "golden/roles/haproxy/molecule/default/Dockerfile.j2"
        val config = "golden/roles/haproxy/molecule/default/molecule.yml"
        val renders = inBackgroundReadAction { MoleculePlatforms.rendersOf(project, vf(dockerfile)) }
        val render = renders.single()
        assertEquals(vf(config), render.config)
        assertEquals("the platforms key", 9, line(config, render.platformsKey.offset))
        assertEquals(listOf(10, 24), render.platforms.map { line(config, it.offset) })

        val loop = render.loop
        assertEquals("item", loop.loopVar)
        assertEquals(MoleculePlatforms.SOURCE_VARIABLE, loop.sourceVariable)
        assertEquals(listOf("platforms"), loop.sourcePath)
        val item = loop.item!!
        assertEquals(OptionType.Dict, item.type)
        val options = item.options!!
        assertEquals(OptionType.Str, options.getValue("image").type)
        assertEquals(OptionType.Str, options.getValue("command").type)
        assertEquals(OptionType.Bool, options.getValue("privileged").type)
        assertEquals(OptionType.List, options.getValue("tmpfs").type)
        assertEquals(OptionType.Str, options.getValue("tmpfs").elements)
        assertEquals("written dict keys are kept", setOf("container"), options.getValue("env").options!!.keys)
        assertTrue("documented keys complete the written ones", "dockerfile" in options && "registry" in options)
        assertTrue(options.getValue("name").required)

        assertEquals(OptionType.Str, LoopItemTyper.typeOfPath(loop, "item", listOf("image"))?.type)
        assertEquals(OptionType.Str, LoopItemTyper.typeOfPath(loop, "item", listOf("registry", "url"))?.type)

        val context = render.renderContext()
        assertEquals(RenderKind.STATIC, context.kind)
        assertEquals(vf(dockerfile), context.template)
        assertNull("molecule's playbook does not apply the role", context.role)
        assertEquals(loop, context.loop)
        assertEquals(loop, inBackgroundReadAction { MoleculePlatforms.loopOf(project, vf(dockerfile)) })
        assertEquals(listOf(context), inBackgroundReadAction { MoleculePlatforms.renderContexts(project, vf(dockerfile)) })
    }

    fun testRoleTemplatesAreNotMoleculeRendered() {
        copyInfra("golden/roles/haproxy")
        assertEmpty(inBackgroundReadAction { MoleculePlatforms.rendersOf(project, vf("golden/roles/haproxy/templates/haproxy.cfg.j2")) })
        assertNull(inBackgroundReadAction { MoleculePlatforms.loopOf(project, vf("golden/roles/haproxy/molecule/default/molecule.yml")) })
        assertEmpty(inBackgroundReadAction { MoleculePlatforms.renderContexts(project, vf("golden/roles/haproxy/templates/haproxy.cfg.j2")) })
    }

    fun testSharedDockerfilePreBuiltAndOtherDrivers() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/roles/web/molecule/shared/Dockerfile.j2", "FROM {{ item.image }}\n")
        createFile("site/roles/web/molecule/default/Dockerfile.j2", "FROM {{ item.image }}\n")
        createFile(
            "site/roles/web/molecule/default/molecule.yml",
            """
            driver:
              name: podman
            platforms:
              - name: built
                image: debian:13
                dockerfile: ../shared/Dockerfile.j2
                exposed_ports: ["80/tcp"]
              - name: prebuilt
                image: debian:12
                pre_build_image: true
                dockerfile: ../shared/Dockerfile.j2
                extra_key: 1
            """,
        )
        createFile(
            "site/roles/web/molecule/delegated/molecule.yml",
            """
            driver:
              name: default
            platforms:
              - name: remote
            """,
        )
        val shared = inBackgroundReadAction { MoleculePlatforms.rendersOf(project, vf("site/roles/web/molecule/shared/Dockerfile.j2")) }
        val render = shared.single()
        assertEquals("pre-built platforms are not rendered", 1, render.platforms.size)
        val options = render.loop.item!!.options!!
        assertFalse("only the building platform's keys", "extra_key" in options)
        assertEquals(OptionType.List, options.getValue("exposed_ports").type)
        assertEmpty("no platform builds from the default Dockerfile.j2 here", inBackgroundReadAction {
            MoleculePlatforms.rendersOf(project, vf("site/roles/web/molecule/default/Dockerfile.j2"))
        })
    }

    fun testEveryPlatformPreBuiltStillTypesTheTemplate() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/roles/web/molecule/default/Dockerfile.j2", "FROM {{ item.image }}\n")
        createFile(
            "site/roles/web/molecule/default/molecule.yml",
            "driver:\n  name: docker\nplatforms:\n  - name: a\n    image: x\n    pre_build_image: true\n",
        )
        val loop = inBackgroundReadAction { MoleculePlatforms.loopOf(project, vf("site/roles/web/molecule/default/Dockerfile.j2")) }
        assertEquals(OptionType.Str, loop?.item?.options?.get("image")?.type)
    }
}
