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

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier;
import org.gradle.api.internal.composite.CompositeBuildContext;
import org.gradle.api.specs.Spec;
import org.gradle.initialization.NestedBuildFactory;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.util.CollectionUtils;
import org.gradle.util.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultIncludedBuildRegistry implements BuildStateRegistry, Stoppable {
    private final IncludedBuildFactory includedBuildFactory;
    private final DefaultProjectPathRegistry projectRegistry;
    private final IncludedBuildDependencySubstitutionsBuilder dependencySubstitutionsBuilder;

    // TODO: Locking around this state
    // TODO: use some kind of build identifier as the key
    private final Map<File, IncludedBuildState> includedBuilds = Maps.newLinkedHashMap();

    public DefaultIncludedBuildRegistry(IncludedBuildFactory includedBuildFactory, DefaultProjectPathRegistry projectRegistry, IncludedBuildDependencySubstitutionsBuilder dependencySubstitutionsBuilder, CompositeBuildContext compositeBuildContext) {
        this.includedBuildFactory = includedBuildFactory;
        this.projectRegistry = projectRegistry;
        this.dependencySubstitutionsBuilder = dependencySubstitutionsBuilder;
        compositeBuildContext.setIncludedBuildRegistry(this);
    }

    public boolean hasIncludedBuilds() {
        return !includedBuilds.isEmpty();
    }

    @Override
    public Collection<IncludedBuildState> getIncludedBuilds() {
        return includedBuilds.values();
    }

    @Override
    public IncludedBuildState addExplicitBuild(BuildDefinition buildDefinition, NestedBuildFactory nestedBuildFactory) {
        return registerBuild(buildDefinition, false, nestedBuildFactory);
    }

    @Override
    public IncludedBuildState getBuild(final BuildIdentifier buildIdentifier) {
        return CollectionUtils.findFirst(includedBuilds.values(), new Spec<IncludedBuildState>() {
            @Override
            public boolean isSatisfiedBy(IncludedBuildState includedBuild) {
                return includedBuild.getName().equals(buildIdentifier.getName());
            }
        });
    }

    @Override
    public void validateExplicitIncludedBuilds(SettingsInternal settings) {
        validateIncludedBuilds(settings);

        registerRootBuildProjects(settings);
        Collection<IncludedBuildState> includedBuilds = getIncludedBuilds();
        List<IncludedBuild> modelElements = new ArrayList<IncludedBuild>(includedBuilds.size());
        for (IncludedBuildState includedBuild : includedBuilds) {
            modelElements.add(includedBuild.getModel());
        }
        // Set the only visible included builds from the root build
        settings.getGradle().setIncludedBuilds(modelElements);
        registerProjects(includedBuilds);
        registerSubstitutions(includedBuilds);
    }

    private void registerProjects(Collection<IncludedBuildState> includedBuilds) {
        for (IncludedBuildState includedBuild : includedBuilds) {
            projectRegistry.registerProjects(includedBuild);
        }
    }

    private void validateIncludedBuilds(SettingsInternal settings) {
        Set<String> names = Sets.newHashSet();
        for (IncludedBuildState build : includedBuilds.values()) {
            String buildName = build.getName();
            if (!names.add(buildName)) {
                throw new GradleException("Included build '" + buildName + "' is not unique in composite.");
            }
            if (settings.getRootProject().getName().equals(buildName)) {
                throw new GradleException("Included build '" + buildName + "' collides with root project name.");
            }
            if (settings.findProject(":" + buildName) != null) {
                throw new GradleException("Included build '" + buildName + "' collides with subproject of the same name.");
            }
        }
    }

    private void registerSubstitutions(Iterable<IncludedBuildState> includedBuilds) {
        for (IncludedBuildState includedBuild : includedBuilds) {
            dependencySubstitutionsBuilder.build(includedBuild);
        }
    }

    @Override
    public IncludedBuildState addImplicitBuild(BuildDefinition buildDefinition, NestedBuildFactory nestedBuildFactory) {
        // TODO: synchronization
        IncludedBuildState includedBuild = includedBuilds.get(buildDefinition.getBuildRootDir());
        if (includedBuild == null) {
            includedBuild = registerBuild(buildDefinition, true, nestedBuildFactory);
            projectRegistry.registerProjects(includedBuild);
        }
        // TODO: else, verify that the build definition is the same
        return includedBuild;
    }

    private IncludedBuildState registerBuild(BuildDefinition buildDefinition, boolean isImplicit, NestedBuildFactory nestedBuildFactory) {
        // TODO: synchronization
        IncludedBuildState includedBuild = includedBuilds.get(buildDefinition.getBuildRootDir());
        if (includedBuild == null) {
            includedBuild = includedBuildFactory.createBuild(buildDefinition, isImplicit, nestedBuildFactory);
            includedBuilds.put(buildDefinition.getBuildRootDir(), includedBuild);
        }
        // TODO: else, verify that the build definition is the same
        return includedBuild;
    }

    private void registerRootBuildProjects(final SettingsInternal settings) {
        BuildState rootBuild = new BuildState() {
            @Override
            public SettingsInternal getLoadedSettings() {
                return settings;
            }

            @Override
            public Path getIdentityPathForProject(Path path) {
                return path;
            }

            @Override
            public BuildIdentifier getBuildIdentifier() {
                return DefaultBuildIdentifier.ROOT;
            }

            @Override
            public boolean isImplicitBuild() {
                return false;
            }
        };
        projectRegistry.registerProjects(rootBuild);
    }

    @Override
    public void stop() {
        CompositeStoppable.stoppable(includedBuilds.values()).stop();
    }
}
