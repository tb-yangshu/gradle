/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.composite.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.concurrent.Stoppable;

import java.util.Map;

class DefaultIncludedBuildControllers implements Stoppable, IncludedBuildControllers {
    private final Map<BuildIdentifier, IncludedBuildController> buildControllers = Maps.newHashMap();
    private final ManagedExecutor executorService;
    private final BuildStateRegistry includedBuildRegistry;

    DefaultIncludedBuildControllers(ExecutorFactory executorFactory, BuildStateRegistry includedBuildRegistry) {
        this.includedBuildRegistry = includedBuildRegistry;
        this.executorService = executorFactory.create("included builds");
    }

    public IncludedBuildController getBuildController(BuildIdentifier buildId) {
        IncludedBuildController buildController = buildControllers.get(buildId);
        if (buildController != null) {
            return buildController;
        }

        IncludedBuildState build = includedBuildRegistry.getBuild(buildId);
        DefaultIncludedBuildController newBuildController = new DefaultIncludedBuildController(build);
        buildControllers.put(buildId, newBuildController);
        executorService.submit(newBuildController);
        return newBuildController;
    }

    @Override
    public void startTaskExecution() {
        populateTaskGraphs();
        for (IncludedBuildController buildController : buildControllers.values()) {
            buildController.startTaskExecution();
        }
    }

    private void populateTaskGraphs() {
        boolean tasksDiscovered = true;
        while (tasksDiscovered) {
            tasksDiscovered = false;
            for (IncludedBuildController buildController : ImmutableList.copyOf(buildControllers.values())) {
                if (buildController.populateTaskGraph()) {
                    tasksDiscovered = true;
                }
            }
        }
    }

    @Override
    public void stopTaskExecution() {
        for (IncludedBuildController buildController : buildControllers.values()) {
            buildController.stopTaskExecution();
        }
        buildControllers.clear();
        for (IncludedBuildState includedBuild : includedBuildRegistry.getIncludedBuilds()) {
            includedBuild.finishBuild();
        }
    }

    @Override
    public void stop() {
        CompositeStoppable.stoppable(buildControllers.values()).stop();
        executorService.stop();
    }
}
