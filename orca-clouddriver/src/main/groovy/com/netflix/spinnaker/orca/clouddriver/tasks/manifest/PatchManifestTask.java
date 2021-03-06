/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.manifest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.netflix.spinnaker.kork.annotations.NonnullByDefault;
import com.netflix.spinnaker.orca.api.pipeline.Task;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.KatoService;
import com.netflix.spinnaker.orca.clouddriver.model.TaskId;
import com.netflix.spinnaker.orca.clouddriver.tasks.AbstractCloudProviderAwareTask;
import com.netflix.spinnaker.orca.clouddriver.tasks.manifest.PatchManifestContext.MergeStrategy;
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@NonnullByDefault
public final class PatchManifestTask extends AbstractCloudProviderAwareTask implements Task {
  public static final String TASK_NAME = "patchManifest";
  private static final ObjectMapper objectMapper = OrcaObjectMapper.getInstance();

  private final KatoService katoService;

  @Autowired
  public PatchManifestTask(KatoService katoService) {
    this.katoService = katoService;
  }

  @Nonnull
  @Override
  public TaskResult execute(@Nonnull StageExecution stage) {
    ImmutableMap<String, Map> operation = getOperation(stage);
    TaskId taskId = executeOperation(stage, operation);
    ImmutableMap<String, Object> outputs = getOutputs(stage, taskId);
    return TaskResult.builder(ExecutionStatus.SUCCEEDED).context(outputs).build();
  }

  private ImmutableMap<String, Map> getOperation(StageExecution stage) {
    PatchManifestContext context = stage.mapTo(PatchManifestContext.class);
    MergeStrategy mergeStrategy = context.getOptions().getMergeStrategy();
    ImmutableList<Map<String, Object>> patchBody =
        objectMapper.convertValue(
            stage.getContext().get("manifests"),
            new TypeReference<ImmutableList<Map<String, Object>>>() {});

    if (patchBody.isEmpty()) {
      throw new IllegalArgumentException(
          "The Patch (Manifest) stage requires a valid patch body. Please add a patch body inline or with an artifact.");
    }
    if (mergeStrategy != MergeStrategy.JSON && patchBody.size() > 1) {
      throw new IllegalArgumentException(
          "Only one patch object is valid when patching with `strategic` and `merge` patch strategies.");
    }

    Map<String, Object> task = new HashMap<>(stage.getContext());
    task.put("source", "text");
    task.put("patchBody", mergeStrategy == MergeStrategy.JSON ? patchBody : patchBody.get(0));

    return ImmutableMap.of(TASK_NAME, task);
  }

  private TaskId executeOperation(StageExecution stage, ImmutableMap<String, Map> operation) {
    return katoService.requestOperations(getCloudProvider(stage), ImmutableList.of(operation));
  }

  private ImmutableMap<String, Object> getOutputs(StageExecution stage, TaskId taskId) {
    return new ImmutableMap.Builder<String, Object>()
        .put("kato.result.expected", true)
        .put("kato.last.task.id", taskId)
        .put("deploy.account.name", Objects.requireNonNull(getCredentials(stage)))
        .build();
  }
}
