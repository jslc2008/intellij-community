/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.vcs.log.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.vcs.log.VcsLogProvider;
import com.intellij.vcs.log.VcsLogRefresher;
import com.intellij.vcs.log.data.VcsLogDataManager;
import com.intellij.vcs.log.data.VcsLogFiltererImpl;
import com.intellij.vcs.log.data.VcsLogTabsProperties;
import com.intellij.vcs.log.data.VcsLogUiProperties;
import com.intellij.vcs.log.graph.PermanentGraph;
import com.intellij.vcs.log.ui.VcsLogColorManagerImpl;
import com.intellij.vcs.log.ui.VcsLogPanel;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;
import java.util.Map;

public class VcsLogManager implements Disposable {
  public static final ExtensionPointName<VcsLogProvider> LOG_PROVIDER_EP = ExtensionPointName.create("com.intellij.logProvider");
  private static final Logger LOG = Logger.getInstance(VcsLogManager.class);

  @NotNull private final Project myProject;
  @NotNull private final VcsLogTabsProperties myUiProperties;
  @Nullable private final Runnable myRecreateMainLogHandler;

  private volatile VcsLogUiImpl myUi;
  @NotNull private final VcsLogDataManager myDataManager;
  @NotNull private final VcsLogColorManagerImpl myColorManager;
  @NotNull private final VcsLogTabsWatcher myTabsLogRefresher;
  @NotNull private final PostponableLogRefresher myPostponableRefresher;

  public VcsLogManager(@NotNull Project project, @NotNull VcsLogTabsProperties uiProperties, @NotNull Collection<VcsRoot> roots) {
    this(project, uiProperties, roots, null);
  }

  public VcsLogManager(@NotNull Project project,
                       @NotNull VcsLogTabsProperties uiProperties,
                       @NotNull Collection<VcsRoot> roots,
                       @Nullable Runnable recreateHandler) {
    myProject = project;
    myUiProperties = uiProperties;
    myRecreateMainLogHandler = recreateHandler;

    Map<VirtualFile, VcsLogProvider> logProviders = findLogProviders(roots, myProject);
    myDataManager = new VcsLogDataManager(myProject, logProviders, new MyFatalErrorsConsumer());
    myPostponableRefresher = new PostponableLogRefresher(myDataManager);
    myTabsLogRefresher = new VcsLogTabsWatcher(myProject, myPostponableRefresher, myDataManager);

    refreshLogOnVcsEvents(logProviders, myPostponableRefresher, myDataManager);

    myColorManager = new VcsLogColorManagerImpl(logProviders.keySet());

    myDataManager.refreshCompletely();

    Disposer.register(project, this);
  }

  @NotNull
  public VcsLogDataManager getDataManager() {
    return myDataManager;
  }

  public void watchTab(@NotNull String contentTabName, @NotNull VcsLogUiImpl logUi) {
    Disposer.register(logUi, myTabsLogRefresher.addTabToWatch(contentTabName, logUi.getFilterer()));
  }

  private void watch(@NotNull VcsLogUiImpl ui) {
    Disposer.register(ui, myPostponableRefresher.addLogWindow(ui.getFilterer()));
  }

  @NotNull
  public JComponent initMainLog(@Nullable String contentTabName) {
    myUi = createLog(VcsLogTabsProperties.MAIN_LOG_ID);
    if (contentTabName != null) {
      watchTab(contentTabName, myUi);
    }
    else {
      watch(myUi);
    }
    myUi.requestFocus();
    return new VcsLogPanel(this, myUi);
  }

  @NotNull
  public VcsLogUiImpl createLog(@NotNull String logId) {
    VcsLogUiProperties properties = myUiProperties.createProperties(logId);
    VcsLogFiltererImpl filterer =
      new VcsLogFiltererImpl(myProject, myDataManager, PermanentGraph.SortType.values()[properties.getBekSortType()]);
    return new VcsLogUiImpl(myDataManager, myProject, myColorManager, properties, filterer);
  }

  private static void refreshLogOnVcsEvents(@NotNull Map<VirtualFile, VcsLogProvider> logProviders,
                                            @NotNull VcsLogRefresher refresher,
                                            @NotNull Disposable disposableParent) {
    MultiMap<VcsLogProvider, VirtualFile> providers2roots = MultiMap.create();
    for (Map.Entry<VirtualFile, VcsLogProvider> entry : logProviders.entrySet()) {
      providers2roots.putValue(entry.getValue(), entry.getKey());
    }

    for (Map.Entry<VcsLogProvider, Collection<VirtualFile>> entry : providers2roots.entrySet()) {
      Disposable disposable = entry.getKey().subscribeToRootRefreshEvents(entry.getValue(), refresher);
      Disposer.register(disposableParent, disposable);
    }
  }

  @NotNull
  public static Map<VirtualFile, VcsLogProvider> findLogProviders(@NotNull Collection<VcsRoot> roots, @NotNull Project project) {
    Map<VirtualFile, VcsLogProvider> logProviders = ContainerUtil.newHashMap();
    VcsLogProvider[] allLogProviders = Extensions.getExtensions(LOG_PROVIDER_EP, project);
    for (VcsRoot root : roots) {
      AbstractVcs vcs = root.getVcs();
      VirtualFile path = root.getPath();
      if (vcs == null || path == null) {
        LOG.error("Skipping invalid VCS root: " + root);
        continue;
      }

      for (VcsLogProvider provider : allLogProviders) {
        if (provider.getSupportedVcs().equals(vcs.getKeyInstanceMethod())) {
          logProviders.put(path, provider);
          break;
        }
      }
    }
    return logProviders;
  }

  /**
   * The instance of the {@link VcsLogUiImpl} or null if the log was not initialized yet.
   */
  @Nullable
  public VcsLogUiImpl getMainLogUi() {
    return myUi;
  }

  public void disposeLog() {
    Disposer.dispose(myDataManager);
    myUi = null;
  }

  /*
  * Use VcsLogProjectManager to get main log.
  * */
  @Nullable
  @Deprecated
  public static VcsLogManager getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, VcsLogProjectManager.class).getLogManager();
  }

  @Override
  public void dispose() {
    disposeLog();
  }

  private class MyFatalErrorsConsumer implements Consumer<Exception> {
    private boolean myIsBroken = false;

    @Override
    public void consume(@NotNull final Exception e) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          if (!myIsBroken) {
            myIsBroken = true;
            processErrorFirstTime(e);
          }
          else {
            LOG.debug(e);
          }
        }
      });
    }

    protected void processErrorFirstTime(@NotNull Exception e) {
      if (myRecreateMainLogHandler != null) {
        LOG.info(e);
        VcsBalloonProblemNotifier.showOverChangesView(myProject, "Fatal error, VCS Log recreated: " + e.getMessage(), MessageType.ERROR);
        myRecreateMainLogHandler.run();
      }
      else {
        LOG.error(e);
      }
    }
  }
}
