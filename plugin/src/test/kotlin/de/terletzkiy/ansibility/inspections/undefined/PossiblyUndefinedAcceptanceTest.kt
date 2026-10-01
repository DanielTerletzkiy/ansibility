package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The acceptance of ANS-V003 (plan amendment R7/R8, addendum F8.12) on `repos/falcon/ansible/roles/alloy/templates/
 * config-base.alloy.j2` of a temp copy of the fixture (the real repo is never touched), through the registered
 * inspection and `SeverityPolicy`.
 */
class PossiblyUndefinedAcceptanceTest : UndefinedTestCase() {
    private val prodLine = "alloy_tenant_api_key: \"{{ vault_alloy_tenant_api_key_prod }}\""

    /** Removes the two guards around `bearer_token` (lines 15/17 and 32/34 become empty, so line numbers stay). */
    private fun removeGuards() {
        val text = VfsUtil.loadText(vf(CONFIG_BASE))
        val lines = text.lines().toMutableList()
        for (index in lines.indices) {
            if (lines[index] == GUARD && lines[index + 2] == "{% endif %}") {
                lines[index] = ""
                lines[index + 2] = ""
            }
        }
        write(CONFIG_BASE, lines.joinToString("\n"))
    }

    fun testGuardedTemplateHasNoFinding() {
        assertEquals(GUARD, VfsUtil.loadText(vf(CONFIG_BASE)).lines()[14])
        assertEmpty(findings(CONFIG_BASE))
        assertEmpty(highlights(CONFIG_BASE))
    }

    fun testUnguardedUseIsAWarningWhileEveryEnvironmentSetsIt() {
        removeGuards()
        assertEquals(listOf("16 WARNING alloy_tenant_api_key", "33 WARNING alloy_tenant_api_key"), highlights(CONFIG_BASE))
        val finding = analyse(CONFIG_BASE).first()
        assertEquals(UndefinedKind.EVERYWHERE_SET, finding.kind)
        assertEquals(
            "'alloy_tenant_api_key' has no default in role alloy and this use is not guarded: it works only because every " +
                "current environment sets it; the spec says optional (ANS-V003)",
            finding.message,
        )
    }

    fun testWithoutTheProdDefinitionItIsAnErrorNamingTheProdHosts() {
        removeGuards()
        replace(PROD_ALL, prodLine, "")
        assertEquals(listOf("16 ERROR alloy_tenant_api_key", "33 ERROR alloy_tenant_api_key"), highlights(CONFIG_BASE))
        val finding = analyse(CONFIG_BASE).first()
        assertEquals(UndefinedKind.MISSING, finding.kind)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), finding.missing.map { "${it.environment}/${it.host}" })
        assertEquals(
            "'alloy_tenant_api_key' is undefined on prod (prod-prod1, prod-prod2): this use is not guarded and role alloy has " +
                "no default for it, so rendering fails there (ANS-V003)",
            finding.message,
        )
    }

    fun testDefaultFilterAndDefinedGuardStayClean() {
        replace(PROD_ALL, prodLine, "")
        val lines = VfsUtil.loadText(vf(CONFIG_BASE)).lines().toMutableList()
        assertEquals(listOf(GUARD, "    bearer_token = \"{{ alloy_tenant_api_key }}\"", "{% endif %}"), lines.subList(31, 34))
        lines[14] = "{% if alloy_tenant_api_key is defined %}"
        lines[31] = ""
        lines[32] = "    bearer_token = \"{{ alloy_tenant_api_key | default('') }}\""
        lines[33] = ""
        write(CONFIG_BASE, lines.joinToString("\n"))
        assertEmpty(findings(CONFIG_BASE))
        assertEmpty(highlights(CONFIG_BASE))
    }

    fun testRequiredSpecVariableStaysP003s() {
        replace(CONFIG_BASE, "{% if alloy_tenant_id is defined and alloy_tenant_id %}", "")
        replace(PROD_ALL, prodLine, "")
        assertTrue("alloy_tenant_id is required: ANS-P003, not ANS-V003", findings(CONFIG_BASE).none { "alloy_tenant_id" in it })
    }

    fun testAlwaysErrorSetting() {
        removeGuards()
        rootSettings(FALCON) { it.copy(unguardedOptionalAlwaysError = true) }
        assertEquals(listOf("16 ERROR alloy_tenant_api_key", "33 ERROR alloy_tenant_api_key"), highlights(CONFIG_BASE))
    }

    fun testMandatory() {
        removeGuards()
        replace(CONFIG_BASE, "bearer_token = \"{{ alloy_tenant_api_key }}\"", "bearer_token = \"{{ alloy_tenant_api_key | mandatory }}\"")
        assertEmpty("every environment sets it: the author opted into failing", findings(CONFIG_BASE).filter { it.startsWith("16 ") })
        replace(PROD_ALL, prodLine, "")
        assertEquals(listOf("16 MISSING alloy_tenant_api_key prod/prod-prod1,prod/prod-prod2"), findings(CONFIG_BASE).filter { it.startsWith("16 ") })
    }

    fun testSelectionChangesNothing() {
        removeGuards()
        replace(PROD_ALL, prodLine, "")
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        val context = AnsibleContextService.getInstance(project)
        val results = listOf(
            RootContext(),
            RootContext(EnvironmentChoice.Named("test"), "test-test1", null),
            RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", null),
            RootContext(EnvironmentChoice.Named("ops"), null, null),
        ).map { selection ->
            context.setSelection(root, selection)
            findings(CONFIG_BASE) to highlights(CONFIG_BASE)
        }
        assertEquals(2, results.first().first.size)
        assertTrue(results.joinToString("\n"), results.all { it == results.first() })
    }

    fun testCleanRoleFilesOfTheFixture() {
        for (path in listOf("$ALLOY/tasks/configure.yml", "$ALLOY/tasks/install.yml", "$ALLOY/defaults/main.yml", "$FALCON/playbook-setup-system.yml")) {
            assertEmpty(path, findings(path))
        }
    }
}
