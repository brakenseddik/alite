package co.alite.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;

/**
 * Rule for every rejection test: break exactly ONE rule, and do not depend
 * on generated ids. Postgres checks NOT NULL first, then CHECK constraints in
 * alphabetical order by name, then foreign keys, and reports the first failure.
 */
class CreateCategoriesMigrationIT extends AbstractSchemaIT {

    // ---------- CATEGORIES ---------- //


    // ---------- accepted ----------

    @Test
    void validThreeLevelTreeIsAccepted() {
        Long top = insertCategory("TOP", 1, null);
        Long sub = insertCategory("SUB", 2, top);
        Long det = insertCategory("DET", 3, sub);

        assertThat(jdbc.queryForObject("select count(*) from categories", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select parent_id from categories where id = ?", Long.class, det))
                .isEqualTo(sub);
    }

    @Test
    void defaultsAreFilledByTheDatabase() {
        Long first = insertCategory("A", 1, null);
        insertCategory("B", 1, null);

        Boolean defaultsOk = jdbc.queryForObject(
                """
                        select deleted_at is null
                           and created_at is not null
                           and updated_at is not null
                           and public_id is not null
                        from categories where id = ?
                        """,
                Boolean.class, first);

        assertThat(defaultsOk).isTrue();
        assertThat(jdbc.queryForObject("select count(distinct public_id) from categories", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void leafCategoryCanBeDeleted() {
        Long top = insertCategory("TOP", 1, null);

        jdbc.update("delete from categories where id = ?", top);

        assertThat(jdbc.queryForObject("select count(*) from categories", Integer.class)).isZero();
    }

    // ---------- title ----------

    @Test
    void nullTitleIsRejected() {
        assertRejected("null value in column \"title\"",
                "insert into categories (title, level) values (null, 1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void blankTitleIsRejected(String title) {
        assertRejected("chk_categories_title_not_blank",
                "insert into categories (title, level) values (?, 1)", title);
    }

    @Test
    void titleLongerThan255IsRejected() {
        assertRejected("value too long for type character varying(255)",
                "insert into categories (title, level) values (?, 1)", "x".repeat(256));
    }

    // ---------- level ----------

    /**
     * A parent id is given so the root rule passes; only the level range can fail.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 4, 7})
    void levelOutsideOneToThreeIsRejected(int level) {
        assertRejected("chk_categories_level_range",
                "insert into categories (title, level, parent_id) values ('x', ?, 999)", level);
    }

    @Test
    void levelOneWithAParentIsRejected() {
        Long top = insertCategory("TOP", 1, null);

        assertRejected("chk_categories_root",
                "insert into categories (title, level, parent_id) values ('B', 1, ?)", top);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void levelTwoOrThreeWithoutAParentIsRejected(int level) {
        assertRejected("chk_categories_root",
                "insert into categories (title, level, parent_id) values ('x', ?, null)", level);
    }

    // ---------- parent ----------

    /**
     * Explicit id (needs OVERRIDING SYSTEM VALUE) so the result never depends on the sequence.
     */
    @Test
    void categoryCannotBeItsOwnParent() {
        assertRejected("chk_categories_own_parent",
                """
                        insert into categories (id, title, level, parent_id)
                        overriding system value
                        values (500, 'SUB', 2, 500)
                        """);
    }

    @Test
    void parentMustExist() {
        assertRejected("fk_categories_parent",
                "insert into categories (title, level, parent_id) values ('SUB', 2, 999)");
    }

    @Test
    void categoryWithChildrenCannotBeDeleted() {
        Long top = insertCategory("TOP", 1, null);
        insertCategory("SUB", 2, top);

        assertRejected("fk_categories_parent",
                "delete from categories where id = ?", top);
    }

    // ---------- PRODUCTS ---------- //

    // ---------- Accepted ----------

    @Test
    void insertValidProductDetailsSucceed() {

        Long det = createDetailCategory();
        Long product = insertProduct("Product 1", BigDecimal.ZERO, "PRD-001", det, "USD");
        assertThat(product).isEqualTo(1);

    }

    // --------- Rejected ----------

    @Test
    void insertInvalidProductPriceFails() {
        double invalidPrice = -1;
        String sql = "insert into products (title, sku, category_id, price) values ('x', 'PRD-002', 1, ?)";
        assertRejected("chk_products_price_non_negative", sql, invalidPrice);
    }

    @Test
    void nonUniqueProductSkuGetRejected() {
        String duplicateSku = "PRD-001";

        Long det = createDetailCategory();
        insertProduct("Product 1", BigDecimal.ZERO, duplicateSku, det, "USD");

        String query = "insert into products (title, price, category_id, sku) values ('product 2', 10, 1, ?)";
        assertRejected("uq_products_sku_active", query, duplicateSku);
    }

    @Test
    void invalidSkuFormatGetRejected() {
        String invalidSku = "att%2df";
        String query = "insert into products (title, price, category_id, sku) values ('product', 10, 1, ?)";
        assertRejected("chk_products_sku_format", query, invalidSku);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void emptyProductTitleGetRejected(String title) {
        String query = "insert into products (title, price, category_id, sku) values (?, 10, 1, 'PRD-000')";
        assertRejected("chk_products_title_not_blank", query, title);
    }

}
