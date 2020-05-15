/**
 * Copyright (c) 2020, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.salesforce.bazel.eclipse.runtime.impl;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.salesforce.bazel.eclipse.abstractions.OutputStreamObserver;
import com.salesforce.bazel.eclipse.builder.BazelMarkerSupport;
import com.salesforce.bazel.eclipse.logging.LogHelper;
import com.salesforce.bazel.eclipse.model.BazelBuildError;
import com.salesforce.bazel.eclipse.model.BazelLabel;
import com.salesforce.bazel.eclipse.model.BazelOutputParser;

public class BazelErrorStreamObserver implements OutputStreamObserver {
    private IProgressMonitor monitor;
    private Map<BazelLabel, IProject> labelToProject;
    private IProject rootProject;
    private static final BazelOutputParser outputParser = new BazelOutputParser();
    private static final LogHelper LOG = LogHelper.log(BazelErrorStreamObserver.class);
    static final String UNKNOWN_PROJECT_ERROR_MSG_PREFIX = "ERROR IN UNKNOWN PROJECT: ";
    
    public BazelErrorStreamObserver(final IProgressMonitor monitor, final Map<BazelLabel, IProject> labelToProject, Optional<IProject> rootProject) {
        this.monitor = monitor;
        this.labelToProject = labelToProject;
        this.monitor = monitor;
        if (rootProject.isPresent()) {
            this.rootProject = rootProject.get();
        } else {
            this.rootProject = null;
        }
    }
    
    @Override
    public void update(String error) {
        List<BazelBuildError> bazelBuildErrors = outputParser.getErrorBazelMarkerDetails(Arrays.asList(error));
        Multimap<IProject, BazelBuildError> projectToErrors = assignErrorsToOwningProject(bazelBuildErrors, this.labelToProject, this.rootProject);
        for (IProject project : projectToErrors.keySet()) {
            BazelMarkerSupport.publishToProblemsView(project, projectToErrors.get(project), monitor);
        }
    }
    
    // maps the specified errors to the project instances they belong to, and returns that mapping
    static Multimap<IProject, BazelBuildError> assignErrorsToOwningProject(List<BazelBuildError> errors, Map<BazelLabel, IProject> labelToProject, IProject rootProject) {
        Multimap<IProject, BazelBuildError> projectToErrors = HashMultimap.create();
        List<BazelBuildError> remainingErrors = new ArrayList<>(errors);
        for (BazelBuildError error : errors) {
            BazelLabel owningLabel = error.getOwningLabel(labelToProject.keySet());
            if (owningLabel != null) {
                IProject project = labelToProject.get(owningLabel);
                projectToErrors.put(project, error.toErrorWithRelativizedResourcePath(owningLabel));
                remainingErrors.remove(error);
            }
        }
        if (!remainingErrors.isEmpty()) {
            if (rootProject != null) {
                projectToErrors.putAll(rootProject, remainingErrors.stream()
                        .map(e -> e.toGenericWorkspaceLevelError(UNKNOWN_PROJECT_ERROR_MSG_PREFIX))
                        .collect(Collectors.toList()));
            } else {
                // getting here is a bug - at least log the errors we didn't assign to any project
                for (BazelBuildError error : remainingErrors) {
                    LOG.error("Unhandled error: " + error);
                }
            }
        }
        return projectToErrors;
    }
}
