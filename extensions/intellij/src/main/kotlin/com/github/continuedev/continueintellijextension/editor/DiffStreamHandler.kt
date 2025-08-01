package com.github.continuedev.continueintellijextension.editor

import com.github.continuedev.continueintellijextension.services.ContinuePluginService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.math.max
import kotlin.math.min


enum class DiffLineType {
    SAME, NEW, OLD
}

class DiffStreamHandler(
    private val project: Project,
    private val editor: Editor,
    private val startLine: Int,
    private val endLine: Int,
    private val onClose: () -> Unit,
    private val onFinish: () -> Unit,
    private val streamId: String?,
    private val toolCallId: String?
) {
    private data class CurLineState(
        var index: Int, var highlighter: RangeHighlighter? = null, var diffBlock: VerticalDiffBlock? = null
    )

    private var editorUtils = EditorUtils(editor)
    private var curLine = CurLineState(startLine)
    private var isRunning: Boolean = false
    private var hasAcceptedOrRejectedBlock: Boolean = false

    private val unfinishedHighlighters: MutableList<RangeHighlighter> = mutableListOf()
    private val diffBlocks: MutableList<VerticalDiffBlock> = mutableListOf()

    private val curLineKey = editorUtils.createTextAttributesKey("CONTINUE_DIFF_CURRENT_LINE", 0x40888888)
    private val unfinishedKey = editorUtils.createTextAttributesKey("CONTINUE_DIFF_UNFINISHED_LINE", 0x20888888)

    init {
        initUnfinishedRangeHighlights()
    }

    private fun sendUpdate(status: String) {
        if (this.streamId == null) {
            return
        }
        val virtualFile = getVirtualFile()
        val continuePluginService = ServiceManager.getService(project, ContinuePluginService::class.java)
        continuePluginService.sendToWebview(
            "updateApplyState", mapOf(
                "numDiffs" to this.diffBlocks.size,
                "streamId" to this.streamId,
                "status" to status,
                "fileContent" to "not implemented",
                "toolCallId" to this.toolCallId,
                "filepath" to virtualFile?.url
            )
        )
    }

    fun acceptAll() {
        editor.markupModel.removeAllHighlighters();
        sendUpdate("closed")

        resetState()
    }

    fun rejectAll() {
        // The ideal action here is to undo all changes we made to return the user's edit buffer to the state prior
        // to our changes. However, if the user has accepted or rejected one or more diff blocks, there isn't a simple
        // way to undo our changes without also undoing the diff that the user accepted or rejected.
        if (hasAcceptedOrRejectedBlock) {
            diffBlocks.forEach { it.handleReject() }
        } else {
            undoChanges()
        }
        sendUpdate("closed")

        resetState()
    }

    fun streamDiffLinesToEditor(
        input: String,
        prefix: String,
        highlighted: String,
        suffix: String,
        modelTitle: String,
        includeRulesInSystemMessage: Boolean
    ) {
        isRunning = true
        this.sendUpdate("streaming")

        val continuePluginService = ServiceManager.getService(project, ContinuePluginService::class.java)
        val virtualFile = getVirtualFile()

        continuePluginService.coreMessenger?.request(
            "streamDiffLines",
            createRequestParams(
                input,
                prefix,
                highlighted,
                suffix,
                virtualFile,
                modelTitle,
                includeRulesInSystemMessage
            ),
            null
        ) { response ->
            if (!isRunning) return@request

            val parsed = response as Map<*, *>

            if (response["done"] as? Boolean == true) {
                handleFinishedResponse()
                sendUpdate(if (diffBlocks.isEmpty()) "closed" else "done")
                return@request
            }

            handleDiffLineResponse(parsed)
        }
    }

    private fun initUnfinishedRangeHighlights() {
        for (i in startLine..endLine) {
            val highlighter = editor.markupModel.addLineHighlighter(
                unfinishedKey, min(
                    i, editor.document.lineCount - 1
                ), HighlighterLayer.LAST
            )
            unfinishedHighlighters.add(highlighter)
        }
    }

    private fun handleDiffLine(type: DiffLineType, text: String) {
        try {
            when (type) {
                DiffLineType.SAME -> handleSameLine()
                DiffLineType.NEW -> handleNewLine(text)
                DiffLineType.OLD -> handleOldLine()
            }

            updateProgressHighlighters(type)
        } catch (e: Exception) {
            println(
                "Error handling diff line - " +
                        "Line index: ${curLine.index}, " +
                        "Line type: $type, " +
                        "Line text: $text, " +
                        "Error message: ${e.message}"
            )
        }
    }

    private fun handleDiffBlockAcceptOrReject(diffBlock: VerticalDiffBlock, didAccept: Boolean) {
        hasAcceptedOrRejectedBlock = true

        diffBlocks.remove(diffBlock)

        if (didAccept) {
            updatePositionsOnAccept(diffBlock.startLine)
        } else {
            updatePositionsOnReject(diffBlock.startLine, diffBlock.addedLines.size, diffBlock.deletedLines.size)
        }

        if (diffBlocks.isEmpty()) {
            sendUpdate("closed")
            onClose()
        } else {
            sendUpdate("done")
        }
    }


    private fun createDiffBlock(): VerticalDiffBlock {
        val diffBlock = VerticalDiffBlock(
            editor, project, curLine.index, ::handleDiffBlockAcceptOrReject
        )

        diffBlocks.add(diffBlock)
        sendUpdate("streaming")

        return diffBlock
    }

    private fun handleSameLine() {
        if (curLine.diffBlock != null) {
            curLine.diffBlock!!.onLastDiffLine()
        }

        curLine.diffBlock = null

        curLine.index++
    }

    private fun handleNewLine(text: String) {
        if (curLine.diffBlock == null) {
            curLine.diffBlock = createDiffBlock()
        }

        curLine.diffBlock!!.addNewLine(text, curLine.index)

        curLine.index++
    }

    private fun handleOldLine() {
        if (curLine.diffBlock == null) {
            curLine.diffBlock = createDiffBlock()
        }

        curLine.diffBlock!!.deleteLineAt(curLine.index)
    }

    private fun updateProgressHighlighters(type: DiffLineType) {
        // Update the highlighter to show the current line
        curLine.highlighter?.let { editor.markupModel.removeHighlighter(it) }
        curLine.highlighter = editor.markupModel.addLineHighlighter(
            curLineKey, min(curLine.index, max(0, editor.document.lineCount - 1)), HighlighterLayer.LAST
        )

        // Remove the unfinished lines highlighter
        if (type != DiffLineType.OLD && unfinishedHighlighters.isNotEmpty()) {
            editor.markupModel.removeHighlighter(unfinishedHighlighters.removeAt(0))
        }
    }

    private fun updatePositionsOnAccept(startLine: Int) {
        updatePositions(startLine, 0)
    }

    private fun updatePositionsOnReject(startLine: Int, numAdditions: Int, numDeletions: Int) {
        val offset = -numAdditions + numDeletions
        updatePositions(startLine, offset)
    }

    private fun updatePositions(startLine: Int, offset: Int) {
        diffBlocks.forEach { block ->
            if (block.startLine > startLine) {
                block.updatePosition(block.startLine + offset)
            }
        }
    }

    private fun resetState() {
        // Clear the editor of highlighting/inlays
        editor.markupModel.removeAllHighlighters()
        diffBlocks.forEach { it.clearEditorUI() }

        // Clear state vars
        diffBlocks.clear()
        curLine = CurLineState(startLine)
        isRunning = false

        // Close the Edit input
        onClose()
    }


    private fun undoChanges() {
        WriteCommandAction.runWriteCommandAction(project) {
            val undoManager = UndoManager.getInstance(project)
            val virtualFile = getVirtualFile() ?: return@runWriteCommandAction
            val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(virtualFile) as TextEditor

            if (undoManager.isUndoAvailable(fileEditor)) {
                val numChanges = diffBlocks.sumOf { it.deletedLines.size + it.addedLines.size }

                repeat(numChanges) {
                    undoManager.undo(fileEditor)
                }
            }
            sendUpdate("done")
        }
    }

    private fun getVirtualFile(): VirtualFile? {
        return FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    }

    private fun createRequestParams(
        input: String,
        prefix: String,
        highlighted: String,
        suffix: String,
        virtualFile: VirtualFile?,
        modelTitle: String,
        includeRulesInSystemMessage: Boolean
    ): Map<String, Any?> {
        return mapOf(
            "input" to input,
            "prefix" to prefix,
            "highlighted" to highlighted,
            "suffix" to suffix,
            "language" to virtualFile?.fileType?.name,
            "modelTitle" to modelTitle,
            "includeRulesInSystemMessage" to includeRulesInSystemMessage,
        )
    }

    private fun handleFinishedResponse() {
        ApplicationManager.getApplication().invokeLater {
            // Since we only call onLastDiffLine() when we reach a "same" line, we need to handle the case where
            // the last line in the diff stream is in the middle of a diff block.
            curLine.diffBlock?.onLastDiffLine()

            onFinish()
            cleanupProgressHighlighters()
        }
    }

    private fun cleanupProgressHighlighters() {
        curLine.highlighter?.let { editor.markupModel.removeHighlighter(it) }
        unfinishedHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
    }


    private fun handleDiffLineResponse(parsed: Map<*, *>) {
        val data = parsed["content"] as Map<*, *>
        val diffLineType = getDiffLineType(data["type"] as String)
        val lineText = data["line"] as String

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                handleDiffLine(diffLineType, lineText)
            }
        }
    }

    private fun getDiffLineType(type: String): DiffLineType {
        return when (type) {
            "same" -> DiffLineType.SAME
            "new" -> DiffLineType.NEW
            "old" -> DiffLineType.OLD
            else -> throw Exception("Unknown diff line type: $type")
        }
    }
}