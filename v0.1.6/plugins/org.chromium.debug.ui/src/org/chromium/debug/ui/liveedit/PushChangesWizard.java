// Copyright (c) 2010 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.debug.ui.liveedit;

import org.chromium.debug.core.util.ScriptTargetMapping;
import org.chromium.debug.ui.WizardUtils.LogicBasedWizard;
import org.chromium.debug.ui.WizardUtils.PageControlsFactory;
import org.chromium.debug.ui.WizardUtils.PageElements;
import org.chromium.debug.ui.WizardUtils.PageImpl;
import org.chromium.debug.ui.WizardUtils.WizardFinisher;
import org.chromium.debug.ui.WizardUtils.WizardLogic;
import org.chromium.debug.ui.WizardUtils.WizardPageSet;
import org.chromium.debug.ui.actions.ChooseVmControl;
import org.chromium.debug.ui.actions.PushChangesAction;
import org.chromium.debug.ui.liveedit.LiveEditResultDialog.Input;
import org.chromium.debug.ui.liveedit.LiveEditResultDialog.SingleInput;
import org.chromium.sdk.CallbackSemaphore;
import org.chromium.sdk.UpdatableScript;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * A wizard that pushes script changes to V8 VM (LiveEdit); it also lets user choose target VM(s),
 * review changes in script text, review which function will be patched.
 */
public class PushChangesWizard {

  public static void start(final List<? extends ScriptTargetMapping> filePairs, Shell shell) {
    // Create pages.
    final PageImpl<ChooseVmPageElements> chooseVmPage = new PageImpl<ChooseVmPageElements>(
        "choose VM", //$NON-NLS-1$
        CHOOSE_VM_PAGE_FACTORY,
        "Choose VM",
        "Select one or several V8 VMs to push changes to.");

    final PageImpl<TextualDiffPageElements> textualDiffPage = new PageImpl<TextualDiffPageElements>(
        "textual diff", //$NON-NLS-1$
        TEXTUAL_DIFF_PAGE_FACTORY,
        "Textual difference",
        "Script update is based on textual script diff" +
        // This is inaccurate because V8 and this view do their own diffs which are not necessarily
        // same.
        " (though this view may be not 100% accurate).");
    final PageImpl<V8PreviewPageElements> v8PreviewPage = new PageImpl<V8PreviewPageElements>(
        "v8 preview", //$NON-NLS-1$
        V8_PREVIEW_PAGE_FACTORY,
        "V8 LiveEdit Update Preview",
        "Script update is performed function-wise as shown on the diagram.");
    final PageImpl<PageElements> multipleVmStubPage = new PageImpl<PageElements>(
        "multiple vm",
        MULTIPLE_VM_STUB_PAGE_FACTORY,
        "Multiple VMs Selected",
        "No preview is available with multiple VMs selected.");

    final PageSet pageSet = new PageSet() {
      public List<? extends PageImpl<?>> getAllPages() {
        return Arrays.<PageImpl<?>>asList(chooseVmPage, textualDiffPage, v8PreviewPage, multipleVmStubPage);
      }
      public PageImpl<ChooseVmPageElements> getChooseVmPage() {
        return chooseVmPage;
      }
      public PageImpl<TextualDiffPageElements> getTextualDiffPage() {
        return textualDiffPage;
      }
      public PageImpl<V8PreviewPageElements> getV8PreviewPage() {
        return v8PreviewPage;
      }
      public PageImpl<PageElements> getMultipleVmStubPage() {
        return multipleVmStubPage;
      }
      public WizardLogic createLogic(final LogicBasedWizard wizardImpl) {
        WizardLogicBuilder logicBuilder = new WizardLogicBuilder(this, wizardImpl);
        return logicBuilder.create(filePairs);
      }
    };

    // Start wizard engine.
    LogicBasedWizard wizard = new LogicBasedWizard(pageSet);
    wizard.setWindowTitle("Push LiveEdit Changes to VM");
    WizardDialog wizardDialog = new WizardDialog(shell, wizard);
    wizardDialog.open();
  }

  /**
   * An access to all wizard pages.
   */
  interface PageSet extends WizardPageSet {
    PageImpl<ChooseVmPageElements> getChooseVmPage();
    PageImpl<TextualDiffPageElements> getTextualDiffPage();
    PageImpl<V8PreviewPageElements> getV8PreviewPage();
    PageImpl<PageElements> getMultipleVmStubPage();
  }

  ///  Interfaces that link logic with UI controls on each pages.

  interface ChooseVmPageElements extends PageElements {
    ChooseVmControl.Logic getChooseVm();
  }

  interface TextualDiffPageElements extends PageElements {
    CompareViewerPane getCompareViewerPane();
  }

  interface V8PreviewPageElements extends PageElements {
    LiveEditDiffViewer getPreviewViewer();
  }

  ///  Factories that create UI controls for pages.

  private static final PageControlsFactory<ChooseVmPageElements> CHOOSE_VM_PAGE_FACTORY =
      new PageControlsFactory<ChooseVmPageElements>() {
    public ChooseVmPageElements create(Composite parent) {
      final ChooseVmControl.Logic chooseVm = ChooseVmControl.create(parent);
      chooseVm.getControl().setLayoutData(new GridData(GridData.FILL_BOTH));

      return new ChooseVmPageElements() {
        public ChooseVmControl.Logic getChooseVm() {
          return chooseVm;
        }
        public Control getMainControl() {
          return chooseVm.getControl();
        }
      };
    }
  };

  private static final PageControlsFactory<TextualDiffPageElements> TEXTUAL_DIFF_PAGE_FACTORY =
      new PageControlsFactory<TextualDiffPageElements>() {
    public TextualDiffPageElements create(Composite parent) {
      final Composite page = new Composite(parent, 0);
      GridLayout topLayout = new GridLayout();
      topLayout.numColumns = 1;
      page.setLayout(topLayout);
      // page.setLayoutData(new GridData(GridData.FILL_BOTH));

      final Label label1 = new Label(page, 0);
      label1.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
      label1.setText("V8 preview page");
      final ComparePreviewer comparePreviewer = new ComparePreviewer(page);
      comparePreviewer.setLayoutData(new GridData(GridData.FILL_BOTH));

      return new TextualDiffPageElements() {
        public Control getMainControl() {
          return page;
        }
        public CompareViewerPane getCompareViewerPane() {
          return comparePreviewer;
        }
      };
    }
  };

  private static final PageControlsFactory<V8PreviewPageElements> V8_PREVIEW_PAGE_FACTORY =
      new PageControlsFactory<V8PreviewPageElements>() {
    public V8PreviewPageElements create(Composite parent) {
      final Composite page = new Composite(parent, SWT.NONE);
      page.setLayout(new GridLayout(1, false));
      LiveEditDiffViewer.Configuration configuration =
          new LiveEditDiffViewer.Configuration() {
            public String getNewLabel() {
              return "Changed script";
            }
            public String getOldLabel() {
              return "Script at VM";
            }
            public boolean oldOnLeft() {
              return false;
            }
      };
      final LiveEditDiffViewer viewer = LiveEditDiffViewer.create(page, configuration);

      return new V8PreviewPageElements() {
        public Control getMainControl() {
          return page;
        }
        public LiveEditDiffViewer getPreviewViewer() {
          return viewer;
        }
      };
    }
  };

  private static final PageControlsFactory<PageElements> MULTIPLE_VM_STUB_PAGE_FACTORY =
      new PageControlsFactory<PageElements>() {
    public PageElements create(Composite parent) {
      final Label label = new Label(parent, 0);

      return new PageElements() {
        public Control getMainControl() {
          return label;
        }
      };
    }
  };

  /**
   * A very simple text compare viewer.
   */
  private static class ComparePreviewer extends CompareViewerSwitchingPane {
    private CompareConfiguration configuration;

    public ComparePreviewer(Composite parent) {
        super(parent, SWT.BORDER | SWT.FLAT, true);
        configuration = new CompareConfiguration();
        configuration.setLeftEditable(false);
        configuration.setLeftLabel("Changed script");
        configuration.setRightEditable(false);
        configuration.setRightLabel("Script on VM");
        Dialog.applyDialogFont(this);
    }
    @Override
    protected Viewer getViewer(Viewer oldViewer, Object input) {
      if (input instanceof ICompareInput == false) {
        return null;
      }
      ICompareInput compareInput = (ICompareInput) input;
      return CompareUI.findContentViewer(oldViewer, compareInput, this, configuration);
    }
  }

  interface FinisherDelegate {
    LiveEditResultDialog.Input run(IProgressMonitor monitor);
  }

  static class FinisherImpl implements WizardFinisher {
    private final FinisherDelegate delegate;
    FinisherImpl(FinisherDelegate delegate) {
      this.delegate = delegate;
    }
    public boolean performFinish(IWizard wizard, IProgressMonitor monitor) {
      LiveEditResultDialog.Input dialogInput = delegate.run(monitor);
      LiveEditResultDialog dialog = new LiveEditResultDialog(wizard.getContainer().getShell(), dialogInput);
      dialog.open();
      return true;
    }
  }

  /**
   * A callback that gets called when user presses 'finish' and a single VM is selected.
   */
  static class SingleVmFinisher implements FinisherDelegate {
    private final ScriptTargetMapping filePair;

    public SingleVmFinisher(ScriptTargetMapping filePair) {
      this.filePair = filePair;
    }
    public Input run(IProgressMonitor monitor) {
      return performSingleVmUpdate(filePair, monitor);
    }
  }

  /**
   * A callback that gets called when user presses 'finish' and several VMs are selected.
   * It performs update and open result dialog window.
   */
  static class MultipleVmFinisher implements FinisherDelegate {
    private final List<ScriptTargetMapping> targets;
    public MultipleVmFinisher(List<ScriptTargetMapping> targets) {
      this.targets = targets;
    }

    /**
     * Performs updates for each VM and opens dialog window with composite result.
     */
    public Input run(IProgressMonitor monitor) {
      monitor.beginTask(null, targets.size());
      final List<LiveEditResultDialog.SingleInput> results = new ArrayList<LiveEditResultDialog.SingleInput>();
      for (ScriptTargetMapping filePair : targets) {
        LiveEditResultDialog.SingleInput dialogInput =
            performSingleVmUpdate(filePair, new SubProgressMonitor(monitor, 1));
        results.add(dialogInput);
      }
      monitor.done();

      final LiveEditResultDialog.MultipleResult multipleResult =
          new LiveEditResultDialog.MultipleResult() {
            public List<? extends SingleInput> getList() {
              return results;
            }
      };

      return new LiveEditResultDialog.Input() {
        public <RES> RES accept(LiveEditResultDialog.InputVisitor<RES> visitor) {
          return visitor.visitMultipleResult(multipleResult);
        }
      };
    }
  }

  /**
   * Performs update to a VM and returns result in form of dialog window input.
   */
  private static LiveEditResultDialog.SingleInput performSingleVmUpdate(final ScriptTargetMapping filePair, IProgressMonitor monitor) {
    final LiveEditResultDialog.SingleInput [] input = { null };

    UpdatableScript.UpdateCallback callback = new UpdatableScript.UpdateCallback() {
      public void failure(String message) {
        String text = "Failure: " + message;
        input[0] = createTextInput(text);
      }
      public void success(Object report, final UpdatableScript.ChangeDescription changeDescription) {
        if (changeDescription == null) {
          input[0] = createTextInput("Empty change");
        } else {
          final String oldScriptName = changeDescription.getCreatedScriptName();
          final LiveEditResultDialog.OldScriptData oldScriptData;
          if (oldScriptName == null) {
            oldScriptData = null;
          } else {
            final LiveEditDiffViewer.Input previewInput =
                PushResultParser.createViewerInput(changeDescription, filePair, false);
            oldScriptData = new LiveEditResultDialog.OldScriptData() {
              public LiveEditDiffViewer.Input getScriptStructure() {
                return previewInput;
              }
              public String getOldScriptName() {
                return oldScriptName;
              }
            };
          }
          final LiveEditResultDialog.SuccessResult successResult =
              new LiveEditResultDialog.SuccessResult() {
                public LiveEditResultDialog.OldScriptData getOldScriptData() {
                  return oldScriptData;
                }
                public boolean hasDroppedFrames() {
                  return changeDescription.isStackModified();
                }
          };
          input[0] = new LiveEditResultDialog.SingleInput() {
            public <RES> RES accept(LiveEditResultDialog.InputVisitor<RES> visitor) {
              return acceptSingle(visitor);
            }
            public <RES> RES acceptSingle(LiveEditResultDialog.SingleInputVisitor<RES> visitor) {
              return visitor.visitSuccess(successResult);
            }
            public ScriptTargetMapping getFilePair() {
              return filePair;
            }
          };
        }
      }
      private LiveEditResultDialog.SingleInput createTextInput(final String text) {
        return new LiveEditResultDialog.SingleInput() {
          public <RES> RES accept(LiveEditResultDialog.InputVisitor<RES> visitor) {
            return acceptSingle(visitor);
          }
          public <RES> RES acceptSingle(LiveEditResultDialog.SingleInputVisitor<RES> visitor) {
            return visitor.visitErrorMessage(text);
          }
          public ScriptTargetMapping getFilePair() {
            return filePair;
          }
        };
      }
    };

    CallbackSemaphore syncCallback = new CallbackSemaphore();
    PushChangesAction.execute(filePair, callback, syncCallback, false);
    syncCallback.acquireDefault();

    monitor.done();

    return input[0];
  }
}
