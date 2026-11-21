package com.droplink.store.local;

import com.droplink.config.DropLinkProperties;
import com.droplink.config.SecretKeyHolder;
import com.droplink.store.Notifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Local mode (the default): files in a folder, links in memory, notifications in the log. No AWS needed. */
@Configuration
@ConditionalOnProperty(name = "droplink.mode", havingValue = "local", matchIfMissing = true)
public class LocalConfig {

    @Bean
    public LocalUrlSigner localUrlSigner(SecretKeyHolder keys) {
        return new LocalUrlSigner(keys);
    }

    @Bean
    public InMemoryLinkRepository linkRepository() {
        return new InMemoryLinkRepository();
    }

    @Bean
    public LocalFileStore fileStore(DropLinkProperties properties, LocalUrlSigner signer, Clock clock) {
        return new LocalFileStore(properties, signer, clock);
    }

    @Bean
    public Notifier notifier() {
        return new LoggingNotifier();
    }
}
