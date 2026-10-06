/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Integration tests for {@link com.sap.cds.feature.datainspector.EntityDefinitionReader}. */
class EntityDefinitionReaderIT extends AbstractDataInspectorIT {

  // ═════════════════════════════════════════════════════════════
  // Authorization
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET authorization scope — 200 with role, 403 without role, 401 anonymous")
  void authorizationScope() throws Exception {
    assertThat(getAsAlice("/EntityDefinition").getResponse().getStatus()).isEqualTo(200);
    assertThat(getAsBob("/EntityDefinition").getResponse().getStatus()).isEqualTo(403);
    assertThat(getAnonymous("/EntityDefinition").getResponse().getStatus()).isEqualTo(401);
  }

  // ═════════════════════════════════════════════════════════════
  // Query by ID
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinition('...') — all fields, $select variants, and 404")
  void queryById() throws Exception {
    JsonNode all = getJsonAsAlice("/EntityDefinition('datainspector.test.db.Product')");
    assertThat(all.get("name").asText()).isEqualTo("datainspector.test.db.Product");
    assertThat(all.get("title").asText()).isNotEmpty();
    assertThat(all.get("elements").isArray()).isTrue();
    assertThat(all.get("dataSource").asText()).isNotEmpty();

    // $select=name — 'name' is the key and is always returned non-null;
    // title, elements, dataSource must be absent (null).
    JsonNode selectName =
        getJsonAsAlice("/EntityDefinition('datainspector.test.db.Product')?$select=name");
    assertThat(selectName.get("name") != null && !selectName.get("name").isNull()).isTrue();
    assertThat(selectName.get("title") != null && !selectName.get("title").isNull()).isFalse();
    assertThat(selectName.get("elements") != null && !selectName.get("elements").isNull())
        .isFalse();
    assertThat(selectName.get("dataSource") != null && !selectName.get("dataSource").isNull())
        .isFalse();

    // $select=title — 'name' still returned (key); title returned; others absent.
    JsonNode selectTitle =
        getJsonAsAlice("/EntityDefinition('datainspector.test.db.Product')?$select=title");
    assertThat(selectTitle.get("name") != null && !selectTitle.get("name").isNull()).isTrue();
    assertThat(selectTitle.get("title") != null && !selectTitle.get("title").isNull()).isTrue();
    assertThat(selectTitle.get("elements") != null && !selectTitle.get("elements").isNull())
        .isFalse();
    assertThat(selectTitle.get("dataSource") != null && !selectTitle.get("dataSource").isNull())
        .isFalse();

    // $select=elements — 'name' still returned (key); elements returned; others absent.
    JsonNode selectElements =
        getJsonAsAlice("/EntityDefinition('datainspector.test.db.Product')?$select=elements");
    assertThat(selectElements.get("name") != null && !selectElements.get("name").isNull()).isTrue();
    assertThat(selectElements.get("title") != null && !selectElements.get("title").isNull())
        .isFalse();
    assertThat(selectElements.get("elements") != null && !selectElements.get("elements").isNull())
        .isTrue();
    assertThat(
            selectElements.get("dataSource") != null && !selectElements.get("dataSource").isNull())
        .isFalse();

    // $select=dataSource — 'name' still returned (key); dataSource returned; others absent.
    JsonNode selectDs =
        getJsonAsAlice("/EntityDefinition('datainspector.test.db.Product')?$select=dataSource");
    assertThat(selectDs.get("name") != null && !selectDs.get("name").isNull()).isTrue();
    assertThat(selectDs.get("title") != null && !selectDs.get("title").isNull()).isFalse();
    assertThat(selectDs.get("elements") != null && !selectDs.get("elements").isNull()).isFalse();
    assertThat(selectDs.get("dataSource") != null && !selectDs.get("dataSource").isNull()).isTrue();

    int notFound =
        getAsAlice("/EntityDefinition('datainspector.test.db.Orange')").getResponse().getStatus();
    assertThat(notFound).as("expected 404 for non-existent entity").isEqualTo(404);
  }

  // ═════════════════════════════════════════════════════════════
  // $count
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $count — with, without, and /$count path")
  void count() throws Exception {
    // CAP Java returns the inline count under "@count" when $count=true is set.
    JsonNode withCount = getJsonAsAlice("/EntityDefinition?$count=true");
    long total = withCount.get("@count").asLong();
    assertThat(total).isGreaterThan(0);
    assertThat(withCount.get("value").size()).isEqualTo((int) total);

    // Without $count=true there is no "@count" in the response.
    JsonNode noCount = getJsonAsAlice("/EntityDefinition");
    assertThat(noCount.get("@count")).isNull();
    assertThat(noCount.get("value").size()).isEqualTo((int) total);

    // /$count path — returns a plain integer equal to the total entity count
    var countResult = getAsAlice("/EntityDefinition/$count");
    assertThat(countResult.getResponse().getStatus()).isEqualTo(200);
    int raw = Integer.parseInt(countResult.getResponse().getContentAsString().trim());
    assertThat(raw).isEqualTo((int) total);

    // /$count with $filter — must return a smaller or equal count
    var filteredCountResult =
        getAsAlice("/EntityDefinition/$count?$filter=contains(name, 'Product')");
    assertThat(filteredCountResult.getResponse().getStatus()).isEqualTo(200);
    int filteredRaw =
        Integer.parseInt(filteredCountResult.getResponse().getContentAsString().trim());
    assertThat(filteredRaw)
        .as("filtered /$count must be <= total count")
        .isLessThanOrEqualTo((int) total);
    assertThat(filteredRaw)
        .as("filtered /$count must be > 0 (Product entities exist)")
        .isGreaterThan(0);

    // $count=true combined with $filter=name eq 'X' (key filter)
    JsonNode keyFilterCount =
        getJsonAsAlice(
            "/EntityDefinition?$filter=name eq 'datainspector.test.db.Product'&$select=name&$count=true");
    assertThat(keyFilterCount.get("@count").asLong())
        .as("name eq 'X' with $count=true must return exactly 1")
        .isEqualTo(1);
    assertThat(keyFilterCount.get("value").get(0).get("name").asText())
        .isEqualTo("datainspector.test.db.Product");
  }

  // ═════════════════════════════════════════════════════════════
  // $select (collection)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $select — column projection on collection")
  void selectFieldsOnCollection() throws Exception {
    JsonNode all = getJsonAsAlice("/EntityDefinition");
    JsonNode first = all.get("value").get(0);
    assertThat(first.get("name").asText()).isNotEmpty();
    assertThat(first.get("title").asText()).isNotEmpty();
    assertThat(first.get("elements").isArray()).isTrue();
    assertThat(first.get("dataSource").asText()).isNotEmpty();

    // $select=name — every row has name (key); title, elements, dataSource absent.
    JsonNode selectName = getJsonAsAlice("/EntityDefinition?$select=name");
    JsonNode rowName = selectName.get("value").get(0);
    assertThat(rowName.get("name") != null && !rowName.get("name").isNull()).isTrue();
    assertThat(rowName.get("title") != null && !rowName.get("title").isNull()).isFalse();
    assertThat(rowName.get("elements") != null && !rowName.get("elements").isNull()).isFalse();
    assertThat(rowName.get("dataSource") != null && !rowName.get("dataSource").isNull()).isFalse();

    // $select=title — name always returned (key); title returned; others absent.
    JsonNode selectTitle = getJsonAsAlice("/EntityDefinition?$select=title");
    JsonNode rowTitle = selectTitle.get("value").get(0);
    assertThat(rowTitle.get("name") != null && !rowTitle.get("name").isNull()).isTrue();
    assertThat(rowTitle.get("title") != null && !rowTitle.get("title").isNull()).isTrue();
    assertThat(rowTitle.get("elements") != null && !rowTitle.get("elements").isNull()).isFalse();
    assertThat(rowTitle.get("dataSource") != null && !rowTitle.get("dataSource").isNull())
        .isFalse();

    // $select=elements — name always returned (key); elements returned; others absent.
    JsonNode selectElements = getJsonAsAlice("/EntityDefinition?$select=elements");
    JsonNode rowElements = selectElements.get("value").get(0);
    assertThat(rowElements.get("name") != null && !rowElements.get("name").isNull()).isTrue();
    assertThat(rowElements.get("title") != null && !rowElements.get("title").isNull()).isFalse();
    assertThat(rowElements.get("elements") != null && !rowElements.get("elements").isNull())
        .isTrue();
    assertThat(rowElements.get("dataSource") != null && !rowElements.get("dataSource").isNull())
        .isFalse();

    // $select=dataSource — name always returned (key); dataSource returned; others absent.
    JsonNode selectDs = getJsonAsAlice("/EntityDefinition?$select=dataSource");
    JsonNode rowDs = selectDs.get("value").get(0);
    assertThat(rowDs.get("name") != null && !rowDs.get("name").isNull()).isTrue();
    assertThat(rowDs.get("title") != null && !rowDs.get("title").isNull()).isFalse();
    assertThat(rowDs.get("elements") != null && !rowDs.get("elements").isNull()).isFalse();
    assertThat(rowDs.get("dataSource") != null && !rowDs.get("dataSource").isNull()).isTrue();
  }

  // ═════════════════════════════════════════════════════════════
  // $filter (happy paths)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $filter — eq, contains, combined 'and'")
  void filterHappyPaths() throws Exception {
    // name eq
    JsonNode r0 =
        getJsonAsAlice("/EntityDefinition?$filter=name eq 'datainspector.test.db.Product'");
    assertThat(r0.get("value").size()).isEqualTo(1);
    assertThat(r0.get("value").get(0).get("name").asText())
        .isEqualTo("datainspector.test.db.Product");

    // contains(name, 'X')
    JsonNode r1 = getJsonAsAlice("/EntityDefinition?$filter=contains(name, 'Product')");
    assertThat(r1.get("value").size()).isGreaterThan(1);
    for (JsonNode row : r1.get("value")) {
      assertThat(row.get("name").asText()).contains("Product");
    }

    // Non-existent → empty
    assertThat(
            getJsonAsAlice("/EntityDefinition?$filter=name eq 'datainspector.test.db.Offer'")
                .get("value")
                .size())
        .isZero();
    assertThat(
            getJsonAsAlice(
                    "/EntityDefinition?$filter=contains(name, 'datainspector.test.db.Offer')")
                .get("value")
                .size())
        .isZero();

    // title eq — Product entity has @title: 'Products'
    JsonNode r6 = getJsonAsAlice("/EntityDefinition?$filter=title eq 'Products'");
    assertThat(r6.get("value").size()).isGreaterThanOrEqualTo(1);
    assertThat(r6.get("value").get(0).get("title").asText()).isEqualTo("Products");

    // contains(title, 'Product')
    JsonNode r7 = getJsonAsAlice("/EntityDefinition?$filter=contains(title, 'Product')");
    assertThat(r7.get("value").size()).isGreaterThanOrEqualTo(1);
    for (JsonNode row : r7.get("value")) {
      assertThat(row.get("title").asText()).contains("Product");
    }

    // dataSource eq 'db'
    JsonNode r8 = getJsonAsAlice("/EntityDefinition?$filter=dataSource eq 'db'");
    assertThat(r8.get("value").size()).isGreaterThan(0);
    for (JsonNode row : r8.get("value")) {
      assertThat(row.get("dataSource").asText()).isEqualTo("db");
    }

    // contains(dataSource, 'serv')
    JsonNode r9 = getJsonAsAlice("/EntityDefinition?$filter=contains(dataSource, 'serv')");
    assertThat(r9.get("value").size()).isGreaterThan(0);
    for (JsonNode row : r9.get("value")) {
      assertThat(row.get("dataSource").asText()).isEqualTo("service");
    }

    // contains(dataSource, 'unknown') — entity with @cds.persistence.skip → dataSource=unknown
    JsonNode r10 = getJsonAsAlice("/EntityDefinition?$filter=contains(dataSource, 'unknown')");
    assertThat(r10.get("value").size()).isGreaterThanOrEqualTo(1);
    assertThat(r10.get("value").get(0).get("dataSource").asText()).isEqualTo("unknown");

    // Combined "dataSource eq 'service' and contains(name, 'Product')"
    JsonNode r11 =
        getJsonAsAlice(
            "/EntityDefinition?$filter=dataSource eq 'service' and contains(name, 'Product')");
    assertThat(r11.get("value").size()).isGreaterThan(0);
    for (JsonNode row : r11.get("value")) {
      assertThat(row.get("dataSource").asText()).isEqualTo("service");
      assertThat(row.get("name").asText()).contains("Product");
    }

    // Reversed clause order: normalised to the same result.
    JsonNode r12 =
        getJsonAsAlice(
            "/EntityDefinition?$filter=contains(name, 'Product') and dataSource eq 'service'");
    assertThat(r12.get("value").size())
        .as("reversed AND filter result count should be same")
        .isEqualTo(r11.get("value").size());
  }

  // ═════════════════════════════════════════════════════════════
  // $filter (rejected patterns)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $filter — rejects unsupported column")
  void filterRejections() throws Exception {
    assertThat(
            getAsAlice("/EntityDefinition?$filter=contains(elements, 'test')")
                .getResponse()
                .getStatus())
        .as("contains(elements, ...) must be rejected with 400")
        .isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // $orderby
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $orderby — name asc/desc, rejects unsupported columns")
  void orderby() throws Exception {
    JsonNode asc = getJsonAsAlice("/EntityDefinition?$orderby=name");
    String n0 = asc.get("value").get(0).get("name").asText();
    String n1 = asc.get("value").get(1).get("name").asText();
    assertThat(n0.compareTo(n1)).as("expected name asc").isLessThan(0);

    JsonNode desc = getJsonAsAlice("/EntityDefinition?$orderby=name desc");
    String d0 = desc.get("value").get(0).get("name").asText();
    String d1 = desc.get("value").get(1).get("name").asText();
    assertThat(d0.compareTo(d1)).as("expected name desc").isGreaterThan(0);

    // title, elements, dataSource are not sortable → 400
    assertThat(getAsAlice("/EntityDefinition?$orderby=title").getResponse().getStatus())
        .as("$orderby=title should be rejected")
        .isEqualTo(400);
    assertThat(getAsAlice("/EntityDefinition?$orderby=elements").getResponse().getStatus())
        .as("$orderby=elements should be rejected")
        .isEqualTo(400);
    assertThat(getAsAlice("/EntityDefinition?$orderby=dataSource").getResponse().getStatus())
        .as("$orderby=dataSource should be rejected")
        .isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // $skip / $top
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET EntityDefinitions $skip $top — server-side pagination")
  void skipTop() throws Exception {
    JsonNode r0 = getJsonAsAlice("/EntityDefinition?$skip=0&$top=10");
    assertThat(r0.get("value").size()).isEqualTo(10);

    JsonNode r1 = getJsonAsAlice("/EntityDefinition?$skip=0&$top=4");
    assertThat(r1.get("value").size()).isEqualTo(4);
    assertThat(r1.get("value").get(0).get("name").asText())
        .isEqualTo(r0.get("value").get(0).get("name").asText());
    assertThat(r1.get("value").get(3).get("name").asText())
        .isEqualTo(r0.get("value").get(3).get("name").asText());

    JsonNode r2 = getJsonAsAlice("/EntityDefinition?$skip=4&$top=4");
    assertThat(r2.get("value").size()).isEqualTo(4);
    assertThat(r2.get("value").get(0).get("name").asText())
        .isEqualTo(r0.get("value").get(4).get("name").asText());
    assertThat(r2.get("value").get(3).get("name").asText())
        .isEqualTo(r0.get("value").get(7).get("name").asText());
  }

  @Test
  @DisplayName("GET EntityDefinitions $skip / $top — malformed, negative or overflowing → 400")
  void skipTopNegativeAndMalformed() throws Exception {
    // Malformed, negative and out-of-int-range pagination parameters must be rejected with 400,
    // never escaping as a 500 (see EntityDefinitionReader.parseNonNegativeInt).
    for (String query :
        new String[] {
          "$top=abc", // non-numeric $top
          "$skip=abc", // non-numeric $skip
          "$top=-1", // negative $top
          "$skip=-1", // negative $skip
          "$top=99999999999", // overflows int
          "$skip=99999999999" // overflows int
        }) {
      assertThat(getAsAlice("/EntityDefinition?" + query).getResponse().getStatus())
          .as("invalid pagination parameter '%s' must be rejected with 400", query)
          .isEqualTo(400);
    }
  }

  // ═════════════════════════════════════════════════════════════
  // Elements introspection (CdsCoreTypes)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "GET EntityDefinition('CdsCoreTypes') — element flags, defaults, length, hidden, virtual")
  void elementsIntrospection() throws Exception {
    JsonNode e =
        getJsonAsAlice("/EntityDefinition('datainspector.test.db.CdsCoreTypes')?$select=elements");
    JsonNode elements = e.get("elements");
    assertThat(elements.isArray()).isTrue();

    JsonNode uuid = findElementByName(elements, "uuid");
    assertThat(uuid).as("uuid element must be present").isNotNull();
    assertThat(uuid.get("isKey").asBoolean()).isTrue();

    JsonNode bool = findElementByName(elements, "boolValue");
    assertThat(bool).as("boolValue element must be present").isNotNull();
    assertThat(bool.get("isNotNull").asBoolean()).isTrue();

    JsonNode integer = findElementByName(elements, "integer");
    assertThat(integer).as("integer element must be present").isNotNull();
    assertThat(integer.get("defaultValue").asText()).contains("8");

    JsonNode date = findElementByName(elements, "date");
    assertThat(date).as("date element must be present").isNotNull();
    assertThat(date.get("isSensitive").asBoolean()).isTrue();

    // hiddenField is @HideFromDataInspector so must be excluded
    JsonNode hidden = findElementByName(elements, "hiddenField");
    assertThat(hidden).as("hiddenField must be excluded").isNull();

    JsonNode virtualField = findElementByName(elements, "virtualField");
    assertThat(virtualField).as("virtualField element must be present").isNotNull();
    assertThat(virtualField.get("isVirtual").asBoolean()).isTrue();

    // cds.String(88) — length facet from CdsSimpleType.get("length")
    JsonNode string = findElementByName(elements, "string");
    assertThat(string).as("string element must be present").isNotNull();
    assertThat(string.get("length").asInt()).isEqualTo(88);
    assertThat(string.get("type").asText()).isEqualTo("cds.String");
    assertThat(string.get("isKey").asBoolean()).isFalse();
    assertThat(string.get("isNotNull").asBoolean()).isFalse();
    assertThat(string.get("defaultValue").isNull()).isTrue();
    assertThat(string.get("isSensitive").asBoolean()).isFalse();
    assertThat(string.get("isVirtual").asBoolean()).isFalse();
  }

  // ═════════════════════════════════════════════════════════════
  // DraftAdministrativeData
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET DraftAdministrativeData Entities — hidden by-key and by-filter")
  void draftEntitiesHidden() throws Exception {
    JsonNode parent =
        getJsonAsAlice("/EntityDefinition('datainspector.test.srv.ProductService.Product')");
    assertThat(parent.get("name").asText())
        .isEqualTo("datainspector.test.srv.ProductService.Product");

    int status =
        getAsAlice(
                "/EntityDefinition('datainspector.test.srv.ProductService.Product.DraftAdministrativeData')")
            .getResponse()
            .getStatus();
    assertThat(status).as("DraftAdministrativeData entity must return 404").isEqualTo(404);

    JsonNode fromFilter =
        getJsonAsAlice("/EntityDefinition?$filter=contains(name, '.DraftAdministrativeData')");
    assertThat(fromFilter.get("value").size())
        .as("no DraftAdministrativeData entities should ever be listed")
        .isZero();
  }

  // ═════════════════════════════════════════════════════════════
  // Helpers
  // ═════════════════════════════════════════════════════════════

  /** Finds the first element sub-object with the given name inside an 'elements' array. */
  private JsonNode findElementByName(JsonNode elements, String name) {
    for (JsonNode el : elements) {
      if (name.equals(el.get("name").asText())) return el;
    }
    return null;
  }
}
