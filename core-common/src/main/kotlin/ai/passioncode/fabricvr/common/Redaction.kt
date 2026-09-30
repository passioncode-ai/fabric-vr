package ai.passioncode.fabricvr.common

/**
 * Removes credential-shaped substrings from free text that is about to be written down.
 *
 * Distinct from [Log2.redact], which replaces a whole value with its shape because the caller
 * already knows the value is a secret. Here the caller does *not* know: an exception message is
 * arbitrary text that may or may not carry a key — `"401 for sk-…"` is a real shape for an HTTP
 * client to throw — and replacing all of it would leave a crash report that diagnoses nothing.
 *
 * **The shapes are the same ones `scripts/check-secrets.sh` refuses to let into the repository**,
 * and `RedactionParityTest` reads that script and fails when the two lists drift. Two copies of a
 * security rule are one copy and one lie; a test is what makes them one rule in two places.
 */
object Redaction {

    private val SHAPES: List<Regex> = listOf(
        """sk-or-v1-[A-Za-z0-9]{8,}""",
        """sk-ant-[A-Za-z0-9-]{8,}""",
        """sk-proj-[A-Za-z0-9_-]{20,}""",
        // Left-anchored. Without the boundary this matched inside any word ending in `sk` —
        // `task-<32 hex>`, `Disk-…`, `ggml_backend_task-…` — and silently destroyed the
        // identifiers a crash report exists to carry, which is the value `Log2.redact` was the
        // wrong tool for. In the secret gate a false positive only blocks a commit; here it
        // blinds the diagnosis.
        """(?<![A-Za-z0-9])sk-[A-Za-z0-9]{32,}""",
        """sk_live_[A-Za-z0-9]{20,}""",
        """rk_live_[A-Za-z0-9]{20,}""",
        """AIza[0-9A-Za-z_-]{30,}""",
        """hf_[A-Za-z0-9]{20,}""",
        """gh[po]_[A-Za-z0-9]{36}""",
        """github_pat_[A-Za-z0-9_]{60,}""",
        """AKIA[0-9A-Z]{16}""",
        """xox[baprs]-[A-Za-z0-9-]{10,}""",
        """xapp-[0-9]-[A-Za-z0-9-]{10,}""",
        """glpat-[A-Za-z0-9_-]{20,}""",
        """lin_api_[A-Za-z0-9]{20,}""",
        """SG\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}""",
        """-----BEGIN [A-Z ]*PRIVATE KEY-----""",
    ).map { it.toRegex() }

    /** The shapes, for the parity test. Not for matching — use [scrub]. */
    internal val shapeCount: Int get() = SHAPES.size

    /**
     * @return [text] with every credential-shaped run replaced by its length, so a reader can see
     * that something was removed and how much of it, and nobody can read the value back.
     */
    fun scrub(text: String): String =
        SHAPES.fold(text) { acc, shape ->
            shape.replace(acc) { match -> "<redacted:${match.value.length} chars>" }
        }
}
