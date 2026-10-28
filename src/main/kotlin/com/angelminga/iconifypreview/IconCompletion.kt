package com.angelminga.iconifypreview

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import com.intellij.util.ui.EmptyIcon
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The object behind every lookup item this plugin adds, so [IconCharFilter] can recognize them. */
internal data class IconLookupObject(val id: String)

/**
 * Icon name completion inside quoted strings, in any language:
 * - `"mdi:arr|`: every icon of the `mdi` set whose name contains `arr`, with its picture.
 * - `icon="wallet|`: icon sets starting with the word, plus Iconify search results (only for icon properties).
 * - `"md|`: icon set prefixes starting with the word.
 */
internal class IconCompletionContributor : CompletionContributor() {

    override fun beforeCompletion(context: CompletionInitializationContext) {
        val text = context.editor.document.charsSequence
        if (IconTyping.typedAt(text, context.startOffset) == null) return
        // Replace the rest of the name too when completing in the middle of it.
        context.offsetMap.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, IconTyping.nameEnd(text, context.startOffset))
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!IconifyPreviewSettings.getInstance().state.enabled) return
        val text = parameters.editor.document.charsSequence
        val typed = IconTyping.typedAt(text, parameters.offset) ?: return
        val client = IconifyClient.getInstance()
        val prefixes = await(client.prefixesFuture()) ?: return
        val color = parameters.editor.colorsScheme.defaultForeground
        val icons = result.withPrefixMatcher(IconPrefixMatcher(typed.text))
        // Results depend on the typed text (search, set), so recompute on every keystroke instead of only filtering.
        icons.restartCompletionOnAnyPrefixChange()

        val prefix = typed.prefix
        if (prefix != null) {
            if (prefix !in prefixes) return
            val set = await(client.iconSetFuture(prefix)) ?: return
            val partial = typed.partialName
            for (name in set.visible) {
                ProgressManager.checkCanceled()
                val priority = when {
                    name == partial -> 3.0
                    name.startsWith(partial) -> 2.0
                    else -> 0.0
                }
                icons.addElement(iconElement(IconMatch(prefix, name, 0), prefixes[prefix], color, priority))
            }
            // Inside "set:…" nothing else makes sense; hide plain word completion.
            icons.stopHere()
            return
        }

        for ((setPrefix, title) in prefixes) {
            if (setPrefix.startsWith(typed.text)) icons.addElement(prefixElement(setPrefix, title))
        }
        if (typed.text.length >= MIN_SEARCH_LENGTH && IconTyping.isIconContext(text, typed.quoteOffset)) {
            for (id in await(client.search(typed.text)).orEmpty()) {
                val match = IconMatch(id.substringBefore(':'), id.substringAfter(':'), 0)
                val priority = if (match.name.startsWith(typed.text)) 1.0 else 0.0
                icons.addElement(iconElement(match, prefixes[match.prefix], color, priority))
            }
        }
    }

    private fun iconElement(match: IconMatch, setTitle: String?, color: Color, priority: Double): LookupElement {
        val element = LookupElementBuilder.create(IconLookupObject(match.id), match.id)
            .withIcon(EmptyIcon.ICON_16)
            .withTypeText(setTitle, true)
            // Pictures download while the list is shown; only rows that get displayed are fetched.
            .withExpensiveRenderer(object : LookupElementRenderer<LookupElement>() {
                override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
                    presentation.itemText = match.id
                    presentation.setTypeText(setTitle, null)
                    presentation.isTypeGrayed = true
                    presentation.icon = IconifyClient.getInstance().iconNow(match, color) ?: EmptyIcon.ICON_16
                }
            })
        return PrioritizedLookupElement.withPriority(element, priority)
    }

    private fun prefixElement(prefix: String, title: String): LookupElement {
        val element = LookupElementBuilder.create(IconLookupObject("$prefix:"), "$prefix:")
            .withTypeText(title, true)
            .withInsertHandler { context, _ ->
                // Go straight on to the icon names of the chosen set.
                AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
            }
        return PrioritizedLookupElement.withPriority(element, 4.0)
    }

    /** Waits for a download while letting the user keep typing (completion cancels and restarts). */
    private fun <T> await(future: CompletableFuture<T>): T? {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (true) {
            ProgressManager.checkCanceled()
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (System.currentTimeMillis() > deadline) return null
            } catch (_: ExecutionException) {
                return null
            }
        }
    }

    private class IconPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
        override fun prefixMatches(name: String): Boolean = IconTyping.matches(prefix, name)
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = IconPrefixMatcher(prefix)
    }

    private companion object {
        const val MIN_SEARCH_LENGTH = 2
        const val AWAIT_TIMEOUT_MS = 8_000L
        const val POLL_MS = 20L
    }
}

/** Opens completion when `:` is typed right after an icon set prefix, e.g. `"mdi:`. */
internal class IconTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':' || !IconifyPreviewSettings.getInstance().state.enabled) return Result.CONTINUE
        // Called before the colon is inserted: the text before the caret is the prefix alone.
        val typed = IconTyping.typedAt(editor.document.charsSequence, editor.caretModel.offset) ?: return Result.CONTINUE
        val prefixes = IconifyClient.getInstance().prefixes() ?: return Result.CONTINUE
        if (typed.prefix != null || typed.text !in prefixes) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

/** Many languages skip completion popups inside strings; icon names are the exception. */
internal class IconCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val prefix = IconTyping.typedAt(editor.document.charsSequence, offset)?.prefix ?: return ThreeState.UNSURE
        val prefixes = IconifyClient.getInstance().prefixes() ?: return ThreeState.UNSURE
        return if (prefix in prefixes) ThreeState.NO else ThreeState.UNSURE
    }
}

/** Keeps the list open while typing `-` and `:`, which other languages treat as the end of a word. */
internal class IconCharFilter : CharFilter() {
    override fun acceptChar(c: Char, prefixLength: Int, lookup: Lookup): Result? {
        if (lookup.currentItem?.`object` !is IconLookupObject) return null
        return if (IconTyping.isIconChar(c)) Result.ADD_TO_PREFIX else null
    }
}
