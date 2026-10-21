package io.floci.az.services.resourcegraph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The subset of the Kusto Query Language that Resource Graph clients send: a table name followed by
 * {@code where}, {@code project}, {@code project-away}, {@code extend}, {@code take}/{@code limit},
 * {@code top}, {@code order by}/{@code sort by}, {@code count}, {@code distinct}, {@code summarize}
 * with {@code count()}, {@code union} of other tables or parenthesized subqueries, and {@code mv-expand}
 * of an array or property bag.
 *
 * <p>Expressions cover {@code and}, {@code or}, the string operators ({@code ==}, {@code !=}, {@code =~},
 * {@code !~}, {@code contains}, {@code startswith}, {@code endswith}, {@code has}, each with its negation),
 * {@code in}, {@code !in} and {@code in~}, the numeric comparisons, dynamic access ({@code tags.env},
 * {@code tags['env']}) and the scalar functions listed on {@code Parser.call}. Anything else is a {@link KqlException}.
 */
public final class KqlQuery {

    /** A query that does not parse, or uses a part of KQL this subset leaves out. */
    public static final class KqlException extends RuntimeException {
        public KqlException(String message) {
            super(message);
        }
    }

    /** The rows of each table a query reads, by the name the query writes. */
    public interface Tables {
        /** The table's rows; a {@link KqlException} for a table this source does not hold. */
        List<Map<String, Object>> rows(String table);
    }

    private interface Stage {
        List<Map<String, Object>> apply(List<Map<String, Object>> rows, Tables tables);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String table;
    private final List<Stage> stages;

    private KqlQuery(String table, List<Stage> stages) {
        this.table = table;
        this.stages = stages;
    }

    /** The table the query reads, as written. */
    public String table() {
        return table;
    }

    public static KqlQuery parse(String text) {
        Parser parser = new Parser(tokenize(text == null ? "" : text));
        return parser.query();
    }

    /** Runs the query over {@code rows} as its own table; a {@code union} of another table is a {@link KqlException}. */
    public List<Map<String, Object>> run(List<Map<String, Object>> rows) {
        return run(name -> {
            if (!name.equalsIgnoreCase(table)) {
                throw new KqlException("Table '" + name + "' is not supported");
            }
            return rows;
        });
    }

    /** Runs the query over its table and any table it unions, read from {@code tables}. */
    public List<Map<String, Object>> run(Tables tables) {
        List<Map<String, Object>> current = tables.rows(table);
        for (Stage stage : stages) {
            current = stage.apply(current, tables);
        }
        return current;
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────

    private enum Kind { IDENT, STRING, NUMBER, SYMBOL, END }

    private record Token(Kind kind, String text) {
    }

    private static final List<String> SYMBOLS = List.of("!contains", "!startswith", "!endswith", "!has", "!in~",
            "!in", "==", "!=", "=~", "!~", "<=", ">=", "|", ",", "(", ")", "[", "]", ".", "=", "<", ">");

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int end = i + 1;
                StringBuilder value = new StringBuilder();
                while (end < text.length() && text.charAt(end) != c) {
                    if (text.charAt(end) == '\\' && end + 1 < text.length()) {
                        end++;
                    }
                    value.append(text.charAt(end));
                    end++;
                }
                if (end >= text.length()) {
                    throw new KqlException("Unterminated string literal at position " + i);
                }
                tokens.add(new Token(Kind.STRING, value.toString()));
                i = end + 1;
            } else if (Character.isDigit(c)) {
                int end = i;
                while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(Kind.NUMBER, text.substring(i, end)));
                i = end;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int end = i;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end))
                        || text.charAt(end) == '_' || text.charAt(end) == '-' || text.charAt(end) == '~'
                        || text.charAt(end) == '$')) {
                    end++;
                }
                tokens.add(new Token(Kind.IDENT, text.substring(i, end)));
                i = end;
            } else {
                String symbol = null;
                for (String candidate : SYMBOLS) {
                    if (text.startsWith(candidate, i)) {
                        symbol = candidate;
                        break;
                    }
                }
                if (symbol == null) {
                    throw new KqlException("Unexpected character '" + c + "' at position " + i);
                }
                tokens.add(new Token(Kind.SYMBOL, symbol));
                i += symbol.length();
            }
        }
        tokens.add(new Token(Kind.END, ""));
        return tokens;
    }

    // ── Expressions ─────────────────────────────────────────────────────────────

    private interface Expr {
        Object eval(Map<String, Object> row);
    }

    private record SortKey(Expr expr, boolean ascending) {
    }

    private record Column(String name, Expr expr) {
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        KqlQuery query() {
            KqlQuery query = pipeline();
            if (peek().kind() != Kind.END) {
                throw new KqlException("Unexpected '" + peek().text() + "'; operators are separated by '|'");
            }
            return query;
        }

        /** A table name and the operators piped after it, up to the end or a closing parenthesis. */
        private KqlQuery pipeline() {
            Token first = next();
            if (first.kind() != Kind.IDENT) {
                throw new KqlException("A query starts with a table name");
            }
            List<Stage> stages = new ArrayList<>();
            while (accept("|")) {
                stages.add(operator());
            }
            return new KqlQuery(first.text(), stages);
        }

        private Stage operator() {
            String name = ident().toLowerCase(Locale.ROOT);
            return switch (name) {
                case "where" -> {
                    Expr predicate = expression();
                    yield rows(rows -> rows.stream().filter(row -> truthy(predicate.eval(row))).toList());
                }
                case "project" -> {
                    List<Column> columns = columns();
                    yield rows(rows -> rows.stream().map(row -> {
                        Map<String, Object> out = new LinkedHashMap<>();
                        columns.forEach(column -> out.put(column.name(), column.expr().eval(row)));
                        return out;
                    }).toList());
                }
                case "project-away" -> {
                    List<String> names = identList();
                    yield rows(rows -> rows.stream().map(row -> {
                        Map<String, Object> out = new LinkedHashMap<>(row);
                        names.forEach(out::remove);
                        return out;
                    }).toList());
                }
                case "extend" -> {
                    List<Column> columns = columns();
                    yield rows(rows -> rows.stream().map(row -> {
                        Map<String, Object> out = new LinkedHashMap<>(row);
                        columns.forEach(column -> out.put(column.name(), column.expr().eval(row)));
                        return out;
                    }).toList());
                }
                case "take", "limit" -> {
                    int count = integer();
                    yield rows(rows -> rows.subList(0, Math.min(count, rows.size())));
                }
                case "top" -> {
                    int count = integer();
                    expectIdent("by");
                    SortKey key = sortKey();
                    yield rows(rows -> {
                        List<Map<String, Object>> sorted = sorted(rows, List.of(key));
                        return sorted.subList(0, Math.min(count, sorted.size()));
                    });
                }
                case "order", "sort" -> {
                    expectIdent("by");
                    List<SortKey> keys = new ArrayList<>();
                    do {
                        keys.add(sortKey());
                    } while (accept(","));
                    yield rows(rows -> sorted(rows, keys));
                }
                case "count" -> rows(rows -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("Count", (long) rows.size());
                    return List.of(out);
                });
                case "distinct" -> {
                    List<Column> columns = columns();
                    yield rows(rows -> {
                        Set<Map<String, Object>> seen = new LinkedHashSet<>();
                        for (Map<String, Object> row : rows) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            columns.forEach(column -> out.put(column.name(), column.expr().eval(row)));
                            seen.add(out);
                        }
                        return List.copyOf(seen);
                    });
                }
                case "summarize" -> summarize();
                case "union" -> union();
                case "mv-expand" -> mvExpand();
                default -> throw new KqlException("Operator '" + name + "' is not supported");
            };
        }

        private interface RowStage {
            List<Map<String, Object>> apply(List<Map<String, Object>> rows);
        }

        /** A stage that reads only the rows piped into it. */
        private static Stage rows(RowStage stage) {
            return (rows, tables) -> stage.apply(rows);
        }

        /** {@code union T, (T | ...), ...}: the piped rows followed by each table's or subquery's rows. */
        private Stage union() {
            List<KqlQuery> sources = new ArrayList<>();
            do {
                if (accept("(")) {
                    sources.add(pipeline());
                    expect(")");
                } else {
                    sources.add(new KqlQuery(ident(), List.of()));
                }
            } while (accept(","));
            return (rows, tables) -> {
                List<Map<String, Object>> out = new ArrayList<>(rows);
                sources.forEach(source -> out.addAll(source.run(tables)));
                return out;
            };
        }

        /**
         * {@code mv-expand [name =] column}: one row per element of an array, or per entry of a property bag as a
         * one-entry bag. A null value keeps its row and an empty array or bag drops it, as Kusto does.
         */
        private Stage mvExpand() {
            int start = pos;
            String name;
            Expr expr;
            if (peek().kind() == Kind.IDENT && tokens.get(pos + 1).text().equals("=")) {
                name = ident();
                expect("=");
                expr = expression();
            } else {
                expr = expression();
                name = columnName(start);
            }
            return rows(rows -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object value = expr.eval(row);
                    List<Object> elements = new ArrayList<>();
                    if (value instanceof List<?> list) {
                        elements.addAll(list);
                    } else if (value instanceof Map<?, ?> bag) {
                        bag.forEach((key, entry) -> {
                            Map<String, Object> single = new LinkedHashMap<>();
                            single.put(String.valueOf(key), entry);
                            elements.add(single);
                        });
                    } else {
                        elements.add(value);
                    }
                    for (Object element : elements) {
                        Map<String, Object> expanded = new LinkedHashMap<>(row);
                        expanded.put(name, element);
                        out.add(expanded);
                    }
                }
                return out;
            });
        }

        /** {@code summarize [name =] count() [by column, ...]}, or {@code summarize by column, ...}. */
        private Stage summarize() {
            String countColumn = null;
            if (!peekIdent("by")) {
                countColumn = "count_";
                if (peek().kind() == Kind.IDENT && tokens.get(pos + 1).text().equals("=")) {
                    countColumn = ident();
                    expect("=");
                }
                String function = ident();
                if (!function.equals("count")) {
                    throw new KqlException("Aggregation '" + function + "' is not supported; use count()");
                }
                expect("(");
                expect(")");
            }
            List<Column> by = acceptIdent("by") ? columns() : List.of();
            String counted = countColumn;
            return rows(rows -> {
                Map<Map<String, Object>, Long> groups = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> key = new LinkedHashMap<>();
                    by.forEach(column -> key.put(column.name(), column.expr().eval(row)));
                    groups.merge(key, 1L, Long::sum);
                }
                if (groups.isEmpty() && by.isEmpty()) {
                    groups.put(new LinkedHashMap<>(), 0L);
                }
                List<Map<String, Object>> out = new ArrayList<>();
                groups.forEach((key, count) -> {
                    Map<String, Object> row = new LinkedHashMap<>(key);
                    if (counted != null) {
                        row.put(counted, count);
                    }
                    out.add(row);
                });
                return out;
            });
        }

        private SortKey sortKey() {
            Expr expr = expression();
            boolean ascending = false;
            if (acceptIdent("asc")) {
                ascending = true;
            } else {
                acceptIdent("desc");
            }
            return new SortKey(expr, ascending);
        }

        private List<Column> columns() {
            List<Column> columns = new ArrayList<>();
            do {
                int start = pos;
                Expr expr;
                String name;
                if (peek().kind() == Kind.IDENT && tokens.get(pos + 1).text().equals("=")) {
                    name = ident();
                    expect("=");
                    expr = expression();
                } else {
                    expr = expression();
                    name = columnName(start);
                }
                columns.add(new Column(name, expr));
            } while (accept(","));
            return columns;
        }

        /** The name KQL gives an unnamed column: the last name in a path such as {@code properties.state}. */
        private String columnName(int start) {
            String name = null;
            for (int i = start; i < pos; i++) {
                Token token = tokens.get(i);
                if (token.kind() == Kind.IDENT || token.kind() == Kind.STRING) {
                    name = token.text();
                }
            }
            if (name == null) {
                throw new KqlException("A computed column needs a name: name = expression");
            }
            return name;
        }

        private List<String> identList() {
            List<String> names = new ArrayList<>();
            do {
                names.add(ident());
            } while (accept(","));
            return names;
        }

        private Expr expression() {
            Expr left = conjunction();
            while (acceptIdent("or")) {
                Expr l = left;
                Expr r = conjunction();
                left = row -> truthy(l.eval(row)) || truthy(r.eval(row));
            }
            return left;
        }

        private Expr conjunction() {
            Expr left = comparison();
            while (acceptIdent("and")) {
                Expr l = left;
                Expr r = comparison();
                left = row -> truthy(l.eval(row)) && truthy(r.eval(row));
            }
            return left;
        }

        private Expr comparison() {
            Expr left = primary();
            Token op = peek();
            String text = op.text();
            String lower = text.toLowerCase(Locale.ROOT);
            if (op.kind() == Kind.SYMBOL && Set.of("!in", "!in~").contains(text)
                    || op.kind() == Kind.IDENT && Set.of("in", "in~").contains(lower)) {
                pos++;
                boolean negated = text.startsWith("!");
                boolean caseless = text.endsWith("~");
                List<Expr> values = new ArrayList<>();
                expect("(");
                do {
                    values.add(primary());
                } while (accept(","));
                expect(")");
                return row -> {
                    Object value = left.eval(row);
                    boolean found = values.stream().anyMatch(candidate -> caseless
                            ? equalsIgnoreCase(value, candidate.eval(row))
                            : equalValues(value, candidate.eval(row)));
                    return found != negated;
                };
            }
            boolean binary = op.kind() == Kind.SYMBOL && Set.of("==", "!=", "=~", "!~", "<", ">", "<=", ">=",
                    "!contains", "!startswith", "!endswith", "!has").contains(text)
                    || op.kind() == Kind.IDENT && Set.of("contains", "contains_cs", "startswith", "endswith", "has")
                            .contains(lower);
            if (!binary) {
                return left;
            }
            pos++;
            Expr right = primary();
            return row -> compare(lower, left.eval(row), right.eval(row));
        }

        private Expr primary() {
            Token token = next();
            Expr expr = switch (token.kind()) {
                case STRING -> row -> token.text();
                case NUMBER -> {
                    Number number = token.text().contains(".")
                            ? Double.parseDouble(token.text())
                            : Long.parseLong(token.text());
                    yield row -> number;
                }
                case SYMBOL -> {
                    if (!token.text().equals("(")) {
                        throw new KqlException("Unexpected '" + token.text() + "'");
                    }
                    Expr inner = expression();
                    expect(")");
                    yield inner;
                }
                case IDENT -> identifier(token.text());
                case END -> throw new KqlException("The query ends where an expression was expected");
            };
            while (true) {
                if (accept(".")) {
                    String key = ident();
                    Expr base = expr;
                    expr = row -> member(base.eval(row), key);
                } else if (accept("[")) {
                    Expr key = expression();
                    expect("]");
                    Expr base = expr;
                    expr = row -> {
                        Object k = key.eval(row);
                        return k instanceof Number n ? index(base.eval(row), n.intValue()) : member(base.eval(row), text(k));
                    };
                } else {
                    return expr;
                }
            }
        }

        private Expr identifier(String name) {
            switch (name) {
                case "true" -> {
                    return row -> Boolean.TRUE;
                }
                case "false" -> {
                    return row -> Boolean.FALSE;
                }
                case "null" -> {
                    return row -> null;
                }
                default -> {
                }
            }
            if (accept("(")) {
                List<Expr> args = new ArrayList<>();
                if (!accept(")")) {
                    do {
                        args.add(expression());
                    } while (accept(","));
                    expect(")");
                }
                return call(name, args);
            }
            return row -> row.get(name);
        }

        /** Builds a call to one of the supported scalar functions, each taking one argument. */
        private static Expr call(String name, List<Expr> args) {
            if (args.size() != 1) {
                throw new KqlException("Function '" + name + "' takes one argument");
            }
            Expr arg = args.getFirst();
            return switch (name) {
                case "tolower" -> row -> {
                    String value = text(arg.eval(row));
                    return value == null ? null : value.toLowerCase(Locale.ROOT);
                };
                case "toupper" -> row -> {
                    String value = text(arg.eval(row));
                    return value == null ? null : value.toUpperCase(Locale.ROOT);
                };
                case "tostring" -> row -> {
                    String value = text(arg.eval(row));
                    return value == null ? "" : value;
                };
                case "bag_keys" -> row -> arg.eval(row) instanceof Map<?, ?> bag
                        ? bag.keySet().stream().map(String::valueOf).toList()
                        : null;
                case "strlen" -> row -> {
                    String value = text(arg.eval(row));
                    return value == null ? null : (long) value.length();
                };
                case "isempty" -> row -> isEmpty(arg.eval(row));
                case "isnotempty" -> row -> !isEmpty(arg.eval(row));
                case "isnull" -> row -> arg.eval(row) == null;
                case "isnotnull" -> row -> arg.eval(row) != null;
                case "not" -> row -> !truthy(arg.eval(row));
                default -> throw new KqlException("Function '" + name + "' is not supported");
            };
        }

        private int integer() {
            Token token = next();
            if (token.kind() != Kind.NUMBER || token.text().contains(".")) {
                throw new KqlException("Expected a whole number, found '" + token.text() + "'");
            }
            return Integer.parseInt(token.text());
        }

        private String ident() {
            Token token = next();
            if (token.kind() != Kind.IDENT) {
                throw new KqlException("Expected a name, found '" + token.text() + "'");
            }
            return token.text();
        }

        private void expectIdent(String word) {
            if (!acceptIdent(word)) {
                throw new KqlException("Expected '" + word + "', found '" + peek().text() + "'");
            }
        }

        private boolean peekIdent(String word) {
            return peek().kind() == Kind.IDENT && peek().text().equalsIgnoreCase(word);
        }

        private boolean acceptIdent(String word) {
            if (peekIdent(word)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(String symbol) {
            if (!accept(symbol)) {
                throw new KqlException("Expected '" + symbol + "', found '" + peek().text() + "'");
            }
        }

        private boolean accept(String symbol) {
            if (peek().kind() == Kind.SYMBOL && peek().text().equals(symbol)) {
                pos++;
                return true;
            }
            return false;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            Token token = tokens.get(pos);
            if (token.kind() != Kind.END) {
                pos++;
            }
            return token;
        }
    }

    // ── Values ──────────────────────────────────────────────────────────────────

    private static Object member(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static Object index(Object value, int i) {
        return value instanceof List<?> list && i >= 0 && i < list.size() ? list.get(i) : null;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static boolean isEmpty(Object value) {
        return value == null || value instanceof String text && text.isEmpty()
                || value instanceof Map<?, ?> bag && bag.isEmpty() || value instanceof List<?> list && list.isEmpty();
    }

    /** A value as text; an array or property bag as its JSON, as Kusto's {@code tostring} gives it. */
    private static String text(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    private static boolean equalValues(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return Objects.equals(text(left), text(right));
        }
        return Objects.equals(left, right);
    }

    private static boolean equalsIgnoreCase(Object left, Object right) {
        String a = text(left);
        String b = text(right);
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean compare(String op, Object left, Object right) {
        String a = text(left) == null ? "" : text(left);
        String b = text(right) == null ? "" : text(right);
        String la = a.toLowerCase(Locale.ROOT);
        String lb = b.toLowerCase(Locale.ROOT);
        return switch (op) {
            case "==" -> equalValues(left, right);
            case "!=" -> !equalValues(left, right);
            case "=~" -> la.equals(lb);
            case "!~" -> !la.equals(lb);
            case "contains" -> la.contains(lb);
            case "!contains" -> !la.contains(lb);
            case "contains_cs" -> a.contains(b);
            case "startswith" -> la.startsWith(lb);
            case "!startswith" -> !la.startsWith(lb);
            case "endswith" -> la.endsWith(lb);
            case "!endswith" -> !la.endsWith(lb);
            case "has" -> hasTerm(la, lb);
            case "!has" -> !hasTerm(la, lb);
            case "<", ">", "<=", ">=" -> {
                int order = compareValues(left, right);
                yield switch (op) {
                    case "<" -> order < 0;
                    case ">" -> order > 0;
                    case "<=" -> order <= 0;
                    default -> order >= 0;
                };
            }
            default -> throw new KqlException("Operator '" + op + "' is not supported");
        };
    }

    /** Whether {@code term} appears in {@code text} as a whole run of letters and digits. */
    private static boolean hasTerm(String text, String term) {
        int from = 0;
        while (!term.isEmpty()) {
            int at = text.indexOf(term, from);
            if (at < 0) {
                return false;
            }
            int end = at + term.length();
            boolean startsTerm = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean endsTerm = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startsTerm && endsTerm) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static int compareValues(Object left, Object right) {
        if (left == null || right == null) {
            return left == right ? 0 : left == null ? -1 : 1;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return text(left).compareTo(text(right));
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> rows, List<SortKey> keys) {
        Comparator<Map<String, Object>> order = null;
        for (SortKey key : keys) {
            Comparator<Map<String, Object>> next = (a, b) -> compareValues(key.expr().eval(a), key.expr().eval(b));
            if (!key.ascending()) {
                next = next.reversed();
            }
            order = order == null ? next : order.thenComparing(next);
        }
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        if (order != null) {
            copy.sort(order);
        }
        return copy;
    }
}
