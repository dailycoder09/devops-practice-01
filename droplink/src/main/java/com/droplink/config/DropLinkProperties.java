package com.droplink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All settings, from the environment (DROPLINK_MODE, DROPLINK_BUCKET, ...). Nothing is read from files that are
 * pushed to GitHub, and no secret has a default.
 */
@ConfigurationProperties(prefix = "droplink")
public class DropLinkProperties {

    /** "local" (files in a folder, links in memory) or "aws" (S3, DynamoDB, SNS). */
    private String mode = "local";
    /** The public address of this app, for the links and the QR code (https://droplink.example.com). Empty = taken from the request. */
    private String publicUrl = "";
    /** The largest file in megabytes. */
    private int maxUploadMb = 100;
    /** The expiry choices in hours offered to the user. */
    private List<Integer> expiryHours = List.of(1, 24, 168);

    /** local mode: the folder for the uploaded files. */
    private String localDir = "./droplink-data";

    /** aws mode: the S3 bucket (private) and the key prefix inside it. */
    private String bucket = "";
    private String keyPrefix = "files/";
    /** aws mode: the DynamoDB table (partition key pk, sort key sk, time-to-live attribute ttl). */
    private String table = "";
    /** aws mode: an SNS topic that gets "downloaded" messages. Empty = none. */
    private String topicArn = "";
    /** aws mode: the region. Empty = the default of the machine (AWS_REGION or the instance). */
    private String region = "";
    /** aws mode: an endpoint of an emulator (DynamoDB Local, MinIO) for tests. Empty = the real AWS. */
    private String endpoint = "";
    /** aws mode: path-style S3 addresses (needed by MinIO). */
    private boolean pathStyle = false;

    /**
     * The secret that signs the cookie of a password-protected link. All instances must use the same value.
     * In local mode an empty value gives a random one at start.
     */
    private String secret = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    public int getMaxUploadMb() { return maxUploadMb; }
    public void setMaxUploadMb(int maxUploadMb) { this.maxUploadMb = maxUploadMb; }
    public List<Integer> getExpiryHours() { return expiryHours; }
    public void setExpiryHours(List<Integer> expiryHours) { this.expiryHours = expiryHours; }
    public String getLocalDir() { return localDir; }
    public void setLocalDir(String localDir) { this.localDir = localDir; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public String getTopicArn() { return topicArn; }
    public void setTopicArn(String topicArn) { this.topicArn = topicArn; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public boolean isAws() {
        return "aws".equalsIgnoreCase(mode);
    }

    public long maxUploadBytes() {
        return maxUploadMb * 1024L * 1024L;
    }
}
