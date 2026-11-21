package com.droplink.store.aws;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.model.LinkStatus;
import com.droplink.store.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Links and their download events in ONE DynamoDB table (partition key pk, sort key sk, time-to-live attribute ttl).
 *
 * <pre>
 * link:  pk = LINK#id    sk = META
 * event: pk = LINK#id    sk = EVT#&lt;13 digit epoch millis&gt;#&lt;4 hex&gt;
 * </pre>
 *
 * The two steps that must be safe with several app instances ({@link #activate}, {@link #tryCountDownload}) are each
 * ONE conditional UpdateItem. The link id is the secret of the link, so it is never logged in full.
 */
public class DynamoLinkRepository implements LinkRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoLinkRepository.class);

    static final String PK = "pk";
    static final String SK = "sk";
    static final String LINK_PREFIX = "LINK#";
    static final String META = "META";
    static final String EVENT_PREFIX = "EVT#";
    /** The item lives one day after the link expires (the time-to-live of DynamoDB is not exact, and the page shows "expired"). */
    static final long TTL_EXTRA_SECONDS = 86_400;

    static final String CREATE_CONDITION = "attribute_not_exists(pk)";
    static final String ACTIVATE_UPDATE = "SET #status = :active, #size = :size, contentType = :ct";
    static final String ACTIVATE_CONDITION = "#status = :pending AND attribute_exists(pk)";
    static final String COUNT_UPDATE = "ADD downloads :one";
    static final String COUNT_CONDITION = "attribute_exists(pk) AND #status = :active AND expiresAtEpoch > :now"
            + " AND (attribute_not_exists(maxDownloads) OR downloads < maxDownloads)";
    static final String EVENTS_CONDITION = "pk = :pk AND begins_with(sk, :evt)";

    private static final int BATCH_SIZE = 25;
    private static final int BATCH_ATTEMPTS = 5;
    /** An id is 12 characters; anything much longer cannot be a link (and could break the DynamoDB key limit). */
    private static final int MAX_ID_LENGTH = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoLinkRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void create(Link link) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(toItem(link))
                    .conditionExpression(CREATE_CONDITION)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new IllegalStateException("A link with the same id already exists (" + shortId(link.id()) + "...)", e);
        }
    }

    @Override
    public Optional<Link> find(String id) {
        if (!plausible(id)) {
            return Optional.empty();
        }
        GetItemResponse response = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(metaKey(id))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(response.item()));
    }

    @Override
    public boolean activate(String id, long size, String contentType) {
        if (!plausible(id)) {
            return false;
        }
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(metaKey(id))
                    .updateExpression(ACTIVATE_UPDATE)
                    .conditionExpression(ACTIVATE_CONDITION)
                    // "status" and "size" are reserved words in DynamoDB expressions
                    .expressionAttributeNames(Map.of("#status", "status", "#size", "size"))
                    .expressionAttributeValues(Map.of(
                            ":active", s(LinkStatus.ACTIVE.name()),
                            ":pending", s(LinkStatus.PENDING.name()),
                            ":size", n(size),
                            ":ct", s(contentType == null ? "application/octet-stream" : contentType)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public Optional<Long> tryCountDownload(String id, Instant now) {
        if (!plausible(id)) {
            return Optional.empty();
        }
        try {
            // The expiry is compared in whole seconds. A link is refused at most one second early, never late.
            UpdateItemResponse response = dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(metaKey(id))
                    .updateExpression(COUNT_UPDATE)
                    .conditionExpression(COUNT_CONDITION)
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":one", n(1),
                            ":active", s(LinkStatus.ACTIVE.name()),
                            ":now", n(now.getEpochSecond())))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            return Optional.of(Long.parseLong(response.attributes().get("downloads").n()));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    @Override
    public void addEvent(String id, DownloadEvent event) {
        if (!plausible(id)) {
            return;
        }
        // ONE read of the link, only to copy its ttl so the event disappears together with the link.
        GetItemResponse meta = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(metaKey(id))
                .projectionExpression("#ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .consistentRead(true)
                .build());
        if (!meta.hasItem() || meta.item().get("ttl") == null) {
            log.debug("Event ignored: link {}... no longer exists", shortId(id));
            return;
        }
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(LINK_PREFIX + id));
        item.put(SK, s(eventSortKey(event.at())));
        item.put("at", s(event.at().toString()));
        if (event.ip() != null) {
            item.put("ip", s(event.ip()));
        }
        if (event.userAgent() != null && !event.userAgent().isEmpty()) {
            item.put("userAgent", s(event.userAgent()));
        }
        item.put("ttl", meta.item().get("ttl"));
        dynamo.putItem(PutItemRequest.builder().tableName(table).item(item).build());
    }

    @Override
    public List<DownloadEvent> events(String id, int limit) {
        if (!plausible(id) || limit <= 0) {
            return List.of();
        }
        QueryResponse response = dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression(EVENTS_CONDITION)
                .expressionAttributeValues(Map.of(":pk", s(LINK_PREFIX + id), ":evt", s(EVENT_PREFIX)))
                .scanIndexForward(false)
                .limit(limit)
                .consistentRead(true)
                .build());
        List<DownloadEvent> events = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            events.add(new DownloadEvent(
                    Instant.parse(item.get("at").s()),
                    item.containsKey("ip") ? item.get("ip").s() : null,
                    item.containsKey("userAgent") ? item.get("userAgent").s() : null));
        }
        return events;
    }

    @Override
    public boolean delete(String id) {
        if (!plausible(id)) {
            return false;
        }
        DeleteItemResponse deleted = dynamo.deleteItem(DeleteItemRequest.builder()
                .tableName(table)
                .key(metaKey(id))
                .returnValues(ReturnValue.ALL_OLD)
                .build());
        boolean existed = deleted.hasAttributes() && !deleted.attributes().isEmpty();
        deleteEvents(id);
        return existed;
    }

    // ---- events cleanup ----------------------------------------------------------------------------------------

    private void deleteEvents(String id) {
        Map<String, AttributeValue> startKey = null;
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        do {
            QueryResponse page = dynamo.query(QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression(EVENTS_CONDITION)
                    .expressionAttributeValues(Map.of(":pk", s(LINK_PREFIX + id), ":evt", s(EVENT_PREFIX)))
                    .projectionExpression("pk, sk")
                    .consistentRead(true)
                    .exclusiveStartKey(startKey)
                    .build());
            for (Map<String, AttributeValue> item : page.items()) {
                keys.add(Map.of(PK, item.get(PK), SK, item.get(SK)));
                if (keys.size() == BATCH_SIZE) {
                    deleteBatch(id, keys);
                    keys.clear();
                }
            }
            startKey = page.hasLastEvaluatedKey() ? page.lastEvaluatedKey() : null;
        } while (startKey != null && !startKey.isEmpty());
        if (!keys.isEmpty()) {
            deleteBatch(id, keys);
        }
    }

    private void deleteBatch(String id, List<Map<String, AttributeValue>> keys) {
        List<WriteRequest> writes = new ArrayList<>();
        for (Map<String, AttributeValue> key : keys) {
            writes.add(WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(key).build()).build());
        }
        Map<String, List<WriteRequest>> pending = Map.of(table, writes);
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS && !pending.isEmpty(); attempt++) {
            if (attempt > 1) {
                sleep(50L * attempt);
            }
            BatchWriteItemResponse response = dynamo.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pending).build());
            pending = response.hasUnprocessedItems() ? response.unprocessedItems() : Map.of();
            // an empty list for the table also means "nothing left"
            if (pending.values().stream().allMatch(List::isEmpty)) {
                pending = Map.of();
            }
        }
        if (!pending.isEmpty()) {
            // The events carry a ttl, so DynamoDB removes them anyway; the link itself is already gone.
            log.warn("Some events of link {}... were not deleted (they will expire by their ttl)", shortId(id));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- mapping -----------------------------------------------------------------------------------------------

    static Map<String, AttributeValue> toItem(Link link) {
        long expiresAtEpoch = link.expiresAt().getEpochSecond();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(LINK_PREFIX + link.id()));
        item.put(SK, s(META));
        item.put("id", s(link.id()));
        item.put("fileName", s(link.fileName()));
        item.put("contentType", s(link.contentType()));
        item.put("size", n(link.size()));
        item.put("key", s(link.key()));
        item.put("createdAt", s(link.createdAt().toString()));
        item.put("expiresAt", s(link.expiresAt().toString()));
        item.put("expiresAtEpoch", n(expiresAtEpoch));
        if (link.maxDownloads() != null) {
            item.put("maxDownloads", n(link.maxDownloads()));
        }
        item.put("downloads", n(link.downloads()));
        if (link.passwordHash() != null) {
            item.put("passwordHash", s(link.passwordHash()));
        }
        item.put("manageTokenHash", s(link.manageTokenHash()));
        item.put("status", s(link.status().name()));
        item.put("ttl", n(expiresAtEpoch + TTL_EXTRA_SECONDS));
        return item;
    }

    static Link fromItem(Map<String, AttributeValue> item) {
        return new Link(
                item.get("id").s(),
                item.get("fileName").s(),
                item.get("contentType").s(),
                Long.parseLong(item.get("size").n()),
                item.get("key").s(),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("expiresAt").s()),
                item.containsKey("maxDownloads") ? Integer.valueOf(item.get("maxDownloads").n()) : null,
                Long.parseLong(item.get("downloads").n()),
                item.containsKey("passwordHash") ? item.get("passwordHash").s() : null,
                item.get("manageTokenHash").s(),
                LinkStatus.valueOf(item.get("status").s()));
    }

    static String eventSortKey(Instant at) {
        return EVENT_PREFIX + String.format("%013d", at.toEpochMilli()) + "#" + randomHex4();
    }

    private static String randomHex4() {
        return String.format("%04x", RANDOM.nextInt(0x10000));
    }

    private static Map<String, AttributeValue> metaKey(String id) {
        return Map.of(PK, s(LINK_PREFIX + id), SK, s(META));
    }

    private static boolean plausible(String id) {
        return id != null && !id.isBlank() && id.length() <= MAX_ID_LENGTH;
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.substring(0, Math.min(4, id.length()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
