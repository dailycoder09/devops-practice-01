package com.microservices.order.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client address for the logs: the first usable address of X-Forwarded-For, otherwise the socket address.
 * The header can be faked, so this is for observability only (never for a security decision).
 */
class ClientIpResolverTest {

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void withoutTheHeader_theSocketAddressIsUsed() {
        assertThat(ClientIpResolver.resolve(request(null))).isEqualTo("192.0.2.10");
    }

    @Test
    void aSingleAddress_winsOverTheSocketAddress() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void aChain_givesTheFirstAddress_withSpacesRemoved() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, 10.0.0.1 ,10.0.0.2"))).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.resolve(request("  203.0.113.7  "))).isEqualTo("203.0.113.7");
    }

    @Test
    void anIpv6Address_isAccepted() {
        assertThat(ClientIpResolver.resolve(request("2001:db8::1"))).isEqualTo("2001:db8::1");
        assertThat(ClientIpResolver.resolve(request("2001:DB8:0:0:0:0:0:ff, 10.0.0.1"))).isEqualTo("2001:DB8:0:0:0:0:0:ff");
    }

    @Test
    void anElementOfExactly45Characters_isAccepted() {
        String longest = "0000:0000:0000:0000:0000:ffff:255.255.255.255".substring(0, 45);
        assertThat(longest).hasSize(45);

        assertThat(ClientIpResolver.resolve(request(longest))).isEqualTo(longest);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "evil",                                   // letters that are not hex digits
            "1.2.3.4<script>",                        // markup
            "1.2.3.4 5.6.7.8",                        // a space inside the element
            "1.2.3.4\r\nX-Injected: yes",             // a line break (header injection / log injection)
            "1.2.3.4\nINFO fake",                     // LF only
            "unknown",
            "",
            "   ",
            ",",
            "[2001:db8::1]:8080",                     // brackets and a port are not an address
    })
    void aBadValue_isIgnored_andTheSocketAddressIsUsed(String bad) {
        assertThat(ClientIpResolver.resolve(request(bad))).isEqualTo("192.0.2.10");
    }

    @Test
    void anElementOf46Characters_isIgnored() {
        assertThat(ClientIpResolver.resolve(request("1".repeat(46)))).isEqualTo("192.0.2.10");
        assertThat(ClientIpResolver.resolve(request("1".repeat(10_000)))).isEqualTo("192.0.2.10");
    }

    @Test
    void aBadFirstElement_isSkipped_andTheFirstUsableOneIsTaken() {
        assertThat(ClientIpResolver.resolve(request("evil, 198.51.100.4, 10.0.0.1"))).isEqualTo("198.51.100.4");
    }

    @Test
    void theResultNeverContainsAControlCharacter() {
        String resolved = ClientIpResolver.resolve(request("1.2.3.4\r\n"));

        assertThat(resolved).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void ifThereIsNoSocketAddressEither_theResultIsStillAString() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertThat(ClientIpResolver.resolve(request)).isNotNull();
    }
}
