/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Server-side pagination tests for {@link com.sap.cds.feature.datainspector.handler.DataReader}.
 *
 * <p>The FoodService in the test-app declares {@code @cds.query.limit: { default: 10, max: 20 }}.
 * The DataReader applies these limits when constructing the SQL LIMIT/OFFSET clause; a db-level
 * entity without that annotation is returned unpaged.
 *
 * <p>Inline count: with {@code $count=true} CAP Java's OData V4 serializer emits the total under
 * the control-information key {@code @count} (verified against the running app; the Node.js
 * reference uses the fully-qualified {@code @odata.count}, but the value is identical).
 *
 * <p>Note on {@code @odata.nextLink}: the CAP Java OData adapter does not emit a nextLink for this
 * virtual, {@code @cds.persistence.skip} Data entity, and an {@code @On} handler has no hook to add
 * one. The handler therefore does not compute one, so these tests never assert on it. Clients
 * paginate by passing {@code $skiptoken=<offset>} explicitly; truncation is verified behaviourally
 * in {@link #truncationReachableViaSkiptoken()}.
 *
 * <p>The Food data set has exactly 100 rows and 19 of them contain "Rice" in the ingredients column
 * (verified from the CSV at test scaffolding time).
 */
class DataReaderPaginationIT extends AbstractDataInspectorIT {

  private static final int FOOD_TOTAL = 100;
  private static final int RICE_TOTAL = 19;

  private static final String FOOD_SERVICE =
      "/Data?$filter=entityName eq 'datainspector.test.srv.FoodService.Food'";
  private static final String FOOD_DB = "/Data?$filter=entityName eq 'datainspector.test.db.Food'";

  // ═════════════════════════════════════════════════════════════
  // Basic pagination — default page size, max clamping, $count
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Basic pagination — default page size, max-page clamping, $count behaviour")
  void basicPagination() throws Exception {
    // (1) No $top → default page size (10) applied by @cds.query.limit.default; @count is the
    // total.
    JsonNode r0 = getJsonAsAlice(FOOD_SERVICE + "&$count=true");
    assertThat(r0.get("value").size()).isEqualTo(10);
    assertThat(r0.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // (2) db entity has no @cds.query.limit → default page size does NOT apply → all 100 rows.
    JsonNode r1 = getJsonAsAlice(FOOD_DB + "&$count=true");
    assertThat(r1.get("value").size()).isEqualTo(FOOD_TOTAL);
    assertThat(r1.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // (3) $top=30 > max=20 → clamped to the max page size (20).
    JsonNode r2 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&$top=30");
    assertThat(r2.get("value").size()).isEqualTo(20);
    assertThat(r2.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // (4) $skip=80 & $top=30 → tail of 20 rows ($top clamped to max=20).
    JsonNode r3 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&$skip=80&$top=30");
    assertThat(r3.get("value").size()).isEqualTo(20);
    assertThat(r3.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // (5) $skip=80 & $top=15 → 15 rows (sub-default $top honoured as-is).
    JsonNode r4 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&$skip=80&$top=15");
    assertThat(r4.get("value").size()).isEqualTo(15);
    assertThat(r4.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // (6) Same request as (5) but without $count → @count must be omitted.
    JsonNode r5 = getJsonAsAlice(FOOD_SERVICE + "&$skip=80&$top=15");
    assertThat(r5.get("value").size()).isEqualTo(15);
    assertThat(r5.get("@count")).as("@count omitted when $count is absent").isNull();

    // (7) $top=0 with $count=true → zero rows returned but the total count is still reported.
    JsonNode r6 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&$top=0");
    assertThat(r6.get("value").size()).isZero();
    assertThat(r6.get("@count").asInt()).isEqualTo(FOOD_TOTAL);
  }

  // ═════════════════════════════════════════════════════════════
  // Pagination combined with r_filter
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Pagination with r_filter — page-2 fetch via $skiptoken, consistent data across pages")
  void paginationWithRFilter() throws Exception {
    // Page 1: default page size (10) of the 19 Rice rows; @count reflects the filtered total.
    JsonNode r0 =
        getJsonAsAlice(FOOD_SERVICE + "&$count=true&r_filter=contains(ingredients, 'Rice')");
    assertThat(r0.get("value").size()).isEqualTo(10);
    assertThat(r0.get("@count").asInt()).isEqualTo(RICE_TOTAL);

    // Page 2 via $skiptoken=10 → the remaining 9 filtered rows. (Explicit $skiptoken stands in for
    // following an @odata.nextLink, which the adapter does not emit — see class Javadoc.)
    JsonNode r1 =
        getJsonAsAlice(
            FOOD_SERVICE + "&$count=true&r_filter=contains(ingredients, 'Rice')&$skiptoken=10");
    assertThat(r1.get("value").size()).isEqualTo(9);
    assertThat(r1.get("@count").asInt()).isEqualTo(RICE_TOTAL);

    // Single-page fetch with $top=100 → all 19 filtered rows at once.
    JsonNode r2 =
        getJsonAsAlice(
            FOOD_SERVICE + "&$count=true&r_filter=contains(ingredients, 'Rice')&$top=100");
    assertThat(r2.get("value").size()).isEqualTo(RICE_TOTAL);
    assertThat(r2.get("@count").asInt()).isEqualTo(RICE_TOTAL);

    // Consistency: page-1 followed by page-2 must equal the single-page fetch, in order.
    // Compare on the food's 'name' from the record JSON; ordering is deterministic (by primary
    // key).
    assertThat(recordName(r0, 0)).isEqualTo(recordName(r2, 0));
    assertThat(recordName(r0, 9)).isEqualTo(recordName(r2, 9));
    assertThat(recordName(r1, 0)).isEqualTo(recordName(r2, 10));
    assertThat(recordName(r1, 8)).isEqualTo(recordName(r2, 18));
  }

  // ═════════════════════════════════════════════════════════════
  // Pagination combined with r_orderby & $skip
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Pagination with r_orderby + $skip — order preserved across pages via $skiptoken")
  void paginationWithROrderby() throws Exception {
    // r_orderby=name desc + $skip=10 + $top=5 → 5 rows honouring the sub-default $top.
    JsonNode r00 =
        getJsonAsAlice(FOOD_SERVICE + "&$count=true&r_orderby=name desc&$skip=10&$top=5");
    assertThat(r00.get("value").size()).isEqualTo(5);
    assertThat(r00.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // Page 1 (name desc, no $top) → default page size (10).
    JsonNode r0 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&r_orderby=name desc");
    assertThat(r0.get("value").size()).isEqualTo(10);
    assertThat(r0.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // Page 2 via $skiptoken=10, same ordering → the next 10 rows in the descending sequence.
    JsonNode r1 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&r_orderby=name desc&$skiptoken=10");
    assertThat(r1.get("value").size()).isEqualTo(10);
    assertThat(r1.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // Opposite ordering, last chunk: name asc, $skip=80, $top=2000 clamped to max=20 → 20 rows.
    JsonNode r2 = getJsonAsAlice(FOOD_SERVICE + "&$count=true&r_orderby=name&$skip=80&$top=2000");
    assertThat(r2.get("value").size()).isEqualTo(20);
    assertThat(r2.get("@count").asInt()).isEqualTo(FOOD_TOTAL);

    // Data-consistency across inverted orderings: name-desc rows 1..10 (r0) equal the mirror of
    // name-asc last-20 rows (r2); page-2 desc (r1) mirrors the earlier part of the asc chunk.
    assertThat(recordName(r0, 0)).isEqualTo(recordName(r2, 19));
    assertThat(recordName(r0, 9)).isEqualTo(recordName(r2, 10));
    assertThat(recordName(r1, 0)).isEqualTo(recordName(r2, 9));
    assertThat(recordName(r1, 9)).isEqualTo(recordName(r2, 0));
  }

  // ═════════════════════════════════════════════════════════════
  // Negative validation of pagination parameters
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Negative tests — invalid / negative $skip / $top / $skiptoken → 400")
  void negativeTests() throws Exception {
    // Non-numeric $skip → 400.
    assertThat(getAsAlice(FOOD_SERVICE + "&$skip=a&$top=-2").getResponse().getStatus())
        .as("non-numeric $skip must be rejected")
        .isEqualTo(400);

    // Negative $skip → 400.
    assertThat(getAsAlice(FOOD_SERVICE + "&$count=true&$skip=-1&$top=1").getResponse().getStatus())
        .as("negative $skip must be rejected")
        .isEqualTo(400);

    // Negative $skiptoken → 400 (see DataReader.computeOffset).
    assertThat(getAsAlice(FOOD_SERVICE + "&$count=true&$skiptoken=-1").getResponse().getStatus())
        .as("negative $skiptoken must be rejected")
        .isEqualTo(400);

    // Negative $top → 400.
    assertThat(getAsAlice(FOOD_SERVICE + "&$count=true&$skip=1&$top=-1").getResponse().getStatus())
        .as("negative $top must be rejected")
        .isEqualTo(400);

    // Non-numeric $top → 400.
    assertThat(getAsAlice(FOOD_SERVICE + "&$top=abc").getResponse().getStatus())
        .as("non-numeric $top must be rejected")
        .isEqualTo(400);
  }

  // ═════════════════════════════════════════════════════════════
  // Truncation is reachable via $skiptoken (behavioural nextLink check)
  // ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("Truncated pages have a reachable next page via $skiptoken; nothing past the end")
  void truncationReachableViaSkiptoken() throws Exception {
    // nextLink is not emitted (see class Javadoc); instead assert the behaviour it would represent:
    // after a truncated page the next rows are fetchable via $skiptoken, and past the last row
    // none.

    // (1) Default page = 10 of 100. Row at $skiptoken=10 must be the 11th row of the full sequence.
    JsonNode all = getJsonAsAlice(FOOD_DB);
    JsonNode nextPage = getJsonAsAlice(FOOD_SERVICE + "&$skiptoken=10");
    assertThat(nextPage.get("value").size()).as("a next page must exist").isPositive();
    assertThat(nextPage.get("value").get(0).get("recordKey").asText())
        .as("row at offset 10 must be the 11th row of the full sequence")
        .isEqualTo(all.get("value").get(10).get("recordKey").asText());

    // (2) $top=30 clamps to 20 → rows still remain at offset 20.
    JsonNode afterClamp = getJsonAsAlice(FOOD_SERVICE + "&$top=30&$skiptoken=20");
    assertThat(afterClamp.get("value").size())
        .as("rows must remain after the clamped 20-row page")
        .isPositive();

    // (3) Past the final row → empty (service entity and unpaged db entity alike).
    assertThat(getJsonAsAlice(FOOD_SERVICE + "&$skiptoken=100").get("value").size())
        .as("no rows past the final row (service entity)")
        .isZero();
    assertThat(getJsonAsAlice(FOOD_DB + "&$skiptoken=100").get("value").size())
        .as("no rows past the final row (unpaged db entity)")
        .isZero();
  }

  // ═════════════════════════════════════════════════════════════
  // Helpers
  // ═════════════════════════════════════════════════════════════

  /**
   * Extracts the "name" property from the 'record' JSON string at value[index]. The Food entity's
   * (id, name, ingredients, description) means "name" uniquely identifies a row for cross-page
   * consistency checks.
   */
  private String recordName(JsonNode envelope, int index) throws Exception {
    String recordJson = envelope.get("value").get(index).get("record").asText();
    return objectMapper.readTree(recordJson).get("name").asText();
  }
}
