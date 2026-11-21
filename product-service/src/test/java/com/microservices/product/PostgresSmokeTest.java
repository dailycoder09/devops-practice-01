package com.microservices.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservices.product.repository.ProductRepository;
import com.microservices.product.security.JwtTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test against a real PostgreSQL (the "postgres" profile, as on Amazon RDS). The normal build skips it.
 *
 * <pre>
 * TEST_PG_URL=jdbc:postgresql://localhost:5432/microshop TEST_PG_USER=... TEST_PG_PASSWORD=... \
 *   mvn test -Dtest=PostgresSmokeTest
 * </pre>
 *
 * The schema {@code product_svc_test} of that database is dropped before every run, so the test can be repeated.
 * It starts the whole application (Flyway V1-V5 on PostgreSQL, Hibernate ddl-auto=validate) and calls it over HTTP.
 * The expected numbers of the search test were taken from the same data on H2: they must be the same on both databases.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.jwt.secret=" + JwtTestSupport.TEST_SECRET,
        "server-info.cloud-metadata.enabled=false",
        // Nothing listens here, so this test can never call a real inventory-service.
        "product.inventory-service.url=http://localhost:1",
        // With an address set, V5 really rewrites the photo URLs (so its regular expressions run on PostgreSQL).
        "spring.flyway.placeholders.imageBaseUrl=https://img.test/"
})
@ActiveProfiles({"test", "postgres"})
class PostgresSmokeTest {

    private static final String SCHEMA = "product_svc_test";
    private static final int ALL_PRODUCTS = 5010;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private Environment environment;

    /** Plain JDBC, before the Spring context exists: forget everything an earlier run left behind. */
    @BeforeAll
    static void dropTheTestSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_PG_URL"), System.getenv("TEST_PG_USER"), System.getenv("TEST_PG_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> System.getenv("TEST_PG_URL"));
        registry.add("DB_USERNAME", () -> System.getenv("TEST_PG_USER"));
        registry.add("DB_PASSWORD", () -> System.getenv("TEST_PG_PASSWORD"));
        registry.add("DB_SCHEMA", () -> SCHEMA);
    }

    // ------------------------------------------------------------------ helpers

    /** The path must already be URL-encoded: it is used as it is. */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(uri(path), JsonNode.class);
        assertThat(response.getStatusCode()).as("GET " + path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> send(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN"));
        return rest.exchange(uri(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.get(field).asText()));
        return values;
    }

    // ------------------------------------------------------------------ start-up: Flyway and Hibernate

    @Test
    void allFlywayMigrationsSucceededInTheTestSchema() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM " + SCHEMA + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4", "5");
        assertThat(history).extracting(row -> row.get("success")).containsOnly(true);

        // everything is in the schema of this service, and the helper tables and view of V4/V5 are gone again
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, SCHEMA);
        assertThat(tables).containsExactlyInAnyOrder("flyway_schema_history", "products", "product_images",
                "product_highlights", "product_specifications");
    }

    @Test
    void theContextStartedOnPostgres_soHibernateValidatePassed() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @Test
    void v5RewroteThePhotoUrlsOfEveryProductTypeThatHasPhotos() throws Exception {
        // photo-folders.csv was built independently (Python): the folders the SQL derived must match it for all products
        Map<String, List<String>> urlsBySku = jdbc.queryForList(
                        "SELECT p.sku_code AS sku, i.url AS url FROM products p JOIN product_images i ON i.product_id = p.id "
                                + "ORDER BY p.sku_code, i.sort_order")
                .stream().collect(Collectors.groupingBy(r -> (String) r.get("sku"),
                        Collectors.mapping(r -> (String) r.get("url"), Collectors.toList())));
        int rows = 0;
        int withPhotos = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/photo-folders.csv"), StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                rows++;
                List<String> urls = urlsBySku.get(parts[0]);
                assertThat(urls).as(parts[0]).hasSize(4);
                if (Boolean.parseBoolean(parts[2])) {
                    withPhotos++;
                    for (int n = 1; n <= 4; n++) {
                        assertThat(urls.get(n - 1)).as(parts[0] + " image " + n)
                                .isEqualTo("https://img.test/products/" + parts[1] + "/" + n + ".jpg");
                    }
                } else {
                    assertThat(urls).as(parts[0]).allMatch(url -> url.startsWith("https://placehold.co/"));
                }
            }
        }
        assertThat(rows).isEqualTo(ALL_PRODUCTS);
        assertThat(withPhotos).isGreaterThan(4000);
    }

    // ------------------------------------------------------------------ reads over HTTP

    @Test
    void productCount_equalsTheNumberOfSeededProducts() {
        assertThat(get("/api/products?size=1").get("totalElements").asLong()).isEqualTo(ALL_PRODUCTS);
        assertThat(count("SELECT COUNT(*) FROM products")).isEqualTo(ALL_PRODUCTS);
    }

    /** sortBy is the field of the entity (and of the JSON), column the database column behind it. */
    @ParameterizedTest(name = "sortBy={0} {1}")
    @CsvSource({
            "id,asc,id", "id,desc,id",
            "name,asc,name", "name,desc,name",
            "price,asc,price", "price,desc,price",
            "category,asc,category", "category,desc,category",
            "skuCode,asc,sku_code", "skuCode,desc,sku_code",
            "brand,asc,brand", "brand,desc,brand",
            "mrp,asc,mrp", "mrp,desc,mrp",
            "createdAt,asc,created_at", "createdAt,desc,created_at"
    })
    void list_pagesAndSorts_likeTheDatabaseDoes(String sortBy, String direction, String column) {
        JsonNode page = get("/api/products?page=3&size=7&sortBy=" + sortBy + "&sortDir=" + direction);
        assertThat(page.get("totalElements").asLong()).isEqualTo(ALL_PRODUCTS);
        assertThat(page.get("number").asInt()).isEqualTo(3);
        assertThat(page.get("content")).hasSize(7);
        if (column.equals("created_at")) {
            return; // every seeded row was written in the same moment: here the request only has to work
        }
        // The values of the sort column on this page must be the ones the database itself puts at rows 22-28
        List<String> expected = jdbc.queryForList(
                "SELECT CAST(" + column + " AS VARCHAR) AS sort_value FROM products ORDER BY " + column + " " + direction
                        + " LIMIT 7 OFFSET 21",
                String.class);
        List<String> actual = new ArrayList<>();
        page.get("content").forEach(item -> actual.add(item.get(sortBy).asText()));
        if (sortBy.equals("price") || sortBy.equals("mrp")) {
            assertThat(actual.stream().map(BigDecimal::new).toList())
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyElementsOf(expected.stream().map(BigDecimal::new).toList());
        } else {
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void detailsById_andBySku_returnImagesHighlightsAndSpecifications() {
        JsonNode byId = get("/api/products/1/details");
        assertThat(get("/api/products/sku/APPLE-IP15P-128/details")).isEqualTo(byId);

        assertThat(byId.get("skuCode").asText()).isEqualTo("APPLE-IP15P-128");
        assertThat(byId.get("brand").asText()).isEqualTo("Apple");
        assertThat(byId.get("mrp").decimalValue()).isEqualByComparingTo("1099.99");
        assertThat(byId.get("discountPercent").asInt()).isEqualTo(9);
        assertThat(texts(byId.get("images"), "url")).hasSize(4);
        assertThat(byId.get("highlights")).hasSize(5);
        assertThat(texts(byId.get("specifications"), "group")).containsExactly("Display", "Performance", "Camera");
        assertThat(byId.get("specifications").get(0).get("items")).hasSize(3);

        // a generated product (id 11 is ELEC-00001): templates filled in, 4 images, 3-5 highlights, 6-8 specification rows
        JsonNode generated = get("/api/products/11/details");
        assertThat(generated.get("skuCode").asText()).isEqualTo("ELEC-00001");
        assertThat(generated.get("images")).hasSize(4);
        assertThat(generated.get("highlights").size()).isBetween(3, 5);
        int specRows = 0;
        for (JsonNode group : generated.get("specifications")) {
            specRows += group.get("items").size();
        }
        assertThat(specRows).isBetween(6, 8);
        assertThat(generated.toString()).doesNotContain("{brand}", "{type}", "{name}", "{sku}");

        assertThat(rest.getForEntity(uri("/api/products/999999/details"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void productById_bySku_andTheSkuCheck_work() {
        assertThat(get("/api/products/1").get("name").asText()).isEqualTo("iPhone 15 Pro");
        assertThat(get("/api/products/sku/APPLE-IP15P-128").get("id").asLong()).isEqualTo(1L);
        assertThat(get("/api/products/sku/APPLE-IP15P-128/exists").asBoolean()).isTrue();
        assertThat(get("/api/products/sku/NO-SUCH-SKU/exists").asBoolean()).isFalse();
    }

    @Test
    void categories_areTheTenSeededOnes_inTheSameOrderAsOnH2() {
        List<String> categories = new ArrayList<>();
        get("/api/products/categories").forEach(item -> categories.add(item.asText()));
        assertThat(categories).containsExactly("Automotive", "Beauty and Personal Care", "Books", "Clothing", "Electronics",
                "Furniture", "Groceries", "Home Appliances", "Sports and Outdoors", "Toys and Games");
        assertThat(get("/api/products/category/Electronics/count").asLong()).isEqualTo(509L);
        assertThat(get("/api/products/category/Home%20Appliances/count").asLong()).isEqualTo(501L);
        assertThat(get("/api/products/category/Books/count").asLong()).isEqualTo(500L);
        assertThat(get("/api/products/category/books/count").asLong()).isZero(); // the category match is case-sensitive
    }

    @Test
    void listByCategory_pagesAndSorts() {
        JsonNode page = get("/api/products/category/Books?page=1&size=5&sortBy=price&sortDir=desc");
        assertThat(page.get("totalElements").asLong()).isEqualTo(500L);
        assertThat(page.get("content")).hasSize(5);
        assertThat(texts(page.get("content"), "category")).containsOnly("Books");
        List<BigDecimal> prices = new ArrayList<>();
        page.get("content").forEach(item -> prices.add(item.get("price").decimalValue()));
        assertThat(prices).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    /**
     * Query of /api/products/search, the total number of hits and the first SKU (default order: by id). The numbers come
     * from the same data on H2. The rows without a search term are what the web UI sends for a category or a price filter.
     */
    @ParameterizedTest(name = "search?{0}")
    @CsvSource(delimiter = '|', value = {
            "searchTerm=iphone                                   | 1    | APPLE-IP15P-128",
            "searchTerm=IPHONE                                   | 1    | APPLE-IP15P-128",
            "searchTerm=monitor                                  | 28   | ELEC-00001",
            "searchTerm=MoNiToR&category=Electronics             | 28   | ELEC-00001",
            "searchTerm=monitor&category=Books                   | 0    | -",
            "searchTerm=item%20%2312                             | 110  | ELEC-00012",
            "searchTerm=premium%20edition                        | 495  | ELEC-00022",
            "searchTerm=%25                                      | 5010 | APPLE-IP15P-128",
            "searchTerm=_                                        | 5010 | APPLE-IP15P-128",
            "searchTerm=100%25                                   | 29   | SONY-WH1000XM5",
            "searchTerm=no-such-product-xyz                      | 0    | -",
            "searchTerm=                                         | 5010 | APPLE-IP15P-128",
            "category=Electronics                                | 509  | APPLE-IP15P-128",
            "category=electronics                                | 0    | -",
            "category=Home%20Appliances                          | 501  | DYSON-V15-DETECT",
            "category=Books                                      | 500  | BOOK-00001",
            "minPrice=2000                                       | 267  | APPLE-MBP16-M3",
            "maxPrice=10                                         | 151  | CLTH-00052",
            "minPrice=100&maxPrice=100.5                         | 6    | HOME-00074",
            "minPrice=0                                          | 5010 | APPLE-IP15P-128",
            "searchTerm=pro&minPrice=500&maxPrice=1500&category=Electronics | 12 | APPLE-IP15P-128"
    })
    void search_andFilter_giveTheSameResultsAsOnH2(String query, long total, String firstSku) {
        JsonNode page = get("/api/products/search?" + query);
        assertThat(page.get("totalElements").asLong()).isEqualTo(total);
        if (total == 0) {
            assertThat(page.get("content")).isEmpty();
        } else {
            assertThat(page.get("content").get(0).get("skuCode").asText()).isEqualTo(firstSku);
        }
    }

    @ParameterizedTest(name = "category filter sorted by {0} {1}")
    @CsvSource({"price,asc", "price,desc", "id,desc"})
    void search_withoutATerm_andSorted_likeTheWebUiAsksForIt(String sortBy, String direction) {
        JsonNode page = get("/api/products/search?category=Electronics&page=0&size=24&sortBy=" + sortBy + "&sortDir=" + direction);
        assertThat(page.get("totalElements").asLong()).isEqualTo(509L);
        assertThat(page.get("content")).hasSize(24);
        List<BigDecimal> values = new ArrayList<>();
        page.get("content").forEach(item -> values.add(item.get(sortBy).decimalValue()));
        assertThat(values).isSortedAccordingTo(direction.equals("asc") ? BigDecimal::compareTo : (a, b) -> b.compareTo(a));
    }

    @Test
    void everyRepositoryQueryRunsOnPostgres() {
        PageRequest page = PageRequest.of(0, 5);
        // searchProducts and findByCategoryAndPriceRange with every mix of given and missing parameters.
        // A missing (null) parameter is the risky part on PostgreSQL: the null has to get a type.
        for (String term : new String[]{null, "pro"}) {
            for (String category : new String[]{null, "Electronics"}) {
                for (BigDecimal min : new BigDecimal[]{null, new BigDecimal("100")}) {
                    for (BigDecimal max : new BigDecimal[]{null, new BigDecimal("2000")}) {
                        assertThat(productRepository.searchProducts(term, category, min, max, page).getContent())
                                .as("searchProducts(%s, %s, %s, %s)", term, category, min, max).isNotEmpty();
                        assertThat(productRepository.findByCategoryAndPriceRange(category, min, max, page).getContent())
                                .as("findByCategoryAndPriceRange(%s, %s, %s)", category, min, max).isNotEmpty();
                    }
                }
            }
        }
        assertThat(productRepository.findByNameContainingIgnoreCase("IPHONE", page).getTotalElements()).isEqualTo(1L);
        assertThat(productRepository.findByPriceBetween(new BigDecimal("100"), new BigDecimal("100.5"), page).getTotalElements())
                .isEqualTo(6L);
        assertThat(productRepository.findByCategory("Books", page).getTotalElements()).isEqualTo(500L);
        assertThat(productRepository.findByCategory("Books")).hasSize(500);
        assertThat(productRepository.countByCategory("Books")).isEqualTo(500L);
        assertThat(productRepository.existsBySkuCode("APPLE-IP15P-128")).isTrue();
        assertThat(productRepository.findBySkuCode("APPLE-IP15P-128")).isPresent();
        assertThat(productRepository.findAllDistinctCategories()).hasSize(10);
    }

    // ------------------------------------------------------------------ writes over HTTP (ADMIN token)

    @Test
    void createUpdateReplaceImagesAndDelete_work_andLeaveTheSeedAsItWas() {
        String body = "{\"name\":\"Smoke Test Phone\",\"description\":\"PostgreSQL smoke test\",\"price\":10.50,"
                + "\"category\":\"Electronics\",\"skuCode\":\"PG-SMOKE-1\",\"brand\":\"Smoke\",\"mrp\":12.00,"
                + "\"highlights\":[\"One\",\"Two\"],"
                + "\"specifications\":[{\"group\":\"General\",\"key\":\"Colour\",\"value\":\"Black\"}]}";
        ResponseEntity<JsonNode> created = send(HttpMethod.POST, "/api/products", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().get("id").asLong();
        assertThat(id).isGreaterThan(ALL_PRODUCTS); // the identity column carries on after the seed rows
        assertThat(created.getBody().get("createdAt").asText()).isNotBlank();

        assertThat(send(HttpMethod.POST, "/api/products", body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // unique SKU

        JsonNode details = get("/api/products/" + id + "/details");
        assertThat(details.get("highlights")).hasSize(2);
        assertThat(details.get("specifications")).hasSize(1);

        ResponseEntity<JsonNode> replaced = send(HttpMethod.PUT, "/api/products/" + id + "/images",
                "{\"images\":[{\"url\":\"https://cdn.example.com/a.jpg\",\"alt\":\"A\"},{\"url\":\"https://cdn.example.com/b.jpg\",\"alt\":\"B\"}]}");
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody().get("images")).hasSize(2);
        assertThat(count("SELECT COUNT(*) FROM product_images WHERE product_id = ?", id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id))
                .isEqualTo("https://cdn.example.com/a.jpg");

        ResponseEntity<JsonNode> updated = send(HttpMethod.PUT, "/api/products/" + id,
                body.replace("Smoke Test Phone", "Smoke Test Phone 2").replace("\"price\":10.50", "\"price\":11.25"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("price").decimalValue()).isEqualByComparingTo("11.25");

        assertThat(send(HttpMethod.DELETE, "/api/products/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT COUNT(*) FROM products WHERE id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_images WHERE product_id = ?", id)).isZero(); // the cascade
        assertThat(count("SELECT COUNT(*) FROM product_highlights WHERE product_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_specifications WHERE product_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM products")).isEqualTo(ALL_PRODUCTS);
    }
}
