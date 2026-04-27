package com.janyee.agent.infra.tool;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the set of `schema.table` references out of a SELECT/CTE statement for whitelist
 * enforcement. Intentionally light — a proper AST parser would be heavier than we need and
 * the agent only ever sends SELECT/CTE SQL (write statements are rejected elsewhere).
 *
 * Approach:
 *   1. Strip string literals and comments so FROM/JOIN inside a string does not match.
 *   2. Collect CTE names declared via `WITH name AS (...)` and exclude them from results,
 *      so a CTE reference is not mistaken for a real table.
 *   3. Regex-scan the remaining text for FROM/JOIN followed by a bare identifier. Skip
 *      subquery sources starting with `(`.
 *
 * Identifiers are lowercased and quote characters stripped; results always carry a schema
 * if the SQL used one. Entries without a schema surface as schema="".
 */
final class SqlTableExtractor {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/", Pattern.MULTILINE);

    // `from|join table`, where table is either a bare identifier or a `schema.table` pair.
    // Uses a broad character class that allows quoted identifiers, which we sanitize after.
    private static final Pattern FROM_OR_JOIN = Pattern.compile(
            "(?is)\\b(?:from|join)\\s+([\\w\"`\\[\\]$]+(?:\\.[\\w\"`\\[\\]$]+)?)"
    );

    private static final Pattern WITH_CTE = Pattern.compile(
            "(?is)\\b(?:with|,)\\s+([\\w\"`]+)\\s*(?:\\([^)]*\\))?\\s+as\\s*\\("
    );

    private SqlTableExtractor() {
    }

    record Ref(String schema, String table) {
        String qualified() {
            return schema == null || schema.isEmpty() ? table : schema + "." + table;
        }
    }

    static Set<Ref> extract(String sql) {
        Set<Ref> refs = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return refs;
        }
        String cleaned = stripLiteralsAndComments(sql);
        Set<String> cteNames = collectCteNames(cleaned);
        Matcher matcher = FROM_OR_JOIN.matcher(cleaned);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null || token.isBlank()) {
                continue;
            }
            // `FROM (SELECT ...)` subqueries: the character-class regex above won't match `(`,
            // so we never reach here with a paren. The subquery body itself is scanned by the
            // outer loop — any tables inside will be picked up as their own matches.
            Ref ref = parseRef(token);
            if (ref == null) {
                continue;
            }
            // Skip CTE self-references so we don't reject a WITH clause as a "non-whitelist table".
            if (ref.schema.isEmpty() && cteNames.contains(ref.table)) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

    private static String stripLiteralsAndComments(String sql) {
        String step1 = STRING_LITERAL.matcher(sql).replaceAll("''");
        String step2 = LINE_COMMENT.matcher(step1).replaceAll("");
        return BLOCK_COMMENT.matcher(step2).replaceAll("");
    }

    private static Set<String> collectCteNames(String cleaned) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = WITH_CTE.matcher(cleaned);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null) {
                continue;
            }
            String name = sanitize(raw).toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static Ref parseRef(String token) {
        String cleaned = sanitize(token);
        if (cleaned.isEmpty()) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < lower.length() - 1) {
            String schema = lower.substring(0, dotIndex);
            String table = lower.substring(dotIndex + 1);
            // Reject multi-level DB.schema.table (take last two parts) to stay schema.table.
            int prevDot = schema.lastIndexOf('.');
            if (prevDot >= 0) {
                schema = schema.substring(prevDot + 1);
            }
            return new Ref(schema, table);
        }
        return new Ref("", lower);
    }

    private static String sanitize(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.trim();
        if (cleaned.endsWith(",") || cleaned.endsWith(";") || cleaned.endsWith(")")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned
                .replace("\"", "")
                .replace("`", "")
                .replace("[", "")
                .replace("]", "");
    }
}
