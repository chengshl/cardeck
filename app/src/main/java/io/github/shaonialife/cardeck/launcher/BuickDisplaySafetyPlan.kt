package io.github.shaonialife.cardeck.launcher

/**
 * Safety policy is injected only for Sony XQ-DQ72/Android15 + Buick B233.
 * Other receivers and devices keep the established four-display path.
 */
internal object BuickDisplaySafetyPlan {
    fun optionalScreensEnabled(safeTwoScreenMode: Boolean) = !safeTwoScreenMode

    fun mayStartWithResidualScreens(safeTwoScreenMode: Boolean, count: Int) =
        !safeTwoScreenMode || count == 0

    fun allowPublicFallback(safeTwoScreenMode: Boolean) = !safeTwoScreenMode
}
