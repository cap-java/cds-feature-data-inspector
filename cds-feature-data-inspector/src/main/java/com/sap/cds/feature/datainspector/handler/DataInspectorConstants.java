/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.handler;

import java.util.Set;

/** Shared constants for the Data Inspector plugin. */
public final class DataInspectorConstants {

  private DataInspectorConstants() {}

  // ── Annotations ──────────────────────────────────────────────────────────────
  /** Marks an entity/element as hidden from the Data Inspector. */
  public static final String HIDDEN_ANNOTATION = "@HideFromDataInspector";

  /** Flags an element as potentially sensitive personal data (drives isSensitive + audit logs). */
  public static final String PERSONAL_DATA_SENSITIVE_ANNOTATION =
      "@PersonalData.IsPotentiallySensitive";

  /** Marks an entity as personal data; required by audit-log v2 and gates audit emission. */
  public static final String PERSONAL_DATA_ENTITY_SEMANTICS_ANNOTATION =
      "@PersonalData.EntitySemantics";

  /** Optional data-subject role copied into the audit log's DataSubject.role. */
  public static final String PERSONAL_DATA_DATA_SUBJECT_ROLE_ANNOTATION =
      "@PersonalData.DataSubjectRole";

  /** Marks a computed/virtual element (maps to isVirtual). */
  public static final String CORE_COMPUTED_ANNOTATION = "@Core.Computed";

  /** Overrides the default page size for an entity/service. */
  public static final String SELECT_DEFAULT_PAGE_ANNOTATION = "@cds.query.limit.default";

  /** Caps the maximum page size for an entity/service. */
  public static final String SELECT_MAX_PAGE_ANNOTATION = "@cds.query.limit.max";

  // ── Exclusion lists ──────────────────────────────────────────────────────────
  /** Suffix of CAP draft administrative-data entities, which are hidden. */
  public static final String DRAFT_ENTITIES_SUFFIX = ".DraftAdministrativeData";

  /** CAP outbox system entity, hidden from the Data Inspector. */
  public static final String CDS_OUTBOX_ENTITY = "cds.outbox.Messages";

  /** CAP draft / Fiori system element names, hidden from the Data Inspector. */
  public static final Set<String> CDS_SYSTEM_ELEMENTS =
      Set.of(
          "IsActiveEntity",
          "HasActiveEntity",
          "HasDraftEntity",
          "DraftAdministrativeData_DraftUUID",
          "DraftMessages");

  // ── Pagination defaults ──────────────────────────────────────────────────────
  /** Default page size when no $top and no @cds.query.limit.default are given. */
  public static final int DEFAULT_PAGE_SIZE = 1000;

  /** Absolute upper bound on rows per response ($top is capped to this). */
  public static final int MAX_PAGE_SIZE = 1000;

  // ── dataSource values ────────────────────────────────────────────────────────
  public static final String DATA_SOURCE_DB = "db";
  public static final String DATA_SOURCE_SERVICE = "service";
  public static final String DATA_SOURCE_UNKNOWN = "unknown";

  // ── CDS type names ───────────────────────────────────────────────────────────
  /** Association element type — excluded from element lists / column selection. */
  public static final String TYPE_ASSOCIATION = "cds.Association";

  /** Composition element type — excluded from element lists / column selection. */
  public static final String TYPE_COMPOSITION = "cds.Composition";
}
