/*
 * Copyright 2015 the original author or authors.
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
package org.gradle.api.internal.resolve;

import com.google.common.base.Predicate;
import org.gradle.api.UnknownProjectException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.artifacts.component.LibraryComponentSelector;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.internal.component.local.model.*;
import org.gradle.internal.component.model.*;
import org.gradle.internal.component.model.ComponentUsage;
import org.gradle.internal.resolve.ArtifactResolveException;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.resolver.ArtifactResolver;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.language.base.internal.model.VariantAxisCompatibilityFactory;
import org.gradle.language.base.internal.model.VariantsMetaData;
import org.gradle.language.base.internal.resolve.LibraryResolveException;
import org.gradle.model.ModelMap;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.platform.base.BinarySpec;
import org.gradle.platform.base.ComponentSpecContainer;
import org.gradle.platform.base.LibrarySpec;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class LocalLibraryDependencyResolver<T extends BinarySpec> implements DependencyToComponentIdResolver, ComponentMetaDataResolver, ArtifactResolver {
    private final ProjectModelResolver projectModelResolver;
    private final VariantsMetaData variantsMetaData;
    private final VariantsMatcher matcher;
    private final LibraryResolutionErrorMessageBuilder errorMessageBuilder;
    private final LocalLibraryMetaDataAdapter libraryMetaDataAdapter;
    private final Class<? extends BinarySpec> binaryType;
    private final Predicate<LibrarySpec> binarySpecPredicate;

    public LocalLibraryDependencyResolver(
            Class<T> binarySpecType,
            ProjectModelResolver projectModelResolver,
            List<VariantAxisCompatibilityFactory> selectorFactories,
            VariantsMetaData variantsMetaData,
            ModelSchemaStore schemaStore,
            LocalLibraryMetaDataAdapter libraryMetaDataAdapter,
            LibraryResolutionErrorMessageBuilder errorMessageBuilder) {
        this.projectModelResolver = projectModelResolver;
        this.libraryMetaDataAdapter = libraryMetaDataAdapter;
        this.matcher = new VariantsMatcher(selectorFactories, binarySpecType, schemaStore);
        this.errorMessageBuilder = errorMessageBuilder;
        this.variantsMetaData = variantsMetaData;
        this.binaryType = binarySpecType;
        this.binarySpecPredicate = new Predicate<LibrarySpec>() {
            @Override
            public boolean apply(LibrarySpec input) {
                return !input.getBinaries().withType(binaryType).isEmpty();
            }
        };
    }

    @Override
    public void resolve(DependencyMetaData dependency, final BuildableComponentIdResolveResult result) {
        if (dependency.getSelector() instanceof LibraryComponentSelector) {
            LibraryComponentSelector selector = (LibraryComponentSelector) dependency.getSelector();
            final String selectorProjectPath = selector.getProjectPath();
            final String libraryName = selector.getLibraryName();
            LibraryResolutionErrorMessageBuilder.LibraryResolutionResult resolutionResult = doResolve(selectorProjectPath, libraryName);
            LibrarySpec selectedLibrary = resolutionResult.getSelectedLibrary();
            if (selectedLibrary != null) {
                Collection<BinarySpec> allBinaries = selectedLibrary.getBinaries().values();
                Collection<? extends BinarySpec> compatibleBinaries = matcher.filterBinaries(variantsMetaData, allBinaries);
                if (!allBinaries.isEmpty() && compatibleBinaries.isEmpty()) {
                    // no compatible variant found
                    result.failed(new ModuleVersionResolveException(selector, errorMessageBuilder.noCompatibleVariantErrorMessage(libraryName, allBinaries)));
                } else if (compatibleBinaries.size() > 1) {
                    result.failed(new ModuleVersionResolveException(selector, errorMessageBuilder.multipleCompatibleVariantsErrorMessage(libraryName, compatibleBinaries)));
                } else {
                    BinarySpec selectedBinary = compatibleBinaries.iterator().next();
                    LocalComponentMetaData metaData = libraryMetaDataAdapter.createLocalComponentMetaData(selectedBinary, selectorProjectPath);
                    result.resolved(metaData);
                }
            }
            if (!result.hasResult()) {
                String message = resolutionResult.toResolutionErrorMessage(binaryType, selector);
                ModuleVersionResolveException failure = new ModuleVersionResolveException(selector, new LibraryResolveException(message));
                result.failed(failure);
            }
        }
    }

    // This method tries to extract "dependency configuration" from the dependency metadata, knowing that in our case
    // there should be only one target configuration. However for backwards compatibility with Ivy we cannot simplify the API yet.
    private String findTargetConfigurationName(DependencyMetaData dependency) {
        String targetConfigurationName = UsageKind.API.getConfigurationName();
        String[] moduleConfigurations = dependency.getModuleConfigurations();
        if (moduleConfigurations==null) {
            return targetConfigurationName;
        }
        for (String moduleConfig : moduleConfigurations) {
            String[] dependencyConfigurations = dependency.getDependencyConfigurations(moduleConfig, null);
            if (dependencyConfigurations.length==1) {
                targetConfigurationName = dependencyConfigurations[0];
                break;
            }
        }
        return targetConfigurationName;
    }

    private LibraryResolutionErrorMessageBuilder.LibraryResolutionResult doResolve(String projectPath,
                                                                                   String libraryName) {
        try {
            ModelRegistry projectModel = projectModelResolver.resolveProjectModel(projectPath);
            ComponentSpecContainer components = projectModel.find("components", ComponentSpecContainer.class);
            if (components != null) {
                ModelMap<? extends LibrarySpec> libraries = components.withType(LibrarySpec.class);
                return LibraryResolutionErrorMessageBuilder.LibraryResolutionResult.of(libraries.values(), libraryName, binarySpecPredicate);
            } else {
                return LibraryResolutionErrorMessageBuilder.LibraryResolutionResult.emptyResolutionResult();
            }
        } catch (UnknownProjectException e) {
            return LibraryResolutionErrorMessageBuilder.LibraryResolutionResult.projectNotFound();
        }
    }

    @Override
    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        if (isLibrary(identifier)) {
            throw new RuntimeException("Not yet implemented");
        }
    }

    private boolean isLibrary(ComponentIdentifier identifier) {
        return identifier instanceof LibraryBinaryIdentifier;
    }

    @Override
    public void resolveModuleArtifacts(ComponentResolveMetaData component, ComponentUsage usage, BuildableArtifactSetResolveResult result) {
        ComponentIdentifier componentId = component.getComponentId();
        if (isLibrary(componentId)) {
            ConfigurationMetaData configuration = component.getConfiguration(usage.getConfigurationName());
            if (configuration != null) {
                Set<ComponentArtifactMetaData> artifacts = configuration.getArtifacts();
                result.resolved(artifacts);
            }
            if (!result.hasResult()) {
                result.failed(new ArtifactResolveException(String.format("Unable to resolve artifact for %s", componentId)));
            }
        }
    }

    @Override
    public void resolveModuleArtifacts(ComponentResolveMetaData component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        if (isLibrary(component.getComponentId())) {
            result.resolved(Collections.<ComponentArtifactMetaData>emptyList());
        }
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetaData artifact, ModuleSource moduleSource, BuildableArtifactResolveResult result) {
        if (isLibrary(artifact.getComponentId())) {
            if (artifact instanceof PublishArtifactLocalArtifactMetaData) {
                result.resolved(((PublishArtifactLocalArtifactMetaData) artifact).getFile());
            } else {
                result.failed(new ArtifactResolveException("Unsupported artifact metadata type: " + artifact));
            }
        }
    }
}
