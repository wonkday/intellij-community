package com.intellij.execution.console;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.DocumentBulkUpdateListener;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.RangeMarkerImpl;
import com.intellij.openapi.editor.impl.event.MarkupModelListener;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.components.JBLayeredPane;
import com.intellij.util.Consumer;
import com.intellij.util.NotNullFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class LanguageConsoleBuilder {
  @Nullable
  private LanguageConsoleView consoleView;
  @Nullable
  private Condition<LanguageConsoleImpl> executionEnabled = Conditions.alwaysTrue();

  @Nullable
  private NotNullFunction<VirtualFile, PsiFile> psiFileFactory;
  @Nullable
  private BaseConsoleExecuteActionHandler executeActionHandler;
  @Nullable
  private String historyType;

  @Nullable
  private GutterContentProvider gutterContentProvider;

  public LanguageConsoleBuilder(@SuppressWarnings("NullableProblems") @NotNull LanguageConsoleView consoleView) {
    this.consoleView = consoleView;
  }

  public LanguageConsoleBuilder() {
  }

  public LanguageConsoleBuilder processHandler(@NotNull ProcessHandler processHandler) {
    //noinspection deprecation
    executionEnabled = new ProcessBackedExecutionEnabledCondition(processHandler);
    return this;
  }

  public LanguageConsoleBuilder executionEnabled(@NotNull Condition<LanguageConsoleImpl> condition) {
    executionEnabled = condition;
    return this;
  }

  public LanguageConsoleBuilder psiFileFactory(@NotNull NotNullFunction<VirtualFile, PsiFile> value) {
    psiFileFactory = value;
    return this;
  }

  public LanguageConsoleBuilder initActions(@NotNull BaseConsoleExecuteActionHandler executeActionHandler, @NotNull String historyType) {
    if (consoleView == null) {
      this.executeActionHandler = executeActionHandler;
      this.historyType = historyType;
    }
    else {
      doInitAction(consoleView, executeActionHandler, historyType);
    }
    return this;
  }

  private void doInitAction(@NotNull LanguageConsoleView consoleView, @NotNull BaseConsoleExecuteActionHandler executeActionHandler, @NotNull String historyType) {
    ConsoleExecuteAction action = new ConsoleExecuteAction(consoleView, executeActionHandler, executionEnabled);
    action.registerCustomShortcutSet(action.getShortcutSet(), consoleView.getConsole().getConsoleEditor().getComponent());

    new ConsoleHistoryController(historyType, null, consoleView.getConsole(), executeActionHandler.getConsoleHistoryModel()).install();
  }

  /**
   * todo This API doesn't look good, but it is much better than force client to know low-level details
   */
  public static Pair<AnAction, ConsoleHistoryController> registerExecuteAction(@NotNull LanguageConsoleImpl console,
                                                                               @NotNull final Consumer<String> executeActionHandler,
                                                                               @NotNull String historyType,
                                                                               @Nullable String historyPersistenceId,
                                                                               @Nullable Condition<LanguageConsoleImpl> enabledCondition) {
    ConsoleExecuteAction.ConsoleExecuteActionHandler handler = new ConsoleExecuteAction.ConsoleExecuteActionHandler(true) {
      @Override
      void doExecute(@NotNull String text, @NotNull LanguageConsoleImpl console, @Nullable LanguageConsoleView consoleView) {
        executeActionHandler.consume(text);
      }
    };

    ConsoleExecuteAction action = new ConsoleExecuteAction(console, handler, enabledCondition);
    action.registerCustomShortcutSet(action.getShortcutSet(), console.getConsoleEditor().getComponent());

    ConsoleHistoryController historyController = new ConsoleHistoryController(historyType, historyPersistenceId, console, handler.getConsoleHistoryModel());
    historyController.install();
    return new Pair<AnAction, ConsoleHistoryController>(action, historyController);
  }

  public LanguageConsoleBuilder historyAnnotation(@Nullable GutterContentProvider value) {
    gutterContentProvider = value;
    return this;
  }

  public LanguageConsoleView build(@NotNull Project project, @NotNull Language language) {
    GutteredLanguageConsole console = new GutteredLanguageConsole(project, language, gutterContentProvider, psiFileFactory);
    LanguageConsoleViewImpl consoleView = new LanguageConsoleViewImpl(console, true);
    if (executeActionHandler != null) {
      assert historyType != null;
      doInitAction(consoleView, executeActionHandler, historyType);
    }
    console.initComponents();
    return consoleView;
  }

  @Deprecated
  /**
   * @deprecated Don't use it directly!
   * Will be private in IDEA >13
   */
  public static class ProcessBackedExecutionEnabledCondition implements Condition<LanguageConsoleImpl> {
    private final ProcessHandler myProcessHandler;

    public ProcessBackedExecutionEnabledCondition(ProcessHandler myProcessHandler) {
      this.myProcessHandler = myProcessHandler;
    }

    @Override
    public boolean value(LanguageConsoleImpl console) {
      return !myProcessHandler.isProcessTerminated();
    }
  }

  private final static class GutteredLanguageConsole extends LanguageConsoleImpl {
    @Nullable
    private final GutterContentProvider gutterContentProvider;

    public GutteredLanguageConsole(@NotNull Project project,
                                   @NotNull Language language,
                                   @Nullable GutterContentProvider gutterContentProvider,
                                   @Nullable NotNullFunction<VirtualFile, PsiFile> psiFileFactory) {
      super(project, language.getDisplayName() + " Console", language, psiFileFactory);

      setShowSeparatorLine(false);
      this.gutterContentProvider = gutterContentProvider;
    }

    @Override
    protected void setupEditorDefault(@NotNull EditorEx editor) {
      super.setupEditorDefault(editor);

      if (editor == getConsoleEditor()) {
        editor.setOneLineMode(true);
        return;
      }
      else if (gutterContentProvider == null) {
        return;
      }

      final ConsoleIconGutterComponent lineStartGutter = new ConsoleIconGutterComponent(editor, gutterContentProvider);
      final ConsoleGutterComponent lineEndGutter = new ConsoleGutterComponent(editor, gutterContentProvider);
      JLayeredPane layeredPane = new JBLayeredPane() {
        @Override
        public Dimension getPreferredSize() {
          Dimension editorSize = getEditorComponent().getPreferredSize();
          return new Dimension(lineStartGutter.getPreferredSize().width + editorSize.width, editorSize.height);
        }

        @Override
        public Dimension getMinimumSize() {
          Dimension editorSize = getEditorComponent().getMinimumSize();
          return new Dimension(lineStartGutter.getPreferredSize().width + editorSize.width, editorSize.height);
        }

        @Override
        public void doLayout() {
          EditorComponentImpl editor = getEditorComponent();
          int w = getWidth();
          int h = getHeight();
          int lineStartGutterWidth = lineStartGutter.getPreferredSize().width;
          lineStartGutter.setBounds(0, 0, lineStartGutterWidth, h);

          editor.setBounds(lineStartGutterWidth, 0, w - lineStartGutterWidth, h);

          int lineEndGutterWidth = lineEndGutter.getPreferredSize().width;
          lineEndGutter.setBounds(lineStartGutterWidth + (w - lineEndGutterWidth - editor.getEditor().getScrollPane().getVerticalScrollBar().getWidth()), 0, lineEndGutterWidth, h);
        }

        @NotNull
        private EditorComponentImpl getEditorComponent() {
          for (int i = getComponentCount() - 1; i >= 0; i--) {
            Component component = getComponent(i);
            if (component instanceof EditorComponentImpl) {
              return (EditorComponentImpl)component;
            }
          }
          throw new IllegalStateException();
        }
      };

      layeredPane.add(lineStartGutter, JLayeredPane.DEFAULT_LAYER);

      JScrollPane scrollPane = editor.getScrollPane();
      layeredPane.add(scrollPane.getViewport().getView(), JLayeredPane.DEFAULT_LAYER);

      layeredPane.add(lineEndGutter, JLayeredPane.PALETTE_LAYER);

      scrollPane.setViewportView(layeredPane);

      GutterUpdateScheduler gutterUpdateScheduler = new GutterUpdateScheduler(lineStartGutter, lineEndGutter);
      getProject().getMessageBus().connect(this).subscribe(DocumentBulkUpdateListener.TOPIC, gutterUpdateScheduler);
      editor.getDocument().addDocumentListener(gutterUpdateScheduler);
    }

    @Override
    protected void doAddPromptToHistory() {
      if (gutterContentProvider == null) {
        super.doAddPromptToHistory();
      }
      else {
        gutterContentProvider.beforeEvaluate(getHistoryViewer());
      }
    }

    private final class GutterUpdateScheduler extends DocumentAdapter implements DocumentBulkUpdateListener {
      private final ConsoleIconGutterComponent lineStartGutter;
      private final ConsoleGutterComponent lineEndGutter;

      private Runnable gutterSizeUpdater;
      private RangeHighlighterEx lineSeparatorPainter;

      public GutterUpdateScheduler(@NotNull ConsoleIconGutterComponent lineStartGutter, @NotNull ConsoleGutterComponent lineEndGutter) {
        this.lineStartGutter = lineStartGutter;
        this.lineEndGutter = lineEndGutter;

        // console view can invoke markupModel.removeAllHighlighters(), so, we must be aware of it
        getHistoryViewer().getMarkupModel().addMarkupModelListener(GutteredLanguageConsole.this, new MarkupModelListener.Adapter() {
          @Override
          public void beforeRemoved(@NotNull RangeHighlighterEx highlighter) {
            if (lineSeparatorPainter == highlighter) {
              lineSeparatorPainter = null;
            }
          }
        });
      }

      private void addLineSeparatorPainterIfNeed() {
        if (lineSeparatorPainter != null) {
          return;
        }

        EditorEx editor = getHistoryViewer();
        int endOffset = getDocument().getTextLength();
        lineSeparatorPainter = new LineSeparatorPainter(editor, endOffset);
        editor.getMarkupModel().addRangeHighlighter(lineSeparatorPainter, 0, endOffset, false, false, HighlighterLayer.ADDITIONAL_SYNTAX);
      }

      private DocumentEx getDocument() {
        return getHistoryViewer().getDocument();
      }

      @Override
      public void documentChanged(DocumentEvent event) {
        DocumentEx document = getDocument();
        if (document.isInBulkUpdate()) {
          return;
        }

        if (document.getTextLength() > 0) {
          addLineSeparatorPainterIfNeed();
          int startDocLine = document.getLineNumber(event.getOffset());
          int endDocLine = document.getLineNumber(event.getOffset() + event.getNewLength());
          if (event.getOldLength() > event.getNewLength() || startDocLine != endDocLine || StringUtil.indexOf(event.getOldFragment(), '\n') != -1) {
            updateGutterSize();
          }
        }
        else if (event.getOldLength() > 0) {
          documentCleared();
        }
      }

      private void documentCleared() {
        assert gutterContentProvider != null;
        gutterContentProvider.documentCleared(getHistoryViewer());
      }

      @Override
      public void updateStarted(@NotNull Document doc) {
      }

      @Override
      public void updateFinished(@NotNull Document doc) {
        if (getDocument().getTextLength() == 0) {
          documentCleared();
        }
        else {
          addLineSeparatorPainterIfNeed();
        }
        updateGutterSize();
      }

      private void updateGutterSize() {
        if (gutterSizeUpdater != null) {
          return;
        }

        gutterSizeUpdater = new Runnable() {
          @Override
          public void run() {
            if (!getHistoryViewer().isDisposed()) {
              lineStartGutter.updateSize();
              lineEndGutter.updateSize();
            }
            gutterSizeUpdater = null;
          }
        };
        SwingUtilities.invokeLater(gutterSizeUpdater);
      }
    }

    private final class LineSeparatorPainter extends RangeMarkerImpl implements RangeHighlighterEx, Getter<RangeHighlighterEx> {
      private final CustomHighlighterRenderer renderer = new CustomHighlighterRenderer() {
        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
          Rectangle clip = g.getClipBounds();
          int lineHeight = editor.getLineHeight();
          int startLine = clip.y / lineHeight;
          int endLine = Math.min(((clip.y + clip.height) / lineHeight) + 1, ((EditorImpl)editor).getVisibleLineCount());
          if (startLine >= endLine) {
            return;
          }

          // workaround - editor ask us to paint line 4-6, but we should draw line for line 3 (startLine - 1) also, otherwise it will be not rendered
          int actualStartLine = startLine == 0 ? 0 : startLine - 1;
          int y = (actualStartLine + 1) * lineHeight;
          g.setColor(editor.getColorsScheme().getColor(EditorColors.INDENT_GUIDE_COLOR));
          assert gutterContentProvider != null;
          for (int visualLine = actualStartLine; visualLine < endLine; visualLine++) {
            if (gutterContentProvider.isShowSeparatorLine(editor.visualToLogicalPosition(new VisualPosition(visualLine, 0)).line, editor)) {
              g.drawLine(0, y, clip.width, y);
            }
            y += lineHeight;
          }
        }
      };

      public LineSeparatorPainter(@NotNull EditorEx editor, int endOffset) {
        super(editor.getDocument(), 0, endOffset, false);
      }

      @Override
      protected void changedUpdateImpl(DocumentEvent e) {
        setIntervalEnd(myDocument.getTextLength());
      }

      @Override
      public boolean setValid(boolean value) {
        return super.setValid(value);
      }

      @Override
      public boolean isAfterEndOfLine() {
        return false;
      }

      @Override
      public void setAfterEndOfLine(boolean value) {
      }

      @Override
      public int getAffectedAreaStartOffset() {
        return 0;
      }

      @Override
      public int getAffectedAreaEndOffset() {
        return myDocument.getTextLength();
      }

      @Override
      public void setTextAttributes(@NotNull TextAttributes textAttributes) {
      }

      @NotNull
      @Override
      public HighlighterTargetArea getTargetArea() {
        return HighlighterTargetArea.EXACT_RANGE;
      }

      @Nullable
      @Override
      public TextAttributes getTextAttributes() {
        return null;
      }

      @Nullable
      @Override
      public LineMarkerRenderer getLineMarkerRenderer() {
        return null;
      }

      @Override
      public void setLineMarkerRenderer(@Nullable LineMarkerRenderer renderer) {
      }

      @Nullable
      @Override
      public CustomHighlighterRenderer getCustomRenderer() {
        return renderer;
      }

      @Override
      public void setCustomRenderer(CustomHighlighterRenderer renderer) {
      }

      @Nullable
      @Override
      public GutterIconRenderer getGutterIconRenderer() {
        return null;
      }

      @Override
      public void setGutterIconRenderer(@Nullable GutterIconRenderer renderer) {
      }

      @Nullable
      @Override
      public Color getErrorStripeMarkColor() {
        return null;
      }

      @Override
      public void setErrorStripeMarkColor(@Nullable Color color) {
      }

      @Nullable
      @Override
      public Object getErrorStripeTooltip() {
        return null;
      }

      @Override
      public void setErrorStripeTooltip(@Nullable Object tooltipObject) {
      }

      @Override
      public boolean isThinErrorStripeMark() {
        return false;
      }

      @Override
      public void setThinErrorStripeMark(boolean value) {
      }

      @Nullable
      @Override
      public Color getLineSeparatorColor() {
        return null;
      }

      @Override
      public void setLineSeparatorColor(@Nullable Color color) {
      }

      @Override
      public void setLineSeparatorRenderer(LineSeparatorRenderer renderer) {
      }

      @Override
      public LineSeparatorRenderer getLineSeparatorRenderer() {
        return null;
      }

      @Nullable
      @Override
      public SeparatorPlacement getLineSeparatorPlacement() {
        return null;
      }

      @Override
      public void setLineSeparatorPlacement(@Nullable SeparatorPlacement placement) {
      }

      @Override
      public void setEditorFilter(@NotNull MarkupEditorFilter filter) {
      }

      @NotNull
      @Override
      public MarkupEditorFilter getEditorFilter() {
        return MarkupEditorFilter.EMPTY;
      }

      @Override
      public RangeHighlighterEx get() {
        return this;
      }
    }
  }
}