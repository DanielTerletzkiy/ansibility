package de.terletzkiy.ansibility.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.RoleDirectoryIcons

/** The icons of a role's own directories and of the drift folders (plan amendment R24, follow-up of 2026-10-09). */
class RoleDirectoryIconsTest : BasePlatformTestCase() {
    fun testEachStandardRoleDirectoryHasItsIcon() {
        val expected = mapOf(
            "tasks" to NodeIcon.ROLE_TASKS,
            "handlers" to NodeIcon.ROLE_HANDLERS,
            "defaults" to NodeIcon.ROLE_VARS,
            "vars" to NodeIcon.ROLE_VARS,
            "meta" to NodeIcon.ROLE_META,
            "templates" to NodeIcon.ROLE_TEMPLATES,
            "files" to NodeIcon.ROLE_FILES,
            "molecule" to NodeIcon.ROLE_TESTS,
            "tests" to NodeIcon.ROLE_TESTS,
            "library" to NodeIcon.ROLE_PLUGINS,
            "module_utils" to NodeIcon.ROLE_PLUGINS,
            "filter_plugins" to NodeIcon.ROLE_PLUGINS,
            "lookup_plugins" to NodeIcon.ROLE_PLUGINS,
        )
        for ((name, icon) in expected) assertEquals(name, icon, RoleDirectoryIcons.of(name))
        for (name in listOf("docs", "Tasks", "plugins", "scripts")) assertEquals(name, NodeIcon.FOLDER, RoleDirectoryIcons.of(name))
    }

    fun testTheRoleDirectoriesUseThePlatformsFoldersWhereTheyFit() {
        assertSame(AllIcons.Modules.SourceRoot, AnsibilityToolWindowIcons.of(NodeIcon.ROLE_TASKS))
        assertSame(AllIcons.Nodes.TemplateRoot, AnsibilityToolWindowIcons.of(NodeIcon.ROLE_TEMPLATES))
        assertSame(AllIcons.Modules.ResourcesRoot, AnsibilityToolWindowIcons.of(NodeIcon.ROLE_FILES))
        assertSame(AllIcons.Modules.TestRoot, AnsibilityToolWindowIcons.of(NodeIcon.ROLE_TESTS))
        assertSame(AllIcons.Nodes.PpLibFolder, AnsibilityToolWindowIcons.of(NodeIcon.ROLE_PLUGINS))
        assertNotSame("the drift folder is not the plain folder", AllIcons.Nodes.Folder, AnsibilityToolWindowIcons.of(NodeIcon.DRIFT_FOLDER))
    }

    fun testEveryNodeIconHasAnIconAndTheOwnSvgsHaveDarkVariants() {
        for (icon in NodeIcon.entries) assertTrue(icon.name, AnsibilityToolWindowIcons.of(icon).iconWidth > 0)
        for (name in listOf("driftFolder", "roleVarsFolder", "roleHandlersFolder", "roleMetaFolder")) {
            for (variant in listOf("/icons/$name.svg", "/icons/${name}_dark.svg")) {
                val svg = javaClass.getResource(variant)?.readText()
                assertNotNull(variant, svg)
                assertTrue("$variant is a 16×16 icon", svg!!.contains("width=\"16\" height=\"16\""))
            }
        }
    }
}
