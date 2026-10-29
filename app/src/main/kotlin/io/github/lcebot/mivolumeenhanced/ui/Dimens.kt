package io.github.lcebot.mivolumeenhanced.ui

import androidx.compose.ui.unit.dp

/**
 * The spacing vocabulary, named for the rule rather than the value: two gaps that are both 8dp
 * for different reasons get different names, so changing one never moves the other.
 */
object Dimens {
    /** Between the fields of one card in the status sheet. Tighter than the gap to the card's name. */
    val SpacingSeam = 4.dp

    /** Under a section title: closer to its own block than to the block above. */
    val SpacingHeading = 6.dp

    /** Below a sheet heading, before its first card. */
    val SpacingGroup = 8.dp

    /** Above a section title: what separates one whole section from the last. */
    val SpacingSection = 12.dp

    /** A card's padding where the sheet heading sits above it. */
    val CardPadding = 16.dp

    /** The page's side margin, the column every block's edge sits on. */
    val PageGutter = 16.dp

    /** The sheet's, wider: its title and headings sit on it and the cards line up with them. */
    val SheetGutter = 24.dp
}
