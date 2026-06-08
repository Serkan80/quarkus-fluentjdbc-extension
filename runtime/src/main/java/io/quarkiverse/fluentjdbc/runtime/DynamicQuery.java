package io.quarkiverse.fluentjdbc.runtime;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bmwgroup.fs.fleetreporting.queryservice.panache.QueryOperator.AND;
import static com.bmwgroup.fs.fleetreporting.queryservice.panache.QueryOperator.COMMA;
import static com.bmwgroup.fs.fleetreporting.queryservice.panache.QueryParamNamer.NUMBERED;

/**
 * <p>
 * A helper class to produce dynamic queries which adds clauses to the query when the clauses are not null.
 * </p>
 *
 * <p>
 * Example: <br/>
 * <code>
 * - name = :name => with name null, </br>
 * - age > :age => age = 18 </br>
 * </code>
 * <p>
 * Will produce the following query: <code>where age > :age</code>
 * </p>
 */
@RegisterForReflection
public class DynamicQuery {

    /**
     * Matches a named JPQL/HQL parameter such as {@code :name} or {@code :age}.
     */
    private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");

    protected final List<Object> parameters = new ArrayList<>();

    protected String[] clauses;
    protected QueryOperator operator = AND;
    protected QueryParamNamer paramNamer = NUMBERED;
    protected boolean includeWhereKeyword = false;

    public DynamicQuery selectClauses(String... clauses) {
        this.clauses = clauses;
        return this;
    }

    public UpdateQuery updateClauses(String... clauses) {
        return new UpdateQuery(clauses);
    }

    public DynamicQuery params(Object... params) {
        this.parameters.addAll(Arrays.asList(params));
        return this;
    }

    public DynamicQuery paramsFromDto(Object dto, Object... otherParams) {
        return paramsFromDto(dto, _ -> true, otherParams);
    }

    /**
     * Reads the values from the given DTO.
     * Note: params() and paramsFromDto() are mutually exclusive — paramsFromDto() resets the parameter list.
     *
     * @param dto         the DTO
     * @param nameFilter  the parameters to be ex- or included by checking the name of a field in the DTO.
     * @param otherParams additional parameters that need to be used. These will be added after the parameters of the DTO.
     * @return a list of parameters from the DTO plus the additional parameters
     */
    public DynamicQuery paramsFromDto(Object dto, Predicate<String> nameFilter, Object... otherParams) {
        this.parameters.clear();
        var clauseFields = extractFieldNamesFromClauses();
        var dtoParams = JsonObject.mapFrom(dto).stream()
                // Only include DTO fields that are referenced in a clause; exclude fields not in any clause
                .filter(entry -> clauseFields.isEmpty() || clauseFields.contains(entry.getKey()))
                // Filtered-out fields become null so their clause is excluded by validateParams
                .map(entry -> nameFilter.test(entry.getKey()) ? entry.getValue() : null)
                .toList();

        this.parameters.addAll(dtoParams);
        Collections.addAll(this.parameters, otherParams);
        return this;
    }

    /**
     * The naming of the parameters.
     * <ul>
     * <li>numbered: <code>name = ?1 and age > ?2</code>
     * <li>unnumbered: <code>name = ? and age > ?</code>
     * <li>named: <code>name = :name and age > :age</code>
     * </ul>
     */
    public DynamicQuery paramNamer(QueryParamNamer paramNamer) {
        this.paramNamer = paramNamer;
        return this;
    }

    public DynamicQuery operator(QueryOperator op) {
        this.operator = op;
        return this;
    }

    /**
     * Instructs {@link #build()} to prepend {@code where} to the generated condition fragment.
     */
    public DynamicQuery withWhere() {
        this.includeWhereKeyword = true;
        return this;
    }

    public QueryResult build() {
        var paramCounter = new Counter(1);
        var finalQuery = processClauses(paramCounter, (exp, list) -> list.add("(%s)".formatted(renameParams(exp, paramCounter))), this.clauses);

        if (this.includeWhereKeyword && !finalQuery.isBlank()) {
            finalQuery = " where " + finalQuery;
        }

        return new QueryResult(finalQuery, this.parameters);
    }

    protected String processClauses(Counter paramCounter, BiConsumer<String, List<String>> consumer, String... clauses) {
        var validExpressions = new ArrayList<String>();
        var paramIndex = 0;

        for (var clause : clauses) {
            var expression = clause.stripTrailing();
            var validationResult = validateParams(expression, paramIndex);

            if (validationResult.isValid()) {
                if (isFieldNameOnly(expression)) {
                    validExpressions.add(nameExpression(expression, paramCounter));
                } else {
                    consumer.accept(expression, validExpressions);
                }
                paramIndex += validationResult.paramCount();
            } else {
                removeNullParams(paramIndex, validationResult.paramCount());
            }
        }

        return String.join(this.operator.value, validExpressions.toArray(String[]::new));
    }

    protected String renameParams(String expression, Counter paramCounter) {
        return switch (this.paramNamer) {
            case NAMED -> expression;
            case NUMBERED -> {
                var matcher = NAMED_PARAM.matcher(expression);
                var sb = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("?%d".formatted(paramCounter.next())));
                }
                matcher.appendTail(sb);
                yield sb.toString();
            }
            case UNNUMBERED -> replaceParams(expression, "?");
        };
    }

    private String nameExpression(String expression, Counter paramCounter) {
        return switch (this.paramNamer) {
            case NAMED -> "%s = %s%s".formatted(expression, this.paramNamer.param, expression);
            case NUMBERED -> "%s = ?%d".formatted(expression, paramCounter.next());
            case UNNUMBERED -> "%s = %s".formatted(expression, this.paramNamer.param);
        };
    }

    // Distinguishes a bare field name like "age" from a full expression like "age > :age"
    private static boolean isFieldNameOnly(String expression) {
        return expression.matches("\\w+");
    }

    private void removeNullParams(int paramIndex, int times) {
        this.parameters.subList(paramIndex, paramIndex + times).clear();
    }

    private ValidParamResult validateParams(String expression, int paramIndex) {
        var matcher = NAMED_PARAM.matcher(expression);
        var isValid = true;
        var paramCount = 0;

        while (matcher.find()) {
            var idx = paramIndex + paramCount;
            isValid &= idx < this.parameters.size() && this.parameters.get(idx) != null;
            paramCount++;
        }

        if (paramCount == 0) {
            // No named params — clause uses one implicit/positional parameter
            isValid = paramIndex < this.parameters.size() && this.parameters.get(paramIndex) != null;
            paramCount = 1;
        }

        return new ValidParamResult(isValid, paramCount);
    }

    private static String replaceParams(String expression, String replacement) {
        return NAMED_PARAM.matcher(expression).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Extracts all field names referenced in the current clauses.
     * Named parameters (":fieldName") and bare field names ("fieldName") are both collected.
     */
    private Set<String> extractFieldNamesFromClauses() {
        if (this.clauses == null || this.clauses.length == 0) {
            return Set.of();
        }
        return Arrays.stream(this.clauses)
                .flatMap(clause -> {
                    if (isFieldNameOnly(clause)) {
                        return Stream.of(clause);
                    }
                    var matcher = NAMED_PARAM.matcher(clause);
                    var names = new ArrayList<String>();
                    while (matcher.find()) {
                        names.add(matcher.group(1));
                    }
                    return names.stream();
                })
                .collect(Collectors.toSet());
    }

    /**
     * Simple mutable counter used to number query parameters (?1, ?2, …).
     */
    protected static final class Counter {

        private int value;

        Counter(int start) {
            this.value = start;
        }

        int next() {
            return this.value++;
        }
    }

    private record ValidParamResult(boolean isValid, int paramCount) {
    }

    public record QueryResult(String query, List<Object> parameters) {
    }

    public static class UpdateQuery extends DynamicQuery {
        protected String whereClause;

        public UpdateQuery(String[] clauses) {
            this.clauses = clauses;
        }

        @Override
        public UpdateQuery params(Object... params) {
            this.parameters.addAll(Arrays.asList(params));
            return this;
        }

        @Override
        public UpdateQuery paramsFromDto(Object dto, Object... otherParams) {
            super.paramsFromDto(dto, otherParams);
            return this;
        }

        @Override
        public UpdateQuery paramsFromDto(Object dto, Predicate<String> nameFilter, Object... otherParams) {
            super.paramsFromDto(dto, nameFilter, otherParams);
            return this;
        }

        @Override
        public UpdateQuery paramNamer(QueryParamNamer namer) {
            this.paramNamer = namer;
            return this;
        }

        public UpdateQuery where(String where) {
            this.whereClause = where;
            return this;
        }

        public QueryResult build() {
            if (this.parameters.isEmpty()) {
                throw new IllegalArgumentException("No parameters provided");
            }

            this.operator = COMMA;
            var paramCounter = new Counter(1);
            BiConsumer<String, List<String>> updateExpAdder = (exp, list) -> list.add(renameParams(exp, paramCounter));
            var query = processClauses(paramCounter, updateExpAdder, this.clauses);

            if (query.isBlank()) {
                throw new IllegalStateException("Invalid UPDATE statement: No fields to update.");
            }

            var whereQuery = this.whereClause != null ? processClauses(paramCounter, updateExpAdder, this.whereClause) : "";
            var finalQuery = "SET " + query;
            if (!whereQuery.isBlank()) {
                finalQuery += " WHERE " + whereQuery;
            }

            return new QueryResult(finalQuery, this.parameters);
        }
    }
}
