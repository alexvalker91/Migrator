package com.project.migration.application.pagesource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {

    private final SecretMasker masker = new SecretMasker();

    @Test
    void masksBearerToken() {
        assertThat(masker.mask("Authorization: Bearer abc.DEF-123_xyz=="))
                .isEqualTo("Authorization: Bearer ***MASKED***");
    }

    @Test
    void masksJwt() {
        assertThat(masker.mask("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("token ***MASKED***");
    }

    @Test
    void masksLongHexAndBase64Tokens() {
        assertThat(masker.mask("session 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
                .isEqualTo("session ***MASKED***");
        assertThat(masker.mask("key=AbCdEf0123456789GhIjKlMnOpQrStUvWx=="))
                .isEqualTo("key=***MASKED***");
    }

    @Test
    void keepsLongReadableIdentifiers() {
        assertThat(masker.mask("transaction_history_list_container_item"))
                .isEqualTo("transaction_history_list_container_item");
        assertThat(masker.mask("ffffffffffffffffffffffffffffffffffff")).isEqualTo("ffffffffffffffffffffffffffffffffffff");
    }

    @Test
    void masksLuhnValidCardNumbersKeepingLastFourDigits() {
        assertThat(masker.mask("Visa 4111 1111 1111 1111")).isEqualTo("Visa **** **** **** 1111");
        assertThat(masker.mask("5500-0000-0000-0004")).isEqualTo("**** **** **** 0004");
        assertThat(masker.mask("4111111111111111")).isEqualTo("**** **** **** 1111");
    }

    @Test
    void keepsNumbersThatAreNotCards() {
        assertThat(masker.mask("4111 1111 1111 1112")).isEqualTo("4111 1111 1111 1112");
        assertThat(masker.mask("**** 1234")).isEqualTo("**** 1234");
        assertThat(masker.mask("+375 29 123 45 67")).isEqualTo("+375 29 123 45 67");
        assertThat(masker.mask("1 234,56 BYN")).isEqualTo("1 234,56 BYN");
    }

    @Test
    void keepsIbans() {
        assertThat(masker.mask("DE89 3704 0044 0532 0130 00")).isEqualTo("DE89 3704 0044 0532 0130 00");
        assertThat(masker.mask("BY13NBRB3600900000002Z00AB00")).isEqualTo("BY13NBRB3600900000002Z00AB00");
    }

    @Test
    void luhnCheck() {
        assertThat(SecretMasker.passesLuhn("79927398713")).isTrue();
        assertThat(SecretMasker.passesLuhn("79927398710")).isFalse();
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
