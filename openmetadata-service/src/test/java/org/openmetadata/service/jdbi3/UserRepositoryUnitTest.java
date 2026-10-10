package org.openmetadata.service.jdbi3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.ChangeDescription;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.security.AuthServeletHandlerRegistry;
import org.openmetadata.service.security.NoopAuthServeletHandler;
import org.openmetadata.service.security.integrate.IntegrateSsoConfig;

class UserRepositoryUnitTest {

  @Test
  void test_taskCleanupRetryDelayBacksOffExponentially() {
    assertEquals(100L, UserRepository.getTaskCleanupRetryDelayMillis(1));
    assertEquals(200L, UserRepository.getTaskCleanupRetryDelayMillis(2));
    assertEquals(400L, UserRepository.getTaskCleanupRetryDelayMillis(3));
  }

  @Test
  void test_taskCleanupRetryDelayIsCapped() {
    assertEquals(1000L, UserRepository.getTaskCleanupRetryDelayMillis(5));
    assertEquals(1000L, UserRepository.getTaskCleanupRetryDelayMillis(8));
  }

  @Test
  void legacyInternalEmailMigratesThroughTheRealPatchUpdater() {
    assertLegacyEmailMigrates(EntityRepository.Operation.PATCH);
  }

  @Test
  void legacyInternalEmailMigratesDuringColdBootstrapBeforeHandlerRegistration() {
    var previousHandler = AuthServeletHandlerRegistry.getHandler();
    try {
      AuthServeletHandlerRegistry.setHandler(NoopAuthServeletHandler.getInstance());
      assertLegacyEmailMigrates(EntityRepository.Operation.PUT);
    } finally {
      AuthServeletHandlerRegistry.setHandler(previousHandler);
    }
  }

  private void assertLegacyEmailMigrates(EntityRepository.Operation operation) {
    try (var entities = mockStatic(Entity.class)) {
      var repository = repository(entities, true);
      User original = legacyUser();
      User updated = JsonUtils.deepCopy(original, User.class).withEmail(canonicalEmail());
      var updater = repository.new UserUpdater(original, updated, operation);
      updater.changeDescription = new ChangeDescription();
      updater.setPatchedFields(Set.of("email"));

      updater.entitySpecificUpdate(false);

      assertEquals(canonicalEmail(), updated.getEmail());
      assertEquals(original.getId(), updated.getId());
      assertEquals(original.getName(), updated.getName());
      assertEquals(original.getIsAdmin(), updated.getIsAdmin());
      assertEquals(original.getRoles(), updated.getRoles());
      assertEquals(original.getTeams(), updated.getTeams());
      assertEquals(1, updater.changeDescription.getFieldsUpdated().size());
      assertEquals("email", updater.changeDescription.getFieldsUpdated().getFirst().getName());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "disabled",
        "bot",
        "deleted",
        "renamed",
        "different-id",
        "custom-email",
        "wrong-domain",
        "untrusted-actor",
        "new-bot",
        "new-deleted"
      })
  void preservesNativeEmailImmutabilityOutsideTheExactMigration(String scenario) {
    try (var entities = mockStatic(Entity.class)) {
      var repository = repository(entities, !"disabled".equals(scenario));
      User original = legacyUser();
      User updated = JsonUtils.deepCopy(original, User.class).withEmail(canonicalEmail());
      switch (scenario) {
        case "bot" -> original.setIsBot(true);
        case "deleted" -> original.setDeleted(true);
        case "renamed" -> updated.setName("different-user");
        case "different-id" -> updated.setId(UUID.randomUUID());
        case "custom-email" -> original.setEmail("custom@integrate.invalid");
        case "wrong-domain" -> updated.setEmail("a".repeat(64) + "@other.invalid");
        case "untrusted-actor" -> {}
        case "new-bot" -> updated.setIsBot(true);
        case "new-deleted" -> updated.setDeleted(true);
        default -> {}
      }
      var updater = repository.new UserUpdater(original, updated, EntityRepository.Operation.PATCH);
      if ("untrusted-actor".equals(scenario)) {
        updated.setUpdatedBy("employee");
      }
      updater.changeDescription = new ChangeDescription();
      updater.setPatchedFields(Set.of("email"));

      updater.entitySpecificUpdate(false);

      assertEquals(original.getEmail(), updated.getEmail());
      assertTrue(updater.changeDescription.getFieldsUpdated().isEmpty());
    }
  }

  private UserRepository repository(MockedStatic<Entity> entities, boolean enabled) {
    entities.when(Entity::getCollectionDAO).thenReturn(mock(CollectionDAO.class));
    entities.when(() -> Entity.getEntityFields(User.class)).thenCallRealMethod();
    entities.when(() -> Entity.getEntityClassFromType(Entity.USER)).thenReturn(User.class);
    IntegrateSsoConfig config =
        IntegrateSsoConfig.fromEnvironment(
            enabled
                ? Map.of(
                    "INTEGRATE_SSO_ENABLED", "true",
                    "INTEGRATE_SSO_ISSUER", "https://integrate.example.hospital",
                    "INTEGRATE_SSO_TARGET_ORIGIN", "https://metadata.example.hospital",
                    "INTEGRATE_SSO_CLIENT_ID", "hospital-metadata",
                    "INTEGRATE_SSO_CLIENT_SECRET", "s".repeat(40))
                : Map.of());
    return new UserRepository(config);
  }

  private User legacyUser() {
    String name = "integrate_" + "a".repeat(64);
    return new User()
        .withId(UUID.randomUUID())
        .withName(name)
        .withFullyQualifiedName(name)
        .withEmail(name + "@integrate.invalid")
        .withUpdatedBy("admin")
        .withIsAdmin(true)
        .withIsBot(false)
        .withDeleted(false)
        .withRoles(List.of(new EntityReference().withId(UUID.randomUUID()).withType("role")))
        .withTeams(List.of(new EntityReference().withId(UUID.randomUUID()).withType("team")));
  }

  private String canonicalEmail() {
    return "a".repeat(64) + "@integrate.invalid";
  }
}
