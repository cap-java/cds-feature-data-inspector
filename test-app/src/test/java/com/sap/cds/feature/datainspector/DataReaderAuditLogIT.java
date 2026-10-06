/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.sap.cds.services.auditlog.AuditLogService;
import com.sap.cds.services.auditlog.DataAccessLogContext;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Audit-log integration tests for {@link com.sap.cds.feature.datainspector.DataReader}.
 *
 * <p>CAP Java's audit log v2 exposes an {@link AuditLogService} whose typed "logDataAccess" event
 * (event name: {@link DataAccessLogContext#CDS_NAME}) fires for every read of an element annotated
 * with {@code @PersonalData.IsPotentiallySensitive}. In this test-app the Order.address element
 * (plus Order.phoneNumber, which is hidden) is annotated as such — so reading Order rows must fire
 * the event, but reading Product rows must not.
 *
 * <p>How we intercept the events: We register a Spring-scoped {@link AuditEventRecorder} bean that
 * hooks the AuditLogService with @After on the DATA_ACCESS_LOG event and captures each fired {@link
 * DataAccessLogContext}. Tests then inspect the captured list.
 *
 * <p>This is the CAP-Java-idiomatic equivalent of the Node.js test's cds.connect.to = (name) => {
 * if (name === "audit-log") return { log: ... } } monkey-patch — but here we don't replace the
 * service, we merely observe it.
 */
@Import(DataReaderAuditLogIT.AuditRecorderConfig.class)
class DataReaderAuditLogIT extends AbstractDataInspectorIT {

  /**
   * Spring @TestConfiguration that pulls the AuditEventRecorder into the test's ApplicationContext.
   * Placing it in a static nested class keeps this test file self-contained — no separate
   * configuration class needed.
   */
  @TestConfiguration
  static class AuditRecorderConfig {
    @org.springframework.context.annotation.Bean
    AuditEventRecorder auditEventRecorder() {
      return new AuditEventRecorder();
    }
  }

  /**
   * Event handler that records every "logDataAccess" event fired on the AuditLogService during a
   * test. Registered @After the default emitter so we observe the fully-populated context
   * (DataAccessLog + Accesses).
   *
   * <p>We bind to {@link AuditLogService#DEFAULT_NAME} (== "AuditLogService") and the event name is
   * {@link DataAccessLogContext#CDS_NAME}.
   *
   * <p>Note: NOT annotated with @Component — Spring picks it up through the @Bean method in
   * AuditRecorderConfig above. Having both @Component AND @Bean would register two beans and break
   * autowiring by type.
   */
  @ServiceName(AuditLogService.DEFAULT_NAME)
  static class AuditEventRecorder implements EventHandler {

    private final List<DataAccessLogContext> events = new ArrayList<>();

    @After(event = DataAccessLogContext.CDS_NAME)
    public synchronized void onDataAccessLog(DataAccessLogContext ctx) {
      events.add(ctx);
    }

    synchronized List<DataAccessLogContext> snapshot() {
      return new ArrayList<>(events);
    }

    synchronized void reset() {
      events.clear();
    }
  }

  private AuditEventRecorder recorder;

  @BeforeEach
  void resetRecorder() {
    // Look up the bean lazily from the WebApplicationContext so we don't
    // need @Autowired (the field would be null before Spring initialises the base class).
    this.recorder = webApplicationContext.getBean(AuditEventRecorder.class);
    this.recorder.reset();
  }

  // ═════════════════════════════════════════════════════════════
  // Order (has @PersonalData.IsPotentiallySensitive on address)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data on Order emits DATA_ACCESS_LOG for each row")
  void auditLoggingForSensitiveReads() throws Exception {
    // Read all Order rows — 11 rows, each with one visible sensitive attribute
    // (address). phoneNumber is @HideFromDataInspector so it never leaves the
    // handler and does not contribute an Access entry.
    JsonNode r = getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Order'");
    assertThat(r.get("value").size()).isEqualTo(11);

    // DataReader batches all sensitive-row reads of a single request into ONE
    // AuditLogService.logDataAccess(List<Access>) call.  CAP Java's auditlog-v2
    // dispatches the DataAccessLogContext event asynchronously (via the default
    // outbox implementation), so we cannot assert immediately — we must
    // wait for the recorder to see it.  ~5s is well above the typical
    // in-process delivery time (~10ms) while still failing fast on regressions.
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () ->
                assertThat(recorder.snapshot())
                    .as("at least one DATA_ACCESS_LOG event must fire for a sensitive Order read")
                    .isNotEmpty());

    List<DataAccessLogContext> events = recorder.snapshot();

    long orderAccessCount =
        events.stream()
            .filter(c -> c.getData() != null && c.getData().getAccesses() != null)
            .flatMap(c -> c.getData().getAccesses().stream())
            .filter(
                a ->
                    a.getDataObject() != null
                        && "datainspector.test.db.Order".equals(a.getDataObject().getType()))
            .count();
    assertThat(orderAccessCount)
        .as("expected one Access record per returned Order row")
        .isEqualTo(11);

    // Every reported Access must list 'address' among its Attributes (the only
    // visible sensitive element of Order in this test-app).
    boolean everyAccessHasAddress =
        events.stream()
            .flatMap(c -> c.getData().getAccesses().stream())
            .filter(a -> "datainspector.test.db.Order".equals(a.getDataObject().getType()))
            .allMatch(
                a ->
                    a.getAttributes() != null
                        && a.getAttributes().stream().anyMatch(x -> "address".equals(x.getName())));
    assertThat(everyAccessHasAddress)
        .as("every Order access must list 'address' as a sensitive attribute")
        .isTrue();
  }

  // ═════════════════════════════════════════════════════════════
  // r_select filters out sensitive columns → no audit
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data on Order with r_select=status only — no audit events emitted")
  void noAuditWhenSensitiveNotSelected() throws Exception {
    JsonNode r =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Order'&r_select=status");
    assertThat(r.get("value").size()).isEqualTo(11);

    // Because 'address' is not in r_select, no sensitive columns are read
    // and no DATA_ACCESS_LOG event should be fired.
    //
    // Assert the negative twice: once immediately (catches synchronous
    // emissions) and once after a small grace window (catches any async
    // event from the outbox arriving late).
    assertThat(recorder.snapshot())
        .as("audit event must NOT fire when no sensitive column is selected")
        .isEmpty();
    Thread.sleep(300);
    assertThat(recorder.snapshot())
        .as("no async audit event may arrive after the response")
        .isEmpty();
  }

  // ═════════════════════════════════════════════════════════════
  // No @PersonalData annotation → no audit
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data on Product (no @PersonalData) — no audit events")
  void noAuditForNonPersonalDataEntity() throws Exception {
    JsonNode r = getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'");
    assertThat(r.get("value").size()).isEqualTo(12);

    // Product has no @PersonalData annotations → audit stays silent.
    // Assert once immediately, then again after a short grace window so a
    // late-arriving async event from the outbox can't slip through.
    assertThat(recorder.snapshot()).as("Product reads must not fire audit-log events").isEmpty();
    Thread.sleep(300);
    assertThat(recorder.snapshot())
        .as("no async audit event may arrive after the response")
        .isEmpty();
  }
}
