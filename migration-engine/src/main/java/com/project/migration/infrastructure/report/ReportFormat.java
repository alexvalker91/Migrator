package com.project.migration.infrastructure.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;

/**
 * Number formats shared by the dashboard and the Markdown report.
 */
final class ReportFormat {

    private ReportFormat() {
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, rest);
        }
        return rest + "s";
    }

    static String dollars(BigDecimal amount) {
        BigDecimal scaled = amount.abs().compareTo(new BigDecimal("0.01")) < 0 && amount.signum() != 0
                ? amount.setScale(4, RoundingMode.HALF_UP) : amount.setScale(2, RoundingMode.HALF_UP);
        return "$" + scaled.toPlainString();
    }

    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    static String number(double value) {
        return number(Math.round(value));
    }

    static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }

    static double hours(Duration duration) {
        return duration.toSeconds() / 3600.0;
    }
}
