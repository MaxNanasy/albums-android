package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.Matchers.startsWith

object Ui {
    object Auth {
        fun status(): ViewInteraction = onView(withId(R.id.authStatus))
        fun connectButton(): ViewInteraction = onView(withId(R.id.connectButton))
        fun disconnectButton(): ViewInteraction = onView(withId(R.id.disconnectButton))
    }

    object Playback {
        fun status(): ViewInteraction = onView(withId(R.id.playbackStatus))
        fun startButton(): ViewInteraction = onView(withId(R.id.startButton))
        fun reattachButton(): ViewInteraction = onView(withId(R.id.reattachButton))
        fun nextButton(): ViewInteraction = onView(withId(R.id.skipButton))
        fun stopButton(): ViewInteraction = onView(withId(R.id.stopButton))

        fun queueRow(text: String): ViewInteraction {
            return onView(
                allOf(
                    withId(R.id.queueTitle),
                    withText(text),
                    isDescendantOfA(withId(R.id.queueRecycler)),
                ),
            )
        }
    }

    object SavedItems {
        fun uriInput(): ViewInteraction = onView(withId(R.id.itemUriInput))
        fun addButton(): ViewInteraction = onView(withId(R.id.addButton))
        fun importAlbumsButton(): ViewInteraction = onView(withId(R.id.importPlaylistButton))

        fun row(text: String): ViewInteraction {
            return onView(
                allOf(
                    withId(R.id.title),
                    withText(text),
                    isDescendantOfA(withId(R.id.itemRecycler)),
                ),
            )
        }

        fun removeButton(itemText: String): ViewInteraction {
            return onView(
                allOf(
                    withId(R.id.removeButton),
                    withText("Remove"),
                    isDescendantOfA(withId(R.id.itemRecycler)),
                    hasSiblingTitle(itemText),
                ),
            )
        }
    }

    object RemovedItems {
        fun section(): ViewInteraction = onView(withId(R.id.removedItemsSection))
        fun count(): ViewInteraction = onView(withId(R.id.removedItemsCount))
        fun purgeButton(): ViewInteraction = onView(withId(R.id.purgeRemovedItemsButton))
        fun purgeDialogMessage(): ViewInteraction = onView(withText(startsWith("Permanently remove "))).inRoot(isDialog())
        fun cancelPurgeButton(): ViewInteraction = onView(withText("Cancel")).inRoot(isDialog())
        fun confirmPurgeButton(): ViewInteraction = onView(withText("Purge")).inRoot(isDialog())

        fun row(text: String): ViewInteraction {
            return onView(
                allOf(
                    withId(R.id.title),
                    withText(text),
                    isDescendantOfA(withId(R.id.removedItemsRecycler)),
                ),
            )
        }

        fun restoreButton(itemText: String): ViewInteraction {
            return onView(
                allOf(
                    withId(R.id.removeButton),
                    withText("Restore"),
                    isDescendantOfA(withId(R.id.removedItemsRecycler)),
                    hasSiblingTitle(itemText),
                ),
            )
        }
    }

    object Toasts {
        fun instance(text: String): ViewInteraction = onView(withText(text))
        fun undoButton(): ViewInteraction = onView(withText("Undo"))
    }

    object Storage {
        fun json(): ViewInteraction = onView(withId(R.id.storageJsonInput))
        fun exportDataButton(): ViewInteraction = onView(withId(R.id.exportStorageButton))
        fun importDataButton(): ViewInteraction = onView(withId(R.id.importStorageButton))
    }
}

private fun hasSiblingTitle(text: String) = hasSibling(allOf(withId(R.id.title), withText(text)))
