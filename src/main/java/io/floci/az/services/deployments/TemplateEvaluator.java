package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.deployments.TemplateExpression.Access;
import io.floci.az.services.deployments.TemplateExpression.Call;
import io.floci.az.services.deployments.TemplateExpression.Literal;
import io.floci.az.services.deployments.TemplateExpression.Node;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Evaluates template values: a string between {@code [} and {@code ]} is an expression (a leading {@code [[}
 * escapes a literal bracket), and objects and arrays are evaluated member by member. The template functions
 * cover parameters and variables, the scope functions ({@code resourceGroup()}, {@code subscription()},
 * {@code tenant()}, {@code deployment()}), resource ids, {@code reference} and {@code list*} through
 * {@link Resources}, and the string, array, object, logical, comparison and numeric functions templates use most.
 * A function outside that set is an {@code InvalidTemplate} error.
 */
final class TemplateEvaluator {

    /** What a template can read about deployed resources. */
    interface Resources {
        /** The resource's {@code GET} body, or null when it does not exist. */
        Map<String, Object> get(String resourceId, String apiVersion);

        /** The response of {@code POST {resourceId}/{action}}, or null when it fails. */
        Object post(String resourceId, String action, String apiVersion);
    }

    /** The scope a deployment runs in. */
    record Scope(String tenantId, String subscriptionId, String resourceGroup, String resourceGroupLocation,
                 String deploymentName) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> LIST_ACTIONS = Map.of("listkeys", "listKeys",
            "listsecrets", "listSecrets", "listconnectionstrings", "listConnectionStrings");
    private static final DateTimeFormatter UTC_NOW = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Scope scope;
    private final Map<String, Object> parameters;
    private final Map<String, Object> variableDefinitions;
    private final Map<String, Object> variables = new HashMap<>();
    private final Set<String> evaluating = new HashSet<>();
    private final Resources resources;
    private final Map<String, Integer> copyIndexes;
    private final String defaultCopy;

    TemplateEvaluator(Scope scope, Map<String, Object> parameters, Map<String, Object> variableDefinitions,
                      Resources resources) {
        this(scope, parameters, variableDefinitions, resources, Map.of(), null);
    }

    private TemplateEvaluator(Scope scope, Map<String, Object> parameters, Map<String, Object> variableDefinitions,
                              Resources resources, Map<String, Integer> copyIndexes, String defaultCopy) {
        this.scope = scope;
        this.parameters = parameters;
        this.variableDefinitions = variableDefinitions;
        this.resources = resources;
        this.copyIndexes = copyIndexes;
        this.defaultCopy = defaultCopy;
    }

    /** This evaluator inside the copy loop {@code name} at {@code index}; {@code copyIndex()} reads it. */
    TemplateEvaluator inCopy(String name, int index) {
        Map<String, Integer> indexes = new HashMap<>(copyIndexes);
        indexes.put(name.toLowerCase(Locale.ROOT), index);
        TemplateEvaluator inner = new TemplateEvaluator(scope, parameters, variableDefinitions, resources, indexes,
                name.toLowerCase(Locale.ROOT));
        inner.variables.putAll(variables);
        return inner;
    }

    Scope scope() {
        return scope;
    }

    /** {@code value} with every expression in it evaluated, and property-level {@code copy} arrays expanded. */
    Object evaluate(Object value) {
        if (value instanceof String text) {
            if (text.startsWith("[[")) {
                return text.substring(1);
            }
            if (text.startsWith("[") && text.endsWith("]")) {
                return eval(TemplateExpression.parse(text.substring(1, text.length() - 1)));
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(evaluate(String.valueOf(entry.getKey())));
                if (key.equals("copy") && entry.getValue() instanceof List<?> loops) {
                    for (Object loop : loops) {
                        expandPropertyCopy(loop, out);
                    }
                } else {
                    out.put(key, evaluate(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(item -> out.add(evaluate(item)));
            return out;
        }
        return value;
    }

    /** A property-level loop, {@code {"name", "count", "input"}}, as an array under its name. */
    private void expandPropertyCopy(Object loop, Map<String, Object> out) {
        Map<String, Object> spec = map(loop, "copy");
        String name = String.valueOf(spec.get("name"));
        int count = toInt(evaluate(spec.get("count")));
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(inCopy(name, i).evaluate(spec.get("input")));
        }
        out.put(name, items);
    }

    boolean truthy(Object condition) {
        Object value = evaluate(condition);
        return value == null || Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private Object eval(Node node) {
        return switch (node) {
            case Literal literal -> literal.value();
            case Access access -> access(eval(access.target()),
                    access.name() != null ? access.name() : eval(access.index()));
            case Call call -> call(call.function().toLowerCase(Locale.ROOT), call.args());
        };
    }

    private static Object access(Object target, Object key) {
        if (target instanceof Map<?, ?> map) {
            String name = String.valueOf(key);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            throw new TemplateException("InvalidTemplate", "The property '" + name + "' does not exist");
        }
        if (target instanceof List<?> list) {
            int index = toInt(key);
            if (index < 0 || index >= list.size()) {
                throw new TemplateException("InvalidTemplate", "The index " + index + " is out of range");
            }
            return list.get(index);
        }
        throw new TemplateException("InvalidTemplate", "Cannot read '" + key + "' of a "
                + (target == null ? "null" : target.getClass().getSimpleName()) + " value");
    }

    private Object call(String function, List<Node> argNodes) {
        switch (function) {
            case "if" -> {
                arity(function, argNodes, 3);
                return truthyValue(eval(argNodes.get(0))) ? eval(argNodes.get(1)) : eval(argNodes.get(2));
            }
            case "copyindex" -> {
                return copyIndex(argNodes);
            }
            default -> {
            }
        }
        List<Object> args = new ArrayList<>();
        argNodes.forEach(arg -> args.add(eval(arg)));
        return switch (function) {
            case "parameters" -> parameter(text(one(function, args)));
            case "variables" -> variable(text(one(function, args)));
            case "resourcegroup" -> resourceGroup();
            case "subscription" -> Map.of("id", "/subscriptions/" + scope.subscriptionId(),
                    "subscriptionId", scope.subscriptionId(), "tenantId", scope.tenantId());
            case "tenant" -> Map.of("tenantId", scope.tenantId());
            case "deployment" -> Map.of("name", scope.deploymentName(), "properties", Map.of());
            case "environment" -> Map.of("name", "AzureCloud",
                    "suffixes", Map.of("storage", "core.windows.net", "keyvaultDns", ".vault.azure.net"));
            case "resourceid" -> resourceId(args, true);
            case "subscriptionresourceid" -> subscriptionResourceId(args);
            case "tenantresourceid" -> "/providers/" + typeAndNames(args);
            case "extensionresourceid" -> text(args.getFirst()) + "/providers/"
                    + typeAndNames(args.subList(1, args.size()));
            case "reference" -> reference(args);
            case "listkeys", "listsecrets", "listconnectionstrings" -> resources.post(text(args.get(0)),
                    LIST_ACTIONS.get(function), args.size() > 1 ? text(args.get(1)) : null);
            case "concat" -> concat(args);
            case "format" -> format(args);
            case "string" -> stringOf(one(function, args));
            case "int" -> (long) toInt(one(function, args));
            case "bool" -> truthyValue(one(function, args));
            case "json" -> json(text(one(function, args)));
            case "tolower" -> text(one(function, args)).toLowerCase(Locale.ROOT);
            case "toupper" -> text(one(function, args)).toUpperCase(Locale.ROOT);
            case "trim" -> text(one(function, args)).trim();
            case "replace" -> text(args.get(0)).replace(text(args.get(1)), text(args.get(2)));
            case "substring" -> substring(args);
            case "split" -> split(args);
            case "join" -> String.join(text(args.get(1)),
                    list(args.get(0)).stream().map(TemplateEvaluator::text).toList());
            case "startswith" -> lower(args.get(0)).startsWith(lower(args.get(1)));
            case "endswith" -> lower(args.get(0)).endsWith(lower(args.get(1)));
            case "indexof" -> (long) lower(args.get(0)).indexOf(lower(args.get(1)));
            case "length" -> (long) length(one(function, args));
            case "empty" -> length(one(function, args)) == 0;
            case "contains" -> contains(args.get(0), args.get(1));
            case "first" -> first(one(function, args));
            case "last" -> last(one(function, args));
            case "take" -> slice(args.get(0), 0, toInt(args.get(1)));
            case "skip" -> slice(args.get(0), toInt(args.get(1)), Integer.MAX_VALUE);
            case "createarray" -> new ArrayList<>(args);
            case "createobject" -> createObject(args);
            case "union" -> union(args);
            case "range" -> range(toInt(args.get(0)), toInt(args.get(1)));
            case "coalesce" -> args.stream().filter(Objects::nonNull).findFirst().orElse(null);
            case "equals" -> equalValues(args.get(0), args.get(1));
            case "not" -> !truthyValue(one(function, args));
            case "and" -> args.stream().allMatch(TemplateEvaluator::truthyValue);
            case "or" -> args.stream().anyMatch(TemplateEvaluator::truthyValue);
            case "less" -> compare(args.get(0), args.get(1)) < 0;
            case "lessorequals" -> compare(args.get(0), args.get(1)) <= 0;
            case "greater" -> compare(args.get(0), args.get(1)) > 0;
            case "greaterorequals" -> compare(args.get(0), args.get(1)) >= 0;
            case "add" -> toLong(args.get(0)) + toLong(args.get(1));
            case "sub" -> toLong(args.get(0)) - toLong(args.get(1));
            case "mul" -> toLong(args.get(0)) * toLong(args.get(1));
            case "div" -> toLong(args.get(0)) / toLong(args.get(1));
            case "mod" -> toLong(args.get(0)) % toLong(args.get(1));
            case "min" -> args.stream().map(TemplateEvaluator::toLong).min(Long::compare).orElseThrow();
            case "max" -> args.stream().map(TemplateEvaluator::toLong).max(Long::compare).orElseThrow();
            case "uniquestring" -> uniqueString(args);
            case "guid" -> UUID.nameUUIDFromBytes(joined(args).getBytes(StandardCharsets.UTF_8)).toString();
            case "newguid" -> UUID.randomUUID().toString();
            case "utcnow" -> UTC_NOW.format(Instant.now());
            case "base64" -> Base64.getEncoder()
                    .encodeToString(text(one(function, args)).getBytes(StandardCharsets.UTF_8));
            case "base64tostring" -> new String(Base64.getDecoder().decode(text(one(function, args))),
                    StandardCharsets.UTF_8);
            default -> throw new TemplateException("InvalidTemplate",
                    "The template function '" + function + "' is not supported by floci-az");
        };
    }

    private Object parameter(String name) {
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        throw new TemplateException("InvalidTemplate", "The template parameter '" + name + "' is not found");
    }

    private Object variable(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (variables.containsKey(key)) {
            return variables.get(key);
        }
        Object definition = null;
        boolean found = false;
        for (Map.Entry<String, Object> entry : variableDefinitions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                definition = entry.getValue();
                found = true;
            }
        }
        if (!found) {
            throw new TemplateException("InvalidTemplate", "The template variable '" + name + "' is not found");
        }
        if (!evaluating.add(key)) {
            throw new TemplateException("InvalidTemplate", "The template variable '" + name + "' refers to itself");
        }
        Object value = evaluate(definition);
        evaluating.remove(key);
        variables.put(key, value);
        return value;
    }

    private Object copyIndex(List<Node> argNodes) {
        String loop = defaultCopy;
        long offset = 0;
        for (Node node : argNodes) {
            Object arg = eval(node);
            if (arg instanceof Number number) {
                offset = number.longValue();
            } else {
                loop = text(arg).toLowerCase(Locale.ROOT);
            }
        }
        Integer index = loop == null ? null : copyIndexes.get(loop);
        if (index == null) {
            throw new TemplateException("InvalidTemplate", "copyIndex() is used outside a copy loop");
        }
        return index + offset;
    }

    private Map<String, Object> resourceGroup() {
        if (scope.resourceGroup() == null) {
            throw new TemplateException("InvalidTemplate",
                    "resourceGroup() is not available in a subscription-scope deployment");
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "/subscriptions/" + scope.subscriptionId() + "/resourceGroups/" + scope.resourceGroup());
        group.put("name", scope.resourceGroup());
        group.put("type", "Microsoft.Resources/resourceGroups");
        group.put("location", scope.resourceGroupLocation());
        group.put("properties", Map.of("provisioningState", "Succeeded"));
        return group;
    }

    /**
     * {@code resourceId([subscriptionId], [resourceGroupName], type, name...)}: the leading arguments before the
     * one holding a {@code /} name the subscription and group.
     */
    private String resourceId(List<Object> args, boolean inGroup) {
        int typeAt = typeArgument(args);
        String subscription = scope.subscriptionId();
        String group = scope.resourceGroup();
        if (typeAt == 2) {
            subscription = text(args.get(0));
            group = text(args.get(1));
        } else if (typeAt == 1) {
            group = text(args.get(0));
        }
        if (!inGroup || group == null) {
            return "/subscriptions/" + subscription + "/providers/" + typeAndNames(args.subList(typeAt, args.size()));
        }
        return "/subscriptions/" + subscription + "/resourceGroups/" + group + "/providers/"
                + typeAndNames(args.subList(typeAt, args.size()));
    }

    private String subscriptionResourceId(List<Object> args) {
        int typeAt = typeArgument(args);
        String subscription = typeAt >= 1 ? text(args.get(0)) : scope.subscriptionId();
        return "/subscriptions/" + subscription + "/providers/" + typeAndNames(args.subList(typeAt, args.size()));
    }

    private static int typeArgument(List<Object> args) {
        for (int i = 0; i < args.size(); i++) {
            if (text(args.get(i)).contains("/")) {
                return i;
            }
        }
        throw new TemplateException("InvalidTemplate", "resourceId needs a resource type such as 'Ns/type'");
    }

    /** {@code Ns/type/child} with names {@code a, b} as {@code Ns/type/a/child/b}. */
    static String typeAndNames(List<Object> args) {
        String[] type = text(args.getFirst()).split("/");
        List<String> names = args.subList(1, args.size()).stream().map(TemplateEvaluator::text).toList();
        if (names.size() != type.length - 1) {
            throw new TemplateException("InvalidTemplate", "The resource type '" + text(args.getFirst())
                    + "' needs " + (type.length - 1) + " name segment(s), got " + names.size());
        }
        StringBuilder path = new StringBuilder(type[0]);
        for (int i = 1; i < type.length; i++) {
            path.append('/').append(type[i]).append('/').append(names.get(i - 1));
        }
        return path.toString();
    }

    /** Reads a resource for {@code reference(id, [apiVersion], ['Full'])}: its properties, or its body with 'Full'. */
    private Object reference(List<Object> args) {
        String id = text(args.getFirst());
        String apiVersion = args.size() > 1 ? text(args.get(1)) : null;
        boolean full = args.size() > 2 && "full".equalsIgnoreCase(text(args.get(2)));
        Map<String, Object> body = resources.get(id, apiVersion);
        if (body == null) {
            throw new TemplateException("ResourceNotFound", "The resource '" + id + "' referenced by the template "
                    + "is not found");
        }
        return full ? body : body.getOrDefault("properties", Map.of());
    }

    private static Object concat(List<Object> args) {
        if (!args.isEmpty() && args.getFirst() instanceof List<?>) {
            List<Object> joined = new ArrayList<>();
            args.forEach(arg -> joined.addAll(list(arg)));
            return joined;
        }
        StringBuilder text = new StringBuilder();
        args.forEach(arg -> text.append(stringOf(arg)));
        return text.toString();
    }

    private static String format(List<Object> args) {
        String result = text(args.getFirst());
        for (int i = 1; i < args.size(); i++) {
            result = result.replace("{" + (i - 1) + "}", stringOf(args.get(i)));
        }
        return result;
    }

    private static String substring(List<Object> args) {
        String value = text(args.get(0));
        int start = toInt(args.get(1));
        int length = args.size() > 2 ? toInt(args.get(2)) : value.length() - start;
        return value.substring(start, start + length);
    }

    private static List<Object> split(List<Object> args) {
        List<String> delimiters = args.get(1) instanceof List<?> many
                ? many.stream().map(TemplateEvaluator::text).toList()
                : List.of(text(args.get(1)));
        List<Object> parts = new ArrayList<>(List.of(text(args.get(0))));
        for (String delimiter : delimiters) {
            List<Object> next = new ArrayList<>();
            for (Object part : parts) {
                next.addAll(List.of(text(part).split(Pattern.quote(delimiter), -1)));
            }
            parts = next;
        }
        return parts;
    }

    private static int length(Object value) {
        return switch (value) {
            case null -> 0;
            case List<?> list -> list.size();
            case Map<?, ?> map -> map.size();
            default -> text(value).length();
        };
    }

    private static boolean contains(Object container, Object item) {
        return switch (container) {
            case List<?> list -> list.stream().anyMatch(element -> equalValues(element, item));
            case Map<?, ?> map -> map.keySet().stream().anyMatch(key -> text(key).equalsIgnoreCase(text(item)));
            case null -> false;
            default -> text(container).toLowerCase(Locale.ROOT).contains(text(item).toLowerCase(Locale.ROOT));
        };
    }

    private static Object first(Object value) {
        return value instanceof List<?> list ? (list.isEmpty() ? null : list.getFirst())
                : text(value).isEmpty() ? "" : text(value).substring(0, 1);
    }

    private static Object last(Object value) {
        return value instanceof List<?> list ? (list.isEmpty() ? null : list.getLast())
                : text(value).isEmpty() ? "" : text(value).substring(text(value).length() - 1);
    }

    private static Object slice(Object value, int from, int to) {
        if (value instanceof List<?> list) {
            int start = Math.min(Math.max(from, 0), list.size());
            return new ArrayList<>(list.subList(start, Math.min(Math.max(to, start), list.size())));
        }
        String text = text(value);
        int start = Math.min(Math.max(from, 0), text.length());
        return text.substring(start, Math.min(Math.max(to, start), text.length()));
    }

    private static Map<String, Object> createObject(List<Object> args) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            object.put(text(args.get(i)), args.get(i + 1));
        }
        return object;
    }

    private static Object union(List<Object> args) {
        if (!args.isEmpty() && args.getFirst() instanceof Map<?, ?>) {
            Map<String, Object> merged = new LinkedHashMap<>();
            args.forEach(arg -> map(arg, "union").forEach(merged::put));
            return merged;
        }
        Set<Object> merged = new LinkedHashSet<>();
        args.forEach(arg -> merged.addAll(list(arg)));
        return new ArrayList<>(merged);
    }

    private static List<Object> range(int start, int count) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add((long) start + i);
        }
        return values;
    }

    /**
     * A deterministic 13-character string from the arguments, in the shape of Azure's {@code uniqueString}. Its
     * value is floci-az's own: Azure's hash is not reproduced.
     */
    private static String uniqueString(List<Object> args) {
        UUID hash = UUID.nameUUIDFromBytes(joined(args).getBytes(StandardCharsets.UTF_8));
        long bits = hash.getMostSignificantBits() ^ hash.getLeastSignificantBits();
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            out.append(alphabet.charAt((int) ((bits >>> (59 - 5 * Math.min(i, 11))) & 31)));
        }
        return out.toString();
    }

    private static String joined(List<Object> args) {
        return String.join("-", args.stream().map(TemplateEvaluator::text).toList());
    }

    private static Object json(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new TemplateException("InvalidTemplate", "json() received text that is not JSON");
        }
    }

    private static String stringOf(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return value == null ? "" : String.valueOf(value);
    }

    static boolean truthyValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean equalValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.longValue() == y.longValue();
        }
        return Objects.equals(a, b);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Long.compare(x.longValue(), y.longValue());
        }
        return text(a).compareTo(text(b));
    }

    static int toInt(Object value) {
        return (int) toLong(value);
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value).trim());
        } catch (NumberFormatException e) {
            throw new TemplateException("InvalidTemplate", "'" + value + "' is not an integer");
        }
    }

    private static String lower(Object value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new TemplateException("InvalidTemplate", "Expected an array, got '" + value + "'");
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new TemplateException("InvalidTemplate", what + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static Object one(String function, List<Object> args) {
        if (args.size() != 1) {
            throw new TemplateException("InvalidTemplate", function + "() takes one argument");
        }
        return args.getFirst();
    }

    private static void arity(String function, List<Node> args, int expected) {
        if (args.size() != expected) {
            throw new TemplateException("InvalidTemplate", function + "() takes " + expected + " arguments");
        }
    }
}
