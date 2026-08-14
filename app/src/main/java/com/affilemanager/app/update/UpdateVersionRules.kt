package com.affilemanager.app.update

object UpdateVersionRules {
    private val stableVersion = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    fun normalized(tagOrVersion: String): String {
        val clean = tagOrVersion.substringBefore('-').trim()
        val match = stableVersion.matchEntire(clean)
            ?: throw IllegalArgumentException("Leidimo versija turi būti vMAJOR.MINOR.PATCH formato")
        return match.groupValues.drop(1).joinToString(".") { it.toLong().toString() }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = parts(candidate)
        val currentParts = parts(current)
        return candidateParts.zip(currentParts)
            .firstOrNull { (left, right) -> left != right }
            ?.let { (left, right) -> left > right }
            ?: false
    }

    private fun parts(value: String): List<Long> = normalized(value).split('.').map(String::toLong)
}
