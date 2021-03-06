// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.mac.touchbar;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.FocusChangeListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TouchBarsManager {
  private static final Logger LOG = Logger.getInstance(TouchBarsManager.class);
  private static final StackTouchBars ourStack = new StackTouchBars();

  private static final Map<Project, ProjectData> ourProjectData = new HashMap<>(); // NOTE: probably it is better to use api of UserDataHolder

  public static void initialize() {
    if (!isTouchBarAvailable())
      return;

    ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectOpened(Project project) {
        // System.out.println("opened project " + project + ", set default touchbar");

        final ProjectData pd = _getProjData(project);
        ourStack.showContainer(pd.get(BarType.DEFAULT));

        project.getMessageBus().connect().subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
          @Override
          public void stateChanged() {
            final ToolWindowManagerEx twm = ToolWindowManagerEx.getInstanceEx(project);
            final String activeId = twm.getActiveToolWindowId();
            if (activeId != null && (activeId.equals(ToolWindowId.DEBUG) || activeId.equals(ToolWindowId.RUN_DASHBOARD))) {
              // System.out.println("stateChanged, dbgSessionsCount=" + pd.getDbgSessions());
              if (pd.getDbgSessions() <= 0)
                return;

              ourStack.showContainer(pd.get(BarType.DEBUGGER));
            }
          }
        });

        project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
          @Override
          public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
            ApplicationManager.getApplication().invokeLater(TouchBarsManager::_updateCurrentTouchbar);
          }
          @Override
          public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
            ourStack.pop(topContainer -> {
              if (topContainer.getType() != BarType.DEBUGGER)
                return false;
              if (!executorId.equals(ToolWindowId.DEBUG) && !executorId.equals(ToolWindowId.RUN_DASHBOARD))
                return false;

              // System.out.println("processTerminated, dbgSessionsCount=" + pd.getDbgSessions());
              return !_hasAnyActiveSession(project, handler) || pd.getDbgSessions() <= 0;
            });
            ApplicationManager.getApplication().invokeLater(TouchBarsManager::_updateCurrentTouchbar);
          }
        });
      }

      @Override
      public void projectClosed(Project project) {
        // System.out.println("closed project " + project + ", hide touchbar");
        final ProjectData pd = _getProjData(project);
        ourStack.removeAll(pd.getAllContainers());
        pd.releaseAll();
        ourProjectData.remove(project);
      }
    });
  }

  public static boolean isTouchBarAvailable() { return NST.isAvailable(); }

  public static void reloadAll() {
    if (!isTouchBarAvailable())
      return;

    ourProjectData.forEach((p, pd)->{
      pd.reloadAll();
    });
    ourStack.setTouchBarFromTopContainer();
  }

  public static void onInputEvent(InputEvent e) {
    if (!isTouchBarAvailable())
      return;

    // NOTE: skip wheel-events, because scrolling by touchpad produces mouse-wheel events with pressed modifier, expamle:
    // MouseWheelEvent[MOUSE_WHEEL,(890,571),absolute(0,0),button=0,modifiers=⇧,extModifiers=⇧,clickCount=0,scrollType=WHEEL_UNIT_SCROLL,scrollAmount=1,wheelRotation=0,preciseWheelRotation=0.1] on frame0
    if (e instanceof MouseWheelEvent)
      return;

    ourStack.updateKeyMask(e.getModifiersEx());
  }

  public static void onFocusEvent(AWTEvent e) {
    if (!isTouchBarAvailable())
      return;

    // NOTE: WindowEvent.WINDOW_GAINED_FOCUS can be fired when frame focused
    if (e.getID() == FocusEvent.FOCUS_GAINED) {
      if (!(e.getSource() instanceof Component))
        return;

      for (ProjectData pd: ourProjectData.values()) {
        if (pd.isDisposed())
          continue;

        final BarContainer parent = pd.findByComponent((Component)e.getSource());
        if (parent != null) {
          ourStack.showContainer(parent);
          return;
        }
      }
    }
  }

  public static void registerEditor(@NotNull Editor editor) {
    if (!isTouchBarAvailable())
      return;

    final Project proj = editor.getProject();
    if (proj == null)
      return;

    final ProjectData pd = _getProjData(proj);
    pd.registerEditor(editor);

    if (editor instanceof EditorEx)
      ((EditorEx)editor).addFocusListener(new FocusChangeListener() {
      @Override
      public void focusGained(Editor editor) {
        final boolean hasDebugSession = pd.getDbgSessions() > 0;
        if (!hasDebugSession)
          ourStack.elevateContainer(pd.get(BarType.DEFAULT));
      }
      @Override
      public void focusLost(Editor editor) {}
    });
  }

  public static void releaseEditor(@NotNull Editor editor) {
    if (!isTouchBarAvailable())
      return;

    final Project proj = editor.getProject();
    if (proj == null)
      return;

    final ProjectData pd = _getProjData(proj);
    pd.removeEditor(editor);
  }

  public static void onUpdateEditorHeader(@NotNull Editor editor, JComponent header, ActionGroup actions) {
    if (!isTouchBarAvailable())
      return;

    final Project proj = editor.getProject();
    if (proj == null)
      return;

    final ProjectData pd = _getProjData(proj);
    final ProjectData.EditorData ed = pd.getEditorData(editor);
    if (ed == null) {
      LOG.error("can't find editor-data for editor: " + editor);
      return;
    }

    if (header == null) {
      // System.out.println("set null header");
      ed.editorHeader = null;
      if (ed.containerSearch != null)
        ourStack.removeContainer(ed.containerSearch);
    } else {
      // System.out.println("set header: " + header);
      // System.out.println("\t\tparent: " + header.getParent());
      ed.editorHeader = header;

      if (ed.containerSearch == null || ed.actionsSearch != actions) {
        if (ed.containerSearch != null) {
          ourStack.removeContainer(ed.containerSearch);
          ed.containerSearch.release();
        }

        ed.containerSearch = new BarContainer(BarType.EDITOR_SEARCH, TouchBar.buildFromGroup("editor_search_" + header, actions, false), null);
        ourStack.showContainer(ed.containerSearch);
      }
    }
  }

  public static void attachPopupBar(@NotNull ListPopupImpl listPopup) {
    if (!isTouchBarAvailable())
      return;

    listPopup.addPopupListener(new JBPopupListener() {
        private TouchBar myPopupBar = BuildUtils.createScrubberBarFromPopup(listPopup);
        @Override
        public void beforeShown(LightweightWindowEvent event) {
          _showTempTouchBar(myPopupBar, BarType.POPUP);
        }
        @Override
        public void onClosed(LightweightWindowEvent event) {
          ourStack.removeTouchBar(myPopupBar);
          myPopupBar = null;
        }
      }
    );
  }

  public static @Nullable Runnable showDlgButtonsBar(List<JButton> jbuttons) {
    if (!isTouchBarAvailable())
      return null;

    final TouchBar tb = BuildUtils.createButtonsBar(jbuttons);
    _showTempTouchBar(tb, BarType.DIALOG);
    return ()->{ourStack.removeTouchBar(tb);};
  }

  public static Runnable showMessageDlgBar(@NotNull String[] buttons, @NotNull Runnable[] actions, String defaultButton) {
    if (!isTouchBarAvailable())
      return null;

    final TouchBar tb = BuildUtils.createMessageDlgBar(buttons, actions, defaultButton);
    _showTempTouchBar(tb, BarType.DIALOG);
    return ()->{ourStack.removeTouchBar(tb);};
  }

  public static void showStopRunningBar(List<Pair<RunContentDescriptor, Runnable>> stoppableDescriptors) {
    final TouchBar tb = BuildUtils.createStopRunningBar(stoppableDescriptors);
    _showTempTouchBar(tb, BarType.DIALOG);
  }

  static void removeTouchBar(TouchBar tb) {
    if (tb != null)
      ourStack.removeTouchBar(tb);
  }

  private static void _showTempTouchBar(TouchBar tb, BarType type) {
    if (tb == null)
      return;
    BarContainer container = new BarContainer(type, tb, null);
    ourStack.showContainer(container);
  }

  private static boolean _hasAnyActiveSession(Project proj, ProcessHandler handler/*already terminated*/) {
    final ProcessHandler[] processes = ExecutionManager.getInstance(proj).getRunningProcesses();
    return Arrays.stream(processes).anyMatch(h -> h != null && h != handler && (!h.isProcessTerminated() && !h.isProcessTerminating()));
  }

  private static @NotNull ProjectData _getProjData(@NotNull Project project) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    ProjectData result = ourProjectData.get(project);
    if (result == null) {
      result = new ProjectData(project);
      ourProjectData.put(project, result);
    }
    return result;
  }

  private static void _updateCurrentTouchbar() {
    ApplicationManager.getApplication().assertIsDispatchThread();

    final TouchBar top = ourStack.getTopTouchBar();
    if (top != null)
      top.updateActionItems();
  }
}
