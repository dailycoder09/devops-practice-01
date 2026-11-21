package com.droplink.web;

import com.droplink.service.LinkService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.LinkedHashMap;
import java.util.Map;

/** The two pages that do not belong to one visitor: the start page and the manage page (its key stays after the # and never reaches the server). */
@Controller
public class PageController {

    private final LinkService service;

    public PageController(LinkService service) {
        this.service = service;
    }

    @GetMapping("/")
    public String home(Model model) {
        Map<Integer, String> expiries = new LinkedHashMap<>();
        for (int hours : service.settings().getExpiryHours()) {
            expiries.put(hours, label(hours));
        }
        model.addAttribute("expiries", expiries);
        model.addAttribute("defaultExpiry", service.settings().getExpiryHours().contains(24) ? 24 : service.settings().getExpiryHours().get(0));
        model.addAttribute("maxMb", service.settings().getMaxUploadMb());
        return "index";
    }

    @GetMapping("/m/" + ApiController.ID)
    public String manage(@PathVariable String id, Model model) {
        model.addAttribute("id", id);
        return "manage";
    }

    static String label(int hours) {
        if (hours % 24 == 0) {
            int days = hours / 24;
            return days == 1 ? "1 day" : days + " days";
        }
        return hours == 1 ? "1 hour" : hours + " hours";
    }
}
