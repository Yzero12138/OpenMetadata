package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.RuntimeOptions;
import org.openmetadata.service.integration.IntegrationRuntimeOptions.Resolved;

class IntegrationRuntimeOptionsTest {
  @Test
  void resolvesExplicitModeProfilesWithoutPersistingDefaults() {
    var task = IntegrationValidatorTest.validInput();
    assertEquals(new Resolved(1000, 8192, 10000, 60, 900), IntegrationRuntimeOptions.resolve(task));
    assertNull(task.runtime);
    task.mode = "CDC";
    assertEquals(new Resolved(1000, 8192, 1000, 30, 300), IntegrationRuntimeOptions.resolve(task));
    task = IntegrationValidatorTest.sqlInput();
    assertEquals(new Resolved(1000, null, 100, 60, 900), IntegrationRuntimeOptions.resolve(task));
    assertNull(task.runtime);
  }

  @Test
  void partialOverridesUseDefaultsAndTaskCopiesShareOnlyTheImmutableValue() {
    var input = IntegrationValidatorTest.validInput();
    input.runtime = new RuntimeOptions(2000, null, 5000, null, 1200);
    var task = IntegrationModels.IntegrationTask.from(input);
    var resolved = IntegrationRuntimeOptions.resolve(task);
    assertEquals(new Resolved(2000, 8192, 5000, 60, 1200), resolved);
    assertSame(input.runtime, task.runtime);
    assertNull(task.runtime.splitSize());
    assertNull(task.runtime.checkpointIntervalSeconds());
    var replacement = IntegrationModels.IntegrationTask.from(IntegrationValidatorTest.validInput());
    replacement.replaceInput(task);
    assertSame(task.runtime, replacement.runtime);
    assertEquals(
        task.runtime,
        IntegrationModels.deserialize(IntegrationModels.serialize(replacement)).runtime);
    input.runtime = new RuntimeOptions(null, null, null, null, null);
    assertEquals(
        new Resolved(1000, 8192, 10000, 60, 900), IntegrationRuntimeOptions.resolve(input));
    assertEquals(new Resolved(2000, 8192, 5000, 60, 1200), IntegrationRuntimeOptions.resolve(task));
  }

  @Test
  void resolverRejectsInvalidModesAndSettingsBeforeProducingAProfile() {
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(null));
    var task = IntegrationValidatorTest.validInput();
    for (String mode : List.of("full", "INVALID", "")) {
      task.mode = mode;
      assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
    }
    task.mode = null;
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
    task.mode = "FULL";
    task.runtime = new RuntimeOptions(0, null, null, null, null);
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
    task.runtime = new RuntimeOptions(null, null, null, 300, 599);
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
    task.mode = "SQL_T1";
    task.runtime = new RuntimeOptions(null, 8192, null, null, null);
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
  }

  @Test
  void legacyProfileStaysSeparateFromNewCdcDefaults() {
    var task = IntegrationValidatorTest.validInput();
    task.mode = "CDC";
    assertEquals(new Resolved(1000, null, 100, 5, 60), IntegrationRuntimeOptions.legacyCdc());
    assertEquals(new Resolved(1000, 8192, 1000, 30, 300), IntegrationRuntimeOptions.resolve(task));
    task.runtime = new RuntimeOptions(null, null, null, 5, 60);
    assertThrows(IntegrationException.class, () -> IntegrationRuntimeOptions.resolve(task));
    assertEquals(new Resolved(1000, null, 100, 5, 60), IntegrationRuntimeOptions.legacyCdc());
  }
}
