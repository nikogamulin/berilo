package app.berilo.reader.translate.epub

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the hand-rolled version-5 UUID to CPython's `uuid.uuid5`.
 *
 * `java.util.UUID` has no v5 factory, so [uuid5] is written by hand; the two ways it can be
 * silently wrong (namespace bytes taken from the string instead of the 16 octets, version or
 * variant bits left unset) both still yield something that prints like a UUID.
 */
class Uuid5Test {

    @Test
    fun `every python uuid5 vector reproduces`() {
        val cases = AssembleVectors.uuid5Cases
        // A vector file that silently emptied would make this class pass while testing nothing.
        assert(cases.size >= 6) { "expected at least six uuid5 probes, got ${cases.size}" }
        cases.forEach { case ->
            assertEquals(
                "uuid5(${case.namespace}, ${case.name})",
                case.uuid,
                uuid5(UUID.fromString(case.namespace), case.name).toString(),
            )
        }
    }

    @Test
    fun `the published RFC 4122 DNS vector reproduces`() {
        // Independent of the generator: this value is published, so it cross-checks the whole
        // chain rather than just agreeing with whatever Python happened to emit.
        assertEquals(
            "886313e1-3b8a-5372-9b90-0c9aee199e5d",
            uuid5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org").toString(),
        )
    }

    @Test
    fun `the version nibble is 5 and the variant is RFC 4122`() {
        AssembleVectors.uuid5Cases.forEach { case ->
            val derived = uuid5(UUID.fromString(case.namespace), case.name)
            assertEquals("version of ${case.name}", 5, derived.version())
            assertEquals("variant of ${case.name}", 2, derived.variant())
        }
    }

    @Test
    fun `the namespace is hashed as bytes, not as its string form`() {
        // The classic port bug: SHA-1 over "6ba7b810-9dad-..." instead of over the 16 octets.
        // It differs from the correct answer, which is the whole point of asserting it.
        val namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        val fromStringBytes =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((namespace.toString() + "python.org").toByteArray(Charsets.UTF_8))
                .copyOf(16)
        assertNotEquals(
            uuid5(namespace, "python.org").toString(),
            fromStringBytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `non-ASCII names are hashed as UTF-8`() {
        // Latin-1 or UTF-16 encoding of the same name gives a different digest; the vector
        // carries šumniki and an astral dragon precisely so a wrong charset fails here.
        val nonAscii = AssembleVectors.uuid5Cases.filter { it.name.any { c -> c.code > 127 } }
        assert(nonAscii.size >= 2) { "expected non-ASCII uuid5 probes, got ${nonAscii.size}" }
        nonAscii.forEach { case ->
            assertEquals(case.uuid, uuid5(UUID.fromString(case.namespace), case.name).toString())
        }
    }
}
