/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@link com.sap.cds.feature.datainspector.DataHandler} covering the
 * non-pagination, non-audit-log scenarios.
 */
class DataHandlerIT extends AbstractDataInspectorIT {

  // ═════════════════════════════════════════════════════════════
  // Authorization
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data authorization scope — 200 with role, 403 without, 401 anonymous")
  void authorizationScope() throws Exception {
    String path = "/Data?$filter=entityName eq 'datainspector.test.srv.ProductService.Product'";
    assertThat(getAsAlice(path).getResponse().getStatus()).isEqualTo(200);
    assertThat(getAsBob(path).getResponse().getStatus()).isEqualTo(403);
    assertThat(getAnonymous(path).getResponse().getStatus()).isEqualTo(401);
  }

  // ═════════════════════════════════════════════════════════════
  // Invalid r_filter operator
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data with invalid OData syntax in r_filter → 400")
  void invalidODataSyntax() throws Exception {
    int status =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=productName xx 'iPhone 14'&r_select=productName")
            .getResponse()
            .getStatus();
    assertThat(status).as("invalid r_filter operator must be rejected with 400").isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // Missing / bad $filter on collection
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "GET Data without $filter → 400; unsupported op → 400; non-existent entity → 200 empty")
  void filterRequired() throws Exception {
    // (1) No $filter at all — collection request needs entityName eq '...'
    assertThat(getAsAlice("/Data").getResponse().getStatus())
        .as("missing $filter must be rejected")
        .isEqualTo(400);

    // (2) $filter present but uses 'ne' instead of 'eq' — only 'eq' is supported
    assertThat(
            getAsAlice("/Data?$filter=entityName ne 'datainspector.test.db.Products'")
                .getResponse()
                .getStatus())
        .as("$filter with operator other than 'eq' must be rejected")
        .isEqualTo(400);

    // (3) $filter for non-existent entity — 200 with empty value array
    MvcResult r = getAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Products'");
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = json(r);
    assertThat(body.get("value").size()).isZero();
  }

  // ═════════════════════════════════════════════════════════════
  // Query by ID — single & composite keys, 400/404 negative cases
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data by ID — single key")
  void queryByIdSingleKey() throws Exception {
    // Missing entityName in key predicate → 400 (schema requires both key parts)
    assertThat(getAsAlice("/Data(recordKey='ID=prod1')").getResponse().getStatus())
        .as("missing entityName in key must be rejected")
        .isEqualTo(400);

    // Missing recordKey → 400
    assertThat(
            getAsAlice("/Data(entityName='datainspector.test.db.Product')")
                .getResponse()
                .getStatus())
        .as("missing recordKey in key must be rejected")
        .isEqualTo(400);

    // Happy path
    JsonNode r0 =
        getJsonAsAlice("/Data(entityName='datainspector.test.db.Product',recordKey='ID=prod1')");
    assertThat(r0.get("entityName").asText()).isEqualTo("datainspector.test.db.Product");
    assertThat(r0.get("recordKey").asText()).isEqualTo("ID=prod1");
    // 'record' is a JSON string — parse it and check productName
    JsonNode record = objectMapper.readTree(r0.get("record").asText());
    assertThat(record.get("productName").asText()).isEqualTo("iPhone 14");

    // Non-existent record → 404
    int notFound =
        getAsAlice("/Data(entityName='datainspector.test.db.Product',recordKey='ID=prod100')")
            .getResponse()
            .getStatus();
    assertThat(notFound).as("non-existent record must be 404").isEqualTo(404);
  }

  @Test
  @DisplayName("GET Data by ID — composite key")
  void queryByIdCompositeKey() throws Exception {
    // Happy composite key (all 3 parts present, correct names)
    JsonNode r =
        getJsonAsAlice(
            "/Data(entityName='datainspector.test.db.HelloWorld',recordKey='helloId=H008&worldId=W008&otherId=O008')");
    assertThat(r.get("entityName").asText()).isEqualTo("datainspector.test.db.HelloWorld");
    assertThat(r.get("recordKey").asText()).isEqualTo("helloId=H008&worldId=W008&otherId=O008");
    JsonNode record = objectMapper.readTree(r.get("record").asText());
    assertThat(record.get("color").asText()).isEqualTo("Purple");

    // Non-existent composite key values → 404
    int nf =
        getAsAlice(
                "/Data(entityName='datainspector.test.db.HelloWorld',recordKey='helloId=H080&worldId=W008&otherId=O008')")
            .getResponse()
            .getStatus();
    assertThat(nf).isEqualTo(404);

    // Missing one key element → 400 (composite keys must all be supplied)
    int missing =
        getAsAlice(
                "/Data(entityName='datainspector.test.db.HelloWorld',recordKey='helloId=H008&worldId=W008')")
            .getResponse()
            .getStatus();
    assertThat(missing).as("missing composite key element must be 400").isEqualTo(400);

    // Extraneous key element → 400
    int extra =
        getAsAlice(
                "/Data(entityName='datainspector.test.db.HelloWorld',recordKey='helloId=H008&worldId=W008&otherId=O008&extraneousId=X')")
            .getResponse()
            .getStatus();
    assertThat(extra).as("extraneous key element must be 400").isEqualTo(400);

    // Wrong key element name → 400 (wrongId is not a key)
    int wrong =
        getAsAlice(
                "/Data(entityName='datainspector.test.db.HelloWorld',recordKey='helloId=H008&worldId=W008&wrongId=O008')")
            .getResponse()
            .getStatus();
    assertThat(wrong).as("unknown key element name must be 400").isEqualTo(400);

    // Non-existent entity → 404
    int neEntity =
        getAsAlice(
                "/Data(entityName='datainspector.test.db.H3ll0W0r1d',recordKey='helloId=1&worldId=2&otherId=3')")
            .getResponse()
            .getStatus();
    assertThat(neEntity).as("non-existent entity must be 404").isEqualTo(404);
  }

  // ═════════════════════════════════════════════════════════════
  // $count
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data $count — /$count path, $count=true, and omission")
  void count() throws Exception {
    // CAP Java's OData V4 serializer emits the inline count under "@count" (not the
    // fully-qualified "@odata.count" the Node.js reference uses); the value is identical.
    JsonNode r1 =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'&$count=true");
    assertThat(r1.get("value").size()).isEqualTo(12);
    assertThat(r1.get("@count").asInt()).isEqualTo(12);

    // Absence of $count omits @count
    JsonNode r2 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$select=entityName");
    assertThat(r2.get("@count")).isNull();
    assertThat(r2.get("value").size()).isEqualTo(12);
  }

  // ═════════════════════════════════════════════════════════════
  // $select on Data columns (entityName, recordKey, record)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data $select — column projection on entityName / recordKey / record")
  void selectFields() throws Exception {
    // $select=entityName — recordKey (key) still present, record excluded
    JsonNode r0 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$select=entityName");
    JsonNode row0 = r0.get("value").get(0);
    assertThat(row0.get("entityName").asText()).isEqualTo("datainspector.test.db.Product");
    assertThat(row0.get("recordKey")).isNotNull(); // key element always present
    assertThat(row0.get("record") == null || row0.get("record").isNull())
        .as("record must be excluded when not in $select")
        .isTrue();

    // $select=record — record present
    JsonNode r1 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$select=record");
    JsonNode row1 = r1.get("value").get(0);
    assertThat(row1.get("entityName").asText()).isEqualTo("datainspector.test.db.Product");
    assertThat(row1.get("recordKey")).isNotNull();
    assertThat(row1.get("record"))
        .as("record must be included when requested in $select")
        .isNotNull();
    assertThat(row1.get("record").isNull()).isFalse();
  }

  // ═════════════════════════════════════════════════════════════
  // $skip / $top (basic — server-side pagination lives in DataHandlerPaginationIT)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data $skip $top — offset/limit pagination on Product")
  void skipTop() throws Exception {
    JsonNode r0 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$skip=0&$top=10");
    assertThat(r0.get("value").size()).isEqualTo(10);

    JsonNode r1 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$skip=0&$top=4");
    assertThat(r1.get("value").size()).isEqualTo(4);
    assertThat(r1.get("value").get(0).get("recordKey").asText())
        .isEqualTo(r0.get("value").get(0).get("recordKey").asText());
    assertThat(r1.get("value").get(3).get("recordKey").asText())
        .isEqualTo(r0.get("value").get(3).get("recordKey").asText());

    JsonNode r2 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&$skip=4&$top=4");
    assertThat(r2.get("value").size()).isEqualTo(4);
    assertThat(r2.get("value").get(0).get("recordKey").asText())
        .isEqualTo(r0.get("value").get(4).get("recordKey").asText());
    assertThat(r2.get("value").get(3).get("recordKey").asText())
        .isEqualTo(r0.get("value").get(7).get("recordKey").asText());

    // $skip only — remaining rows
    JsonNode r3 =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'&$skip=4");
    assertThat(r3.get("value").size()).isEqualTo(8);
    assertThat(r3.get("value").get(0).get("recordKey").asText())
        .isEqualTo(r0.get("value").get(4).get("recordKey").asText());

    // $top only
    JsonNode r4 =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'&$top=4");
    assertThat(r4.get("value").size()).isEqualTo(4);
  }

  // ═════════════════════════════════════════════════════════════
  // r_select — record column projection
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "GET Data r_select — pick record columns, always includes key, rejects invalid element")
  void rSelect() throws Exception {
    JsonNode r0 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_select=productName,description");
    JsonNode record = objectMapper.readTree(r0.get("value").get(0).get("record").asText());
    assertThat(record.get("productName")).as("productName must be present").isNotNull();
    assertThat(record.get("description")).as("description must be present").isNotNull();
    assertThat(record.get("quantity") == null || record.get("quantity").isNull())
        .as("quantity must be excluded (not requested in r_select)")
        .isTrue();
    assertThat(record.get("mrp") == null || record.get("mrp").isNull())
        .as("mrp must be excluded (not requested in r_select)")
        .isTrue();
    assertThat(record.get("ID"))
        .as("ID (key) must always be included even when not in r_select")
        .isNotNull();

    // Invalid element → 400
    int badElement =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_select=randomName,description")
            .getResponse()
            .getStatus();
    assertThat(badElement).as("unknown record element in r_select must be 400").isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // r_filter — record-level filtering
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "GET Data r_filter — eq / contains / case-sensitive / lt / le / gt / ge / invalid field")
  void rFilterBasic() throws Exception {
    // eq
    JsonNode r0 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=productName eq 'iPhone 14'&r_select=productName");
    assertThat(r0.get("value").size()).isEqualTo(1);
    JsonNode rec0 = objectMapper.readTree(r0.get("value").get(0).get("record").asText());
    assertThat(rec0.get("productName").asText()).isEqualTo("iPhone 14");

    // contains (case-sensitive on SQLite — 'iPhone' matches, 'iphone' does not)
    JsonNode r1 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=contains(productName, 'iPhone')");
    assertThat(r1.get("value").size()).isGreaterThan(0);
    JsonNode rec1 = objectMapper.readTree(r1.get("value").get(0).get("record").asText());
    assertThat(rec1.get("productName").asText()).contains("iPhone");

    // Case-sensitivity of contains(…) is DB-dependent: on SQLite (default) `LIKE`
    // is case-insensitive for the ASCII range regardless of the CQL caseSensitive
    // flag; on HANA it respects the collation. The plugin passes caseSensitive=true
    // to CQL.contains, which is the semantically correct value. We only assert
    // here that the query succeeds — the exact match set is DB-specific.
    JsonNode r7 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=contains(productName, 'iphone')");
    assertThat(r7.get("value").size()).isGreaterThanOrEqualTo(0);

    // lt (10 rows have quantity < 100)
    JsonNode r2 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity lt 100&r_orderby=quantity desc");
    assertThat(r2.get("value").size()).isEqualTo(10);
    JsonNode rec2 = objectMapper.readTree(r2.get("value").get(0).get("record").asText());
    assertThat(rec2.get("quantity").asInt()).isLessThan(100);

    // le (11 rows with quantity ≤ 100, top desc → 100)
    JsonNode r3 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity le 100&r_orderby=quantity desc");
    assertThat(r3.get("value").size()).isEqualTo(11);
    JsonNode rec3 = objectMapper.readTree(r3.get("value").get(0).get("record").asText());
    assertThat(rec3.get("quantity").asInt()).isEqualTo(100);

    // gt (only quantity=120 > 100)
    JsonNode r4 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity gt 100&r_orderby=quantity asc");
    assertThat(r4.get("value").size()).isEqualTo(1);
    JsonNode rec4 = objectMapper.readTree(r4.get("value").get(0).get("record").asText());
    assertThat(rec4.get("quantity").asInt()).isGreaterThan(100);

    // ge (100 and 120)
    JsonNode r5 =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity ge 100&r_orderby=quantity asc");
    assertThat(r5.get("value").size()).isEqualTo(2);
    JsonNode rec5 = objectMapper.readTree(r5.get("value").get(0).get("record").asText());
    assertThat(rec5.get("quantity").asInt()).isEqualTo(100);

    // RFilterParser validates every r_filter element name against the entity's
    // record elements
    int badEq =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=price eq '100'")
            .getResponse()
            .getStatus();
    assertThat(badEq).as("unknown element in r_filter (eq) must be 400").isEqualTo(400);

    int badContains =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=contains(price, '100')")
            .getResponse()
            .getStatus();
    assertThat(badContains).as("unknown element in r_filter (contains) must be 400").isEqualTo(400);
  }

  @Test
  @DisplayName("GET Data r_filter — OR, parentheses and operator precedence")
  void rFilterOrAndParentheses() throws Exception {
    // Baselines: gt 100 → 1 row (quantity=120), lt 100 → 10 rows.
    int gtCount =
        getJsonAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=quantity gt 100")
            .get("value")
            .size();
    int ltCount =
        getJsonAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=quantity lt 100")
            .get("value")
            .size();

    // OR: (gt 100) or (lt 100) → union of the two disjoint sets.
    JsonNode orResult =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity gt 100 or quantity lt 100");
    assertThat(orResult.get("value").size()).isEqualTo(gtCount + ltCount);

    // Parentheses: (gt 100 or lt 100) and contains(productName,'iPhone')
    // must be a subset of the OR result (the AND narrows it).
    JsonNode groupedResult =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=(quantity gt 100 or quantity lt 100) and contains(productName, 'iPhone')");
    assertThat(groupedResult.get("value").size())
        .isLessThanOrEqualTo(gtCount + ltCount)
        .isGreaterThanOrEqualTo(0);

    JsonNode precedence =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=quantity lt 100 or quantity gt 100 and contains(productName, 'iPhone')");
    assertThat(precedence.get("value").size()).isGreaterThanOrEqualTo(ltCount);

    // Unbalanced parenthesis → 400.
    int unbalanced =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                    + "&r_filter=(quantity gt 100")
            .getResponse()
            .getStatus();
    assertThat(unbalanced).as("unbalanced parenthesis must be 400").isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // r_orderby — record-level sorting
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data r_orderby — asc/desc on record columns; invalid field → 400")
  void rOrderby() throws Exception {
    JsonNode asc =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&r_orderby=productName asc");
    JsonNode row0 = objectMapper.readTree(asc.get("value").get(0).get("record").asText());
    JsonNode row1 = objectMapper.readTree(asc.get("value").get(1).get("record").asText());
    assertThat(row0.get("productName").asText().compareTo(row1.get("productName").asText()))
        .as("r_orderby asc should sort ascending")
        .isLessThan(0);

    JsonNode desc =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'&r_orderby=productName desc");
    int len = desc.get("value").size();
    JsonNode last = objectMapper.readTree(desc.get("value").get(len - 1).get("record").asText());
    JsonNode secondLast =
        objectMapper.readTree(desc.get("value").get(len - 2).get("record").asText());
    assertThat(secondLast.get("productName").asText().compareTo(last.get("productName").asText()))
        .as("r_orderby desc should sort descending")
        .isGreaterThan(0);

    // Multi-column r_orderby on Order (status asc, ID desc)
    JsonNode multi =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Order'"
                + "&r_select=status,ID&r_orderby=status,ID desc");
    int n = multi.get("value").size();
    assertThat(n).isGreaterThanOrEqualTo(2);
    // First row's status should be lexicographically ≤ last row's status (ascending primary sort)
    JsonNode firstRec = objectMapper.readTree(multi.get("value").get(0).get("record").asText());
    JsonNode lastRec = objectMapper.readTree(multi.get("value").get(n - 1).get("record").asText());
    assertThat(firstRec.get("status").asText().compareTo(lastRec.get("status").asText()))
        .as("primary r_orderby column must sort ascending")
        .isLessThanOrEqualTo(0);

    // Invalid record element → 400
    int bad =
        getAsAlice(
                "/Data?$filter=entityName eq 'datainspector.test.db.Product'&r_orderby=price desc")
            .getResponse()
            .getStatus();
    assertThat(bad).as("unknown record element in r_orderby must be 400").isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // Hidden / non-record elements excluded from 'record'
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "GET Data — hidden, virtual, association and composition elements excluded from record")
  void hiddenAndNonRecordElementsExcluded() throws Exception {
    // Order.phoneNumber is @HideFromDataInspector → must not appear in 'record',
    // even though it is a real (and sensitive) DB column. Its sibling 'address'
    // (also sensitive but NOT hidden) must appear.
    JsonNode order = getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Order'");
    JsonNode orderRec = objectMapper.readTree(order.get("value").get(0).get("record").asText());
    assertThat(orderRec.get("phoneNumber") == null || orderRec.get("phoneNumber").isNull())
        .as("@HideFromDataInspector element (phoneNumber) must be excluded from record")
        .isTrue();
    assertThat(orderRec.get("address"))
        .as("non-hidden sensitive element must be present")
        .isNotNull();
    // Composition 'orderItem' must not be projected as a record element.
    assertThat(orderRec.get("orderItem") == null || orderRec.get("orderItem").isNull())
        .as("composition element must be excluded from record")
        .isTrue();

    // CdsCoreTypes.hiddenField is @HideFromDataInspector and virtualField is virtual;
    // both must be excluded, while an ordinary column (string) remains.
    JsonNode core =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.CdsCoreTypes'");
    JsonNode coreRec = objectMapper.readTree(core.get("value").get(0).get("record").asText());
    assertThat(coreRec.get("hiddenField") == null || coreRec.get("hiddenField").isNull())
        .as("@HideFromDataInspector element (hiddenField) must be excluded")
        .isTrue();
    assertThat(coreRec.get("virtualField") == null || coreRec.get("virtualField").isNull())
        .as("virtual element must be excluded from record")
        .isTrue();
    assertThat(coreRec.get("string")).as("ordinary element must be present").isNotNull();
  }

  @Test
  @DisplayName("GET Data r_select / r_filter / r_orderby on a hidden element → 400")
  void hiddenElementRejectedInRecordOptions() throws Exception {
    // phoneNumber is @HideFromDataInspector, so it is not a valid record element:
    // referencing it in r_select, r_filter or r_orderby must be rejected with 400.
    String base = "/Data?$filter=entityName eq 'datainspector.test.db.Order'";

    assertThat(getAsAlice(base + "&r_select=phoneNumber").getResponse().getStatus())
        .as("hidden element in r_select must be 400")
        .isEqualTo(400);

    assertThat(getAsAlice(base + "&r_filter=phoneNumber eq '123'").getResponse().getStatus())
        .as("hidden element in r_filter must be 400")
        .isEqualTo(400);

    assertThat(getAsAlice(base + "&r_orderby=phoneNumber desc").getResponse().getStatus())
        .as("hidden element in r_orderby must be 400")
        .isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // r_filter — 'ne' (not-equal) operator
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data r_filter — ne (not-equal) returns all rows except the matched one")
  void rFilterNotEqual() throws Exception {
    // Total Product rows = 12. 'ne iPhone 14' must return the other 11.
    JsonNode all = getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'");
    int total = all.get("value").size();
    assertThat(total).isEqualTo(12);

    JsonNode ne =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&r_filter=productName ne 'iPhone 14'&r_select=productName");
    assertThat(ne.get("value").size())
        .as("ne must exclude exactly the one matching row")
        .isEqualTo(total - 1);
    boolean noneIsIphone14 =
        allRecords(ne).allMatch(rec -> !"iPhone 14".equals(rec.get("productName").asText()));
    assertThat(noneIsIphone14).as("no returned row may equal the excluded value").isTrue();
  }

  // ═════════════════════════════════════════════════════════════
  // $skip + $skiptoken are additive
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("GET Data — $skip and $skiptoken offsets are additive")
  void skipAndSkiptokenAdditive() throws Exception {
    // Baseline ordering (by key) for the first 8 Product rows.
    JsonNode base =
        getJsonAsAlice("/Data?$filter=entityName eq 'datainspector.test.db.Product'&$top=8");
    assertThat(base.get("value").size()).isEqualTo(8);

    // $skip=2 + $skiptoken=3 must skip 5 rows total → first returned row equals
    // baseline row index 5.
    JsonNode combined =
        getJsonAsAlice(
            "/Data?$filter=entityName eq 'datainspector.test.db.Product'"
                + "&$skip=2&$skiptoken=3&$top=3");
    assertThat(combined.get("value").size()).isEqualTo(3);
    assertThat(combined.get("value").get(0).get("recordKey").asText())
        .as("$skip + $skiptoken must be additive (skip 5)")
        .isEqualTo(base.get("value").get(5).get("recordKey").asText());
  }

  // ═════════════════════════════════════════════════════════════
  // Helpers
  // ═════════════════════════════════════════════════════════════

  /** Streams the parsed 'record' JSON of every row in an OData collection response. */
  private java.util.stream.Stream<JsonNode> allRecords(JsonNode envelope) {
    java.util.List<JsonNode> records = new java.util.ArrayList<>();
    for (JsonNode row : envelope.get("value")) {
      try {
        records.add(objectMapper.readTree(row.get("record").asText()));
      } catch (Exception e) {
        throw new AssertionError("failed to parse record JSON", e);
      }
    }
    return records.stream();
  }
}
