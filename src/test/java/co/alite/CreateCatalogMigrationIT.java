package co.alite;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreateCatalogMigrationIT {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4");

    @BeforeAll
    static void beforeAll() {
        db.start();
    }

    @AfterAll
    static void afterAll() {
        db.stop();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationCreatesTable() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'categories'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void createCategoryWithoutTitle() {
        String query1 = "insert into categories (title, level) values (' ', 1)";
        assertThatThrownBy(() -> jdbc.update(query1))
                .isInstanceOf(DataIntegrityViolationException.class);
        String query2 = "insert into categories (title, level) values (null, 1)";
        assertThatThrownBy(() -> jdbc.update(query2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createCategoryWithInvalidLevel() {
        String query = "insert into categories (title, level) values ('top', 7)";
        assertThatThrownBy(() -> jdbc.update(query)).isInstanceOf(DataIntegrityViolationException.class);
    }


    @Test
    void createCategoryWithInvalidParent() {
        String query = "insert into categories (title, level, parent_id) values ('TOP', 1, 1)";
        assertThatThrownBy(() -> jdbc.update(query)).isInstanceOf(DataIntegrityViolationException.class);
    }

}