/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.wm.impl.status;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFilePropertyEvent;
import com.intellij.openapi.vfs.encoding.ChangeFileEncodingAction;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.encoding.EncodingManagerImpl;
import com.intellij.openapi.vfs.encoding.EncodingUtil;
import com.intellij.openapi.vfs.impl.BulkVirtualFileListenerAdapter;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.ClickListener;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.charset.Charset;

/**
 * @author cdr
 */
public class EncodingPanel extends EditorBasedWidget implements StatusBarWidget.Multiframe, CustomStatusBarWidget {
  private final TextPanel myComponent;
  private boolean actionEnabled;

  public EncodingPanel(@NotNull final Project project) {
    super(project);

    myComponent = new TextPanel() {
      @Override
      protected void paintComponent(@NotNull final Graphics g) {
        super.paintComponent(g);
        if (actionEnabled && getText() != null) {
          final Rectangle r = getBounds();
          final Insets insets = getInsets();
          AllIcons.Ide.Statusbar_arrows.paintIcon(this, g, r.width - insets.right - AllIcons.Ide.Statusbar_arrows.getIconWidth() - 2,
                                                  r.height / 2 - AllIcons.Ide.Statusbar_arrows.getIconHeight() / 2);
        }
      }
    };

    new ClickListener() {
      @Override
      public boolean onClick(MouseEvent e, int clickCount) {
        update();
        showPopup(e);
        return true;
      }
    }.installOn(myComponent);
    myComponent.setBorder(WidgetBorder.INSTANCE);
  }

  @Nullable("returns null if charset set cannot be determined from content")
  private static Charset cachedCharsetFromContent(final VirtualFile virtualFile) {
    if (virtualFile == null) return null;
    final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    if (document == null) return null;

    return EncodingManager.getInstance().getCachedCharsetFromContent(document);
  }

  @Override
  public void selectionChanged(FileEditorManagerEvent event) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    update();
  }

  @Override
  public void fileOpened(FileEditorManager source, VirtualFile file) {
    update();
  }

  @Override
  public StatusBarWidget copy() {
    return new EncodingPanel(getProject());
  }

  @Override
  @NotNull
  public String ID() {
    return "Encoding";
  }

  @Override
  public WidgetPresentation getPresentation(@NotNull PlatformType type) {
    return null;
  }

  @Override
  public void install(@NotNull StatusBar statusBar) {
    super.install(statusBar);
    // should update to reflect encoding-from-content
    EncodingManager.getInstance().addPropertyChangeListener(new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getPropertyName().equals(EncodingManagerImpl.PROP_CACHED_ENCODING_CHANGED)) {
          update();
        }
      }
    }, this);
    ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkVirtualFileListenerAdapter(new VirtualFileAdapter() {
      @Override
      public void propertyChanged(VirtualFilePropertyEvent event) {
        if (VirtualFile.PROP_ENCODING.equals(event.getPropertyName())) {
          update();
        }
      }
    }));
    final Alarm update = new Alarm();
    EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent e) {
        Document document = e.getDocument();
        Editor selectedEditor = getEditor();
        if (selectedEditor == null || selectedEditor.getDocument() != document) return;
        update.cancelAllRequests();
        update.addRequest(new Runnable() {
                              @Override
                              public void run() {
                                if (!isDisposed()) update();
                              }
                            }, 200);
      }
    }, this);
  }

  private void showPopup(MouseEvent e) {
    if (!actionEnabled) {
      return;
    }
    DataContext dataContext = getContext();
    ListPopup popup = new ChangeFileEncodingAction().createPopup(dataContext);

    if (popup != null) {
      Dimension dimension = popup.getContent().getPreferredSize();
      Point at = new Point(0, -dimension.height);
      popup.show(new RelativePoint(e.getComponent(), at));
      Disposer.register(this, popup); // destroy popup on unexpected project close
    }
  }

  @NotNull
  private DataContext getContext() {
    Editor editor = getEditor();
    DataContext parent = DataManager.getInstance().getDataContext((Component)myStatusBar);
    return SimpleDataContext.getSimpleContext(PlatformDataKeys.VIRTUAL_FILE.getName(), getSelectedFile(),
           SimpleDataContext.getSimpleContext(PlatformDataKeys.PROJECT.getName(), getProject(),
           SimpleDataContext.getSimpleContext(PlatformDataKeys.CONTEXT_COMPONENT.getName(), editor == null ? null : editor.getComponent(), parent)
           ));
  }

  private void update() {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        VirtualFile file = getSelectedFile();
        actionEnabled = false;
        String charsetName = null;
        Pair<Charset,String> check = null;

        if (file != null) {
          check = EncodingUtil.checkSomeActionEnabled(file);
          Charset charset = null;

          if (LoadTextUtil.wasCharsetDetectedFromBytes(file) != null)
          {
            charset = cachedCharsetFromContent(file);
          }

          if (charset == null) {
            charset = file.getCharset();
          }

          actionEnabled = (check == null || check.second == null);

          if (!actionEnabled) {
            charset = check.first;
          }

          if (charset != null) {
            charsetName = charset.displayName();
          }
        }

        if (charsetName == null) {
          charsetName = "n/a";
        }

        String toolTipText;

        if (actionEnabled) {
          toolTipText = String.format(
            "File Encoding%n%s", charsetName);

          myComponent.setForeground(UIUtil.getActiveTextColor());
          myComponent.setTextAlignment(Component.LEFT_ALIGNMENT);
        } else {
          String failReason = (check == null) ? "" : check.second;
          toolTipText = String.format("File encoding is disabled%n%s",
                                      failReason);

          myComponent.setForeground(UIUtil.getInactiveTextColor());
          myComponent.setTextAlignment(Component.CENTER_ALIGNMENT);
        }

        myComponent.setToolTipText(toolTipText);
        myComponent.setText(charsetName);

        if (myStatusBar != null) {
          myStatusBar.updateWidget(ID());
        }
      }
    });
    
  }

  @Override
  public JComponent getComponent() {
    return myComponent;
  }
}
