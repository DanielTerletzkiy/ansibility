package de.terletzkiy.ansibility.render

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.context.host.HostContextTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.render.editor.PreviewSecretsFilter
import de.terletzkiy.ansibility.render.editor.RenderedPreviewEditor
import de.terletzkiy.ansibility.render.editor.RenderedTemplateEditorProvider
import de.terletzkiy.ansibility.render.service.PreviewPick
import de.terletzkiy.ansibility.render.service.PreviewReport
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.yaml.YAMLFileType

/**
 * The rendered template preview (plan amendment R11, F11.2) on falcon's `keepalived` role, which the KeepAliveD play
 * applies to prod-prod1 and prod-prod2: per-host values, task vars, magic variables, facts and vault values as
 * placeholders, and the loop items whose `src` renders the file.
 */
@RequiresInfraFixture
class TemplatePreviewServiceTest : HostContextTestCase() {
    override fun addFixtureFiles() {
        add("$ROLE/defaults/main.yml", "---\nkeepalived_priority: 100\nkeepalived_is_master: false")
        add(
            "$ROLE/tasks/main.yml",
            """
            ---
            - name: Configure keepalived
              ansible.builtin.template:
                src: keepalived.conf.j2
                dest: /etc/keepalived/keepalived.conf
              vars:
                keepalived_label: from-task
            - name: Deploy checks
              ansible.builtin.template:
                src: "checks/{{ item.template }}"
                dest: "/etc/keepalived/{{ item.name }}"
              loop:
                - { name: a, template: check.sh.j2, port: 80 }
                - { name: b, template: other.sh.j2, port: 81 }
                - { name: c, template: check.sh.j2, port: 82 }
              loop_control:
                label: "{{ item.name }}"
            """,
        )
        add(
            TEMPLATE,
            """
            # {{ ansible_managed }}
            vrrp_instance VI_1 {
                priority {{ keepalived_priority }}
                label {{ keepalived_label }}
                host {{ inventory_hostname }}
            {% if keepalived_unicast is defined %}
                unicast
            {% endif %}
                address {{ ansible_default_ipv4.address }}
            }
            """,
        )
        add("$ROLE/templates/checks/check.sh.j2", "port={{ item.port }}")
        add("$ROLE/templates/checks/other.sh.j2", "other={{ item.port }}")
        add("$ROLE/templates/app.yml.j2", "port: {{ keepalived_priority }}")
        add("$ROLE/templates/secret.j2", "key={{ vault_alloy_tenant_api_key_ops }}")
    }

    override fun tearDown() {
        try {
            AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testRendersTheSelectedHostWithTaskVarsAndMagicVariables() {
        val prod1 = render(TEMPLATE, host = "prod-prod1")
        assertNull(prod1.problem)
        assertTrue(prod1.text, prod1.text.startsWith("# Ansible managed\nvrrp_instance VI_1 {\n    priority 150\n"))
        assertTrue(prod1.text, "    label from-task\n" in prod1.text)
        assertTrue(prod1.text, "    host prod-prod1\n" in prod1.text)
        assertFalse(prod1.text, "unicast" in prod1.text)
        assertTrue(prod1.text, "⟨fact: ansible_default_ipv4⟩" in prod1.text)
        assertFalse("a fact is a placeholder", prod1.complete)
        assertTrue(prod1.headline, "prod › prod-prod1" in prod1.headline)

        val prod2 = render(TEMPLATE, host = "prod-prod2")
        assertTrue(prod2.text, "    priority 100\n" in prod2.text)
        assertTrue(prod2.text, "    host prod-prod2\n" in prod2.text)
    }

    fun testOffersOnlyTheItemsWhoseSrcRendersThisFile() {
        val first = render(CHECK, host = "prod-prod1")
        assertEquals(first.choices.map { it.label }.toString(), listOf(0, 2), first.choices.map { it.pick.item })
        assertTrue(first.choices.first().label, "Deploy checks" in first.choices.first().label && ": a" in first.choices.first().label)
        assertEquals("port=80", first.text.trimEnd())

        val third = render(CHECK, host = "prod-prod1", pick = first.choices[1].pick)
        assertEquals("port=82", third.text.trimEnd())
        assertTrue(third.headline, "item 3 of 3" in third.headline)

        val other = render("$ROLE/templates/checks/other.sh.j2", host = "prod-prod1")
        assertEquals(listOf(1), other.choices.map { it.pick.item })
        assertEquals("other=81", other.text.trimEnd())
    }

    fun testVaultValuesAreNeverRendered() {
        val report = render("$ROLE/templates/secret.j2", host = "prod-prod1")
        assertEquals("key=⟨\uD83D\uDD12 vault_alloy_tenant_api_key_ops⟩", report.text.trimEnd())
        assertFalse("no payload", InfraTestData.containsVaultPayload(report.text))
    }

    /** A vault value is a hole that names where it is written; only a tab that opted in passes its plaintext. */
    fun testVaultValuesRenderOnlyWithTheTabsPlaintext() {
        val plain = render("$ROLE/templates/secret.j2", host = "prod-prod1")
        assertEquals(0, plain.secretsShown)
        val at = plain.secretSources["vault_alloy_tenant_api_key_ops"]
        assertNotNull("the hole knows its source", at)

        val shown = render("$ROLE/templates/secret.j2", host = "prod-prod1", secrets = mapOf("vault_alloy_tenant_api_key_ops" to "s3cr3t"))
        assertEquals("key=s3cr3t", shown.text.trimEnd())
        assertEquals(1, shown.secretsShown)
        assertEquals(at, shown.secretSources["vault_alloy_tenant_api_key_ops"])
    }

    /** The switch is off on every open, and while plaintext is shown nothing but the lexer looks at the output. */
    fun testVaultSwitchIsOffOnOpenAndPlaintextIsNotAnalysed() {
        val editor = RenderedTemplateEditorProvider().createEditor(project, vf("$ROLE/templates/secret.j2")) as TextEditorWithPreview
        try {
            editor.component
            assertFalse((editor.previewEditor as RenderedPreviewEditor).showVaultValues)
        } finally {
            Disposer.dispose(editor)
        }
        val output = com.intellij.testFramework.LightVirtualFile("secret", "key=s3cr3t")
        val psi = runReadActionBlocking { com.intellij.psi.PsiManager.getInstance(project).findFile(output)!! }
        assertTrue(PreviewSecretsFilter().shouldHighlight(psi))
        output.putUserData(RenderedPreviewEditor.PREVIEW_SECRETS, true)
        assertFalse(PreviewSecretsFilter().shouldHighlight(psi))
    }

    /** The context picker offers the hosts that render the template, whatever the selection. */
    fun testRenderingHostsAreTheHostsOfThePlaysThatApplyTheRole() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        val hosts = runReadActionBlocking { TemplatePreviewService.getInstance(project).renderingHosts(vf(TEMPLATE)) }
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts?.map { "${it.environment}/${it.host}" })
    }

    fun testFollowsUnsavedTemplateText() {
        val report = render(TEMPLATE, host = "prod-prod1", source = "p={{ keepalived_priority + 1 }}")
        assertEquals("p=151", report.text.trimEnd())
    }

    /** D76: templates get the split editor, opened as "editor only"; task files keep the plain editor. */
    fun testSplitEditorDefaultsToEditorOnly() {
        val provider = RenderedTemplateEditorProvider()
        assertTrue(runReadActionBlocking { provider.accept(project, vf(TEMPLATE)) })
        assertFalse(runReadActionBlocking { provider.accept(project, vf("$ROLE/tasks/main.yml")) })
        val editor = provider.createEditor(project, vf(TEMPLATE)) as TextEditorWithPreview
        try {
            editor.component
            assertEquals(TextEditorWithPreview.Layout.SHOW_EDITOR, editor.getLayout())
            assertTrue(editor.previewEditor is RenderedPreviewEditor)
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** The preview is highlighted as the output's type, and lines with placeholders are marked like deleted diff lines. */
    fun testPreviewUsesTheOutputTypeAndMarksPlaceholderLines() {
        assertEquals(YAMLFileType.YML, RenderedPreviewEditor.outputType(vf("$ROLE/templates/app.yml.j2")))
        assertEquals("app.yml", RenderedPreviewEditor.outputName(vf("$ROLE/templates/app.yml.j2")))
        val report = render(TEMPLATE, host = "prod-prod1")
        val editor = RenderedTemplateEditorProvider().createEditor(project, vf(TEMPLATE)) as TextEditorWithPreview
        try {
            editor.component
            val preview = editor.previewEditor as RenderedPreviewEditor
            preview.showForTests(report)
            val expected = report.text.lines().withIndex().filter { "⟨" in it.value }.map { it.index }
            assertFalse(expected.isEmpty())
            assertEquals(expected, preview.problemLines)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private fun render(path: String, host: String, pick: PreviewPick? = null, source: String? = null, secrets: Map<String, String> = emptyMap()): PreviewReport {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), host))
        val file = vf(path)
        val text = source ?: FileDocumentManager.getInstance().getDocument(file)!!.text
        return runReadActionBlocking { TemplatePreviewService.getInstance(project).render(file, text, pick, secrets) }
    }

    private companion object {
        const val ROLE = "$FALCON/roles/keepalived"
        const val TEMPLATE = "$ROLE/templates/keepalived.conf.j2"
        const val CHECK = "$ROLE/templates/checks/check.sh.j2"
    }
}
