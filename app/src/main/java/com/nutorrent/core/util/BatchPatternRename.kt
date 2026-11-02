package com.nutorrent.core.util

/**
 * Sequential batch renaming algorithm adapted from Motrix (`src/shared/utils/rename.js`).
 *
 * Evaluates pattern tags like `(01+1)` or `(10-1)` in output filenames:
 * - `Episode_(01+1).mp4` with count 3 -> `Episode_01.mp4`, `Episode_02.mp4`, `Episode_03.mp4`
 * - `Photo_(1+2).jpg` with count 3 -> `Photo_1.jpg`, `Photo_3.jpg`, `Photo_5.jpg`
 * - `Track_(10-1).mp3` with count 3 -> `Track_10.mp3`, `Track_09.mp3`, `Track_08.mp3`
 */
object BatchPatternRename {

    private val RULE_REGEX = Regex("""\(([^)]*)\)""")

    data class Rule(
        val init: Int,
        val step: Int,
        val length: Int
    )

    /**
     * Parses a pattern rule string (e.g. "01+1", "1+2", "10-1").
     */
    fun parseRule(ruleStr: String): Rule? {
        val trimmed = ruleStr.trim()
        val isMinus = trimmed.contains('-')
        val operator = if (isMinus) '-' else '+'
        val parts = trimmed.split(operator)

        if (parts.size == 1) {
            val num = parts[0].trim().toIntOrNull() ?: return null
            return Rule(init = num, step = 1, length = parts[0].trim().length)
        }

        val initStr = parts[0].trim()
        val stepStr = parts[1].trim()

        val init = initStr.toIntOrNull() ?: return null
        val stepMagnitude = stepStr.toIntOrNull() ?: 1
        val step = if (isMinus) -stepMagnitude else stepMagnitude

        return Rule(init = init, step = step, length = initStr.length)
    }

    /**
     * Checks if the template string contains a valid batch renaming rule.
     */
    fun hasPatternRule(template: String): Boolean {
        val match = RULE_REGEX.find(template) ?: return false
        return parseRule(match.groupValues[1]) != null
    }

    /**
     * Generates a sequence of [count] file names by replacing the pattern rule tag.
     */
    fun buildOutputNames(template: String, count: Int): List<String> {
        if (count <= 0 || template.isEmpty()) return emptyList()
        if (count == 1) return listOf(template)

        val match = RULE_REGEX.find(template) ?: return List(count) { template }
        val rule = parseRule(match.groupValues[1]) ?: return List(count) { template }

        return (0 until count).map { index ->
            val currentValue = rule.init + (rule.step * index)
            val formatted = currentValue.toString().padStart(rule.length, '0')
            template.replaceRange(match.range, formatted)
        }
    }
}
