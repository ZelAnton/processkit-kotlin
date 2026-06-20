package net.zelanton.processkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure-unit checks of the [Signal] value type. */
class SignalTest {
    @Test
    fun `curated signals map to their POSIX numbers`() {
        assertEquals(15, Signal.Term.rawUnix)
        assertEquals(9, Signal.Kill.rawUnix)
        assertEquals(2, Signal.Int.rawUnix)
        assertEquals(1, Signal.Hup.rawUnix)
        assertEquals(3, Signal.Quit.rawUnix)
        assertEquals(10, Signal.Usr1.rawUnix)
        assertEquals(12, Signal.Usr2.rawUnix)
    }

    @Test
    fun `Other passes its number through`() {
        assertEquals(64, Signal.Other(64).rawUnix)
    }

    @Test
    fun `deliversAsKill identifies SIGKILL`() {
        assertTrue(Signal.Kill.deliversAsKill)
        assertTrue(Signal.Other(9).deliversAsKill, "Other(SIGKILL) also delivers as the hard kill")
        assertFalse(Signal.Term.deliversAsKill)
    }

    @Test
    fun `equality follows value semantics`() {
        assertEquals(Signal.Other(1), Signal.Other(1))
        assertNotEquals(Signal.Other(1), Signal.Other(2))
        assertEquals<Signal>(Signal.Term, Signal.Term)
    }
}
