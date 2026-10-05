/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector.handler;

import com.sap.cds.ql.CQL;
import com.sap.cds.ql.cqn.CqnComparisonPredicate;
import com.sap.cds.ql.cqn.CqnPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the OData filter grammar accepted in the {@code r_filter} query option into a {@link
 * CqnPredicate}.
 *
 * <p>Why this hand-written parser exists: CAP Java has no public string-based predicate parser
 * (there is no {@code cds.parse.expr()} equivalent — {@code CqnParser.select(String)} parses
 * CQN-JSON, not CQL/OData text). The real OData→CQN pipeline (Apache Olingo + CAP's internal {@code
 * ExpressionParser}) is metadata-bound: it validates every property against an OData {@code
 * EdmType} that only exists for service-exposed entities, so it cannot parse filters over the
 * runtime-dynamic / DB-level record elements this plugin inspects. We therefore parse the filter
 * ourselves and build predicates via the public {@link CQL} builder API.
 *
 * <p>The accepted filter syntax is described below in EBNF (a standard grammar notation). It is a
 * <em>specification</em> of the language, not code. How to read it:
 *
 * <ul>
 *   <li>{@code :=} means "is defined as".
 *   <li>Lower-case names ({@code expr}, {@code term}, {@code col}, {@code value}) are
 *       <em>rules</em> — placeholders defined by other lines.
 *   <li>Text in single quotes ({@code 'or'}, {@code '('}, {@code 'eq'}) is <em>literal</em> text
 *       that must appear verbatim in the filter string.
 *   <li>{@code A ( X )*} means "an {@code A} followed by zero or more repetitions of {@code X}".
 *   <li>{@code |} separates alternatives ("either / or").
 * </ul>
 *
 * <p>Grammar (logical operators {@code and} / {@code or} are case-insensitive):
 *
 * <pre>
 *   expr    := orExpr                              -- a filter is an or-expression
 *   orExpr  := andExpr ( 'or'  andExpr )*          -- e.g.  A or B or C
 *   andExpr := term    ( 'and' term    )*          -- e.g.  A and B
 *   term    := '(' expr ')' | clause               -- a parenthesised sub-expression, or one clause
 *   clause  := comparison | nullCheck | function    -- a single condition, one of these three shapes
 *     comparison := col ('eq'|'ne'|'gt'|'ge'|'lt'|'le') value   -- e.g.  quantity gt 100
 *     nullCheck  := col ('eq'|'ne') 'null'                       -- e.g.  description eq null
 *     function   := ('contains'|'startswith'|'endswith') '(' col ',' value ')'
 *                                                                -- e.g.  contains(name, 'iPhone')
 * </pre>
 *
 * <p>Because {@code and} is defined as the operand of {@code or}, {@code and} binds tighter than
 * {@code or} (standard precedence): {@code a or b and c} is parsed as {@code a or (b and c)}.
 * Parentheses override this, e.g. {@code (a or b) and c}. Each grammar rule maps to a parse method:
 * {@code orExpr}→{@code parseOr}, {@code andExpr}→{@code parseAnd}, {@code term}→{@code parseTerm},
 * {@code clause}→{@code parseClause}. Concrete example:
 *
 * <pre>
 *   (quantity gt 100 or quantity lt 5) and contains(productName, 'iPhone')
 * </pre>
 *
 * <p>The parser is stateless from the outside (single static {@link #parse} entry point); failures
 * are signalled via {@link FilterParseException} so the calling handler can translate them into the
 * appropriate HTTP error.
 */
final class RFilterParser {

  private static final Pattern CONTAINS =
      Pattern.compile("contains\\(\\s*(\\w+)\\s*,\\s*'([^']*)'\\s*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern STARTS_WITH =
      Pattern.compile("startswith\\(\\s*(\\w+)\\s*,\\s*'([^']*)'\\s*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ENDS_WITH =
      Pattern.compile("endswith\\(\\s*(\\w+)\\s*,\\s*'([^']*)'\\s*\\)", Pattern.CASE_INSENSITIVE);
  private static final Pattern NULL_CHECK =
      Pattern.compile("(\\w+)\\s+(eq|ne)\\s+null", Pattern.CASE_INSENSITIVE);
  private static final Pattern COMPARISON =
      Pattern.compile("(\\w+)\\s+(eq|ne|gt|ge|lt|le)\\s+(.+)", Pattern.CASE_INSENSITIVE);

  private RFilterParser() {}

  /** Thrown when a filter clause cannot be parsed or references an unknown element. */
  static final class FilterParseException extends RuntimeException {
    /** Machine-readable code that maps to the handler's OData error code. */
    private final String code;

    FilterParseException(String code, String message) {
      super(message);
      this.code = code;
    }

    String getCode() {
      return code;
    }
  }

  /**
   * Parses an OData filter string into a {@link CqnPredicate} tree supporting {@code and}, {@code
   * or} and parentheses. Returns {@code null} when the filter is empty.
   *
   * @param filter the raw {@code r_filter} value (already URL-decoded)
   * @param allowedElements element names permitted in the filter, or {@code null} to skip element
   *     validation (used for recordKey-derived filters whose names are already vetted)
   * @throws FilterParseException on an unparseable clause or an unknown element
   */
  static CqnPredicate parse(String filter, List<String> allowedElements) {
    if (filter == null || filter.isBlank()) return null;
    List<String> tokens = tokenize(filter);
    if (tokens.isEmpty()) return null;

    Parser parser = new Parser(tokens, allowedElements);
    CqnPredicate predicate = parser.parseExpression();
    parser.expectEnd();
    return predicate;
  }

  // ── Tokenizer ──────────────────────────────────────────────────────────────

  /**
   * Splits the filter into tokens: parentheses ({@code (} / {@code )}), the logical keywords {@code
   * and} / {@code or}, and "clause" tokens (everything else — comparisons, null checks, function
   * calls). Quoted string literals and function-call parentheses are respected, so a {@code (}
   * inside {@code contains(...)} or a quoted value is NOT treated as a grouping parenthesis and a
   * keyword inside a quoted value is NOT treated as a logical operator.
   */
  private static List<String> tokenize(String filter) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    int funcDepth = 0; // parenthesis depth of a function call, e.g. contains( ... )

    int i = 0;
    while (i < filter.length()) {
      char c = filter.charAt(i);

      if (inQuote) {
        current.append(c);
        if (c == '\'') inQuote = false;
        i++;
        continue;
      }

      if (c == '\'') {
        inQuote = true;
        current.append(c);
        i++;
        continue;
      }

      // A '(' that follows an identifier (no preceding space) opens a function call,
      // not a grouping parenthesis — keep it inside the current clause token.
      if (c == '(' && funcDepth == 0 && endsWithIdentifier(current)) {
        funcDepth = 1;
        current.append(c);
        i++;
        continue;
      }
      if (funcDepth > 0) {
        if (c == '(') funcDepth++;
        else if (c == ')') funcDepth--;
        current.append(c);
        i++;
        continue;
      }

      // Grouping parentheses are standalone tokens.
      if (c == '(' || c == ')') {
        flush(tokens, current);
        tokens.add(String.valueOf(c));
        i++;
        continue;
      }

      // Logical keywords 'and' / 'or', matched as whole words (surrounded by spaces/parens).
      if (isSpace(c)) {
        String kw = matchKeyword(filter, i);
        if (kw != null) {
          flush(tokens, current);
          tokens.add(kw.toLowerCase());
          i +=
              1 + kw.length(); // skip the leading space + keyword; trailing space handled next loop
          continue;
        }
      }

      current.append(c);
      i++;
    }
    if (inQuote) {
      throw new FilterParseException(
          "INVALID_R_FILTER_QUERY_PARAM", "Unterminated string literal in r_filter: " + filter);
    }
    flush(tokens, current);
    return tokens;
  }

  /** Appends the trimmed buffer as a clause token (if non-blank) and resets the buffer. */
  private static void flush(List<String> tokens, StringBuilder buf) {
    String s = buf.toString().trim();
    if (!s.isEmpty()) tokens.add(s);
    buf.setLength(0);
  }

  /**
   * At position {@code i} (a space), returns the logical keyword ("and"/"or") that starts at {@code
   * i+1} and is followed by a word boundary (space or {@code (}), or {@code null} if none.
   */
  private static String matchKeyword(String s, int i) {
    int j = i + 1;
    for (String kw : new String[] {"and", "or"}) {
      int end = j + kw.length();
      if (end <= s.length() && s.regionMatches(true, j, kw, 0, kw.length())) {
        char after = end < s.length() ? s.charAt(end) : ' ';
        if (isSpace(after) || after == '(') return kw;
      }
    }
    return null;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t';
  }

  private static boolean endsWithIdentifier(StringBuilder buf) {
    int n = buf.length();
    return n > 0 && (Character.isLetterOrDigit(buf.charAt(n - 1)) || buf.charAt(n - 1) == '_');
  }

  // ── Recursive-descent parser ───────────────────────────────────────────────

  /** Consumes the token list produced by {@link #tokenize} and builds the predicate tree. */
  private static final class Parser {
    private final List<String> tokens;
    private final List<String> allowedElements;
    private int pos;

    Parser(List<String> tokens, List<String> allowedElements) {
      this.tokens = tokens;
      this.allowedElements = allowedElements;
    }

    /** expr := orExpr */
    CqnPredicate parseExpression() {
      return parseOr();
    }

    /** orExpr := andExpr ( 'or' andExpr )* */
    private CqnPredicate parseOr() {
      List<CqnPredicate> operands = new ArrayList<>();
      operands.add(parseAnd());
      while ("or".equals(peek())) {
        next();
        operands.add(parseAnd());
      }
      return operands.size() == 1 ? operands.get(0) : CQL.or(operands);
    }

    /** andExpr := term ( 'and' term )* */
    private CqnPredicate parseAnd() {
      List<CqnPredicate> operands = new ArrayList<>();
      operands.add(parseTerm());
      while ("and".equals(peek())) {
        next();
        operands.add(parseTerm());
      }
      return operands.size() == 1 ? operands.get(0) : CQL.and(operands);
    }

    /** term := '(' expr ')' | clause */
    private CqnPredicate parseTerm() {
      String token = peek();
      if (token == null) {
        throw new FilterParseException(
            "INVALID_R_FILTER_QUERY_PARAM", "Unexpected end of r_filter expression");
      }
      if ("(".equals(token)) {
        next(); // consume '('
        CqnPredicate inner = parseExpression();
        if (!")".equals(peek())) {
          throw new FilterParseException(
              "INVALID_R_FILTER_QUERY_PARAM", "Missing closing ')' in r_filter");
        }
        next(); // consume ')'
        return inner;
      }
      if (")".equals(token) || "and".equals(token) || "or".equals(token)) {
        throw new FilterParseException(
            "INVALID_R_FILTER_QUERY_PARAM", "Unexpected token in r_filter: " + token);
      }
      next(); // consume the clause token
      return parseClause(token, allowedElements);
    }

    // ── Token cursor ──────────────────────────────────────────────────────

    /** Returns the current token without consuming it, or {@code null} at the end. */
    private String peek() {
      return pos < tokens.size() ? tokens.get(pos) : null;
    }

    /** Consumes and returns the current token. */
    private String next() {
      return tokens.get(pos++);
    }

    /** Fails if there are unconsumed tokens (e.g. a dangling ')' or trailing garbage). */
    void expectEnd() {
      if (pos != tokens.size()) {
        throw new FilterParseException(
            "INVALID_R_FILTER_QUERY_PARAM", "Unexpected token in r_filter: " + peek());
      }
    }
  }

  // ── Clause parsing (comparison / null check / function) ─────────────────────

  /** Parses one clause by trying each supported shape in turn. */
  private static CqnPredicate parseClause(String clause, List<String> allowedElements) {
    CqnPredicate function = parseFunction(clause, allowedElements);
    if (function != null) return function;

    CqnPredicate nullCheck = parseNullCheck(clause, allowedElements);
    if (nullCheck != null) return nullCheck;

    return parseComparison(clause, allowedElements);
  }

  /** Handles {@code contains|startswith|endswith(col, 'value')}; returns {@code null} otherwise. */
  private static CqnPredicate parseFunction(String clause, List<String> allowedElements) {
    Matcher contains = CONTAINS.matcher(clause);
    if (contains.matches()) {
      String col = validated(contains.group(1), allowedElements);
      // caseSensitive=true matches the Node.js reference.
      return CQL.contains(CQL.get(col), CQL.val(contains.group(2)), true);
    }
    Matcher starts = STARTS_WITH.matcher(clause);
    if (starts.matches()) {
      String col = validated(starts.group(1), allowedElements);
      return CQL.startsWith(CQL.get(col), CQL.val(starts.group(2)), true);
    }
    Matcher ends = ENDS_WITH.matcher(clause);
    if (ends.matches()) {
      String col = validated(ends.group(1), allowedElements);
      return CQL.endsWith(CQL.get(col), CQL.val(ends.group(2)), true);
    }
    return null;
  }

  /** Handles {@code col eq null} / {@code col ne null}; returns {@code null} otherwise. */
  private static CqnPredicate parseNullCheck(String clause, List<String> allowedElements) {
    Matcher m = NULL_CHECK.matcher(clause);
    if (!m.matches()) return null;

    String col = validated(m.group(1), allowedElements);
    boolean isEq = m.group(2).equalsIgnoreCase("eq");
    CqnComparisonPredicate.Operator op =
        isEq ? CqnComparisonPredicate.Operator.IS : CqnComparisonPredicate.Operator.IS_NOT;
    return CQL.comparison(CQL.get(col), op, CQL.NULL);
  }

  /** Handles {@code col eq|ne|gt|ge|lt|le value}; throws on anything unrecognised. */
  private static CqnPredicate parseComparison(String clause, List<String> allowedElements) {
    Matcher m = COMPARISON.matcher(clause);
    if (!m.matches()) {
      throw new FilterParseException(
          "INVALID_R_FILTER_QUERY_PARAM", "Could not parse r_filter clause: " + clause);
    }
    String col = validated(m.group(1), allowedElements);
    CqnComparisonPredicate.Operator op = toOperator(m.group(2));
    String value = stripQuotes(m.group(3).trim());
    return CQL.comparison(CQL.get(col), op, CQL.val(value));
  }

  /** Maps an OData comparison operator keyword to its CQN operator. */
  private static CqnComparisonPredicate.Operator toOperator(String odataOp) {
    return switch (odataOp.toLowerCase()) {
      case "eq" -> CqnComparisonPredicate.Operator.EQ;
      case "ne" -> CqnComparisonPredicate.Operator.NE;
      case "gt" -> CqnComparisonPredicate.Operator.GT;
      case "ge" -> CqnComparisonPredicate.Operator.GE;
      case "lt" -> CqnComparisonPredicate.Operator.LT;
      case "le" -> CqnComparisonPredicate.Operator.LE;
      default ->
          throw new FilterParseException(
              "INVALID_R_FILTER_QUERY_PARAM", "Unsupported operator: " + odataOp);
    };
  }

  /** Removes surrounding single quotes from a string literal, leaving bare values untouched. */
  private static String stripQuotes(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /**
   * Returns {@code element} if it is allowed, otherwise throws. Validation is skipped when {@code
   * allowedElements} is {@code null} (recordKey-derived filters, already vetted upstream).
   */
  private static String validated(String element, List<String> allowedElements) {
    if (allowedElements != null && !allowedElements.contains(element)) {
      throw new FilterParseException(
          "INVALID_ELEMENT_IN_R_FILTER", "Unknown element in r_filter: " + element);
    }
    return element;
  }
}
