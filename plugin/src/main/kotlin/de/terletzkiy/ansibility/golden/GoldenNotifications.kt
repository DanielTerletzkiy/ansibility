package de.terletzkiy.ansibility.golden

/** The notifications of the golden write paths (plan amendment R24): Push's and Align's results and notices. */
object GoldenNotifications {
    /**
     * The group of Push's and Align's notifications: the sticky "Ansibility Golden" group of `ansibility-golden.xml`,
     * so a result and its Undo stay on screen until dismissed.
     */
    const val GROUP_ID: String = "Ansibility Golden"

    /** Short notices (nothing to align, nothing to push, a refusal): the general "Ansibility" balloon group. */
    const val NOTICE_GROUP_ID: String = "Ansibility"
}
