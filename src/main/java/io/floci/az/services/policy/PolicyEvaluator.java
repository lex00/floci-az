package io.floci.az.services.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a policy rule's {@code if} condition against one resource, as the Azure Policy definition structure
 * describes it. The supported subset is the logical operators ({@code allOf}, {@code anyOf}, {@code not}), field
 * and value conditions with every comparison operator, the fields {@code type}, {@code name}, {@code fullName},
 * {@code id}, {@code kind}, {@code location} and {@code tags} (whole or by key), and the template functions
 * {@code parameters}, {@code field}, {@code concat}, {@code toLower} and {@code toUpper}.
 *
 * <p>Anything else, such as an alias, a {@code count} expression or another template function, throws
 * {@link NotEvaluable}, so a caller can refuse the request instead of letting a rule it cannot read pass.
 * String comparisons ignore case, as Azure's do, and a field the resource lacks compares as an empty string.
 */
final class PolicyEvaluator {

    /** A rule shape outside the supported subset. */
    static final class NotEvaluable extends RuntimeException {
        NotEvaluable(String message) {
            super(message);
        }
    }

    private static final List<String> OPERATORS = List.of("equals", "notequals", "like", "notlike", "match",
            "notmatch", "matchinsensitively", "notmatchinsensitively", "contains", "notcontains", "in", "notin",
            "containskey", "notcontainskey", "less", "lessorequals", "greater", "greaterorequals", "exists");
    private static final Pattern TAG_FIELD = Pattern.compile("tags(?:\\['([^']*)'\\]|\\[([^\\]]*)\\]|\\.(.+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile("(\\w+)\\((.*)\\)", Pattern.DOTALL);

    private final Map<String, Object> parameters;
    private final Map<String, Object> resource;

    /**
     * @param parameters the definition's parameter values, by name
     * @param resource   the resource's {@code id}, {@code name}, {@code fullName}, {@code type}, {@code kind},
     *                   {@code location} and {@code tags}
     */
    PolicyEvaluator(Map<String, Object> parameters, Map<String, Object> resource) {
        this.parameters = parameters;
        this.resource = resource;
    }

    boolean matches(Object condition) {
        if (!(condition instanceof Map<?, ?> map)) {
            throw new NotEvaluable("A condition must be an object");
        }
        Object allOf = entry(map, "allOf");
        if (allOf != null) {
            for (Object inner : list(allOf, "allOf")) {
                if (!matches(inner)) {
                    return false;
                }
            }
            return true;
        }
        Object anyOf = entry(map, "anyOf");
        if (anyOf != null) {
            for (Object inner : list(anyOf, "anyOf")) {
                if (matches(inner)) {
                    return true;
                }
            }
            return false;
        }
        Object not = entry(map, "not");
        if (not != null) {
            return !matches(not);
        }
        if (entry(map, "count") != null) {
            throw new NotEvaluable("count expressions are not supported");
        }
        Object subject;
        if (entry(map, "field") != null) {
            subject = field(String.valueOf(resolve(entry(map, "field"))));
        } else if (map.containsKey("value")) {
            subject = resolve(map.get("value"));
        } else {
            throw new NotEvaluable("A condition needs allOf, anyOf, not, field or value");
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
            if (OPERATORS.contains(key)) {
                return compare(key, subject, resolve(e.getValue()));
            }
        }
        throw new NotEvaluable("The condition " + map.keySet() + " names no supported operator");
    }

    /** A rule value with its template expression, if any, evaluated; {@code [[} escapes a literal bracket. */
    Object resolve(Object value) {
        if (!(value instanceof String text) || !text.startsWith("[") || !text.endsWith("]")) {
            return value;
        }
        if (text.startsWith("[[")) {
            return text.substring(1);
        }
        return expression(text.substring(1, text.length() - 1).trim());
    }

    private Object expression(String text) {
        if (text.startsWith("'") && text.endsWith("'") && text.length() >= 2) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        Matcher call = CALL.matcher(text);
        if (!call.matches()) {
            throw new NotEvaluable("The expression '" + text + "' is not supported");
        }
        String function = call.group(1).toLowerCase(Locale.ROOT);
        List<Object> args = new ArrayList<>();
        for (String arg : arguments(call.group(2))) {
            args.add(expression(arg));
        }
        return switch (function) {
            case "parameters" -> {
                String name = String.valueOf(single(args, function));
                if (!parameters.containsKey(name)) {
                    throw new NotEvaluable("The parameter '" + name + "' has no value");
                }
                yield parameters.get(name);
            }
            case "field" -> field(String.valueOf(single(args, function)));
            case "concat" -> {
                StringBuilder joined = new StringBuilder();
                args.forEach(arg -> joined.append(arg == null ? "" : arg));
                yield joined.toString();
            }
            case "tolower" -> String.valueOf(single(args, function)).toLowerCase(Locale.ROOT);
            case "toupper" -> String.valueOf(single(args, function)).toUpperCase(Locale.ROOT);
            default -> throw new NotEvaluable("The template function '" + call.group(1) + "' is not supported");
        };
    }

    /** Splits a function's arguments on the commas outside quotes and parentheses. */
    private static List<String> arguments(String text) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                args.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!text.isBlank()) {
            args.add(text.substring(start).trim());
        }
        return args;
    }

    private static Object single(List<Object> args, String function) {
        if (args.size() != 1) {
            throw new NotEvaluable("The template function '" + function + "' takes one argument");
        }
        return args.getFirst();
    }

    private Object field(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "type", "name", "fullname", "id", "kind", "location", "tags" -> {
                return valueIgnoreCase(resource, name);
            }
            default -> {
            }
        }
        Matcher tag = TAG_FIELD.matcher(name);
        if (tag.matches()) {
            String key = tag.group(1) != null ? tag.group(1) : tag.group(2) != null ? tag.group(2) : tag.group(3);
            return resource.get("tags") instanceof Map<?, ?> tags ? valueIgnoreCase(tags, key) : null;
        }
        throw new NotEvaluable("The field '" + name + "' is not supported; aliases are not evaluated");
    }

    private static Object valueIgnoreCase(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (String.valueOf(e.getKey()).equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Object entry(Map<?, ?> map, String key) {
        return valueIgnoreCase(map, key);
    }

    private static List<?> list(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new NotEvaluable(name + " must be an array");
        }
        return list;
    }

    private static boolean compare(String operator, Object subject, Object target) {
        return switch (operator) {
            case "equals" -> same(subject, target);
            case "notequals" -> !same(subject, target);
            case "like" -> wildcard(subject, target);
            case "notlike" -> !wildcard(subject, target);
            case "match" -> pattern(subject, target, false);
            case "notmatch" -> !pattern(subject, target, false);
            case "matchinsensitively" -> pattern(subject, target, true);
            case "notmatchinsensitively" -> !pattern(subject, target, true);
            case "contains" -> text(subject).toLowerCase(Locale.ROOT).contains(text(target).toLowerCase(Locale.ROOT));
            case "notcontains" -> !text(subject).toLowerCase(Locale.ROOT)
                    .contains(text(target).toLowerCase(Locale.ROOT));
            case "in" -> list(target, "in").stream().anyMatch(candidate -> same(subject, candidate));
            case "notin" -> list(target, "notIn").stream().noneMatch(candidate -> same(subject, candidate));
            case "containskey" -> subject instanceof Map<?, ?> map && valueIgnoreCase(map, text(target)) != null;
            case "notcontainskey" -> !(subject instanceof Map<?, ?> map && valueIgnoreCase(map, text(target)) != null);
            case "less" -> order(subject, target) < 0;
            case "lessorequals" -> order(subject, target) <= 0;
            case "greater" -> order(subject, target) > 0;
            case "greaterorequals" -> order(subject, target) >= 0;
            case "exists" -> (subject != null) == Boolean.parseBoolean(text(target));
            default -> throw new NotEvaluable("The operator '" + operator + "' is not supported");
        };
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Map<?, ?> || a instanceof List<?> || b instanceof Map<?, ?> || b instanceof List<?>) {
            return Objects.equals(a, b);
        }
        return text(a).equalsIgnoreCase(text(b));
    }

    private static boolean wildcard(Object subject, Object target) {
        String regex = Pattern.quote(text(target)).replace("*", "\\E.*\\Q");
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text(subject)).matches();
    }

    /**
     * Azure's {@code match}, where {@code #} stands for a digit, {@code ?} for a letter and {@code .} for any
     * character.
     */
    private static boolean pattern(Object subject, Object target, boolean insensitive) {
        StringBuilder regex = new StringBuilder();
        for (char c : text(target).toCharArray()) {
            switch (c) {
                case '#' -> regex.append("[0-9]");
                case '?' -> regex.append("[a-zA-Z]");
                case '.' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        int flags = insensitive ? Pattern.CASE_INSENSITIVE : 0;
        return Pattern.compile(regex.toString(), flags).matcher(text(subject)).matches();
    }

    private static int order(Object a, Object b) {
        try {
            return new BigDecimal(text(a)).compareTo(new BigDecimal(text(b)));
        } catch (NumberFormatException e) {
            return text(a).compareToIgnoreCase(text(b));
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
