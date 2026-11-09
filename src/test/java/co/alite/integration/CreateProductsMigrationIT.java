package co.alite.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema rules of the products table.
 *
 * Rules for every rejection test: every row is valid except for ONE thing, and
 * it always uses a real DET category (never a hardcoded id). Postgres checks
 * NOT NULL first, then CHECK constraints (alphabetical by name), then the
 * unique index, then foreign keys, and reports the first failure.
 */
class CreateProductsMigrationIT extends AbstractSchemaIT {

    private static final String INSERT =
            "insert into products (title, price, sku, category_id) values (?, ?, ?, ?)";

    /** The category every product here belongs to: the DET level (3). */
    private Long det;

    /** Runs after AbstractSchemaIT.cleanTables(), so the tables are empty here. */
    @BeforeEach
    void createCategoryTree() {
        Long top = insertCategory("TOP", 1, null);
        Long sub = insertCategory("SUB", 2, top);
        det = insertCategory("DET", 3, sub);
    }

    private void newProduct(String title, BigDecimal price, String sku) {
        jdbc.update(INSERT, title, price, sku, det);
    }

    private void softDelete(String title) {
        jdbc.update("update products set deleted_at = now() where title = ?", title);
    }

    private int countBySku(String sku) {
        return jdbc.queryForObject("select count(*) from products where sku = ?", Integer.class, sku);
    }

    // ---------- accepted ----------

    @Test
    void validProductIsAcceptedWithDefaults() {
        newProduct("Product 1", new BigDecimal("19.99"), "PRD-001");

        Boolean ok = jdbc.queryForObject(
                """
                select price = 19.99
                   and currency = 'EUR'
                   and description is null
                   and category_id = ?
                   and deleted_at is null
                   and public_id is not null
                   and created_at is not null
                   and updated_at is not null
                from products where sku = 'PRD-001'
                """,
                Boolean.class, det);

        assertThat(ok).isTrue();
    }

    @Test
    void zeroPriceIsAccepted() {
        newProduct("Free sample", BigDecimal.ZERO, "PRD-001");

        assertThat(countBySku("PRD-001")).isEqualTo(1);
    }

    @Test
    void currencyCanBeOverridden() {
        jdbc.update("insert into products (title, price, sku, category_id, currency) values (?, ?, ?, ?, ?)",
                "p", BigDecimal.ONE, "PRD-001", det, "USD");

        assertThat(jdbc.queryForObject("select currency from products where sku = 'PRD-001'", String.class))
                .isEqualTo("USD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "A_1", "A.1-2", "0123456789"})
    void validSkuFormatsAreAccepted(String sku) {
        newProduct("p", BigDecimal.ONE, sku);

        assertThat(countBySku(sku)).isEqualTo(1);
    }

    @Test
    void skuOf64CharactersIsAccepted() {
        String sku = "A".repeat(64);

        newProduct("p", BigDecimal.ONE, sku);

        assertThat(countBySku(sku)).isEqualTo(1);
    }

    @Test
    void skuCanBeReusedAfterSoftDelete() {
        newProduct("Old", BigDecimal.TEN, "PRD-001");
        softDelete("Old");

        newProduct("New", BigDecimal.TEN, "PRD-001");

        assertThat(countBySku("PRD-001")).isEqualTo(2);
    }

    @Test
    void severalSoftDeletedProductsMayShareASku() {
        newProduct("Old", BigDecimal.TEN, "PRD-001");
        softDelete("Old");
        newProduct("New", BigDecimal.TEN, "PRD-001");
        softDelete("New");

        assertThat(jdbc.queryForObject(
                "select count(*) from products where sku = 'PRD-001' and deleted_at is not null",
                Integer.class)).isEqualTo(2);
    }

    // ---------- title ----------

    @Test
    void nullTitleIsRejected() {
        assertRejected("null value in column \"title\"", INSERT, null, BigDecimal.ONE, "PRD-001", det);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void blankTitleIsRejected(String title) {
        assertRejected("chk_products_title_not_blank", INSERT, title, BigDecimal.ONE, "PRD-001", det);
    }

    // ---------- price ----------

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "-999999999.99"})
    void negativePriceIsRejected(String price) {
        assertRejected("chk_products_price_non_negative",
                INSERT, "x", new BigDecimal(price), "PRD-001", det);
    }

    @Test
    void nullPriceIsRejected() {
        assertRejected("null value in column \"price\"", INSERT, "x", null, "PRD-001", det);
    }

    /** NUMERIC(12,2) holds at most 10 digits before the decimal point. */
    @Test
    void priceTooLargeForTheColumnIsRejected() {
        assertRejected("numeric field overflow",
                INSERT, "x", new BigDecimal("10000000000.00"), "PRD-001", det);
    }

    // ---------- sku ----------

    @Test
    void nullSkuIsRejected() {
        assertRejected("null value in column \"sku\"", INSERT, "x", BigDecimal.ONE, null, det);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-A1", "a1", "A 1", "A%1", "att%2df"})
    void invalidSkuFormatIsRejected(String sku) {
        assertRejected("chk_products_sku_format", INSERT, "x", BigDecimal.ONE, sku, det);
    }

    @Test
    void skuLongerThan64IsRejected() {
        assertRejected("value too long for type character varying(64)",
                INSERT, "x", BigDecimal.ONE, "A".repeat(65), det);
    }

    @Test
    void duplicateLiveSkuIsRejected() {
        newProduct("Product 1", BigDecimal.TEN, "PRD-001");

        assertRejected("uq_products_sku_active", INSERT, "Product 2", BigDecimal.TEN, "PRD-001", det);
    }

    /** The subtle sibling: un-deleting is an UPDATE, and the partial index applies to it too. */
    @Test
    void restoringADeletedProductWhoseSkuWasReusedIsRejected() {
        newProduct("Old", BigDecimal.TEN, "PRD-001");
        softDelete("Old");
        newProduct("New", BigDecimal.TEN, "PRD-001");

        assertRejected("uq_products_sku_active",
                "update products set deleted_at = null where title = 'Old'");
    }

    // ---------- category ----------

    @Test
    void productMustReferenceAnExistingCategory() {
        assertRejected("fk_products_category", INSERT, "x", BigDecimal.ONE, "PRD-001", 999L);
    }

    @Test
    void nullCategoryIsRejected() {
        assertRejected("null value in column \"category_id\"", INSERT, "x", BigDecimal.ONE, "PRD-001", null);
    }

    @Test
    void categoryWithProductsCannotBeDeleted() {
        newProduct("Product 1", BigDecimal.TEN, "PRD-001");

        assertRejected("fk_products_category", "delete from categories where id = ?", det);
    }

    // ---------- currency ----------

    @Test
    void currencyLongerThanThreeCharactersIsRejected() {
        assertRejected("value too long for type character(3)",
                "insert into products (title, price, sku, category_id, currency) values ('x', 1, 'PRD-001', ?, 'EURO')",
                det);
    }
}