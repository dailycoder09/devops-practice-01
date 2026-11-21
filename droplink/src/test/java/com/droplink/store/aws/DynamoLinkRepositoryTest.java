package com.droplink.store.aws;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Offline: the exact requests sent to DynamoDB, with a mocked client. */
class DynamoLinkRepositoryTest {

    private static final Instant CREATED = Instant.parse("2025-03-01T10:15:30.123456789Z");
    private static final Instant EXPIRES = Instant.parse("2025-03-02T10:15:30Z");

    private DynamoDbClient dynamo;
    private DynamoLinkRepository repo;

    @BeforeEach
    void setUp() {
        dynamo = mock(DynamoDbClient.class);
        repo = new DynamoLinkRepository(dynamo, "the_table");
    }

    private static Link link(Integer maxDownloads, String passwordHash) {
        return new Link("aB3dE5gH7jK9", "my report \"v2\".pdf", "application/pdf", 12345L, "files/aB3dE5gH7jK9/my.pdf",
                CREATED, EXPIRES, maxDownloads, 0L, passwordHash, "f".repeat(64), LinkStatus.PENDING);
    }

    private static ConditionalCheckFailedException conditionFailed() {
        return ConditionalCheckFailedException.builder().message("The conditional request failed").build();
    }

    // ---- create ------------------------------------------------------------------------------------------------

    @Test
    void createPutsTheItemWithAttributeNotExists() {
        when(dynamo.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());
        repo.create(link(5, "$2a$10$hash"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        PutItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("the_table");
        assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(pk)");

        Map<String, AttributeValue> item = request.item();
        assertThat(item.get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(item.get("sk").s()).isEqualTo("META");
        assertThat(item.get("id").s()).isEqualTo("aB3dE5gH7jK9");
        assertThat(item.get("fileName").s()).isEqualTo("my report \"v2\".pdf");
        assertThat(item.get("contentType").s()).isEqualTo("application/pdf");
        assertThat(item.get("size").n()).isEqualTo("12345");
        assertThat(item.get("key").s()).isEqualTo("files/aB3dE5gH7jK9/my.pdf");
        assertThat(item.get("createdAt").s()).isEqualTo("2025-03-01T10:15:30.123456789Z");
        assertThat(item.get("expiresAt").s()).isEqualTo("2025-03-02T10:15:30Z");
        assertThat(item.get("expiresAtEpoch").n()).isEqualTo(String.valueOf(EXPIRES.getEpochSecond()));
        assertThat(item.get("maxDownloads").n()).isEqualTo("5");
        assertThat(item.get("downloads").n()).isEqualTo("0");
        assertThat(item.get("passwordHash").s()).isEqualTo("$2a$10$hash");
        assertThat(item.get("manageTokenHash").s()).isEqualTo("f".repeat(64));
        assertThat(item.get("status").s()).isEqualTo("PENDING");
        assertThat(item.get("ttl").n()).isEqualTo(String.valueOf(EXPIRES.getEpochSecond() + 86400));
        assertThat(item).hasSize(16);
    }

    @Test
    void createLeavesOutMaxDownloadsAndPasswordHashWhenNull() {
        repo.create(link(null, null));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item).doesNotContainKeys("maxDownloads", "passwordHash");
        assertThat(item).hasSize(14);
    }

    @Test
    void createOfAnExistingIdFailsWithoutShowingTheFullId() {
        when(dynamo.putItem(any(PutItemRequest.class))).thenThrow(conditionFailed());
        assertThatThrownBy(() -> repo.create(link(1, null)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("aB3dE5gH7jK9"));
    }

    // ---- find / round trip -------------------------------------------------------------------------------------

    @Test
    void roundTripOfEveryField() {
        for (Link original : List.of(link(5, "$2a$10$hash"), link(null, null), link(1, null), link(null, "$2a$x"))) {
            Link active = new Link(original.id(), original.fileName(), original.contentType(), original.size(), original.key(),
                    original.createdAt(), original.expiresAt(), original.maxDownloads(), 3L, original.passwordHash(),
                    original.manageTokenHash(), LinkStatus.ACTIVE);
            when(dynamo.getItem(any(GetItemRequest.class)))
                    .thenReturn(GetItemResponse.builder().item(DynamoLinkRepository.toItem(active)).build());

            assertThat(repo.find(active.id())).contains(active);
        }
    }

    @Test
    void roundTripThroughCreateAndFind() {
        Link original = link(7, "$2a$10$hash");
        repo.create(original);
        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(put.capture());
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(put.getValue().item()).build());

        assertThat(repo.find(original.id())).contains(original);
    }

    @Test
    void findUsesAConsistentReadOnTheMetaItem() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertThat(repo.find("aB3dE5gH7jK9")).isEmpty();

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("the_table");
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().key().get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(captor.getValue().key().get("sk").s()).isEqualTo("META");
    }

    @Test
    void findOfImplausibleIdsDoesNotCallDynamo() {
        assertThat(repo.find(null)).isEmpty();
        assertThat(repo.find("")).isEmpty();
        assertThat(repo.find("x".repeat(500))).isEmpty();
        verify(dynamo, never()).getItem(any(GetItemRequest.class));
    }

    // ---- activate ----------------------------------------------------------------------------------------------

    @Test
    void activateIsOneConditionalUpdate() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());

        assertThat(repo.activate("aB3dE5gH7jK9", 999L, "text/plain")).isTrue();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo, times(1)).updateItem(captor.capture());
        UpdateItemRequest r = captor.getValue();
        assertThat(r.tableName()).isEqualTo("the_table");
        assertThat(r.key().get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(r.key().get("sk").s()).isEqualTo("META");
        // "status" and "size" are reserved words, so they go through #names
        assertThat(r.updateExpression()).isEqualTo("SET #status = :active, #size = :size, contentType = :ct");
        assertThat(r.conditionExpression()).isEqualTo("#status = :pending AND attribute_exists(pk)");
        assertThat(r.expressionAttributeNames()).containsOnly(Map.entry("#status", "status"), Map.entry("#size", "size"));
        assertThat(r.expressionAttributeValues()).containsOnlyKeys(":active", ":pending", ":size", ":ct");
        assertThat(r.expressionAttributeValues().get(":active").s()).isEqualTo("ACTIVE");
        assertThat(r.expressionAttributeValues().get(":pending").s()).isEqualTo("PENDING");
        assertThat(r.expressionAttributeValues().get(":size").n()).isEqualTo("999");
        assertThat(r.expressionAttributeValues().get(":ct").s()).isEqualTo("text/plain");
        verify(dynamo, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void activateConditionFailureIsFalse() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(conditionFailed());
        assertThat(repo.activate("aB3dE5gH7jK9", 1L, "x/y")).isFalse();
    }

    @Test
    void activateOtherErrorsPropagate() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(DynamoDbException.builder().message("boom").build());
        assertThatThrownBy(() -> repo.activate("aB3dE5gH7jK9", 1L, "x/y")).isInstanceOf(DynamoDbException.class);
    }

    // ---- tryCountDownload --------------------------------------------------------------------------------------

    @Test
    void tryCountDownloadIsOneConditionalAddAndReturnsTheNewCount() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(Map.of("downloads", AttributeValue.builder().n("4").build())).build());
        Instant now = Instant.parse("2025-03-01T12:00:00.900Z");

        assertThat(repo.tryCountDownload("aB3dE5gH7jK9", now)).contains(4L);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo, times(1)).updateItem(captor.capture());
        UpdateItemRequest r = captor.getValue();
        assertThat(r.tableName()).isEqualTo("the_table");
        assertThat(r.key().get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(r.key().get("sk").s()).isEqualTo("META");
        assertThat(r.updateExpression()).isEqualTo("ADD downloads :one");
        assertThat(r.conditionExpression()).isEqualTo("attribute_exists(pk) AND #status = :active AND expiresAtEpoch > :now"
                + " AND (attribute_not_exists(maxDownloads) OR downloads < maxDownloads)");
        assertThat(r.expressionAttributeNames()).containsOnly(Map.entry("#status", "status"));
        assertThat(r.expressionAttributeValues()).containsOnlyKeys(":one", ":active", ":now");
        assertThat(r.expressionAttributeValues().get(":one").n()).isEqualTo("1");
        assertThat(r.expressionAttributeValues().get(":active").s()).isEqualTo("ACTIVE");
        assertThat(r.expressionAttributeValues().get(":now").n()).isEqualTo(String.valueOf(now.getEpochSecond()));
        assertThat(r.returnValues()).isEqualTo(ReturnValue.ALL_NEW);
        verify(dynamo, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void tryCountDownloadConditionFailureIsEmpty() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(conditionFailed());
        assertThat(repo.tryCountDownload("aB3dE5gH7jK9", Instant.now())).isEqualTo(Optional.empty());
    }

    @Test
    void tryCountDownloadOtherErrorsPropagate() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(DynamoDbException.builder().message("boom").build());
        assertThatThrownBy(() -> repo.tryCountDownload("aB3dE5gH7jK9", Instant.now())).isInstanceOf(DynamoDbException.class);
    }

    // ---- events ------------------------------------------------------------------------------------------------

    @Test
    void addEventCopiesTheTtlOfTheLinkWithOneGetItem() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("ttl", AttributeValue.builder().n("1740996930").build())).build());
        Instant at = Instant.parse("2025-03-01T12:00:00.045Z");

        repo.addEvent("aB3dE5gH7jK9", new DownloadEvent(at, "203.0.113.x", "Firefox"));

        verify(dynamo, times(1)).getItem(any(GetItemRequest.class));
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(captor.getValue().tableName()).isEqualTo("the_table");
        assertThat(item.get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(item.get("sk").s()).matches("EVT#" + String.format("%013d", at.toEpochMilli()) + "#[0-9a-f]{4}");
        assertThat(item.get("at").s()).isEqualTo("2025-03-01T12:00:00.045Z");
        assertThat(item.get("ip").s()).isEqualTo("203.0.113.x");
        assertThat(item.get("userAgent").s()).isEqualTo("Firefox");
        assertThat(item.get("ttl").n()).isEqualTo("1740996930");
    }

    @Test
    void eventSortKeysAreZeroPaddedSoTheyOrderByTime() {
        String early = DynamoLinkRepository.eventSortKey(Instant.ofEpochMilli(999));
        String late = DynamoLinkRepository.eventSortKey(Instant.ofEpochMilli(1_000_000_000_000L));
        assertThat(early).startsWith("EVT#0000000000999#");
        assertThat(late).startsWith("EVT#1000000000000#");
        assertThat(early.compareTo(late)).isNegative();
    }

    @Test
    void addEventIsIgnoredWhenTheLinkIsGone() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        repo.addEvent("aB3dE5gH7jK9", new DownloadEvent(Instant.now(), "1.2.3.x", "UA"));
        verify(dynamo, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void eventsQueryNewestFirstWithLimitAndMapping() {
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(
                Map.of("at", s("2025-03-01T12:00:02Z"), "ip", s("1.2.3.x"), "userAgent", s("Chrome")),
                Map.of("at", s("2025-03-01T12:00:01Z")))).build());

        List<DownloadEvent> events = repo.events("aB3dE5gH7jK9", 20);

        assertThat(events).containsExactly(
                new DownloadEvent(Instant.parse("2025-03-01T12:00:02Z"), "1.2.3.x", "Chrome"),
                new DownloadEvent(Instant.parse("2025-03-01T12:00:01Z"), null, null));
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        QueryRequest r = captor.getValue();
        assertThat(r.tableName()).isEqualTo("the_table");
        assertThat(r.keyConditionExpression()).isEqualTo("pk = :pk AND begins_with(sk, :evt)");
        assertThat(r.expressionAttributeValues().get(":pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(r.expressionAttributeValues().get(":evt").s()).isEqualTo("EVT#");
        assertThat(r.scanIndexForward()).isFalse();
        assertThat(r.limit()).isEqualTo(20);
    }

    @Test
    void eventsWithZeroLimitDoNotCallDynamo() {
        assertThat(repo.events("aB3dE5gH7jK9", 0)).isEmpty();
        verify(dynamo, never()).query(any(QueryRequest.class));
    }

    // ---- delete ------------------------------------------------------------------------------------------------

    @Test
    void deleteReportsWhetherTheLinkExistedAndRemovesEventsInBatchesOf25() {
        when(dynamo.deleteItem(any(DeleteItemRequest.class))).thenReturn(DeleteItemResponse.builder()
                .attributes(Map.of("pk", s("LINK#aB3dE5gH7jK9"))).build());
        List<Map<String, AttributeValue>> first = eventKeys(0, 20);
        List<Map<String, AttributeValue>> second = eventKeys(20, 30);   // 50 events in two pages, last page continues
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(first).lastEvaluatedKey(first.get(first.size() - 1)).build(),
                QueryResponse.builder().items(second).build());
        when(dynamo.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(BatchWriteItemResponse.builder().build());

        assertThat(repo.delete("aB3dE5gH7jK9")).isTrue();

        ArgumentCaptor<DeleteItemRequest> del = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(del.capture());
        assertThat(del.getValue().returnValues()).isEqualTo(ReturnValue.ALL_OLD);
        assertThat(del.getValue().key().get("pk").s()).isEqualTo("LINK#aB3dE5gH7jK9");
        assertThat(del.getValue().key().get("sk").s()).isEqualTo("META");

        ArgumentCaptor<BatchWriteItemRequest> batch = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamo, times(2)).batchWriteItem(batch.capture());
        assertThat(batch.getAllValues().get(0).requestItems().get("the_table")).hasSize(25);
        assertThat(batch.getAllValues().get(1).requestItems().get("the_table")).hasSize(25);
        List<String> deleted = new ArrayList<>();
        batch.getAllValues().forEach(b -> b.requestItems().get("the_table")
                .forEach(w -> deleted.add(w.deleteRequest().key().get("sk").s())));
        assertThat(deleted).doesNotHaveDuplicates().hasSize(50);
    }

    @Test
    void deleteOfAMissingLinkIsFalse() {
        when(dynamo.deleteItem(any(DeleteItemRequest.class))).thenReturn(DeleteItemResponse.builder().build());
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());
        assertThat(repo.delete("aB3dE5gH7jK9")).isFalse();
        verify(dynamo, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void deleteRetriesUnprocessedItems() {
        when(dynamo.deleteItem(any(DeleteItemRequest.class))).thenReturn(DeleteItemResponse.builder()
                .attributes(Map.of("pk", s("LINK#aB3dE5gH7jK9"))).build());
        List<Map<String, AttributeValue>> keys = eventKeys(0, 3);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(keys).build());
        WriteRequest left = WriteRequest.builder().deleteRequest(d -> d.key(keys.get(2))).build();
        when(dynamo.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(
                BatchWriteItemResponse.builder().unprocessedItems(Map.of("the_table", List.of(left))).build(),
                BatchWriteItemResponse.builder().build());

        assertThat(repo.delete("aB3dE5gH7jK9")).isTrue();

        ArgumentCaptor<BatchWriteItemRequest> batch = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamo, times(2)).batchWriteItem(batch.capture());
        assertThat(batch.getAllValues().get(0).requestItems().get("the_table")).hasSize(3);
        assertThat(batch.getAllValues().get(1).requestItems().get("the_table")).containsExactly(left);
    }

    @Test
    void deleteGivesUpAfterAFewRetriesWithoutThrowing() {
        when(dynamo.deleteItem(any(DeleteItemRequest.class))).thenReturn(DeleteItemResponse.builder()
                .attributes(Map.of("pk", s("LINK#aB3dE5gH7jK9"))).build());
        List<Map<String, AttributeValue>> keys = eventKeys(0, 1);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(keys).build());
        WriteRequest left = WriteRequest.builder().deleteRequest(d -> d.key(keys.get(0))).build();
        when(dynamo.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(
                BatchWriteItemResponse.builder().unprocessedItems(Map.of("the_table", List.of(left))).build());

        assertThat(repo.delete("aB3dE5gH7jK9")).isTrue();
        verify(dynamo, times(5)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static List<Map<String, AttributeValue>> eventKeys(int from, int count) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            keys.add(Map.of("pk", s("LINK#aB3dE5gH7jK9"), "sk", s(String.format("EVT#%013d#abcd", i))));
        }
        return keys;
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }
}
