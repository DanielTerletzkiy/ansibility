package de.terletzkiy.ansibility.workspace

import de.terletzkiy.ansibility.api.ScopeChoice
import junit.framework.TestCase

/** The stored form of a scope choice (F9.1 "Persistence"): `all | current-root | named:<scopeId> | roots:<RootKey,…>`. */
class ScopeChoicesTest : TestCase() {

    fun testEveryChoiceRoundTrips() {
        val choices = listOf(
            ScopeChoice.AllRoots,
            ScopeChoice.CurrentFileRoot,
            ScopeChoice.Named("falcon"),
            ScopeChoice.Named("named: with spaces, commas and % signs"),
            ScopeChoice.Roots(setOf("repos/falcon/ansible", "golden")),
            ScopeChoice.Roots(setOf("odd,key", "100%", "%2C")),
            ScopeChoice.Roots(emptySet()),
        )
        for (choice in choices) assertEquals(choice, ScopeChoices.decode(ScopeChoices.encode(choice)))
    }

    fun testTheStoredForms() {
        assertEquals("all", ScopeChoices.encode(ScopeChoice.AllRoots))
        assertEquals("current-root", ScopeChoices.encode(ScopeChoice.CurrentFileRoot))
        assertEquals("named:falcon", ScopeChoices.encode(ScopeChoice.Named("falcon")))
        assertEquals("keys are sorted", "roots:golden,repos/falcon/ansible", ScopeChoices.encode(ScopeChoice.Roots(setOf("repos/falcon/ansible", "golden"))))
        assertEquals("roots:a%2Cb,c%25", ScopeChoices.encode(ScopeChoice.Roots(setOf("a,b", "c%"))))
        assertEquals("roots:", ScopeChoices.encode(ScopeChoice.Roots(emptySet())))
        assertEquals("an empty id is no named scope", "all", ScopeChoices.encode(ScopeChoice.Named("")))
    }

    fun testUnreadableTextIsAllRoots() {
        for (text in listOf(null, "", "  ", "everything", "named:", "scope:falcon")) {
            assertEquals(text.toString(), ScopeChoice.AllRoots, ScopeChoices.decode(text))
        }
        assertEquals(ScopeChoice.Roots(setOf("a", "b")), ScopeChoices.decode("roots:a,,b, "))
        assertEquals("a stray percent stays", ScopeChoice.Roots(setOf("50%", "x%2")), ScopeChoices.decode("roots:50%,x%2"))
        assertEquals(ScopeChoice.CurrentFileRoot, ScopeChoices.decode(" current-root "))
    }
}
