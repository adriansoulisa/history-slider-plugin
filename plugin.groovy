import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diff.DiffContent
import com.intellij.openapi.diff.DiffRequest
import com.intellij.openapi.diff.SimpleContent
import com.intellij.openapi.diff.impl.DiffPanelImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePathImpl
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod

import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.text.SimpleDateFormat
import java.util.List

import static intellijeval.PluginUtil.*


registerAction("showHistorySliderAction", "alt shift H", "ToolsMenu") { AnActionEvent event ->
	def (errorMessage, file, revisions) = checkIfCanRunAction(event)
	if (errorMessage != null) return show(errorMessage)

	def editor = currentEditorIn(event.project)
	def psiElement = currentPsiFileIn(event.project)?.findElementAt(editor.caretModel.offset)
	def psiMethod = findParent(psiElement, {it instanceof PsiMethod})
	def findMethodCodeIn = this.&findMethodCodeIn.curry(PsiFileFactory.getInstance(event.project), file, psiMethod)

	def diffPanel = new DiffPanelImpl((Window) null, event.project, (boolean) true, (boolean) true, (int) 0)
	diffPanel.enableToolbar(false)
	diffPanel.requestFocus = false
	def leftContent = new MySimpleContent(findMethodCodeIn(revisions[-2].content), file, revisions[-2])
	def rightContent = new MySimpleContent(findMethodCodeIn(revisions[-1].content), file, revisions[-1])
	diffPanel.diffRequest = new MyDiffRequest(leftContent, rightContent)

	def sliderPanel = new JPanel().with {
		layout = new BorderLayout()
		add(createSliderPanel(revisions, revisions.size() - 2, { VcsFileRevision revision ->
			catchingAll {
				leftContent = new MySimpleContent(findMethodCodeIn(revision.content), file, revision)
				diffPanel.diffRequest = new MyDiffRequest(leftContent, rightContent)
			}
		}), BorderLayout.NORTH)
		add(createSliderPanel(revisions, revisions.size() - 1, { VcsFileRevision revision ->
			catchingAll {
				rightContent = new MySimpleContent(findMethodCodeIn(revision.content), file, revision)
				diffPanel.diffRequest = new MyDiffRequest(leftContent, rightContent)
			}
		}), BorderLayout.SOUTH)
		it
	}

	new JFrame().with {
		add(new JPanel().with {
			layout = new BorderLayout()
			add(diffPanel.component, BorderLayout.CENTER)
			add(sliderPanel, BorderLayout.SOUTH)
			it
		})
		preferredSize = new Dimension(1600, 800)
		pack()
		visible = true
	}
}
show("reloaded")


def <T> T findParent(PsiElement element, Closure<T> matches) {
	if (element == null) null
	else if (matches(element)) element
	else findParent(element.parent, matches)
}

class MyDiffRequest extends DiffRequest {
	private final leftContent
	private final rightContent

	MyDiffRequest(leftContent, rightContent) {
		super(null)
		this.leftContent = leftContent
		this.rightContent = rightContent
	}

	@Override DiffContent[] getContents() { [leftContent, rightContent] }
	@Override String[] getContentTitles() { [revisionToString(leftContent.revision), revisionToString(rightContent.revision)] }
	@Override String getWindowTitle() { "" }

	private def revisionToString(VcsFileRevision revision) {
		"     " +
		"Date: " + (revision == null ? "unknown" : new SimpleDateFormat("dd/MM/yyyy hh:mm").format(revision.revisionDate)) +
		"    " +
		"(author: ${revision.author}; " +
		"revision: ${revision.revisionNumber.asString().take(6)})"
	}
}

class MySimpleContent extends SimpleContent {
	final VcsFileRevision revision

	MySimpleContent(String text, VirtualFile virtualFile, VcsFileRevision revision) {
		super(text, virtualFile.fileType)
		this.revision = revision
	}
}

DiffRequest withKeepingScrollPositionIn(Editor editor, Closure<DiffRequest> closure) {
	// TODO this was a desparate attemp to make vertical scroll stay in the position/line whil diff panel content
	// problems:
	//  - diff "flickers" when changing date (something like scroll to 1st line, scroll to last position)
	//  - scrolling to last position is not stable; need to check algorithm for DiffNavigationContext positioning or fail back to line number

	if (false) {
		def visibleArea = editor.scrollingModel.visibleArea
		// "height / 3" ugly hack was copied from com.intellij.openapi.diff.impl.util.SyncScrollSupport#syncVerticalScroll
		def lineNumber = editor.xyToLogicalPosition(new Point((int) visibleArea.x, (int) visibleArea.y + visibleArea.height / 3)).line
		def lines = editor.document.text.split("\n").toList()
		def fromLine = (lineNumber - 10 < 0) ? 0 : lineNumber - 10
		def prevLines = lines[fromLine..<lineNumber]
		def line = lines[lineNumber]
//	show(line)
//	show(prevLines.join("\n'"))
//	show(line)
	}

	def diffRequest = closure.call()
//	diffRequest.passForDataContext(DiffTool.SCROLL_TO_LINE, new DiffNavigationContext(prevLines, line))
	diffRequest
}

def createSliderPanel(List<VcsFileRevision> revisions, int sliderPosition, Closure sliderCallback) {
	new JPanel().with{
		layout = new BorderLayout()
		def slider = new JSlider(JScrollBar.HORIZONTAL, 0, revisions.size() - 1, sliderPosition)
		add(slider, BorderLayout.CENTER)
		slider.addChangeListener(new ChangeListener() {
			@Override void stateChanged(ChangeEvent event) {
				sliderCallback.call(revisions[slider.value])
			}
		})
		it
	}
}

def findMethodCodeIn(PsiFileFactory psiFileFactory, originalFile, psiMethod, revisionContent) {
	def psiFile = psiFileFactory.createFileFromText(originalFile.name, originalFile.fileType, new String(revisionContent))
	def psiMethods = psiFile.children.findAll{it instanceof PsiClass}.collect { psiClass ->
		psiClass.children.findAll{it instanceof PsiMethod}
	}.flatten()

	def methodText = psiMethods.find{it.name == psiMethod.name}?.text
	methodText != null ? methodText : ""
}

def checkIfCanRunAction(AnActionEvent event) {
	VirtualFile file = currentFileIn(event.project)
	if (file == null) return ["There are no open file editors"]

	AbstractVcs activeVcs = ProjectLevelVcsManager.getInstance(event.project).getVcsFor(file)
	if (activeVcs == null) return ["There is no history for open file '${file.name}'"]

	def historySession = activeVcs.vcsHistoryProvider.createSessionFor(new FilePathImpl(file))
	revisions = historySession.revisionList.sort{ it.revisionDate }
	if (revisions.size() < 2) return ["There is only one revision for '${file.name}'"]

	[null, file, revisions]
}
