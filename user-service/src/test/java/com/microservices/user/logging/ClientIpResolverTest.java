package com.microservices.user.logging;

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
    void withoutTheHeader_theSocketAddressIsUsed() {
        assertThat(ClientIpResolver.resolve(request(null))).isEqualTo("10.1.2.3");
    }

    @Test
    void forwardedFor_winsOverTheSocketAddress() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void forwardedFor_withSeveralAddresses_usesTheFirstOne() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.9, 198.51.100.4, 10.0.0.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6_isAccepted() {
        assertThat(ClientIpResolver.resolve(request("2001:db8::8a2e:370:7334"))).isEqualTo("2001:db8::8a2e:370:7334");
    }

    @Test
    void aBadFirstElement_isSkipped_andTheNextGoodOneIsUsed() {
        assertThat(ClientIpResolver.resolve(request("not-an-ip, 198.51.100.4"))).isEqualTo("198.51.100.4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "hello world", "1.2.3.4\r\nINJECTED", "1.2.3.4\nX", "<script>", "",
            "   ", "1.2.3.4;rm -rf"})
    void aBadValue_isIgnored_andTheSocketAddressIsUsed(String bad) {
        assertThat(ClientIpResolver.resolve(request(bad))).isEqualTo("10.1.2.3");
    }

    @Test
    void aVeryLongValue_isIgnored() {
        assertThat(ClientIpResolver.resolve(request("1".repeat(46)))).isEqualTo("10.1.2.3");
        assertThat(ClientIpResolver.resolve(request("a".repeat(500)))).isEqualTo("10.1.2.3");
    }

    @Test
    void aValueOfExactly45Characters_isAccepted() {
        String longestIpv6 = "0000:0000:0000:0000:0000:ffff:255.255.255.255".substring(0, 45);
        assertThat(longestIpv6).hasSize(45);
        assertThat(ClientIpResolver.resolve(request(longestIpv6))).isEqualTo(longestIpv6);
    }
}
