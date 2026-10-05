package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.context.host.HostContextTestCase
import de.terletzkiy.ansibility.dispatch.AnsibleDocumentationTargetProvider
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.vars.VarsTestCase
import java.util.concurrent.TimeUnit

/**
 * Base of the host-aware card tests (plan amendment R7/R8, F8.2): the falcon, platform and pelican roots and golden's postfix
 * of the sanitised infra fixture ([HostContextTestCase]), hovered through the plugin's documentation entry point with
 * the real card sections, as the platform does (a read action on a pooled thread).
 *
 * The light test project is shared between test classes, so every selection a test sets is reset in `finally`
 * ([withSelection]) and once more in [tearDown].
 */
abstract class HostCardTestCase : HostContextTestCase() {
    /**
     * Golden's `alloy` role inside falcon (as the real repo has it, for the `alloy_tenant_api_key` example) and falcon's
     * `keepalived` role with the real repo's `defaults/main.yml:2` (the fixture's falcon subset carries neither).
     */
    override fun addFixtureFiles() {
        copyTree("${InfraTestData.INFRA}/golden/roles/alloy", ALLOY)
        add(KEEPALIVED_DEFAULTS, "---\nkeepalived_priority: 100\nkeepalived_is_master: false")
        add(
            "$FALCON/roles/keepalived/tasks/main.yml",
            """
            ---
            - name: Configure keepalived
              ansible.builtin.template:
                src: keepalived.conf.j2
                dest: /etc/keepalived/keepalived.conf
            """,
        )
        add(KEEPALIVED_TEMPLATE, "vrrp_instance VI_1 {\n    priority {{ keepalived_priority }}\n}")
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

    /** Runs [action] with [selection] stored for the root at [rootPath], and resets the selection afterwards. */
    protected fun <T> withSelection(rootPath: String, selection: RootContext, action: () -> T): T {
        val root = root(rootPath)
        context.setSelection(root, selection)
        try {
            return action()
        } finally {
            context.setSelection(root, RootContext.DEFAULT)
        }
    }

    /** The offset of [marker] on 1-based [line] of [path], plus [delta]. */
    protected fun offsetAt(path: String, line: Int, marker: String, delta: Int = 0): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val lineStart = StringUtil.lineColToOffset(text, line - 1, 0)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val index = text.substring(lineStart, lineEnd).indexOf(marker)
        check(index >= 0) { "'$marker' not on line $line of $path: ${text.substring(lineStart, lineEnd)}" }
        return lineStart + index + delta
    }

    /**
     * The value of the `key: value` line [line] (1-based) of [path], unquoted. The tests read the sanitised fixture's
     * values and names this way instead of pinning them, so they keep working when the fixture's aliases are regenerated.
     */
    protected fun valueOn(path: String, line: Int): String = lineOf(path, line).substringAfter(':').trim().removeSurrounding("\"")

    /** The key of the `key: value` line [line] (1-based) of [path]. */
    protected fun keyOn(path: String, line: Int): String = lineOf(path, line).substringBefore(':').trim()

    /** Line [line] (1-based) of [path], or "" past its end. */
    private fun lineOf(path: String, line: Int): String = VfsUtilCore.loadText(vf(path)).lines().getOrNull(line - 1).orEmpty()

    /** `relayinternal.mx.example.de`: the winning `postfix_relayhost` of the falcon System play (`group_vars/all/vars.yml:156`). */
    protected val relayHost: String get() = valueOn(FALCON_PLAYBOOK_ALL, 156)

    /** The shadowed prod (`:471`) and test (`:323`) relay hosts, and the molecule one (`molecule.yml:56`). */
    protected val prodRelayHost: String get() = valueOn(FALCON_PROD_ALL, 471)
    protected val testRelayHost: String get() = valueOn(FALCON_TEST_ALL, 323)
    protected val moleculeRelayHost: String get() = valueOn(POSTFIX_MOLECULE, 56)

    /** The card target at [offset] of [path], through the plugin's documentation entry point. */
    protected fun hover(path: String, offset: Int): DocumentationTarget = runReadActionBlocking {
        val psi = PsiManager.getInstance(project).findFile(vf(path))!!
        AnsibleDocumentationTargetProvider().documentationTargets(psi, offset)
    }.singleOrNull() ?: error("no documentation target at $path:$offset")

    /** The card's HTML, computed as the platform computes it (a read action on a pooled thread). */
    protected fun html(target: DocumentationTarget): String = inBackgroundReadAction { (target.computeDocumentation() as DocumentationData).html }

    /** The card of the variable at [marker] on [line] of [path]. */
    protected fun card(path: String, line: Int, marker: String, delta: Int = 1): String = html(hover(path, offsetAt(path, line, marker, delta)))

    protected fun text(html: String): String = VarsTestCase.plain(html)

    /** The plain text of the Effective section (the TOP chunk between the definition line and the card's own content). */
    protected fun effective(html: String): String {
        val start = html.indexOf("Effective")
        check(start >= 0) { "no Effective section in $html" }
        val end = html.indexOf("</div>", start).let { if (it < 0) html.length else it }
        return text(html.substring(start, end))
    }

    /** The plain text of the sections-table row titled [title], or null when the card has none. */
    protected fun row(html: String, title: String): String? {
        val marker = "<p>$title</p>"
        val at = html.indexOf(marker).takeIf { it >= 0 } ?: html.indexOf(">$title</p>").takeIf { it >= 0 } ?: return null
        val rest = html.substring(at)
        val end = rest.indexOf("</tr>").let { if (it < 0) rest.length else it }
        return text(rest.substring(0, end)).removePrefix(title).trim()
    }

    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)

    protected companion object {
        const val ALLOY = "$FALCON/roles/alloy"
        const val ALLOY_TEMPLATE = "$ALLOY/templates/config-base.alloy.j2"
        const val KEEPALIVED_DEFAULTS = "$FALCON/roles/keepalived/defaults/main.yml"
        const val KEEPALIVED_TEMPLATE = "$FALCON/roles/keepalived/templates/keepalived.conf.j2"
        const val FALCON_PROD1_VARS = "$FALCON/environments/prod/host_vars/prod-prod1/vars.yml"
        const val POSTFIX_SPEC = "$FALCON/roles/postfix/meta/argument_specs.yml"
        const val FALCON_VAULT = "$FALCON/group_vars/all/vault.yml"
        const val HAPROXY_TEMPLATE = "$FALCON/roles/haproxy/templates/haproxy.cfg.j2"
    }
}
