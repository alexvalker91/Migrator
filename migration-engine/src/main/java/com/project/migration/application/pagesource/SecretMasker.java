package com.project.migration.application.pagesource;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in free-text UI values before they are stored or sent to an LLM: bearer tokens, JWTs, long
 * hex/base64 tokens and Luhn-valid card numbers (PAN keeps its last 4 digits, which assertions and locator
 * mapping rely on). IBANs, phone numbers and names are deliberately left intact: locators are built on them.
 */
public final class SecretMasker {

    public static final String MASK = "***MASKED***";

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern HEX_TOKEN = Pattern.compile("\\b[A-Fa-f0-9]{32,}\\b");
    private static final Pattern BASE64_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{32,}={0,2}");
    // whole digit runs only: a 22-digit IBAN tail must not yield a 13..19-digit "card" inside it
    private static final Pattern PAN_CANDIDATE = Pattern.compile("(?<![\\d*][ -]?)(?:\\d[ -]?){12,18}\\d(?![ -]?\\d)");

    private static final int PAN_MIN_DIGITS = 13;
    private static final int PAN_MAX_DIGITS = 19;
    private static final int PAN_VISIBLE_DIGITS = 4;

    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = BEARER.matcher(value).replaceAll("Bearer " + Matcher.quoteReplacement(MASK));
        masked = JWT.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
        masked = replaceMatching(HEX_TOKEN, masked, SecretMasker::containsDigit);
        masked = replaceMatching(BASE64_TOKEN, masked, SecretMasker::looksLikeBase64Token);
        return maskCardNumbers(masked);
    }

    private static String replaceMatching(Pattern pattern, String value, Predicate<String> isSecret) {
        Matcher matcher = pattern.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = isSecret.test(matcher.group()) ? MASK : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String maskCardNumbers(String value) {
        Matcher matcher = PAN_CANDIDATE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            boolean isPan = digits.length() >= PAN_MIN_DIGITS && digits.length() <= PAN_MAX_DIGITS
                    && passesLuhn(digits);
            String replacement = isPan
                    ? "**** **** **** " + digits.substring(digits.length() - PAN_VISIBLE_DIGITS)
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    private static boolean containsDigit(String token) {
        return token.chars().anyMatch(Character::isDigit);
    }

    /** Random-looking: digits plus both letter cases, so long readable identifiers survive. */
    private static boolean looksLikeBase64Token(String token) {
        return containsDigit(token)
                && token.chars().anyMatch(Character::isUpperCase)
                && token.chars().anyMatch(Character::isLowerCase);
    }
}
