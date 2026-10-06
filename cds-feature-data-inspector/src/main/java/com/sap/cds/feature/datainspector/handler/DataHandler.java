/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.handler;

import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.CDS_OUTBOX_ENTITY;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.CDS_SYSTEM_ELEMENTS;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DEFAULT_PAGE_SIZE;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DRAFT_ENTITIES_SUFFIX;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.HIDDEN_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.MAX_PAGE_SIZE;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.PERSONAL_DATA_DATA_SUBJECT_ROLE_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.PERSONAL_DATA_ENTITY_SEMANTICS_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.PERSONAL_DATA_SENSITIVE_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.SELECT_DEFAULT_PAGE_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.SELECT_MAX_PAGE_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.TYPE_ASSOCIATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.TYPE_COMPOSITION;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.CdsData;
import com.sap.cds.Result;
import com.sap.cds.ResultBuilder;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnPredicate;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnSortSpecification;
import com.sap.cds.reflect.CdsElement;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.auditlog.Access;
import com.sap.cds.services.auditlog.Attribute;
import com.sap.cds.services.auditlog.AuditLogService;
import com.sap.cds.services.auditlog.DataObject;
import com.sap.cds.services.auditlog.DataSubject;
import com.sap.cds.services.auditlog.KeyValuePair;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.ParameterInfo;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles READ on DataInspectorService.Data.
 *
 * <p>GET requests can be made in either of these two OData compliant ways only: 1. To get a single
 * record of an entity 'Foo' by its key 'Bar', use OData query by ID:
 * /(entityName='Foo',recordKey='Bar=value') For composite key 'ABC' and 'XYZ', use '&' as
 * separator: /(entityName='Foo',recordKey='ABC=value&XYZ=value') 2. To get multiple records of an
 * entity 'Foo', provide OData filter : $filter=entityName eq 'Foo'
 *
 * <p>A singular GET request for all records of all entities is not supported.
 *
 * <p>To $select, $filter and $orderby on certain columns of the entity records (dynamic metadata),
 * custom query options (r_select, r_filter and r_orderby respectively) are accepted that only work
 * on the record columns.
 */
@ServiceName("DataInspectorService")
public class DataHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(DataHandler.class);

  private final CdsRuntime runtime;

  /** Default persistence service (the database). Used for DB-level entities. */
  private final PersistenceService db;

  /** Optional audit-log service; {@code null} when audit-logging is not on the classpath. */
  private final AuditLogService auditLogService;

  /**
   * Serialises DB rows to the JSON string stored in 'record' (cf. JSON.stringify). JavaTimeModule
   * renders java.time.* as ISO-8601 instead of epoch numbers.
   */
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /**
   * Collaborators are received as constructor parameters (dependency injection), the idiomatic CAP
   * Java style. Unlike Node.js — where a handler reaches global singletons such as {@code
   * cds.services.db} or {@code cds.model} directly — CAP Java has no such globals; services come
   * from the {@link CdsRuntime}, which {@code Registration} obtains at startup and passes in here.
   * This also keeps the handler testable (the tests supply their own services).
   */
  public DataHandler(CdsRuntime runtime, PersistenceService db, AuditLogService auditLogService) {
    this.runtime = runtime;
    this.db = db;
    this.auditLogService = auditLogService;
  }

  // ── Main handler ─────────────────────────────────────────────────────────────

  /**
   * READ entry point. A by-key request carries both the entityName and recordKey key predicates; a
   * collection request ($filter=entityName eq 'X') leaves recordKey null and is routed accordingly.
   */
  @On(
      event = CqnService.EVENT_READ,
      service = "DataInspectorService",
      entity = "DataInspectorService.Data")
  public void onRead(CdsReadEventContext context) {
    CdsModel model = runtime.getCdsModel();
    CqnAnalyzer analyzer = CqnAnalyzer.create(model);
    Map<String, Object> keys = analyzer.analyze(context.getCqn()).targetKeys();

    String entityNameKey = (String) keys.get("entityName");
    String recordKeyVal = (String) keys.get("recordKey");
    if (entityNameKey != null && recordKeyVal != null) {
      handleByKey(context, model, keys);
    } else {
      handleCollection(context, model);
    }
  }

  // ── OData Query by Key
  // ────────────────────────────────────────────────────────

  /**
   * Handles GET /Data(entityName='Foo',recordKey='ID=123'): validates entity and key, converts the
   * recordKey to a filter and runs the query. Empty result → 404.
   */
  private void handleByKey(CdsReadEventContext context, CdsModel model, Map<String, Object> keys) {
    String entityName = (String) keys.get("entityName");
    String recordKey = (String) keys.get("recordKey");

    Optional<CdsEntity> entityOpt = findVisibleEntity(model, entityName);
    if (entityOpt.isEmpty()) {
      context.setResult(Collections.emptyList()); // 404
      return;
    }
    CdsEntity entity = entityOpt.get();

    if (!validateRecordKey(entity, recordKey)) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_RECORD_KEY");
    }

    // "key1=val1&key2=val2" → "key1 eq 'val1' and key2 eq 'val2'" (the WHERE
    // clause)
    String rFilterFromKey = buildRFilterFromRecordKey(recordKey);

    executeAndRespond(context, model, entity, rFilterFromKey, true);
  }

  // ── OData Collection
  // ──────────────────────────────────────────────────────────

  /**
   * Handles GET /Data?$filter=entityName eq 'Foo'. The target entity is taken from the mandatory
   * $filter=entityName eq '...' clause.
   */
  private void handleCollection(CdsReadEventContext context, CdsModel model) {
    ParameterInfo params = context.getParameterInfo();
    String filterParam = params.getQueryParameter("$filter");

    if (filterParam == null || filterParam.isBlank()) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "FILTER_PARAM_NOT_FOUND");
    }

    String entityName = parseEntityNameFromFilter(filterParam);
    if (entityName == null) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "FILTER_PARAM_INVALID");
    }

    Optional<CdsEntity> entityOpt = findVisibleEntity(model, entityName);
    if (entityOpt.isEmpty()) {
      context.setResult(Collections.emptyList());
      return;
    }

    executeAndRespond(context, model, entityOpt.get(), null, false);
  }

  // ── Query execution
  // ───────────────────────────────────────────────────────────

  /**
   * Builds and runs the SELECT against the resolved data source, then sets the OData response with
   * the total count.
   *
   * @param rFilterFromKey pre-built WHERE clause from a recordKey, or null for collections
   * @param isByKey true for by-key requests (empty result → 404)
   */
  private void executeAndRespond(
      CdsReadEventContext context,
      CdsModel model,
      CdsEntity entity,
      String rFilterFromKey,
      boolean isByKey) {

    ParameterInfo params = context.getParameterInfo();
    CqnService dataSource = resolveDataSource(model, entity);

    // Per-request cache computed once and reused.
    // The handler is a shared singleton, so this state must live in a request-scoped object.
    RequestContext rc = new RequestContext(entity, params, rFilterFromKey);

    CqnSelect query = buildQuery(rc);

    Result result;
    try {
      result = dataSource.run(query);
    } catch (ServiceException e) {
      // Preserve the original status (e.g. 403 when the inspected service rejects the user)
      throw e;
    } catch (Exception e) {
      log.error("Failed to run query on entity {}: {}", entity.getQualifiedName(), e.getMessage());
      throw new ServiceException(ErrorStatuses.SERVER_ERROR, "ERROR_RUNNING_DB_QUERY");
    }

    // Find count of records — run the COUNT query exactly once and cache it.
    long totalCount;
    try {
      Result countResult = dataSource.run(buildCountQuery(rc));
      if (countResult.iterator().hasNext()) {
        Object val = countResult.iterator().next().get("count");
        totalCount = val instanceof Number n ? n.longValue() : 0L;
      } else {
        totalCount = 0L;
      }
    } catch (ServiceException e) {
      // Preserve the original status (e.g. 403 when the inspected service rejects the user)
      throw e;
    } catch (Exception e) {
      log.error("Count query failed for {}: {}", entity.getQualifiedName(), e.getMessage());
      throw new ServiceException(ErrorStatuses.SERVER_ERROR, "ERROR_RUNNING_DB_QUERY");
    }
    rc.totalCount = totalCount;

    // By-key request with no match → 404
    if (isByKey && result.rowCount() == 0) {
      context.setResult(Collections.emptyList());
      return;
    }

    // Compute the server-side @odata.nextLink offset.
    // KNOWN LIMITATION — the value computed here is NOT emitted in the OData response.
    Long nextLinkOffset = computeNextLinkOffset(rc, dataSource);
    if (nextLinkOffset != null) {
      context.put("$nextLink", nextLinkOffset);
    }

    List<String> selectedColumns = determineSelectedColumnsForData(params);
    boolean includeRecord = selectedColumns.contains("*") || selectedColumns.contains("record");

    // rawRows keeps the untouched DB rows for audit-log emission; the shallow
    // LinkedHashMap copy just detaches them from the CdsData proxy.
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Map<String, Object>> rawRows = new ArrayList<>();
    for (CdsData row : result) {
      rawRows.add(new LinkedHashMap<>(row));
      Map<String, Object> dataRow = new LinkedHashMap<>();
      dataRow.put("entityName", entity.getQualifiedName());
      dataRow.put("recordKey", buildRecordKey(rc.keyElements(), row));

      if (includeRecord) {
        // 'record' is typed LargeString @IsJSON in the model.
        try {
          dataRow.put("record", objectMapper.writeValueAsString(row));
        } catch (JsonProcessingException e) {
          log.error("Failed to serialise record for {}", entity.getQualifiedName(), e);
          dataRow.put("record", "{}");
        }
      }
      rows.add(dataRow);
    }

    // Emit audit logs for sensitive-field reads.
    emitAuditLog(model, entity, rc.keyElements(), rawRows);

    // inlineCount() feeds @odata.count; setCompleted() stops the default On-handler
    // from overwriting our result.
    context.put("result", ResultBuilder.selectedRows(rows).inlineCount(totalCount).result());
    context.setCompleted();
  }

  // ── nextLink computation ─────────────────────────────────────────────────────

  /**
   * Computes the next-page offset for @odata.nextLink, or {@code null} when there is no next page.
   * nextLink should occur when the result set is truncated due to default or max page size limit 5
   * cases possible: Case 1: Default < $top < max => no nextLink Case 2: $top < default < max => no
   * nextLink Case 3: Default < max < $top => Provide nextLink Case 4: No $top = default => Provide
   * nextLink if $count > default Case 5: $top = 0 => no nextLink
   */
  private Long computeNextLinkOffset(RequestContext rc, CqnService dataSource) {
    CdsEntity entity = rc.entity;
    ParameterInfo params = rc.params;
    long maxPageSize = rc.maxPageSize();
    long defaultPageSize = rc.defaultPageSize();
    String topParam = params.getQueryParameter("$top");

    // Reuse the total count already computed (and cached) in executeAndRespond.
    long totalCount = rc.totalCount;

    boolean truncated =
        (topParam != null && Long.parseLong(topParam) > maxPageSize) // case 3
            || (topParam == null && totalCount > defaultPageSize); // case 4
    if (!truncated) return null;

    long limit = defaultPageSize;
    if (topParam != null) {
      limit = Math.min(Long.parseLong(topParam), maxPageSize);
    }
    long nextOffset = computeOffset(params) + limit;

    // Probe: does a next page actually exist? Keys-only SELECT with the same
    // WHERE/ORDER BY, limited to 1 row at nextOffset.
    try {
      CqnSelect probe = buildNextPageProbeQuery(rc, nextOffset);
      Result probeResult = dataSource.run(probe);
      return probeResult.rowCount() > 0 ? nextOffset : null;
    } catch (ServiceException e) {
      // Preserve the original status (e.g. 403 when the inspected service rejects the user)
      throw e;
    } catch (Exception e) {
      log.error("Next-page probe failed for {}: {}", entity.getQualifiedName(), e.getMessage());
      throw new ServiceException(ErrorStatuses.SERVER_ERROR, "ERROR_RUNNING_DB_QUERY");
    }
  }

  /**
   * Builds the keys-only next-page probe: SELECT keys FROM entity [WHERE r_filter] [ORDER BY
   * r_orderby] LIMIT 1 OFFSET nextOffset.
   */
  private CqnSelect buildNextPageProbeQuery(RequestContext rc, long nextOffset) {
    var select =
        Select.from(rc.entity.getQualifiedName()).columns(rc.keyElements().toArray(String[]::new));
    applyRFilter(select, rc);
    applyROrderBy(select, rc.recordElements(), rc.params);
    return select.limit(1, nextOffset);
  }

  // ── Audit log emission
  // ────────────────────────────────────────────────────────

  /**
   * Emits a DataAccess audit event per row for reads of @PersonalData.IsPotentiallySensitive
   * elements, but only for DB-level entities carrying @PersonalData.EntitySemantics. Service-owned
   * entities are audited by CAP Java itself, so they are skipped here.
   */
  private void emitAuditLog(
      CdsModel model,
      CdsEntity entity,
      List<String> keyElements,
      List<Map<String, Object>> rawRows) {
    // Entities owned by a named service are audit-logged by CAP Java's own handler, so this plugin
    // must only emit audit events for DB-level entities.
    String entityName = entity.getQualifiedName();
    boolean isEntityOwnedByService =
        model.services().anyMatch(svc -> entityName.startsWith(svc.getQualifiedName() + "."));
    if (rawRows.isEmpty() || isEntityOwnedByService || auditLogService == null) return;

    // @PersonalData.EntitySemantics is required by audit-log v2
    if (entity.findAnnotation(PERSONAL_DATA_ENTITY_SEMANTICS_ANNOTATION).isEmpty()) return;

    Set<String> sensitiveElements =
        entity
            .elements()
            .filter(el -> el.findAnnotation(PERSONAL_DATA_SENSITIVE_ANNOTATION).isPresent())
            .map(CdsElement::getName)
            .collect(Collectors.toSet());
    if (sensitiveElements.isEmpty()) return;

    String role =
        entity
            .findAnnotation(PERSONAL_DATA_DATA_SUBJECT_ROLE_ANNOTATION)
            .map(a -> a.getValue())
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .orElse(null);

    List<Access> accesses = new ArrayList<>(rawRows.size());
    for (Map<String, Object> row : rawRows) {
      // Only report sensitive attributes actually present in this row
      // (an r_select may have excluded some).
      List<Attribute> attrs =
          sensitiveElements.stream()
              .filter(row::containsKey)
              .map(
                  name -> {
                    Attribute a = Attribute.create();
                    a.setName(name);
                    return a;
                  })
              .collect(Collectors.toList());
      if (attrs.isEmpty()) continue;

      // DataObject and DataSubject share the same id (the row's key values).
      List<KeyValuePair> id =
          keyElements.stream()
              .map(
                  k -> {
                    KeyValuePair kv = KeyValuePair.create();
                    kv.setKeyName(k);
                    Object v = row.get(k);
                    kv.setValue(v != null ? v.toString() : null);
                    return kv;
                  })
              .collect(Collectors.toList());

      DataObject obj = DataObject.create();
      obj.setType(entityName);
      obj.setId(id);

      DataSubject subj = DataSubject.create();
      subj.setType(entityName);
      subj.setId(id);
      if (role != null) subj.setRole(role);

      Access access = Access.create();
      access.setDataObject(obj);
      access.setDataSubject(subj);
      access.setAttributes(attrs);
      accesses.add(access);
    }

    if (!accesses.isEmpty()) {
      try {
        auditLogService.logDataAccess(accesses);
      } catch (Exception e) {
        // Audit logging is a compliance requirement: if it fails, the read must fail too.
        log.error("Audit log emission failed for entity {}", entityName, e);
        throw new ServiceException(ErrorStatuses.SERVER_ERROR, "ERROR_EMITTING_AUDIT_LOG");
      }
    }
  }

  /**
   * Builds a COUNT(*) query with the same WHERE clause as the main query but no LIMIT/OFFSET,
   * giving the true total for @odata.count.
   */
  private CqnSelect buildCountQuery(RequestContext rc) {
    var select = Select.from(rc.entity.getQualifiedName()).columns(CQL.count().as("count"));
    applyRFilter(select, rc);
    return select;
  }

  /**
   * Builds the main SELECT: column projection (r_select), WHERE (r_filter or recordKey), ORDER BY
   * (r_orderby) and LIMIT/OFFSET ($top, $skip, $skiptoken).
   */
  private CqnSelect buildQuery(RequestContext rc) {
    CdsEntity entity = rc.entity;
    ParameterInfo params = rc.params;
    List<String> recordElements = rc.recordElements();
    List<String> keyElements = rc.keyElements();

    // Column selection: r_select if given (plus key elements so recordKey is
    // buildable), otherwise all non-hidden, non-association elements.
    List<String> columnsToSelect;
    String rSelect = params.getQueryParameter("r_select");
    if (rSelect != null && !rSelect.isBlank()) {
      List<String> requested =
          Arrays.stream(rSelect.split(",")).map(String::trim).collect(Collectors.toList());
      for (String el : requested) {
        if (!recordElements.contains(el)) {
          throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_ELEMENT_IN_R_SELECT");
        }
      }
      columnsToSelect = new ArrayList<>(requested);
      for (String key : keyElements) {
        if (!columnsToSelect.contains(key)) columnsToSelect.add(key);
      }
    } else {
      columnsToSelect = recordElements;
    }

    var select =
        Select.from(entity.getQualifiedName()).columns(columnsToSelect.toArray(String[]::new));

    applyRFilter(select, rc);
    applyROrderBy(select, recordElements, params);

    // LIMIT / OFFSET — $skip and $skiptoken are additive (same as Node.js).
    long maxPageSize = rc.maxPageSize();
    long defaultPageSize = rc.defaultPageSize();

    long limit = defaultPageSize;
    String topParam = params.getQueryParameter("$top");
    if (topParam != null) {
      final long top;
      try {
        top = Long.parseLong(topParam);
      } catch (NumberFormatException e) {
        // Mirror the $skip / $skiptoken handling: a malformed $top is a 400, not a 500.
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_TOP");
      }
      if (top < 0) throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_TOP");
      limit = Math.min(top, maxPageSize);
    }

    select = select.limit(limit, computeOffset(params));
    // inlineCount() sets $count so result.rowCount() reflects the total.
    return select.inlineCount();
  }

  /**
   * Applies the WHERE clause: rFilterFromKey when set (from a validated recordKey), otherwise the
   * user-supplied r_filter. Parsing is delegated to {@link RFilterParser}; its parse failures are
   * translated here into the matching 400 OData error.
   */
  private void applyRFilter(Select<?> select, RequestContext rc) {
    if (!rc.rFilterParsed) {
      String rFilter =
          (rc.rFilterFromKey != null) ? rc.rFilterFromKey : rc.params.getQueryParameter("r_filter");
      if (rFilter != null && !rFilter.isBlank()) {
        // recordKey-derived filters are already vetted, so element validation is skipped (null).
        List<String> allowed = (rc.rFilterFromKey == null) ? rc.recordElements() : null;
        try {
          rc.parsedRFilter = RFilterParser.parse(rFilter, allowed);
        } catch (RFilterParser.FilterParseException e) {
          log.warn("{}: {}", e.getCode(), e.getMessage());
          throw new ServiceException(ErrorStatuses.BAD_REQUEST, e.getCode());
        }
      }
      rc.rFilterParsed = true;
    }
    if (rc.parsedRFilter != null) {
      select.where(rc.parsedRFilter);
    }
  }

  /** Applies r_orderby ("col asc" / "col1 asc, col2 desc"); rejects unknown columns with 400. */
  private void applyROrderBy(Select<?> select, List<String> recordElements, ParameterInfo params) {
    String rOrderby = params.getQueryParameter("r_orderby");
    if (rOrderby == null || rOrderby.isBlank()) return;

    List<CqnSortSpecification> orderSpecs = new ArrayList<>();
    for (String part : rOrderby.split(",")) {
      String[] tokens = part.trim().split("\\s+");
      String colName = tokens[0];
      String dir = tokens.length > 1 ? tokens[1] : "asc";
      if (!recordElements.contains(colName)) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_ELEMENT_IN_R_ORDERBY");
      }
      orderSpecs.add(
          "desc".equalsIgnoreCase(dir) ? CQL.get(colName).desc() : CQL.get(colName).asc());
    }
    select.orderBy(orderSpecs);
  }

  // ── Helpers
  // ───────────────────────────────────────────────────────────────────

  /** Finds a visible entity by qualified name (hidden / draft / outbox entities excluded). */
  private Optional<CdsEntity> findVisibleEntity(CdsModel model, String entityName) {
    return model
        .entities()
        .filter(e -> e.getQualifiedName().equals(entityName))
        .filter(e -> e.findAnnotation(HIDDEN_ANNOTATION).isEmpty())
        .filter(e -> !e.getQualifiedName().endsWith(DRAFT_ENTITIES_SUFFIX))
        .filter(e -> !CDS_OUTBOX_ENTITY.equals(e.getQualifiedName()))
        .findFirst();
  }

  /**
   * Resolves the data source: the owning named service if the entity belongs to one, otherwise the
   * default persistence service.
   */
  private CqnService resolveDataSource(CdsModel model, CdsEntity entity) {
    String entityName = entity.getQualifiedName();
    Optional<String> serviceName =
        model
            .services()
            .filter(svc -> entityName.startsWith(svc.getQualifiedName() + "."))
            .map(svc -> svc.getQualifiedName())
            .findFirst();

    if (serviceName.isPresent()) {
      try {
        return (CqnService)
            runtime.getServiceCatalog().getService(CqnService.class, serviceName.get());
      } catch (Exception e) {
        log.warn("Service {} not in catalog, falling back to db", serviceName.get());
      }
    }
    return db;
  }

  /**
   * Returns element names usable in SELECT/WHERE/ORDER BY — excluding hidden, association,
   * composition and CDS system elements. Java port of DataReader._getRecordElements().
   */
  private List<String> getRecordElements(CdsEntity entity) {
    return entity
        .elements()
        .filter(el -> el.findAnnotation(HIDDEN_ANNOTATION).isEmpty())
        .filter(el -> !TYPE_ASSOCIATION.equals(el.getType().getQualifiedName()))
        .filter(el -> !TYPE_COMPOSITION.equals(el.getType().getQualifiedName()))
        .filter(el -> !CDS_SYSTEM_ELEMENTS.contains(el.getName()))
        .map(CdsElement::getName)
        .collect(Collectors.toList());
  }

  /**
   * Returns the entity's key element names, excluding CDS draft system elements. Java port of
   * entity.keyElements4DataInspector (CsnRuntimeExtensions.ts).
   */
  private List<String> getKeyElements(CdsEntity entity) {
    return entity
        .elements()
        .filter(CdsElement::isKey)
        .filter(el -> !CDS_SYSTEM_ELEMENTS.contains(el.getName()))
        .map(CdsElement::getName)
        .collect(Collectors.toList());
  }

  /**
   * Builds the recordKey from a data row: "ID=5" for single keys, "Country=DE&Code=EUR" for
   * composite. Java port of DataReader._constructRecordKey().
   */
  private String buildRecordKey(List<String> keyElements, Map<String, Object> row) {
    return keyElements.stream()
        .map(key -> key + "=" + row.get(key))
        .collect(Collectors.joining("&"));
  }

  /**
   * Validates that the recordKey contains exactly the entity's key elements. Java port of
   * DataReader._validateRecordKeys().
   */
  private boolean validateRecordKey(CdsEntity entity, String recordKey) {
    if (recordKey == null || recordKey.isBlank()) return false;
    Set<String> expectedKeys = new HashSet<>(getKeyElements(entity));
    for (String pair : recordKey.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv.length < 2) return false;
      if (!expectedKeys.remove(kv[0])) return false; // unknown key element
    }
    return expectedKeys.isEmpty(); // all composite keys must be supplied
  }

  /** Converts "key1=val1&key2=val2" into "key1 eq 'val1' and key2 eq 'val2'". */
  private String buildRFilterFromRecordKey(String recordKey) {
    return Arrays.stream(recordKey.split("&"))
        .map(
            pair -> {
              String[] kv = pair.split("=", 2);
              return kv[0] + " eq '" + kv[1] + "'";
            })
        .collect(Collectors.joining(" and "));
  }

  /** Parses "entityName eq 'Foo'" and returns "Foo", or null if the pattern differs. */
  private String parseEntityNameFromFilter(String filter) {
    var m = java.util.regex.Pattern.compile("entityName\\s+eq\\s+'([^']+)'").matcher(filter.trim());
    return m.matches() ? m.group(1) : null;
  }

  /**
   * Parses $select for the Data entity (entityName, recordKey, record). Returns ["*"] when $select
   * is absent. Java port of DataReader._determineSelectedColumns().
   */
  private List<String> determineSelectedColumnsForData(ParameterInfo params) {
    String selectParam = params.getQueryParameter("$select");
    if (selectParam == null || selectParam.isBlank()) return List.of("*");
    return Arrays.stream(selectParam.split(","))
        .map(String::trim)
        .filter(s -> List.of("entityName", "recordKey", "record").contains(s))
        .collect(Collectors.toList());
  }

  /** Computes the row offset from $skip + $skiptoken (additive); rejects negatives with 400. */
  private long computeOffset(ParameterInfo params) {
    long offset = 0;
    offset += parseOffsetParam(params.getQueryParameter("$skip"), "INVALID_SKIP");
    offset += parseOffsetParam(params.getQueryParameter("$skiptoken"), "INVALID_SKIPTOKEN");
    return offset;
  }

  private long parseOffsetParam(String value, String errorCode) {
    if (value == null) return 0;
    long v;
    try {
      v = Long.parseLong(value);
    } catch (NumberFormatException nfe) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, errorCode);
    }
    if (v < 0) throw new ServiceException(ErrorStatuses.BAD_REQUEST, errorCode);
    return v;
  }

  /** Reads a numeric entity annotation (e.g. @cds.query.limit.*), falling back when absent. */
  private long getEntityAnnotationAsLong(CdsEntity entity, String annotationName, long fallback) {
    return entity
        .findAnnotation(annotationName)
        .map(
            a -> {
              Object v = a.getValue();
              if (v instanceof Number n) return n.longValue();
              if (v instanceof String s) return Long.parseLong(s);
              return fallback;
            })
        .orElse(fallback);
  }

  // ── Per-request context
  // ───────────────────────────────────────────────────────

  /** A utility class to cache calculated results and avoid duplicate efforts */
  private final class RequestContext {
    private final CdsEntity entity;
    private final ParameterInfo params;

    /** Pre-built WHERE clause from a recordKey, or {@code null} for collection requests. */
    private final String rFilterFromKey;

    private List<String> recordElements;
    private List<String> keyElements;
    private boolean rFilterParsed;
    private CqnPredicate parsedRFilter;
    private Long maxPageSize;
    private Long defaultPageSize;

    /** Total matching row count; set once by executeAndRespond after the COUNT query. */
    private long totalCount;

    RequestContext(CdsEntity entity, ParameterInfo params, String rFilterFromKey) {
      this.entity = entity;
      this.params = params;
      this.rFilterFromKey = rFilterFromKey;
    }

    /** Record element names, computed once. */
    List<String> recordElements() {
      if (recordElements == null) {
        recordElements = getRecordElements(entity);
      }
      return recordElements;
    }

    /** Key element names, computed once. */
    List<String> keyElements() {
      if (keyElements == null) {
        keyElements = getKeyElements(entity);
      }
      return keyElements;
    }

    /** Max page size (@cds.query.limit.max or default), computed once. */
    long maxPageSize() {
      if (maxPageSize == null) {
        maxPageSize = getEntityAnnotationAsLong(entity, SELECT_MAX_PAGE_ANNOTATION, MAX_PAGE_SIZE);
      }
      return maxPageSize;
    }

    /** Default page size (@cds.query.limit.default or default), computed once. */
    long defaultPageSize() {
      if (defaultPageSize == null) {
        defaultPageSize =
            getEntityAnnotationAsLong(entity, SELECT_DEFAULT_PAGE_ANNOTATION, DEFAULT_PAGE_SIZE);
      }
      return defaultPageSize;
    }
  }
}
