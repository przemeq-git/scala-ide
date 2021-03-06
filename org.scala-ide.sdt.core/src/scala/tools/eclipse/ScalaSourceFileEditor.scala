
/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import java.util.ResourceBundle
import scala.Option.option2Iterable
import scala.tools.eclipse.javaelements.ScalaCompilationUnit
import scala.tools.eclipse.javaelements.ScalaSourceFile
import scala.tools.eclipse.markoccurrences.Occurrences
import scala.tools.eclipse.markoccurrences.ScalaOccurrencesFinder
import scala.tools.eclipse.semantichighlighting.SemanticHighlightingAnnotations
import scala.tools.eclipse.semicolon.ShowInferredSemicolonsAction
import scala.tools.eclipse.semicolon.ShowInferredSemicolonsBundle
import scala.tools.eclipse.util.RichAnnotationModel.annotationModel2RichAnnotationModel
import scala.tools.eclipse.util.SWTUtils.fnToPropertyChangeListener
import scala.tools.eclipse.util.SWTUtils
import scala.tools.eclipse.util.Utils
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.IAdaptable
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.internal.ui.javaeditor.selectionactions.SelectionHistory
import org.eclipse.jdt.internal.ui.javaeditor.selectionactions.StructureSelectHistoryAction
import org.eclipse.jdt.internal.ui.javaeditor.selectionactions.StructureSelectionAction
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor
import org.eclipse.jdt.internal.ui.javaeditor.JavaSourceViewer
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration
import org.eclipse.jface.action.Action
import org.eclipse.jface.action.IContributionItem
import org.eclipse.jface.action.MenuManager
import org.eclipse.jface.action.Separator
import org.eclipse.jface.text.information.InformationPresenter
import org.eclipse.jface.text.source.Annotation
import org.eclipse.jface.text.source.AnnotationPainter
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.jface.text.source.ISourceViewer
import org.eclipse.jface.text.source.SourceViewerConfiguration
import org.eclipse.jface.text.AbstractReusableInformationControlCreator
import org.eclipse.jface.text.DefaultInformationControl
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextOperationTarget
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.Position
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.jface.util.PropertyChangeEvent
import org.eclipse.jface.viewers.ISelection
import org.eclipse.swt.widgets.Shell
import org.eclipse.ui.texteditor.IAbstractTextEditorHelpContextIds
import org.eclipse.ui.texteditor.ITextEditorActionConstants
import org.eclipse.ui.texteditor.IUpdate
import org.eclipse.ui.texteditor.IWorkbenchActionDefinitionIds
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport
import org.eclipse.ui.texteditor.TextOperationAction
import org.eclipse.ui.ISelectionListener
import org.eclipse.ui.IWorkbenchPart
import ScalaSourceFileEditor.OCCURRENCE_ANNOTATION
import ScalaSourceFileEditor.SCALA_EDITOR_SCOPE
import ScalaSourceFileEditor.bundleForConstructedKeys
import org.eclipse.swt.events.VerifyEvent
import org.eclipse.jface.text.ITextViewerExtension
import scala.tools.eclipse.ui.SurroundSelectionStrategy
import org.eclipse.jface.text.IDocumentExtension4


class ScalaSourceFileEditor extends CompilationUnitEditor with ScalaEditor {

  import ScalaSourceFileEditor._

  setPartName("Scala Editor")

  def scalaPrefStore = ScalaPlugin.prefStore
  def javaPrefStore = getPreferenceStore

  override protected def createActions() {
    super.createActions()

    val cutAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Cut.", this, ITextOperationTarget.CUT) //$NON-NLS-1$
    cutAction.setHelpContextId(IAbstractTextEditorHelpContextIds.CUT_ACTION)
    cutAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.CUT)
    setAction(ITextEditorActionConstants.CUT, cutAction)

    val copyAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Copy.", this, ITextOperationTarget.COPY, true) //$NON-NLS-1$
    copyAction.setHelpContextId(IAbstractTextEditorHelpContextIds.COPY_ACTION)
    copyAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.COPY)
    setAction(ITextEditorActionConstants.COPY, copyAction)

    val pasteAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Paste.", this, ITextOperationTarget.PASTE) //$NON-NLS-1$
    pasteAction.setHelpContextId(IAbstractTextEditorHelpContextIds.PASTE_ACTION)
    pasteAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.PASTE)
    setAction(ITextEditorActionConstants.PASTE, pasteAction)

    val selectionHistory = new SelectionHistory(this)

    val historyAction = new StructureSelectHistoryAction(this, selectionHistory)
    historyAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_LAST)
    setAction(StructureSelectionAction.HISTORY, historyAction)
    selectionHistory.setHistoryAction(historyAction)

    val selectEnclosingAction = new ScalaStructureSelectEnclosingAction(this, selectionHistory)
    selectEnclosingAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_ENCLOSING)
    setAction(StructureSelectionAction.ENCLOSING, selectEnclosingAction)

    val showInferredSemicolons = new ShowInferredSemicolonsAction(ShowInferredSemicolonsBundle.PREFIX, this, ScalaPlugin.prefStore)
    showInferredSemicolons.setActionDefinitionId(ShowInferredSemicolonsAction.ACTION_DEFINITION_ID)
    setAction(ShowInferredSemicolonsAction.ACTION_ID, showInferredSemicolons)

    val openAction = new Action {
      override def run {
        scalaCompilationUnit foreach { scu =>
          scu.followDeclaration(ScalaSourceFileEditor.this, getSelectionProvider.getSelection.asInstanceOf[ITextSelection])
        }
      }
    }
    openAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.OPEN_EDITOR)
    setAction("OpenEditor", openAction)
  }
  
  private def scalaCompilationUnit: Option[ScalaCompilationUnit] = 
    Option(getInputJavaElement) map (_.asInstanceOf[ScalaCompilationUnit])

  override protected def initializeKeyBindingScopes() {
    setKeyBindingScopes(Array(SCALA_EDITOR_SCOPE))
  }

  override def createJavaSourceViewerConfiguration: JavaSourceViewerConfiguration =
    new ScalaSourceViewerConfiguration(javaPrefStore, scalaPrefStore, this)

  override def setSourceViewerConfiguration(configuration: SourceViewerConfiguration) {
    super.setSourceViewerConfiguration(
      configuration match {
        case svc: ScalaSourceViewerConfiguration => svc
        case _ => new ScalaSourceViewerConfiguration(javaPrefStore, scalaPrefStore, this)
      })
  }

  private[eclipse] def sourceViewer = getSourceViewer.asInstanceOf[JavaSourceViewer]

  private var occurrenceAnnotations: Set[Annotation] = Set()

  override def updateOccurrenceAnnotations(selection: ITextSelection, astRoot: CompilationUnit) {
    askForOccurrencesUpdate(selection)
  }

  private def getAnnotationModelOpt: Option[IAnnotationModel] =
    for {
      documentProvider <- Option(getDocumentProvider)
      annotationModel <- Option(documentProvider.getAnnotationModel(getEditorInput))
    } yield annotationModel
  
  private def performOccurrencesUpdate(selection: ITextSelection, documentLastModified: Long) {
      
    // TODO: find out why this code does a cast to IAdaptable before calling getAdapter 
    val adaptable = getEditorInput.asInstanceOf[IAdaptable].getAdapter(classOf[IJavaElement])
    // logger.info("adaptable: " + adaptable.getClass + " : " + adaptable.toString)

    adaptable match {
      case scalaSourceFile: ScalaSourceFile =>
        val annotations = getAnnotations(selection, scalaSourceFile, documentLastModified)
        for (annotationModel <- getAnnotationModelOpt) 
          annotationModel.withLock {
            annotationModel.replaceAnnotations(occurrenceAnnotations, annotations)
            occurrenceAnnotations = annotations.keySet
          }
      case _ =>
        // TODO: pop up a dialog explaining what needs to be fixed or fix it ourselves
        Utils tryExecute (adaptable.asInstanceOf[ScalaSourceFile], // trigger the exception, so as to get a diagnostic stack trace 
          msgIfError = Some("Could not recompute occurrence annotations: configuration problem"))
    }
  }

  private def getAnnotations(selection: ITextSelection, scalaSourceFile: ScalaSourceFile, documentLastModified: Long): Map[Annotation, Position] = {
    val occurrences = ScalaOccurrencesFinder.findOccurrences(scalaSourceFile, selection.getOffset, selection.getLength, documentLastModified)
    for {
      Occurrences(name, locations) <- occurrences.toList
      location <- locations
      annotation = new Annotation(OCCURRENCE_ANNOTATION, false, "Occurrence of '" + name + "'")
      position = new Position(location.getOffset, location.getLength)
    } yield annotation -> position
  }.toMap

  def askForOccurrencesUpdate(selection: ITextSelection) {

    if (selection.getLength < 0 || selection.getOffset < 0) 
      return
    
    if (getDocumentProvider == null || !isActiveEditor)
      return
    
    val lastModified = getSourceViewer.getDocument match {
      case document: IDocumentExtension4 =>
        document.getModificationStamp
      case _ => return
    }

    import org.eclipse.core.runtime.jobs.Job
    import org.eclipse.core.runtime.IProgressMonitor
    import org.eclipse.core.runtime.Status

    val job = new Job("Updating occurrence annotations") {
      def run(monitor: IProgressMonitor) = {
        performOccurrencesUpdate(selection, lastModified)
        Status.OK_STATUS
      }
    }

    job.setPriority(Job.INTERACTIVE)
    job.schedule()
  }

  override def doSelectionChanged(selection: ISelection) {
    super.doSelectionChanged(selection)
    val selectionProvider = getSelectionProvider
    if (selectionProvider != null)
      selectionProvider.getSelection match {
        case textSel: ITextSelection => askForOccurrencesUpdate(textSel)
        case _ =>
      }
  }

  lazy val selectionListener = new ISelectionListener() {
    def selectionChanged(part: IWorkbenchPart, selection: ISelection) {
      selection match {
        case textSel: ITextSelection => askForOccurrencesUpdate(textSel)
        case _ =>
      }
    }
  }

  override def installOccurrencesFinder(forceUpdate: Boolean) {
    super.installOccurrencesFinder(forceUpdate)
    getEditorSite.getPage.addPostSelectionListener(selectionListener)
  }

  override def uninstallOccurrencesFinder() {
    getEditorSite.getPage.removePostSelectionListener(selectionListener)
    super.uninstallOccurrencesFinder
  }

  private val preferenceListener: IPropertyChangeListener = handlePreferenceStoreChanged _

  scalaPrefStore.addPropertyChangeListener(preferenceListener)

  override def dispose() {
    super.dispose()
    scalaPrefStore.removePropertyChangeListener(preferenceListener)
  }
  
  private object controlCreator extends AbstractReusableInformationControlCreator {
    override def doCreateInformationControl(shell: Shell) = 
      new DefaultInformationControl(shell, true)
  }
  
  private lazy val tpePresenter = {
    val infoPresenter = new InformationPresenter(controlCreator) 
    infoPresenter.install(getSourceViewer)
    infoPresenter.setInformationProvider(actions.TypeOfExpressionProvider, IDocument.DEFAULT_CONTENT_TYPE)
    infoPresenter
  }
  
  /** Return the `InformationPresenter` used to display the type of
   *  the selected expression.
   */
  def typeOfExpressionPresenter: InformationPresenter = 
    tpePresenter

  override def editorContextMenuAboutToShow(menu: org.eclipse.jface.action.IMenuManager): Unit = {
    super.editorContextMenuAboutToShow(menu)

    def groupMenuItemsByGroupId(items: Seq[IContributionItem]) = {
      // the different groups (as indicated by separators) and 
      // contributions in a menu are originally just a flat list
      items.foldLeft(Nil: List[(String, List[IContributionItem])]) {

        // start a new group
        case (others, group: Separator) => (group.getId, Nil) :: others

        // append contribution to the current group
        case ((group, others) :: rest, element) => (group, element :: others) :: rest

        // the menu does not start with a group, this shouldn't happen, but if
        // it does we just skip this element, so it will stay in the menu.
        case (others, _) => others
      } toMap
    }

    def findJdtSourceMenuManager(items: Seq[IContributionItem]) = {
      items.collect {
        case mm: MenuManager if mm.getId == "org.eclipse.jdt.ui.source.menu" => mm
      }
    }

    findJdtSourceMenuManager(menu.getItems) foreach { mm =>

      val groups = groupMenuItemsByGroupId(mm.getItems)

      // these two contributions won't work on Scala files, so we remove them
      val blacklist = List("codeGroup", "importGroup", "generateGroup", "externalizeGroup")
      blacklist.flatMap(groups.get).flatten.foreach(mm.remove)

      // and provide our own organize imports instead
      mm.appendToGroup("importGroup", new refactoring.OrganizeImportsAction { setText("Organize Imports") })
      
      // add GenerateHashcodeAndEquals and IntroductProductN source generators
      mm.appendToGroup("generateGroup", new refactoring.source.GenerateHashcodeAndEqualsAction { 
        setText("Generate hashCode() and equals()...") 
      })
      mm.appendToGroup("generateGroup", new refactoring.source.IntroduceProductNTraitAction {
        setText("Introduce ProductN trait...")
      })

    }

    refactoring.RefactoringMenu.fillContextMenu(menu, this)
  }

  override def createPartControl(parent: org.eclipse.swt.widgets.Composite) {
    super.createPartControl(parent)
    refactoring.RefactoringMenu.fillQuickMenu(this)
    getSourceViewer match {
      case sourceViewer: ITextViewerExtension =>
        sourceViewer.prependVerifyKeyListener(new SurroundSelectionStrategy(getSourceViewer))
      case _ =>
    }
  }

  override def handlePreferenceStoreChanged(event: PropertyChangeEvent) =
    event.getProperty match {
      case ShowInferredSemicolonsAction.PREFERENCE_KEY =>
        getAction(ShowInferredSemicolonsAction.ACTION_ID).asInstanceOf[IUpdate].update()
      case _ =>
        if (affectsTextPresentation(event)) {
          // those events will trigger a UI change
          SWTUtils.asyncExec(super.handlePreferenceStoreChanged(event))
        } else {
          super.handlePreferenceStoreChanged(event)
        }
    }

  override def configureSourceViewerDecorationSupport(support: SourceViewerDecorationSupport) {
    super.configureSourceViewerDecorationSupport(support)
    SemanticHighlightingAnnotations.addAnnotationPreferences(support)
  }

  override protected def getSourceViewerDecorationSupport(viewer: ISourceViewer): SourceViewerDecorationSupport = {
    if (fSourceViewerDecorationSupport == null) {
      fSourceViewerDecorationSupport = new ScalaSourceViewerDecorationSupport(viewer)
      configureSourceViewerDecorationSupport(fSourceViewerDecorationSupport)
    }
    fSourceViewerDecorationSupport
  }

  class ScalaSourceViewerDecorationSupport(viewer: ISourceViewer)
    extends SourceViewerDecorationSupport(viewer, getOverviewRuler, getAnnotationAccess, getSharedColors) {

    override protected def createAnnotationPainter(): AnnotationPainter = {
      val annotationPainter = super.createAnnotationPainter
      SemanticHighlightingAnnotations.addTextStyleStrategies(annotationPainter)
      annotationPainter
    }

  }

}

object ScalaSourceFileEditor {

  private val EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS = "org.eclipse.ui.texteditor.ConstructedEditorMessages"
  private val bundleForConstructedKeys = ResourceBundle.getBundle(EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS)

  private val SCALA_EDITOR_SCOPE = "scala.tools.eclipse.scalaEditorScope"

  private val OCCURRENCE_ANNOTATION = "org.eclipse.jdt.ui.occurrences"
}
