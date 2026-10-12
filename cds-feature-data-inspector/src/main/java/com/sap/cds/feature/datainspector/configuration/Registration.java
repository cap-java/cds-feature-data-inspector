/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.configuration;

import com.sap.cds.feature.datainspector.handler.DataReader;
import com.sap.cds.feature.datainspector.handler.EntityDefinitionReader;
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

    configurer.eventHandler(new EntityDefinitionReader(runtime));
    configurer.eventHandler(new DataReader(runtime));

    logger.info("cds-feature-data-inspector event handlers registered.");
  }
}
