package com.droplink.web;

import com.droplink.model.Link;
import com.droplink.service.ApiException;
import com.droplink.service.ClientInfo;
import com.droplink.service.LinkService;
import com.droplink.service.PreviewPolicy;
import com.droplink.service.TextPreview;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** For the person who opens a link: the page, the password, the preview, the download and the QR code. */
@Controller
@RequestMapping("/d/" + ApiController.ID)
public class VisitorController {

    private final LinkService service;
    private final UnlockCookies cookies;
    private final PublicUrls urls;

    public VisitorController(LinkService service, UnlockCookies cookies, PublicUrls urls) {
        this.service = service;
        this.cookies = cookies;
        this.urls = urls;
    }

    @GetMapping
    public String page(@PathVariable String id, HttpServletRequest request, Model model) {
        Link link = service.requireVisible(id);
        boolean unlocked = !link.hasPassword() || cookies.isUnlocked(request, id);
        model.addAttribute("id", id);
        model.addAttribute("fileName", link.fileName());
        model.addAttribute("size", humanSize(link.size()));
        model.addAttribute("expiresAt", link.expiresAt().toString());
        model.addAttribute("hasPassword", link.hasPassword());
        model.addAttribute("unlocked", unlocked);
        model.addAttribute("downloadsLeft", link.maxDownloads() == null ? null : Math.max(0, link.maxDownloads() - link.downloads()));
        model.addAttribute("previewKind", service.previewOf(link).kind().name());
        return "link";
    }

    @PostMapping("/unlock")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unlock(@PathVariable String id, @RequestBody Map<String, String> body,
                                                      HttpServletRequest request, HttpServletResponse response) {
        if (!service.checkPassword(id, body.get("password"), request.getRemoteAddr())) {
            return ResponseEntity.status(401).body(Map.of("error", "Wrong password."));
        }
        cookies.add(request, response, id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/preview")
    @ResponseBody
    public Map<String, Object> preview(@PathVariable String id, HttpServletRequest request) {
        Link link = service.requireVisible(id);
        boolean unlocked = cookies.isUnlocked(request, id);
        PreviewPolicy.Kind kind = service.previewOf(link).kind();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind.name());
        switch (kind) {
            case NONE -> { }
            case TEXT -> {
                TextPreview text = service.textPreview(id, unlocked);
                body.put("text", text.text());
                body.put("truncated", text.truncated());
            }
            default -> body.put("url", service.previewUrl(id, unlocked).toString());
        }
        return body;
    }

    /** POST, not GET, so a link scanner or a browser pre-fetch cannot use up a download. */
    @PostMapping("/download")
    @ResponseBody
    public Map<String, Object> download(@PathVariable String id, HttpServletRequest request) {
        ClientInfo client = new ClientInfo(request.getRemoteAddr(), request.getHeader("User-Agent"));
        return Map.of("url", service.download(id, cookies.isUnlocked(request, id), client).toString());
    }

    @GetMapping("/qr")
    public ResponseEntity<byte[]> qr(@PathVariable String id, HttpServletRequest request) throws Exception {
        service.requireVisible(id);
        BitMatrix matrix = new QRCodeWriter().encode(urls.shareUrl(request, id), BarcodeFormat.QR_CODE, 320, 320,
                Map.of(EncodeHintType.MARGIN, 1));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "The QR code could not be made.");
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png.toByteArray());
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
