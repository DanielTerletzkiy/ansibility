package de.terletzkiy.ansibility.vars

/**
 * Whether Go to Declaration is asked for the Ctrl-hover hint rather than for navigation (plan A4, kept by the R31
 * fixes, decision 8).
 *
 * The platform computes both from the same `gotoDeclarationHandler` call: Ctrl+B and Ctrl+click navigate to its
 * targets (a chooser for several), and Ctrl-hover shows the documentation hint of the target only when there is
 * exactly one, "Multiple implementations" otherwise. Since R31 a variable reference has several targets (its spec, the
 * role default, every override), so Ctrl-hover would lose the variable's hint. No public API tells the two calls apart,
 * so the hover call is recognised by its caller, the `getCtrlMouseData` of the platform's Go to Declaration handlers
 * (`GotoDeclarationOrUsageHandler2`, `GotoDeclarationOnlyHandler2`; the Ctrl+click itself runs the Go to Declaration
 * action, which does not go through it). Should the platform rename that entry point, this answers false and Ctrl-hover
 * falls back to the platform's several-targets text: nothing breaks.
 */
internal object CtrlHover {
    /** The package and name prefix of the platform's Go to Declaration actions and handlers. */
    private const val HANDLER = "com.intellij.codeInsight.navigation.actions.GotoDeclaration"
    private const val METHOD = "getCtrlMouseData"

    /** True while the platform computes the Ctrl-hover hint on this thread. */
    fun isComputing(): Boolean = StackWalker.getInstance().walk { frames ->
        frames.anyMatch { it.methodName == METHOD && it.className.startsWith(HANDLER) }
    }
}
