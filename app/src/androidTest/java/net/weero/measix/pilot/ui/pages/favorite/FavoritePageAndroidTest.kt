package net.weero.measix.pilot.ui.pages.favorite



import androidx.activity.ComponentActivity

import androidx.compose.material3.MaterialTheme

import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.ui.test.*

import androidx.compose.ui.test.junit4.createAndroidComposeRule

import androidx.test.ext.junit.runners.AndroidJUnit4

import io.mockk.coEvery

import io.mockk.coVerify

import io.mockk.every

import io.mockk.mockk

import kotlinx.coroutines.flow.MutableStateFlow

import net.weero.measix.pilot.R

import net.weero.measix.pilot.service.FavoriteService

import net.weero.measix.pilot.service.NodeFavoriteItem

import net.weero.measix.pilot.ui.context.LocalNavController

import net.weero.measix.pilot.ui.context.Navigator

import org.junit.Assert.assertEquals

import org.junit.Assert.assertSame

import org.junit.Rule

import org.junit.Test

import org.junit.runner.RunWith

import java.io.IOException

import kotlin.uuid.Uuid



@RunWith(AndroidJUnit4::class)

class FavoritePageAndroidTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val first = item("first")

    private val second = item("second")

    private val favorites = MutableStateFlow(listOf(first, second))

    private val vm = mockk<FavoriteVM>()

    private val tokens = mapOf(first to mockk<FavoriteService.RestoreToken>(), second to mockk<FavoriteService.RestoreToken>())



    private fun show() {

        every { vm.nodeFavorites } returns favorites

        coEvery { vm.removeForUndo(any()) } coAnswers {

            val item = firstArg<NodeFavoriteItem>()

            favorites.value = favorites.value.filterNot { it.id == item.id }

            tokens.getValue(item)

        }

        coEvery { vm.restoreFavorite(any()) } coAnswers {

            val item = tokens.entries.single { it.value === firstArg<FavoriteService.RestoreToken>() }.key

            favorites.value += item

        }

        compose.setContent {

            MaterialTheme {

                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {

                    FavoritePage(vm)

                }

            }

        }

    }



    @Test fun swipeRemovesOnceAndImmediateUndoRestoresSameIdToSettledCard() {

        show()

        swipe(first)

        undo()

        compose.onNodeWithTag("favorite-first").assertIsDisplayed()

        compose.runOnIdle { assertEquals(2, favorites.value.size) }

        coVerify(exactly = 1) { vm.removeForUndo(first) }

        swipe(first)

        undo()

        coVerify(exactly = 2) { vm.removeForUndo(first) }

    }



    @Test fun consecutiveRemovalsKeepIndependentUndoTokens() {

        show()

        swipe(first)

        swipe(second)

        undo()

        undo()

        compose.runOnIdle { assertEquals(setOf("first", "second"), favorites.value.map { it.id }.toSet()) }

        coVerify(exactly = 1) { vm.removeForUndo(first) }

        coVerify(exactly = 1) { vm.removeForUndo(second) }

        coVerify(exactly = 1) { vm.restoreFavorite(tokens.getValue(first)) }

        coVerify(exactly = 1) { vm.restoreFavorite(tokens.getValue(second)) }

    }



    @Test fun failedRemovalShowsOriginalDiagnosticAndCanSwipeAgain() {

        show()

        var attempts = 0

        coEvery { vm.removeForUndo(first) } coAnswers {

            attempts++

            if (attempts == 1) throw IOException("favorite disk unavailable", IllegalStateException("store cause"))

            favorites.value = favorites.value.filterNot { it === first }

            tokens.getValue(first)

        }

        swipe(first)

        compose.onNodeWithText("IOException", substring = true).assertIsDisplayed()

        compose.onNodeWithText("favorite disk unavailable", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("favorite-first").assertIsDisplayed()

        swipe(first)

        compose.waitUntil { favorites.value.none { it === first } }

        compose.runOnIdle { assertEquals(2, attempts) }

    }



    @Test fun failedUndoRetriesTheOriginalTokenAndKeepsCauseVisible() {

        show()

        var attempts = 0

        val token = tokens.getValue(first)

        coEvery { vm.restoreFavorite(any()) } coAnswers {

            assertSame(token, firstArg<FavoriteService.RestoreToken>())

            attempts++

            if (attempts == 1) throw IOException("restore unavailable", IllegalStateException("disk cause"))

            favorites.value += first

        }

        swipe(first)

        undo()

        compose.onNodeWithText("disk cause", substring = true).assertIsDisplayed()

        compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).performClick()

        compose.onNodeWithTag("favorite-first").assertIsDisplayed()

        compose.runOnIdle { assertEquals(2, attempts) }

    }



    @Test fun oldRealmUndoRejectionIsVisibleAndDoesNotRestoreAnItem() {

        show()

        coEvery { vm.restoreFavorite(tokens.getValue(first)) } throws IllegalStateException("realm_selection_revoked")

        swipe(first)

        undo()

        compose.onNodeWithText("realm_selection_revoked", substring = true).assertIsDisplayed()

        compose.runOnIdle { assertEquals(listOf(second), favorites.value) }

        coVerify(exactly = 1) { vm.restoreFavorite(tokens.getValue(first)) }

    }



    private fun swipe(item: NodeFavoriteItem) {

        compose.onNodeWithTag("favorite-${item.id}").performTouchInput { swipeLeft() }

        compose.waitForIdle()

    }



    private fun undo() {

        compose.onNodeWithText(compose.activity.getString(R.string.history_page_undo)).performClick()

        compose.waitForIdle()

    }



    private fun item(id: String) = mockk<NodeFavoriteItem>().also { item ->

        every { item.id } returns id

        every { item.conversationTitle } returns "Favorite $id"

        every { item.preview } returns "Preview $id"

        every { item.createdAt } returns 1L

        every { item.nodeId } returns Uuid.random()

    }

}
