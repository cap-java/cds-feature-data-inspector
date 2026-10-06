/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.handler;

import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.CDS_OUTBOX_ENTITY;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.CDS_SYSTEM_ELEMENTS;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.CORE_COMPUTED_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DATA_SOURCE_DB;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DATA_SOURCE_SERVICE;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DATA_SOURCE_UNKNOWN;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.DRAFT_ENTITIES_SUFFIX;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.HIDDEN_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.PERSONAL_DATA_SENSITIVE_ANNOTATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.TYPE_ASSOCIATION;
import static com.sap.cds.feature.datainspector.handler.DataInspectorConstants.TYPE_COMPOSITION;

import com.sap.cds.ResultBuilder;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnSelectListValue;
import com.sap.cds.ql.cqn.CqnValue;
import com.sap.cds.reflect.CdsElement;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.request.ParameterInfo;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ServiceName("DataInspectorService")
public class EntityDefinitionReader implements EventHandler {

  private final CdsRuntime runtime;

  /**
   * Runtime is injected via the constructor (see {@link DataReader} for the DI (dependency
   * injection) rationale).
   */
  public EntityDefinitionReader(CdsRuntime runtime) {
    this.runtime = runtime;
  }

  @On(
      event = CqnService.EVENT_READ,
      service = "DataInspectorService",
      entity = "DataInspectorService.EntityDefinition")
  public void onRead(CdsReadEventContext context) {
    CdsModel model = runtime.getCdsModel();
    CqnSelect select = context.getCqn();

    if (isByKeyRequest(select)) {
      handleSingleEntityDefinition(context, model, extractKeyName(select));
    } else {
      handleEntityDefinitionCollection(context, model);
    }
  }

  /**
   * True for a by-key request (GET /EntityDefinition('X')). The OData adapter places the key
   * predicate on the ref's target segment (the URL path segment), NOT on the statement where() —
   * the latter is used for ?$filter=….
   */
  private boolean isByKeyRequest(CqnSelect select) {
    return select.from().isRef() && select.from().asRef().targetSegment().filter().isPresent();
  }

  /**
   * Extracts the 'name' key value from a by-key request's segment filter (e.g. [{"ref":["name"]},
   * "=", {"val":"…"}]). Uses CqnAnalyzer scoped to the key predicate so we reuse the SDK's own key
   * extraction.
   */
  private String extractKeyName(CqnSelect select) {
    CqnAnalyzer analyzer = CqnAnalyzer.create(runtime.getCdsModel());
    Map<String, Object> keys = analyzer.analyze(select).targetKeys();
    return (String) keys.get("name");
  }

  /** Handles GET /EntityDefinition(name='Foo'); no match → 404. */
  private void handleSingleEntityDefinition(
      CdsReadEventContext context, CdsModel model, String requestedName) {
    Optional<CdsEntity> found =
        model
            .entities()
            .filter(e -> e.getQualifiedName().equals(requestedName))
            .filter(this::isVisible)
            .findFirst();

    if (found.isEmpty()) {
      context.setResult(Collections.emptyList()); // 404
      return;
    }

    List<String> selectedColumns = determineSelectedColumns(context.getParameterInfo());
    Map<String, Object> result = buildEntityDefinitionMap(found.get(), selectedColumns, model);
    context.setResult(List.of(result));
  }

  /**
   * Handles GET /EntityDefinition with optional $filter, $orderby, $skip, $top, $select, $count —
   * processed in that order.
   */
  private void handleEntityDefinitionCollection(CdsReadEventContext context, CdsModel model) {
    ParameterInfo params = context.getParameterInfo();

    String filterParam = params.getQueryParameter("$filter");
    String skipParam = params.getQueryParameter("$skip");
    String topParam = params.getQueryParameter("$top");
    String orderByParam = params.getQueryParameter("$orderby");

    // Visibility + OData $filter
    List<CdsEntity> filtered =
        model
            .entities()
            .filter(this::isVisible)
            .filter(e -> matchesODataFilter(e, filterParam, model))
            .collect(Collectors.toList());

    int totalCount = filtered.size();

    // ── /$count path ──────────────────────────────────────────────────────
    // GET /EntityDefinition/$count (with optional $filter) arrives as a SELECT
    // whose single column is a COUNT function aliased "count".
    if (isScalarCountRequest(context.getCqn())) {
      context.put(
          "result",
          ResultBuilder.selectedRows(List.of(Map.of("count", (long) totalCount)))
              .inlineCount(totalCount)
              .result());
      context.setCompleted();
      return;
    }

    // Sort (default: name asc), then paginate ($skip, $top).
    // $skip / $top are parsed defensively: malformed, negative or overflowing values are
    // rejected with 400 rather than escaping as a 500 (NumberFormatException from parseInt or
    // IllegalArgumentException from Stream.skip/limit). A spec-compliant OData adapter already
    // rejects most of these upstream, but this keeps the handler self-contained.
    filtered.sort(buildComparator(orderByParam));

    int skip = 0;
    if (skipParam != null) {
      try {
        skip = Integer.parseInt(skipParam);
      } catch (NumberFormatException e) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_SKIP");
      }
      if (skip < 0) throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_SKIP");
    }

    int top = filtered.size();
    if (topParam != null) {
      try {
        top = Integer.parseInt(topParam);
      } catch (NumberFormatException e) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_TOP");
      }
      if (top < 0) throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_TOP");
    }

    // Project ($select)
    List<String> selectedColumns = determineSelectedColumns(params);
    List<Map<String, Object>> results =
        filtered.stream()
            .skip(skip)
            .limit(top)
            .map(e -> buildEntityDefinitionMap(e, selectedColumns, model))
            .collect(Collectors.toList());

    // The OData adapter reads @odata.count from Result.inlineCount();
    // setCompleted() stops the default On-handler from overwriting our result.
    context.put("result", ResultBuilder.selectedRows(results).inlineCount(totalCount).result());
    context.setCompleted();
  }

  /** Visible = not @HideFromDataInspector, not a draft admin entity, not the outbox. */
  private boolean isVisible(CdsEntity entity) {
    String name = entity.getQualifiedName();
    return entity.findAnnotation(HIDDEN_ANNOTATION).isEmpty()
        && !name.endsWith(DRAFT_ENTITIES_SUFFIX)
        && !CDS_OUTBOX_ENTITY.equals(name);
  }

  /**
   * Supports singular 'eq' or 'contains' on properties: name, title, and dataSource Especially to
   * fulfil UI use case, 'and' is supported with a 'contains' and an 'eq' operation Example: a)
   * dataSource eq '<value>' and contains(name, <value>) b) contains(title, <value>) and dataSource
   * eq '<value>'
   */
  private boolean matchesODataFilter(CdsEntity entity, String filter, CdsModel model) {
    if (filter == null || filter.isBlank()) {
      return true;
    }

    String name = entity.getQualifiedName();
    String title = entity.findAnnotation("@title").map(a -> (String) a.getValue()).orElse(null);
    String dataSource = resolveDataSource(entity, model);

    // ── contains(field, 'value') ──────────────────────────────────────────
    if (filter.startsWith("contains(") && !filter.contains(" and ")) {
      FilterParts p = parseContains(filter);
      if (p == null) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_FILTER_PARAM_SINGULAR");
      }
      requireAllowedColumn(p.column());
      return matchContains(p.column(), p.value(), name, title, dataSource);
    }

    // ── field eq 'value' (no 'and') ───────────────────────────────────────
    if (filter.contains(" eq ") && !filter.contains(" and ")) {
      FilterParts p = parseEq(filter);
      if (p == null) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_FILTER_PARAM_SINGULAR");
      }
      requireAllowedColumn(p.column());
      return matchEq(p.column(), p.value(), name, title, dataSource);
    }

    // ── combined: dataSource eq 'X' and contains(name, 'Y') (both orderings)
    if (filter.contains(" and ")) {
      String[] parts = filter.split(" and ", 2);
      String left = parts[0].trim();
      String right = parts[1].trim();

      // Normalise: contains part first, eq part second regardless of original order
      String containsPart, eqPart;
      if (left.startsWith("contains(")) {
        containsPart = left;
        eqPart = right;
      } else if (right.startsWith("contains(")) {
        eqPart = left;
        containsPart = right;
      } else {
        // Neither side is contains(…): unsupported combined form
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_FILTER_PARAM_SINGULAR");
      }

      FilterParts containsF = parseContains(containsPart);
      FilterParts eqF = parseEq(eqPart);
      if (containsF == null || eqF == null) {
        throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_FILTER_PARAM_SINGULAR");
      }
      requireAllowedColumn(containsF.column());
      requireAllowedColumn(eqF.column());

      return matchContains(containsF.column(), containsF.value(), name, title, dataSource)
          && matchEq(eqF.column(), eqF.value(), name, title, dataSource);
    }

    // No supported shape matched (e.g. "elements in (…)" or unknown operator)
    throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INVALID_FILTER_PARAM_SINGULAR");
  }

  /**
   * Enforces that a filter clause references one of the supported EntityDefinition columns (name /
   * title / dataSource). Any other column — including the 'elements' collection — is rejected with
   * 400 (mirrors the Node.js CANNOT_FILTER_BY_COLUMN check for 'elements').
   */
  private void requireAllowedColumn(String column) {
    if (!Set.of("name", "title", "dataSource").contains(column)) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "CANNOT_FILTER_BY_COLUMN");
    }
  }

  private boolean matchContains(
      String column, String value, String name, String title, String dataSource) {
    return switch (column) {
      case "name" -> name.contains(value);
      case "title" -> title != null && title.contains(value);
      case "dataSource" -> dataSource.contains(value);
      default -> true;
    };
  }

  private boolean matchEq(
      String column, String value, String name, String title, String dataSource) {
    return switch (column) {
      case "name" -> name.equals(value);
      case "title" -> value.equals(title);
      case "dataSource" -> dataSource.equals(value);
      default -> true;
    };
  }

  // ── Sorting ──────────────────────────────────────────────────────────────────
  /** Builds a Comparator from $orderby. Only 'name' is sortable; default is name ascending. */
  private Comparator<CdsEntity> buildComparator(String orderByParam) {
    if (orderByParam == null || orderByParam.isBlank()) {
      return Comparator.comparing(CdsEntity::getQualifiedName); // default: name asc
    }

    String[] parts = orderByParam.trim().split("\\s+");
    String column = parts[0];
    String direction = parts.length > 1 ? parts[1] : "asc";

    // title, dataSource, elements are unsortable
    if (!column.equals("name")) {
      throw new ServiceException(ErrorStatuses.BAD_REQUEST, "CANNOT_ORDER_BY_COLUMN");
    }

    Comparator<CdsEntity> cmp = Comparator.comparing(CdsEntity::getQualifiedName);
    return "desc".equalsIgnoreCase(direction) ? cmp.reversed() : cmp;
  }

  private List<String> determineSelectedColumns(ParameterInfo params) {
    String selectParam = params.getQueryParameter("$select");
    if (selectParam == null || selectParam.isBlank()) {
      return List.of("*");
    }
    List<String> columns =
        Arrays.stream(selectParam.split(","))
            .map(String::trim)
            .filter(s -> List.of("name", "title", "dataSource", "elements").contains(s))
            .collect(Collectors.toList());

    // 'name' is the key. Unlike the Node.js implementation, the CAP OData adapter
    // does NOT implicitly add it to $select when absent.
    if (!columns.contains("name")) {
      columns.add("name");
    }
    return columns;
  }

  private Map<String, Object> buildEntityDefinitionMap(
      CdsEntity entity, List<String> selectedColumns, CdsModel model) {
    Map<String, Object> map = new LinkedHashMap<>();
    boolean all = selectedColumns.contains("*");

    if (all || selectedColumns.contains("name")) {
      map.put("name", entity.getQualifiedName());
    }
    if (all || selectedColumns.contains("title")) {
      // @title may not be set on every entity
      String title = entity.findAnnotation("@title").map(a -> (String) a.getValue()).orElse(null);
      map.put("title", title);
    }
    if (all || selectedColumns.contains("dataSource")) {
      map.put("dataSource", resolveDataSource(entity, model));
    }
    if (all || selectedColumns.contains("elements")) {
      map.put("elements", buildEntityElementList(entity));
    }

    return map;
  }

  /**
   * Resolves the entity's data source in the following order: 1. "service" if owned by a named
   * service 2. "unknown" if @cds.persistence.skip 3. otherwise "db"
   */
  private String resolveDataSource(CdsEntity entity, CdsModel model) {
    String entityName = entity.getQualifiedName();

    boolean ownedByService =
        model.services().anyMatch(svc -> entityName.startsWith(svc.getQualifiedName() + "."));

    if (ownedByService) {
      return DATA_SOURCE_SERVICE;
    }

    boolean persistenceSkip =
        entity
            .findAnnotation("@cds.persistence.skip")
            .map(a -> Boolean.TRUE.equals(a.getValue()))
            .orElse(false);

    // @cds.persistence.skip = no DB table, when defined outside a service, it's
    // source is unknown
    return persistenceSkip ? DATA_SOURCE_UNKNOWN : DATA_SOURCE_DB;
  }

  /** Builds the list of EntityElement maps for a given entity's elements. */
  private List<Map<String, Object>> buildEntityElementList(CdsEntity entity) {
    return entity
        .elements()
        .filter(el -> !isExcludedElement(el))
        .map(this::buildElementMap)
        .collect(Collectors.toList());
  }

  /** True if the element is hidden, an association/composition, or a CDS system element. */
  private boolean isExcludedElement(CdsElement el) {
    if (el.findAnnotation(HIDDEN_ANNOTATION).isPresent()) {
      return true;
    }
    String type = el.getType().getQualifiedName();
    if (TYPE_ASSOCIATION.equals(type) || TYPE_COMPOSITION.equals(type)) {
      return true;
    }
    return CDS_SYSTEM_ELEMENTS.contains(el.getName());
  }

  /** Maps a CdsElement to the EntityElement response shape. */
  private Map<String, Object> buildElementMap(CdsElement el) {
    Map<String, Object> map = new LinkedHashMap<>();

    map.put("name", el.getName());
    map.put("type", el.getType().getQualifiedName());

    // Length lives either on the simple type facet (`String(88)`) or as an explicit
    // @length annotation; probe both, falling back to null.
    Object length = null;
    if (el.getType().isSimple()) {
      var simple = (com.sap.cds.reflect.CdsSimpleType) el.getType();
      Object l = simple.get("length");
      if (l != null) {
        length = l;
      }
    }
    if (length == null) {
      length = el.getType().findAnnotation("@length").map(a -> a.getValue()).orElse(null);
    }
    if (length == null) {
      length = el.findAnnotation("@length").map(a -> a.getValue()).orElse(null);
    }
    map.put("length", length);

    // defaultValue() ~ element.default?.val
    map.put("defaultValue", el.defaultValue().map(Object::toString).orElse(null));

    boolean isKey = el.isKey();
    boolean isNotNull = isKey || el.isNotNull();

    map.put("isKey", isKey);
    map.put("isNotNull", isNotNull);

    map.put(
        "isSensitive",
        el.findAnnotation(PERSONAL_DATA_SENSITIVE_ANNOTATION)
            .map(a -> Boolean.TRUE.equals(a.getValue()))
            .orElse(false));

    // @Core.Computed → isVirtual = true (calculated/virtual field, not stored in
    // DB)
    map.put(
        "isVirtual",
        el.findAnnotation(CORE_COMPUTED_ANNOTATION)
            .map(a -> Boolean.TRUE.equals(a.getValue()))
            .orElse(false));

    return map;
  }

  // ── Request helpers
  // ───────────────────────────────────────────────────────────
  /**
   * Returns true when the request is a scalar /$count request (GET /EntityDefinition/$count,
   * optionally with $filter). Such a request arrives as a SELECT with a single column that is the
   * COUNT aggregate function (verified from the CQN: {"func":"COUNT","as":"count"}).
   */
  private boolean isScalarCountRequest(CqnSelect select) {
    var items = select.items();
    if (items.size() != 1) {
      return false;
    }
    return items
        .get(0)
        .ofValue()
        .map(CqnSelectListValue::value)
        .filter(CqnValue::isFunction)
        .anyMatch(v -> "count".equalsIgnoreCase(v.asFunction().func()));
  }

  // ── Filter expression parsers ────────────────────────────────────────────────
  /** Holds the parsed column name and value from a filter expression. */
  private record FilterParts(String column, String value) {}

  /** Parses "contains(field, 'value')" → FilterParts or null on mismatch. */
  private FilterParts parseContains(String expr) {
    var m =
        java.util.regex.Pattern.compile("contains\\(\\s*(\\w+)\\s*,\\s*'([^']*)'\\s*\\)")
            .matcher(expr.trim());
    return m.matches() ? new FilterParts(m.group(1), m.group(2)) : null;
  }

  /** Parses "field eq 'value'" → FilterParts or null on mismatch. */
  private FilterParts parseEq(String expr) {
    var m = java.util.regex.Pattern.compile("(\\w+)\\s+eq\\s+'([^']*)'").matcher(expr.trim());
    return m.matches() ? new FilterParts(m.group(1), m.group(2)) : null;
  }
}
