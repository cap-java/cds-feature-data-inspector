/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.configuration;

import com.sap.cds.feature.datainspector.handler.DataReader;
import com.sap.cds.feature.datainspector.handler.EntityDefinitionReader;
import com.sap.cds.services.auditlog.AuditLogService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.CdsRuntimeConfiguration;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CAP-side entry point: registers this plugin's CDS event handlers (EntityDefinitionReader,
 * DataReader). Discovered by CAP Java via {@code
 * META-INF/services/com.sap.cds.services.runtime.CdsRuntimeConfiguration}.
 */
public class Registration implements CdsRuntimeConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(Registration.class);

  @Override
  public void eventHandlers(CdsRuntimeConfigurer configurer) {
    CdsRuntime runtime = configurer.getCdsRuntime();

    PersistenceService db =
        runtime
            .getServiceCatalog()
            .getService(PersistenceService.class, PersistenceService.DEFAULT_NAME);

    // Optional: null when cds-feature-auditlog-v2 is absent (audit emission disabled).
    AuditLogService auditLogService =
        runtime.getServiceCatalog().getService(AuditLogService.class, AuditLogService.DEFAULT_NAME);
    if (auditLogService == null) {
      logger.debug("AuditLogService not available — audit-log emission disabled.");
    }

    configurer.eventHandler(new EntityDefinitionReader(runtime));
    configurer.eventHandler(new DataReader(runtime, db, auditLogService));

    logger.info("cds-feature-data-inspector event handlers registered.");
  }
}
