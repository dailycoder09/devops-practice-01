package com.droplink.web;

import com.droplink.model.DownloadEvent;
import com.droplink.model.Link;
import com.droplink.service.Activity;
import com.droplink.service.CreateLinkRequest;
import com.droplink.service.CreatedLink;
import com.droplink.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** For the person who makes the link: create it, say the upload is done, look at the activity, delete it. */
@RestController
@RequestMapping("/api/links")
public class ApiController {

    static final String ID = "{id:[0-9A-Za-z]{12}}";
    static final String MANAGE_HEADER = "X-Manage-Token";

    private final LinkService service;
    private final PublicUrls urls;

    public ApiController(LinkService service, PublicUrls urls) {
        this.service = service;
        this.urls = urls;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateLinkRequest request, HttpServletRequest http) {
        CreatedLink created = service.create(request);
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("method", created.upload().method());
        upload.put("url", created.upload().url().toString());
        upload.put("headers", created.upload().headers());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.link().id());
        body.put("manageToken", created.manageToken());
        body.put("shareUrl", urls.shareUrl(http, created.link().id()));
        body.put("expiresAt", created.link().expiresAt().toString());
        body.put("upload", upload);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/" + ID + "/complete")
    public Map<String, Object> complete(@PathVariable String id, @RequestHeader(value = MANAGE_HEADER, required = false) String token) {
        Link link = service.complete(id, token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", link.id());
        body.put("size", link.size());
        body.put("status", link.status().name());
        return body;
    }

    @GetMapping("/" + ID + "/activity")
    public Map<String, Object> activity(@PathVariable String id, @RequestHeader(value = MANAGE_HEADER, required = false) String token) {
        Activity activity = service.activity(id, token);
        Link link = activity.link();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", link.fileName());
        body.put("size", link.size());
        body.put("status", link.status().name());
        body.put("createdAt", link.createdAt().toString());
        body.put("expiresAt", link.expiresAt().toString());
        body.put("maxDownloads", link.maxDownloads());
        body.put("downloads", link.downloads());
        body.put("hasPassword", link.hasPassword());
        body.put("events", events(activity.events()));
        return body;
    }

    @DeleteMapping("/" + ID)
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestHeader(value = MANAGE_HEADER, required = false) String token) {
        service.delete(id, token);
        return ResponseEntity.noContent().build();
    }

    private static List<Map<String, Object>> events(List<DownloadEvent> events) {
        return events.stream().map(e -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", e.at().toString());
            item.put("ip", e.ip());
            item.put("userAgent", e.userAgent());
            return item;
        }).toList();
    }
}
