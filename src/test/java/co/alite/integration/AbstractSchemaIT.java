package co.alite.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base class for tests that talk to the real, migrated schema.
 * One Postgres container is started once and shared by every subclass
 * (the "singleton container" pattern). Testcontainers' own cleanup process
 * removes it when the JVM ends. Using @Container here would stop and restart
 * the container between test classes, while Spring would still hold a cached
 * connection to the old port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractSchemaIT {

    // Keep this tag identical to docker-compose.yaml.
    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4");

    static {
        db.start();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Every test starts from empty tables with ids restarting at 1.
     * (@Transactional rollback is not used: Postgres aborts a transaction
     * after its first error, which is exactly what these tests provoke.)
     */
    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE products, categories RESTART IDENTITY CASCADE");
    }

    /**
     * Inserts a category and returns the id the database generated.
     */
    protected Long insertCategory(String title, int level, Long parentId) {
        return jdbc.queryForObject(
                "insert into categories (title, level, parent_id) values (?, ?, ?) returning id",
                Long.class, title, level, parentId);
    }

    /**
     * Inserts a product and returns the id the database generated.
     */
    protected Long insertProduct(String title, BigDecimal price, String sku, Long categoryId, String currency) {
        return jdbc.queryForObject(
                "insert into products (title, price, sku, category_id, currency) values (?, ?, ?, ?, ?) returning id",
                Long.class, title, price, sku, categoryId, currency);
    }


    /**
     * Asserts the statement is rejected by the database AND that the error
     * mentions the expected constraint (or message part). Checking the name
     * proves the intended rule fired, not some other one.
     */
    protected void assertRejected(String expectedMessagePart, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(expectedMessagePart);
    }

  protected Long createDetailCategory(){
        Long top = insertCategory("Top cat", 1, null);
        Long sub = insertCategory("Sub cat", 2, top);
        return insertCategory("Det cat", 3, sub);
    }
}