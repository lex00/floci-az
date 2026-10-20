package io.floci.az.services.deployments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses an ARM template expression, the text between {@code [} and {@code ]} in a template string: function
 * calls, single-quoted strings ({@code ''} escapes a quote), integers, {@code true}/{@code false}/{@code null},
 * and property ({@code .name}) or index ({@code [i]}) access on any value.
 */
final class TemplateExpression {

    /** A parsed expression node. */
    sealed interface Node permits Literal, Call, Access {
    }

    record Literal(Object value) implements Node {
    }

    record Call(String function, List<Node> args) implements Node {
    }

    /** {@code target.name} when {@code index} is null and {@code name} is set, {@code target[index]} otherwise. */
    record Access(Node target, String name, Node index) implements Node {
    }

    private final String text;
    private int pos;

    private TemplateExpression(String text) {
        this.text = text;
    }

    static Node parse(String text) {
        TemplateExpression parser = new TemplateExpression(text);
        Node node = parser.expression();
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw new TemplateException("InvalidTemplate",
                    "Unexpected '" + text.substring(parser.pos) + "' in the expression '" + text + "'");
        }
        return node;
    }

    private Node expression() {
        skipSpace();
        Node node = primary();
        while (true) {
            skipSpace();
            if (peek('.')) {
                pos++;
                node = new Access(node, identifier(), null);
            } else if (peek('[')) {
                pos++;
                Node index = expression();
                skipSpace();
                expect(']');
                node = new Access(node, null, index);
            } else {
                return node;
            }
        }
    }

    private Node primary() {
        if (pos >= text.length()) {
            throw new TemplateException("InvalidTemplate", "The expression '" + text + "' ends too early");
        }
        char c = text.charAt(pos);
        if (c == '\'') {
            return new Literal(string());
        }
        if (Character.isDigit(c) || c == '-') {
            int start = pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            return new Literal(Long.parseLong(text.substring(start, pos)));
        }
        String name = identifier();
        skipSpace();
        if (!peek('(')) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "true" -> new Literal(Boolean.TRUE);
                case "false" -> new Literal(Boolean.FALSE);
                case "null" -> new Literal(null);
                default -> throw new TemplateException("InvalidTemplate",
                        "'" + name + "' in the expression '" + text + "' is not a function call");
            };
        }
        pos++;
        List<Node> args = new ArrayList<>();
        skipSpace();
        if (peek(')')) {
            pos++;
            return new Call(name, args);
        }
        while (true) {
            args.add(expression());
            skipSpace();
            if (peek(',')) {
                pos++;
            } else {
                expect(')');
                return new Call(name, args);
            }
        }
    }

    private String string() {
        StringBuilder value = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '\'') {
                if (peek('\'')) {
                    value.append('\'');
                    pos++;
                } else {
                    return value.toString();
                }
            } else {
                value.append(c);
            }
        }
        throw new TemplateException("InvalidTemplate", "Unterminated string in the expression '" + text + "'");
    }

    private String identifier() {
        int start = pos;
        while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
            pos++;
        }
        if (start == pos) {
            throw new TemplateException("InvalidTemplate", "Expected a name at position " + start
                    + " of the expression '" + text + "'");
        }
        return text.substring(start, pos);
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new TemplateException("InvalidTemplate", "Expected '" + c + "' at position " + pos
                    + " of the expression '" + text + "'");
        }
        pos++;
    }
}
