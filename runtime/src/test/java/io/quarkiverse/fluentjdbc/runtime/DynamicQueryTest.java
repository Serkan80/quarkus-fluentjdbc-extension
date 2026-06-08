package io.quarkiverse.fluentjdbc.runtime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicQueryTest {

    record SearchParams(String name, Integer age, String city) {}
    record NameOnly(String name) {}

    // -------------------------------------------------------------------------
    // selectClauses + params
    // -------------------------------------------------------------------------

    @Nested
    class SelectWithParams {

        @Test
        void shorthandAssignment() {
            var result = new DynamicQuery()
                    .selectClauses("name", "age > :age") // should generate name = :name internally
                    .params("Alice", 18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = ?1 and (age > ?2)");
            assertThat(result.parameters()).containsExactly("Alice", 18);
        }

        @Test
        void multipleParamsOneStatement_allPresent() {
            var result = new DynamicQuery()
                    .selectClauses("name", "age between :start and :end")
                    .params("john", 1, 30)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = ?1 and (age between ?2 and ?3)");
            assertThat(result.parameters()).containsExactly("john", 1, 30);
        }

        @Test
        void multipleParamsOneStatement_oneNull_clauseExcluded() {
            var result = new DynamicQuery()
                    .selectClauses("age between :start and :end")
                    .params(1, null)
                    .withWhere()
                    .build();

            assertThat(result.query()).isBlank();
            assertThat(result.parameters()).isEmpty();
        }

        @Test
        void complexStatements() {
            var result = new DynamicQuery()
                    .selectClauses("lower(name) = lower(:name)", "age is not null and age > :age")
                    .params("Alice", 18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (lower(name) = lower(?1)) and (age is not null and age > ?2)");
            assertThat(result.parameters()).containsExactly("Alice", 18);
        }

        @Test
        void allParamsPresent_includesAllClauses() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params("Alice", 18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (age > ?2)");
            assertThat(result.parameters()).containsExactly("Alice", 18);
        }

        @Test
        void firstParamNull_firstClauseExcluded() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params(null, 18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (age > ?1)");
            assertThat(result.parameters()).containsExactly(18);
        }

        @Test
        void lastParamNull_lastClauseExcluded() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params("Alice", null)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1)");
            assertThat(result.parameters()).containsExactly("Alice");
        }

        @Test
        void middleParamNull_middleClauseExcluded() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age", "city = :city")
                    .params("Alice", null, "Berlin")
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (city = ?2)");
            assertThat(result.parameters()).containsExactly("Alice", "Berlin");
        }

        @Test
        void allParamsNull_producesEmptyQuery() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params(null, null)
                    .withWhere()
                    .build();

            assertThat(result.query()).isBlank();
            assertThat(result.parameters()).isEmpty();
        }

        @Test
        void noClauses_producesEmptyQuery() {
            var result = new DynamicQuery()
                    .selectClauses()
                    .build();

            assertThat(result.query()).isBlank();
        }

        @Test
        void singleClause_nonNull_included() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name")
                    .params("Alice")
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1)");
        }

        @Test
        void singleClause_null_producesEmptyQuery() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name")
                    .params((Object) null)
                    .build();

            assertThat(result.query()).isBlank();
        }

        @Test
        void orOperator_joinsClausesWithOr() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params("Alice", 18)
                    .operator(QueryOperator.OR)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) or (age > ?2)");
        }

        @Test
        void withoutWhere_producesConditionOnly() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age")
                    .params("Alice", 18)
                    .build();

            assertThat(result.query()).isEqualTo("(name = ?1) and (age > ?2)");
        }
    }

    // -------------------------------------------------------------------------
    // Unnamed clauses (bare field names)
    // -------------------------------------------------------------------------

    @Nested
    class UnnamedClauses {

        @Test
        void numberedNamer_expandsToEqualityWithNumberedParam() {
            var result = new DynamicQuery()
                    .selectClauses("name")
                    .params("Alice")
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = ?1");
            assertThat(result.parameters()).containsExactly("Alice");
        }

        @Test
        void namedNamer_expandsToNamedParam() {
            var result = new DynamicQuery()
                    .selectClauses("name")
                    .params("Alice")
                    .paramNamer(QueryParamNamer.NAMED)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = :name");
        }

        @Test
        void unnumberedNamer_expandsToPlaceholder() {
            var result = new DynamicQuery()
                    .selectClauses("name")
                    .params("Alice")
                    .paramNamer(QueryParamNamer.UNNUMBERED)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = ?");
        }

        @Test
        void nullParam_unnamedClauseExcluded() {
            var result = new DynamicQuery()
                    .selectClauses("name")
                    .params((Object) null)
                    .build();

            assertThat(result.query()).isBlank();
        }

        @Test
        void mixedUnnamedAndNamedClauses_numberedCorrectly() {
            var result = new DynamicQuery()
                    .selectClauses("name", "age > :age")
                    .params("Alice", 18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where name = ?1 and (age > ?2)");
        }
    }

    // -------------------------------------------------------------------------
    // Param namer variations on full expressions
    // -------------------------------------------------------------------------

    @Nested
    class ParamNamerVariations {

        @Test
        void numbered_replacesPlaceholderWithNumber() {
            var result = new DynamicQuery()
                    .selectClauses("age > :age")
                    .params(18)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (age > ?1)");
        }

        @Test
        void named_keepsExpressionAsIs() {
            var result = new DynamicQuery()
                    .selectClauses("age > :age")
                    .params(18)
                    .paramNamer(QueryParamNamer.NAMED)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (age > :age)");
        }

        @Test
        void unnumbered_replacesPlaceholderWithQuestionMark() {
            var result = new DynamicQuery()
                    .selectClauses("age > :age")
                    .params(18)
                    .paramNamer(QueryParamNamer.UNNUMBERED)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (age > ?)");
        }

        @Test
        void multipleParams_numberedSequentially() {
            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age > :age", "city = :city")
                    .params("Alice", 18, "Berlin")
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (age > ?2) and (city = ?3)");
        }
    }

    // -------------------------------------------------------------------------
    // paramsFromDto
    // -------------------------------------------------------------------------

    @Nested
    class ParamsFromDto {

        @Test
        void allFieldsPresent_includesAllClauses() {
            var dto = new SearchParams("Alice", 18, "Berlin");

            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age = :age", "city = :city")
                    .paramsFromDto(dto)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (age = ?2) and (city = ?3)");
            assertThat(result.parameters()).containsExactly("Alice", 18, "Berlin");
        }

        @Test
        void nullFieldInDto_correspondingClauseExcluded() {
            var dto = new SearchParams("Alice", null, "Berlin");

            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age = :age", "city = :city")
                    .paramsFromDto(dto)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (city = ?2)");
            assertThat(result.parameters()).containsExactly("Alice", "Berlin");
        }

        @Test
        void allFieldsNull_producesEmptyQuery() {
            var dto = new SearchParams(null, null, null);

            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age = :age", "city = :city")
                    .paramsFromDto(dto)
                    .build();

            assertThat(result.query()).isBlank();
            assertThat(result.parameters()).isEmpty();
        }

        @Test
        void nameFilter_excludesFilteredFields() {
            var dto = new SearchParams("Alice", 18, "Berlin");

            var result = new DynamicQuery()
                    .selectClauses("name = :name", "age is not null and age > :age")
                    .paramsFromDto(dto, field -> !field.equals("age"))
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1)");
            assertThat(result.parameters()).containsExactly("Alice");
        }

        @Test
        void otherParams_appendedAfterDtoParams() {
            var dto = new NameOnly("Alice");

            var result = new DynamicQuery()
                    .selectClauses("name = :name", "id = :id")
                    .paramsFromDto(dto, 42L)
                    .withWhere()
                    .build();

            assertThat(result.query()).isEqualTo(" where (name = ?1) and (id = ?2)");
            assertThat(result.parameters()).containsExactly("Alice", 42L);
        }
    }

    // -------------------------------------------------------------------------
    // updateClauses
    // -------------------------------------------------------------------------

    @Nested
    class UpdateClauses {

        @Test
        void allParamsPresent_generatesSetClause() {
            var result = new DynamicQuery()
                    .updateClauses("name = :name", "age = :age")
                    .params("Alice", 25)
                    .build();

            assertThat(result.query()).isEqualTo("SET name = ?1, age = ?2");
            assertThat(result.parameters()).containsExactly("Alice", 25);
        }

        @Test
        void nullParam_excludedFromSetClause() {
            var result = new DynamicQuery()
                    .updateClauses("name = :name", "age = :age")
                    .params("Alice", null)
                    .build();

            assertThat(result.query()).isEqualTo("SET name = ?1");
            assertThat(result.parameters()).containsExactly("Alice");
        }

        @Test
        void withWhereClause_appendsWhereWithCorrectParamOffset() {
            var result = new DynamicQuery()
                    .updateClauses("name")
                    .where("id = :id")
                    .params("Alice", 1L)
                    .build();

            assertThat(result.query()).isEqualTo("SET name = ?1 WHERE id = ?2");
            assertThat(result.parameters()).containsExactly("Alice", 1L);
        }

        @Test
        void noParams_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new DynamicQuery()
                    .updateClauses("name = :name")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No parameters provided");
        }

        @Test
        void allParamsNullLeadingToEmptySet_throwsIllegalStateException() {
            assertThatThrownBy(() -> new DynamicQuery()
                    .updateClauses("name = :name")
                    .params((Object) null)
                    .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No fields to update");
        }

        @Test
        void updateWithParamsFromDto_mapsFieldsToSetClause() {
            var dto = new SearchParams("Alice", null, "Berlin");

            var result = new DynamicQuery()
                    .updateClauses("name = :name", "age = :age", "city = :city")
                    .paramsFromDto(dto)
                    .build();

            assertThat(result.query()).isEqualTo("SET name = ?1, city = ?2");
            assertThat(result.parameters()).containsExactly("Alice", "Berlin");
        }
    }
}

