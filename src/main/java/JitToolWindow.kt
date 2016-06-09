package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.CaretAdapter
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.BCAnnotationType
import java.awt.CardLayout
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.jvm.isAccessible

class JitToolWindow(private val project: Project) : JPanel(CardLayout()), Disposable {
    private val MESSAGE_CARD = "Message"
    private val EDITOR_CARD = "Editor"

    private val messageLabel = JLabel()
    private val cardLayout = layout as CardLayout
    private val bytecodeDocument = EditorFactory.getInstance().createDocument("")
    private val bytecodeEditor = EditorFactory.getInstance().createEditor(bytecodeDocument, project, PlainTextFileType.INSTANCE, true).apply {
        settings.isLineNumbersShown = false
        settings.isFoldingOutlineShown = false
        settings.isLineMarkerAreaShown = false
    }

    private val modelService = JitWatchModelService.getInstance(project)

    private var bytecodeTextBuilder: BytecodeTextBuilder? = null
    private var activeSourceEditor: Editor? = null
    private var activeSourceFile: PsiFile? = null
    private var lineRangeHighlighter: RangeHighlighter? = null

    private var movingCaretInSource = false

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerAdapter() {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                updateContent(event.newFile, event.newEditor)
            }
        })

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretAdapter() {
            override fun caretPositionChanged(e: CaretEvent) {
                if (e.editor != activeSourceEditor || movingCaretInSource) return
                syncBytecodeToEditor(e.newPosition)
            }
        }, this)

        bytecodeEditor.caretModel.addCaretListener(object : CaretAdapter() {
            override fun caretPositionChanged(e: CaretEvent) {
                syncEditorToBytecode(e.newPosition)
            }
        })

        setupUI()
        val fileEditorManager = FileEditorManager.getInstance(project)
        updateContent(fileEditorManager.selectedFiles.firstOrNull(), fileEditorManager.selectedEditors.firstOrNull(), true)
    }

    private fun setupUI() {
        add(messageLabel, MESSAGE_CARD)
        add(bytecodeEditor.component, EDITOR_CARD)
    }

    private fun updateContent(file: VirtualFile?, fileEditor: FileEditor?, syncCaret: Boolean = false) {
        activeSourceEditor = (fileEditor as? TextEditor)?.editor
            ?: return showMessage("Please open a text editor")

        if (modelService.model == null)
            return showMessage("Please open a HotSpot compilation log")

        activeSourceFile = PsiManager.getInstance(project).findFile(file!!)
            ?: return showMessage("Please select a source file")
        val languageSupport = LanguageSupport.forLanguage(activeSourceFile!!.language)
            ?: return showMessage("Please select a file in a supported language")

        if (languageSupport.getAllClasses(activeSourceFile!!).isEmpty())
            return showMessage("Please select a Java file that contains classes")

        val sourceFile = activeSourceFile!!
        modelService.loadBytecodeAsync(sourceFile) { ->
            if (activeSourceFile != sourceFile) return@loadBytecodeAsync
            showBytecode()
            if (syncCaret) {
                syncBytecodeToEditor(activeSourceEditor!!.caretModel.logicalPosition)
            }
        }
    }

    private fun showMessage(message: String) {
        messageLabel.text = message
        cardLayout.show(this, MESSAGE_CARD)
    }

    private fun showBytecode() {
        cardLayout.show(this, EDITOR_CARD)

        bytecodeTextBuilder = BytecodeTextBuilder()

        val psiFile = activeSourceFile!!
        val classes = LanguageSupport.forElement(psiFile).getAllClasses(psiFile)
        val metaClasses = mutableListOf<MetaClass>()
        for (cls in classes) {
            val metaClass = modelService.getMetaClass(cls) ?: continue
            metaClasses.add(metaClass)
            bytecodeTextBuilder!!.appendClass(metaClass)
        }

        object : WriteCommandAction<Unit>(project) {
            override fun run(result: Result<Unit>) {
                bytecodeDocument.replaceString(0, bytecodeDocument.textLength, bytecodeTextBuilder!!.text)
            }
        }.execute()

        renderBytecodeAnnotations(psiFile)
    }

    private fun renderBytecodeAnnotations(psiFile: PsiFile) {
        val markupModel = DocumentMarkupModel.forDocument(bytecodeDocument, project, true)
        markupModel.removeAllHighlighters()

        modelService.processBytecodeAnnotations(psiFile) { method, member, memberBytecode, instruction, annotationsForBCI ->
            val line = bytecodeTextBuilder!!.findLine(member, instruction.offset) ?: return@processBytecodeAnnotations
            val color = getColorForBytecodeAnnotation(annotationsForBCI.first().type)
            highlightBytecodeLine(line, color,
                    annotationsForBCI.joinToString(separator = "\n") { it.annotation },
                    markupModel)
        }
    }

    private fun getColorForBytecodeAnnotation(type: BCAnnotationType): Color? = when(type) {
        BCAnnotationType.BRANCH -> JBColor.BLUE
        BCAnnotationType.ELIMINATED_ALLOCATION -> JBColor.GRAY
        BCAnnotationType.INLINE_FAIL -> JBColor.RED
        BCAnnotationType.INLINE_SUCCESS -> JBColor.GREEN.darker().darker()
        BCAnnotationType.UNCOMMON_TRAP -> JBColor.MAGENTA
        else -> null
    }

    private fun highlightBytecodeLine(line: Int, color: Color?, tooltip: String, markupModel: MarkupModel) {
        val document = bytecodeEditor.document
        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val textAttributes = TextAttributes(color, null, null, null, 0)
        val highlighter = markupModel.addRangeHighlighter(
                (lineStartOffset + 4).coerceAtMost(lineEndOffset),
                lineEndOffset,
                HighlighterLayer.SYNTAX,
                textAttributes, HighlighterTargetArea.EXACT_RANGE) as RangeHighlighterEx

        val highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(highlighter.startOffset, highlighter.endOffset)
            .description(tooltip)
            .textAttributes(textAttributes)
            .unescapedToolTip(tooltip)
            .createUnconditionally()

        val highlighterProperty = HighlightInfo::class.members.find { it.name == "highlighter" }
                as KMutableProperty1<HighlightInfo, RangeHighlighterEx>
        highlighterProperty.isAccessible = true
        highlighterProperty.set(highlightInfo, highlighter)

        highlighter.errorStripeTooltip = highlightInfo
    }

    private fun syncBytecodeToEditor(caretPosition: LogicalPosition) {
        val caretOffset = activeSourceEditor!!.logicalPositionToOffset(caretPosition)
        val languageSupport = LanguageSupport.forLanguage(activeSourceFile!!.language)
        val methodAtCaret = languageSupport.findMethodAtOffset(activeSourceFile!!, caretOffset) ?: return
        val metaMember = modelService.getMetaMember(methodAtCaret) ?: return
        val lineTableEntry = metaMember.memberBytecode.lineTable.getEntryForSourceLine(caretPosition.line + 1) ?: return
        val bytecodeLine = bytecodeTextBuilder?.findLine(metaMember, lineTableEntry.bytecodeOffset) ?: return
        bytecodeEditor.moveCaretToLine(bytecodeLine)
    }

    private fun syncEditorToBytecode(caretPosition: LogicalPosition) {
        val (member, instruction) = bytecodeTextBuilder?.findInstruction(caretPosition.line) ?: return
        val sourceLine = member.memberBytecode.lineTable.findSourceLineForBytecodeOffset(instruction?.offset ?: 0)
        if (sourceLine != -1) {
            movingCaretInSource = true
            try {
                activeSourceEditor?.moveCaretToLine(sourceLine - 1)
            }
            finally {
                movingCaretInSource = false
            }
        }

        if (lineRangeHighlighter != null) {
            bytecodeEditor.markupModel.removeHighlighter(lineRangeHighlighter!!)
        }

        val instructionsForLine = member.memberBytecode.findInstructionsForSourceLine(sourceLine)
        if (!instructionsForLine.isEmpty()) {
            val startLine = bytecodeTextBuilder!!.findLine(member, instructionsForLine.first().offset)
            val endLine = bytecodeTextBuilder!!.findLine(member, instructionsForLine.last().offset)
            if (startLine != null && endLine != null) {
                val startOffset = bytecodeDocument.getLineStartOffset(startLine)
                val endOffset = bytecodeDocument.getLineStartOffset(endLine)

                val color = EditorColorsManager.getInstance().globalScheme.getColor(EditorColors.CARET_ROW_COLOR)!!
                lineRangeHighlighter = bytecodeEditor.markupModel.addRangeHighlighter(startOffset, endOffset, HighlighterLayer.CARET_ROW - 1,
                        TextAttributes(null, color.darker(), null, null, 0), HighlighterTargetArea.LINES_IN_RANGE)
            }
        }
    }

    private fun Editor.moveCaretToLine(line: Int) {
        caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    fun navigateToMember(member: PsiElement) {
        val metaMember = modelService.getMetaMember(member) ?: return
        val bytecodeLine = bytecodeTextBuilder?.findLine(metaMember) ?: return
        bytecodeEditor.moveCaretToLine(bytecodeLine)
    }

    override fun dispose() {
    }

    companion object {
        fun getInstance(project: Project): JitToolWindow? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("JitWatch") ?: return null
            return toolWindow.contentManager.getContent(0)?.component as? JitToolWindow
        }
    }
}

class JitToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
                ContentFactory.SERVICE.getInstance().createContent(JitToolWindow(project), "", false)
        )
    }
}
