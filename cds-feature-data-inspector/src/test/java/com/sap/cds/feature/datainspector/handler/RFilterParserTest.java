/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sap.cds.feature.datainspector.handler.RFilterParser.FilterParseException;
import com.sap.cds.ql.cqn.CqnConnectivePredicate;
import com.sap.cds.ql.cqn.CqnPredicate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RFilterParser}. The parser is a pure string→{@link CqnPredicate} function,
 * so it can be exercised directly without any CAP runtime. Assertions are made on the parsed
 * predicate's JSON (CQN) form, which is stable and readable.
 */
class RFilterParserTest {

  private static final List<String> ALLOWED = List.of("productName", "quantity", "description");

  /** Convenience: parse and return the CQN-JSON of the resulting predicate. */
  private static String json(String filter) {
    CqnPredicate predicate = RFilterParser.parse(filter, ALLOWED);
    assertThat(predicate).isNotNull();
    return predicate.toJson();
  }

  @Test
  @DisplayName("empty / blank / null filter → null predicate")
  void emptyFilter() {
    assertThat(RFilterParser.parse(null, ALLOWED)).isNull();
    assertThat(RFilterParser.parse("", ALLOWED)).isNull();
    assertThat(RFilterParser.parse("   ", ALLOWED)).isNull();
  }

  @Test
  @DisplayName("comparison operators eq/ne/gt/ge/lt/le map to =, <>, >, >=, <, <=")
  void comparisonOperators() {
    assertThat(json("productName eq 'iPhone 14'")).contains("productName").contains("=");
    assertThat(json("quantity ne 5")).contains("<>");
    assertThat(json("quantity gt 5")).contains(">");
    assertThat(json("quantity ge 5")).contains(">=");
    assertThat(json("quantity lt 5")).contains("<");
    assertThat(json("quantity le 5")).contains("<=");
  }

  @Test
  @DisplayName("quoted string value keeps spaces; surrounding quotes are stripped")
  void quotedStringValue() {
    assertThat(json("productName eq 'iPhone 14'")).contains("iPhone 14");
  }

  @Test
  @DisplayName("bare (unquoted) numeric value is preserved")
  void bareNumericValue() {
    assertThat(json("quantity gt 100")).contains("100");
  }

  @Test
  @DisplayName("null checks eq/ne null → is null / is not null")
  void nullChecks() {
    assertThat(json("description eq null").toLowerCase()).contains("null");
    assertThat(json("description ne null").toLowerCase()).contains("null");
  }

  @Test
  @DisplayName("string functions contains/startswith/endswith are recognised")
  void stringFunctions() {
    assertThat(json("contains(productName, 'iPhone')").toLowerCase()).contains("contains");
    assertThat(json("startswith(productName, 'iPhone')").toLowerCase()).contains("startswith");
    assertThat(json("endswith(productName, 'iPhone')").toLowerCase()).contains("endswith");
  }

  @Test
  @DisplayName("clauses joined by 'and' produce a combined predicate")
  void andCombination() {
    String combined = json("productName eq 'iPhone 14' and quantity gt 5");
    assertThat(combined).contains("productName").contains("quantity");
  }

  @Test
  @DisplayName("clauses joined by 'or' produce a combined predicate")
  void orCombination() {
    String combined = json("productName eq 'iPhone 14' or quantity gt 5");
    assertThat(combined).contains("productName").contains("quantity").contains("or");
  }

  @Test
  @DisplayName("'and' binds tighter than 'or': a or b and c parses as OR(a, AND(b, c))")
  void andBindsTighterThanOr() {
    // Without parentheses, 'and' must group before 'or'. So the ROOT must be OR,
    // and one of its operands must be an AND connective.
    CqnPredicate p =
        RFilterParser.parse("quantity lt 5 or quantity gt 100 and productName eq 'x'", ALLOWED);

    CqnConnectivePredicate root = asConnective(p);
    assertThat(root.operator()).isEqualTo(CqnConnectivePredicate.Operator.OR);
    assertThat(root.predicates())
        .as("the 'and' must be nested as an operand of the root 'or'")
        .anyMatch(RFilterParserTest::isAnd);
  }

  @Test
  @DisplayName("parentheses override precedence: (a or b) and c parses as AND(OR(a, b), c)")
  void parenthesesGrouping() {
    // With the parentheses, the grouping flips: the ROOT must now be AND, and one
    // of its operands must be an OR connective (the parenthesised sub-expression).
    CqnPredicate p =
        RFilterParser.parse(
            "(productName eq 'a' or productName eq 'b') and quantity gt 5", ALLOWED);

    CqnConnectivePredicate root = asConnective(p);
    assertThat(root.operator()).isEqualTo(CqnConnectivePredicate.Operator.AND);
    assertThat(root.predicates())
        .as("the parenthesised 'or' must be nested as an operand of the root 'and'")
        .anyMatch(RFilterParserTest::isOr);
  }

  /** Casts a predicate to {@link CqnConnectivePredicate}, failing the test if it isn't one. */
  private static CqnConnectivePredicate asConnective(CqnPredicate p) {
    assertThat(p)
        .as("expected a connective (and/or) predicate at this position")
        .isInstanceOf(CqnConnectivePredicate.class);
    return (CqnConnectivePredicate) p;
  }

  /** True if the predicate is an {@code and} connective. */
  private static boolean isAnd(CqnPredicate p) {
    return p instanceof CqnConnectivePredicate c
        && c.operator() == CqnConnectivePredicate.Operator.AND;
  }

  /** True if the predicate is an {@code or} connective. */
  private static boolean isOr(CqnPredicate p) {
    return p instanceof CqnConnectivePredicate c
        && c.operator() == CqnConnectivePredicate.Operator.OR;
  }

  @Test
  @DisplayName("nested parentheses parse correctly")
  void nestedParentheses() {
    CqnPredicate p =
        RFilterParser.parse(
            "((quantity gt 5))", ALLOWED); // redundant nesting collapses to the inner clause
    assertThat(p).isNotNull();
    assertThat(p.toJson()).contains("quantity");
  }

  @Test
  @DisplayName("'and'/'or' inside a quoted value are not treated as logical operators")
  void keywordsInsideQuotedValueAreLiterals() {
    // The value 'salt and pepper' contains 'and' but must stay a single equality clause.
    String json = json("productName eq 'salt and pepper'");
    assertThat(json).contains("salt and pepper");
    // Only one predicate → no top-level 'and' join. (The literal keeps the phrase intact.)
  }

  @Test
  @DisplayName("unbalanced parenthesis → 400")
  void unbalancedParenthesis() {
    assertThatThrownBy(() -> RFilterParser.parse("(productName eq 'a'", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
    assertThatThrownBy(() -> RFilterParser.parse("productName eq 'a')", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
  }

  @Test
  @DisplayName("dangling logical operator → 400")
  void danglingOperator() {
    assertThatThrownBy(() -> RFilterParser.parse("productName eq 'a' and", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
    assertThatThrownBy(() -> RFilterParser.parse("or productName eq 'a'", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
  }

  @Test
  @DisplayName("unknown element inside a grouped OR expression is still rejected → 400")
  void unknownElementInsideGroup() {
    assertThatThrownBy(() -> RFilterParser.parse("(productName eq 'a' or price gt 5)", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_ELEMENT_IN_R_FILTER");
  }

  @Test
  @DisplayName("unsupported operator → FilterParseException(INVALID_R_FILTER_QUERY_PARAM)")
  void invalidOperator() {
    assertThatThrownBy(() -> RFilterParser.parse("productName xx 'iPhone 14'", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
  }

  @Test
  @DisplayName("unknown element in comparison → FilterParseException(INVALID_ELEMENT_IN_R_FILTER)")
  void unknownElementComparison() {
    assertThatThrownBy(() -> RFilterParser.parse("price eq '100'", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_ELEMENT_IN_R_FILTER");
  }

  @Test
  @DisplayName("unknown element in function → FilterParseException(INVALID_ELEMENT_IN_R_FILTER)")
  void unknownElementFunction() {
    assertThatThrownBy(() -> RFilterParser.parse("contains(price, '100')", ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_ELEMENT_IN_R_FILTER");
  }

  @Test
  @DisplayName("null allowedElements skips element validation (recordKey-derived filters)")
  void nullAllowedSkipsValidation() {
    // 'anyKey' is not in ALLOWED, but with allowedElements=null validation is skipped.
    CqnPredicate predicate = RFilterParser.parse("anyKey eq 'x'", null);
    assertThat(predicate).isNotNull();
    assertThat(predicate.toJson()).contains("anyKey");
  }

  // ── Input-bound guards (DoS / ReDoS safety) ─────────────────────────────────
  // These are fail-closed functional tests: pathological / oversized input must be
  // rejected quickly with a 400-mapped FilterParseException rather than consuming
  // unbounded CPU or overflowing the stack.

  @Test
  @DisplayName("filter longer than MAX_FILTER_LENGTH is rejected → 400")
  void overLengthFilterRejected() {
    // A single clause whose value is padded beyond the 5000-char length bound.
    String huge = "productName eq '" + "a".repeat(6000) + "'";
    assertThatThrownBy(() -> RFilterParser.parse(huge, ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
  }

  @Test
  @DisplayName("filter with more than MAX_TOKENS tokens is rejected → 400")
  void tooManyTokensRejected() {
    // Build "quantity gt 0 or quantity gt 1 or … " with enough clauses that the
    // clause + 'or' tokens exceed the 300-token bound (200 clauses → ~399 tokens).
    StringBuilder sb = new StringBuilder("quantity gt 0");
    for (int i = 1; i < 200; i++) {
      sb.append(" or quantity gt ").append(i);
    }
    assertThatThrownBy(() -> RFilterParser.parse(sb.toString(), ALLOWED))
        .isInstanceOf(FilterParseException.class)
        .extracting(e -> ((FilterParseException) e).getCode())
        .isEqualTo("INVALID_R_FILTER_QUERY_PARAM");
  }

  @Test
  @DisplayName("deeply nested but within token budget still parses (no StackOverflowError)")
  void deeplyNestedWithinBudgetParses() {
    // 80 levels of redundant parentheses: 160 paren tokens + the inner clause stay
    // well under MAX_TOKENS, and recursion depth ~80 is far below any stack limit.
    // This documents that the token bound keeps nesting safe without a depth guard.
    int depth = 80;
    String filter = "(".repeat(depth) + "quantity gt 5" + ")".repeat(depth);
    CqnPredicate predicate = RFilterParser.parse(filter, ALLOWED);
    assertThat(predicate).isNotNull();
    assertThat(predicate.toJson()).contains("quantity");
  }

  @Test
  @DisplayName("a large but within-bounds filter still parses successfully")
  void withinBoundsFilterParses() {
    // 100 clauses → ~199 tokens, under the 300-token bound: must parse, not reject.
    StringBuilder sb = new StringBuilder("quantity gt 0");
    for (int i = 1; i < 100; i++) {
      sb.append(" or quantity gt ").append(i);
    }
    CqnPredicate predicate = RFilterParser.parse(sb.toString(), ALLOWED);
    assertThat(predicate).isNotNull();
  }
}
