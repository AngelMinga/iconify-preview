package com.angelminga.iconifypreview

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Flags icon names that don't exist in their Iconify set, such as `"mdi:hoem"`: `@iconify/react` silently renders
 * nothing for them. Offers the closest names as quick fixes. Registered without a language, so it checks every file.
 *
 * Never downloads on the inspection thread: until the set's names are cached it reports nothing, and the editor
 * re-runs inspections once they arrive (see [EditorIconPreview]).
 */
internal class UnknownIconInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!IconifyPreviewSettings.getInstance().state.enabled) return null
        // Files with several languages (HTML + JS…) are checked once, on the main one.
        if (file.language != file.viewProvider.baseLanguage) return null
        val text = file.viewProvider.contents
        if (text.length > MAX_FILE_LENGTH) return null

        val client = IconifyClient.getInstance()
        val problems = ArrayList<ProblemDescriptor>()
        for (match in IconNameScanner.find(text)) {
            ProgressManager.checkCanceled()
            if (client.status(match) != IconStatus.MISSING) continue
            val fixes = client.iconSet(match.prefix)
                ?.let { IconSuggestions.similar(match.name, it.visible) }
                .orEmpty()
                .map { ReplaceIconFix(match.id, "${match.prefix}:$it") }
            problems += manager.createProblemDescriptor(
                file,
                TextRange(match.offset + 1, match.offset + 1 + match.id.length),
                IconifyPreviewBundle.message("inspection.unknown.icon", match.id),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                *fixes.toTypedArray(),
            )
        }
        return problems.toTypedArray()
    }

    private companion object {
        const val MAX_FILE_LENGTH = 1_000_000
    }
}

internal class ReplaceIconFix(private val oldId: String, private val newId: String) : LocalQuickFix {

    override fun getFamilyName(): String = IconifyPreviewBundle.message("inspection.fix.family")

    override fun getName(): String = IconifyPreviewBundle.message("inspection.fix.replace", newId)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val range = descriptor.textRangeInElement.shiftRight(element.textRange.startOffset)
        // The text may have changed since the file was inspected.
        if (range.endOffset > document.textLength || document.charsSequence.subSequence(range.startOffset, range.endOffset).toString() != oldId) return
        document.replaceString(range.startOffset, range.endOffset, newId)
    }

    // The fix edits the document directly; the name already says what it does.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
}
