package com.microservices.user.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EVENTS_URL empty means off. When it is set it must be an address and EVENTS_SECRET must be set too. */
class EventsConfigTest {

    @Test
    void anEmptyUrl_meansNoEventsAreSent() {
        assertThat(EventsConfig.create("user-service", "", "")).isSameAs(EventPublisher.NONE);
        assertThat(EventsConfig.create("user-service", null, null)).isSameAs(EventPublisher.NONE);
        assertThat(EventsConfig.create("user-service", "   ", "x")).isSameAs(EventPublisher.NONE);
    }

    @Test
    void aGoodUrlAndSecret_giveARealPublisher() {
        EventPublisher publisher = EventsConfig.create("user-service", "https://abc.lambda-url.us-west-2.on.aws/", "a-secret");

        assertThat(publisher).isInstanceOf(HttpEventPublisher.class);
        ((HttpEventPublisher) publisher).close();
    }

    @Test
    void aUrlWithoutHttp_stopsTheServiceWithAClearMessage_withoutShowingIt() {
        assertThatThrownBy(() -> EventsConfig.create("user-service", "abc.lambda-url.example", "a-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVENTS_URL")
                .hasMessageContaining("https://")
                .hasMessageNotContaining("abc.lambda-url.example");
    }

    @Test
    void aUrlWithoutASecret_stopsTheServiceAndNamesTheMissingSetting() {
        assertThatThrownBy(() -> EventsConfig.create("user-service", "https://abc.lambda-url.example/", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVENTS_SECRET");
        assertThatThrownBy(() -> EventsConfig.create("user-service", "https://abc.lambda-url.example/", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVENTS_SECRET");
    }
}
