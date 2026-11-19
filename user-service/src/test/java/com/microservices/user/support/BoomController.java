package com.microservices.user.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint that always fails with an unexpected exception, to check what the service logs
 * (and answers) for a 500. It is only present in tests that import it.
 */
@RestController
public class BoomController {

    @GetMapping("/test-only/boom")
    public String boom() {
        throw new IllegalStateException("boom: something unexpected broke");
    }
}
