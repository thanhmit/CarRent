package com.carrent.booking;

import com.carrent.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RentalPolicyTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-31T02:00:00Z");

    @ParameterizedTest
    @CsvSource({
        "2000000, 600000, 1400000",
        "2000001, 600000, 1400001",
        "4, 1, 3",
        "9223372036854775807, 2767011611056432742, 6456360425798343065"
    })
    void splitsRentalIntoWholeVndWithoutLosingMoneyOrOverflow(long total, long advance, long balance) {
        assertThat(RentalPolicy.advance(total)).isEqualTo(advance);
        assertThat(RentalPolicy.balance(total)).isEqualTo(balance);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1, 2, 3})
    void rejectsTotalsThatCannotProduceTwoPositiveInstallments(long total) {
        assertThatThrownBy(() -> RentalPolicy.advance(total)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RentalPolicy.balance(total)).isInstanceOf(BusinessException.class);
    }

    @Test
    void theFreeHourTakesPriorityEvenOnThePickupDate() {
        assertThat(RentalPolicy.cancellation(600000, PAID_AT, PAID_AT.plusSeconds(3600),
                LocalDate.of(2026, 10, 31), false)).isEqualTo(new CancellationQuote(0, 600000));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-04, false, 60000, 540000",
        "2026-11-03, false, 300000, 300000",
        "2026-10-31, false, 300000, 300000",
        "2026-10-30, false, 300000, 300000",
        "2026-11-04, true, 0, 600000",
        "2026-10-30, true, 0, 600000"
    })
    void cancellationAfterTheFreeHourUsesCalendarDaysAndWhoCancels(
            LocalDate startDate, boolean ownerCancels, long fee, long refund) {
        assertThat(RentalPolicy.cancellation(600000, PAID_AT, PAID_AT.plusSeconds(3601),
                startDate, ownerCancels)).isEqualTo(new CancellationQuote(fee, refund));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidCancellationMoneyIsRejectedBeforeAnyFreeRefund(boolean ownerCancels) {
        for (long advance : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThatThrownBy(() -> RentalPolicy.cancellation(advance, PAID_AT, PAID_AT,
                    LocalDate.of(2026, 11, 4), ownerCancels)).isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidCancellationTimes")
    void invalidCancellationTimesAreRejectedEvenWhenTheOwnerCancels(
            Instant paidAt, Instant cancelledAt, LocalDate startDate, boolean ownerCancels) {
        assertThatThrownBy(() -> RentalPolicy.cancellation(600000, paidAt, cancelledAt, startDate, ownerCancels))
                .isInstanceOf(BusinessException.class);
    }

    static Stream<Arguments> invalidCancellationTimes() {
        LocalDate start = LocalDate.of(2026, 11, 4);
        return Stream.of(false, true).flatMap(owner -> Stream.of(
                Arguments.of(null, PAID_AT, start, owner),
                Arguments.of(PAID_AT, null, start, owner),
                Arguments.of(PAID_AT, PAID_AT, null, owner),
                Arguments.of(PAID_AT, PAID_AT.minusNanos(1), start, owner)));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-31T02:00:00Z, 0, 600000",
        "2026-10-31T02:59:59.999999999Z, 0, 600000",
        "2026-10-31T03:00:00Z, 0, 600000",
        "2026-10-31T03:00:00.000000001Z, 300000, 300000"
    })
    void theFreeHourHasAnInclusiveInstantBoundary(Instant now, long fee, long refund) {
        Clock clock = Clock.fixed(now, ZoneId.of("Pacific/Honolulu"));
        assertThat(RentalPolicy.cancellation(600000, PAID_AT, clock.instant(),
                LocalDate.of(2026, 10, 31), false)).isEqualTo(new CancellationQuote(fee, refund));
    }

    @ParameterizedTest
    @CsvSource({
        "600001, 2026-11-04, 60000, 540001",
        "600001, 2026-11-03, 300000, 300001",
        "9223372036854775807, 2026-11-04, 922337203685477580, 8301034833169298227",
        "9223372036854775807, 2026-11-03, 4611686018427387903, 4611686018427387904",
        "1, 2026-11-04, 0, 1",
        "1, 2026-11-03, 0, 1"
    })
    void cancellationFeesRoundDownAndPreserveTheRemainingMoney(
            long advance, LocalDate startDate, long fee, long refund) {
        assertThat(RentalPolicy.cancellation(advance, PAID_AT, PAID_AT.plusSeconds(3601), startDate, false))
                .isEqualTo(new CancellationQuote(fee, refund));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-31T16:59:59.999999999Z, 60000, 540000",
        "2026-10-31T17:00:00Z, 300000, 300000"
    })
    void theFeeChangesAtVietnamMidnightRatherThanUtcMidnight(Instant now, long fee, long refund) {
        assertThat(RentalPolicy.cancellation(600000, PAID_AT, now, LocalDate.of(2026, 11, 4), false))
                .isEqualTo(new CancellationQuote(fee, refund));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/Los_Angeles", "Asia/Tokyo"})
    void theJvmDefaultZoneCannotChangeTheCancellationFee(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertThat(RentalPolicy.cancellation(600000, PAID_AT, Instant.parse("2026-10-31T17:00:00Z"),
                    LocalDate.of(2026, 11, 4), false)).isEqualTo(new CancellationQuote(300000, 300000));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
