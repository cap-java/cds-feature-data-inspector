/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.testapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the cds-feature-data-inspector test-app.
 *
 * <p>This is a plain @SpringBootApplication class. All the Data Inspector functionality (handlers,
 * WebConfig, CDS model) is provided by the plugin dependency and activated automatically via Spring
 * Boot auto-configuration (see
 * cds-plugin/…/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
 *
 * <p>A downstream CAP Java application that consumes the plugin needs only: the {@code
 * cds-feature-data-inspector} dependency and a {@code @SpringBootApplication} like this one
 *
 * <p>That's it. No @ComponentScan of the plugin package, no manual bean wiring.
 */
@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
