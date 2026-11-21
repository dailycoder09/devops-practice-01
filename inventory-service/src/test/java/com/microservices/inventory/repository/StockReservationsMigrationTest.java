package com.microservices.inventory.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway migration V3 (stock_reservations), run on its own in-memory H2 databases (no Spring context).
 */
class StockReservationsMigrationTest {

    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration-v3-test-" + DATABASE_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/common", "classpath:db/migration/h2")
                .target(target)
                .load();
    }

    private void migrateAll() {
        flyway("latest").migrate();
    }

    private void insert(long orderId, String sku, int quantity, String status) {
        jdbc.update("INSERT INTO stock_reservations (order_id, sku_code, quantity, status) VALUES (?, ?, ?, ?)",
                orderId, sku, quantity, status);
    }

    // ------------------------------------------------------------------ the migration itself

    @Test
    void theMigrationFileExists_withTheExpectedName() {
        assertThat(new ClassPathResource("db/migration/h2/V3__create_stock_reservations.sql").exists()).isTrue();
    }

    @Test
    void v3_runsOnTopOfAnExistingV2Database_andKeepsTheData() {
        flyway("2").migrate();
        int itemsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_items", Integer.class);
        int auditBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_audit_log", Integer.class);
        assertThat(itemsBefore).isEqualTo(5010);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'STOCK_RESERVATIONS'", Integer.class))
                .isZero();

        migrateAll();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_items", Integer.class)).isEqualTo(itemsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_audit_log", Integer.class)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class)).isZero();
    }

    @Test
    void flywayHistory_showsThreeSuccessfulMigrations_theLastOneIsV3() {
        migrateAll();

        // Versioned migrations only (Flyway also stores one row for creating its own history table)
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT \"version\", \"description\", \"success\" FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" IS NOT NULL ORDER BY \"installed_rank\"");

        assertThat(history).hasSize(3);
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(history.get(2)).containsEntry("version", "3").containsEntry("description", "create stock reservations");
    }

    @Test
    void v3_canBeRunAgain_withoutChangingAnything() {
        migrateAll();
        insert(1, "ELEC-00001", 2, "RESERVED");

        migrateAll();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the table

    @Test
    void table_hasTheAgreedColumnsAndTypes() {
        migrateAll();

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE "
                        + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'STOCK_RESERVATIONS' ORDER BY ORDINAL_POSITION");

        assertThat(columns).extracting(row -> row.get("COLUMN_NAME")).containsExactly(
                "ID", "ORDER_ID", "SKU_CODE", "QUANTITY", "STATUS", "CREATED_AT", "UPDATED_AT");
        assertThat(columns).extracting(row -> row.get("DATA_TYPE")).containsExactly(
                "BIGINT", "BIGINT", "CHARACTER VARYING", "INTEGER", "CHARACTER VARYING",
                "TIMESTAMP", "TIMESTAMP");
        assertThat(columns.get(2)).containsEntry("CHARACTER_MAXIMUM_LENGTH", 50L);
        assertThat(columns.get(4)).containsEntry("CHARACTER_MAXIMUM_LENGTH", 20L);
        assertThat(columns).extracting(row -> row.get("IS_NULLABLE")).containsOnly("NO");
    }

    @Test
    void idIsGeneratedByTheDatabase_andTimestampsHaveDefaults() {
        migrateAll();

        insert(1, "SKU-A", 2, "RESERVED");
        insert(2, "SKU-A", 2, "RESERVED");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM stock_reservations ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("ID")).isNotEqualTo(rows.get(1).get("ID"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("CREATED_AT")).isNotNull();
            assertThat(row.get("UPDATED_AT")).isNotNull();
        });
    }

    @Test
    void theSamePairOfOrderAndSku_cannotBeStoredTwice() {
        migrateAll();
        insert(1, "SKU-A", 2, "RESERVED");

        assertThatThrownBy(() -> insert(1, "SKU-A", 5, "RESERVED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(1, "SKU-A", 2, "RELEASED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameOrderWithAnotherSku_andTheSameSkuForAnotherOrder_areAllowed() {
        migrateAll();

        insert(1, "SKU-A", 2, "RESERVED");
        insert(1, "SKU-B", 2, "RESERVED");
        insert(2, "SKU-A", 2, "RESERVED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Integer.class)).isEqualTo(3);
    }

    @Test
    void status_canOnlyBeReservedOrReleased() {
        migrateAll();

        insert(1, "SKU-A", 2, "RESERVED");
        insert(2, "SKU-A", 2, "RELEASED");

        assertThatThrownBy(() -> insert(3, "SKU-A", 2, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(4, "SKU-A", 2, "reserved"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(5, "SKU-A", 2, ""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_reservations SET status = 'GONE' WHERE order_id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyColumn_exceptTheGeneratedOnes_isRequired() {
        migrateAll();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_reservations (sku_code, quantity, status) VALUES ('SKU-A', 1, 'RESERVED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_reservations (order_id, quantity, status) VALUES (1, 1, 'RESERVED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_reservations (order_id, sku_code, status) VALUES (1, 'SKU-A', 'RESERVED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_reservations (order_id, sku_code, quantity) VALUES (1, 'SKU-A', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void quantity_mustBePositive() {
        migrateAll();

        assertThatThrownBy(() -> insert(1, "SKU-A", 0, "RESERVED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(1, "SKU-A", -3, "RESERVED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void skuCode_isLimitedTo50Characters() {
        migrateAll();

        insert(1, "S".repeat(50), 1, "RESERVED");
        assertThatThrownBy(() -> insert(2, "S".repeat(51), 1, "RESERVED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stockLevelsStillCannotGoNegative_theV1ConstraintIsUntouched() {
        migrateAll();

        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_items SET quantity_on_hand = -1 WHERE sku_code = 'ELEC-00001'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
