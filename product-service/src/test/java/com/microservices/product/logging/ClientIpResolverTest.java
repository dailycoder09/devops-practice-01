package com.microservices.product.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void withoutTheHeader_usesTheSocketAddress() {
        assertThat(ClientIpResolver.resolve(request(null))).isEqualTo("10.1.2.3");
    }

    @Test
    void forwardedFor_winsOverTheSocketAddress() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void severalAddresses_theFirstOneIsTheClient() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, 10.0.0.1, 10.0.0.2"))).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6_isAccepted() {
        assertThat(ClientIpResolver.resolve(request("2001:db8::ff00:42:8329"))).isEqualTo("2001:db8::ff00:42:8329");
    }

    @Test
    void aBadFirstElement_isSkipped_andTheFirstValidOneIsUsed() {
        assertThat(ClientIpResolver.resolve(request("unknown, 198.51.100.4"))).isEqualTo("198.51.100.4");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-ip",
            "1.2.3.4 evil",
            "1.2.3.4\r\nforged: yes",
            "1.2.3.4\nforged",
            "<script>",
            "",
            "   ",
            ",,,"
    })
    void badValues_areIgnored(String header) {
        assertThat(ClientIpResolver.resolve(request(header))).isEqualTo("10.1.2.3");
    }

    @Test
    void aVeryLongValue_isIgnored() {
        assertThat(ClientIpResolver.resolve(request("1".repeat(46)))).isEqualTo("10.1.2.3");
        assertThat(ClientIpResolver.resolve(request("1".repeat(5000)))).isEqualTo("10.1.2.3");
    }

    @Test
    void aValueOfExactly45Characters_isAccepted() {
        String ipv6Max = "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255".substring(0, 45);
        assertThat(ipv6Max).hasSize(45);
        assertThat(ClientIpResolver.resolve(request(ipv6Max))).isEqualTo(ipv6Max);
    }
}
