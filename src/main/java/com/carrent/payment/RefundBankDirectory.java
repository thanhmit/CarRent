package com.carrent.payment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Bundled public bank routing metadata; never calls an account-lookup service. */
@Component
public final class RefundBankDirectory {
    public record Bank(String bin, String name, String shortName) { }
    private final List<Bank> banks;
    private final Map<String, Bank> byBin;

    public RefundBankDirectory() throws IOException {
        var loaded = new ArrayList<Bank>();
        try (var input = new ClassPathResource("payment/refund-banks.json").getInputStream()) {
            var data = JsonMapper.builder().build().readTree(input);
            if (!data.isArray() || data.isEmpty()) throw new IllegalStateException("Missing refund banks");
            for (var entry : data) {
                var bank = new Bank(entry.path("bin").asText(), entry.path("name").asText(), entry.path("shortName").asText());
                if (!bank.bin().matches("[0-9]{6}") || bank.name().isBlank() || bank.shortName().isBlank()) {
                    throw new IllegalStateException("Invalid refund bank metadata");
                }
                loaded.add(bank);
            }
        }
        banks = List.copyOf(loaded);
        byBin = banks.stream().collect(Collectors.toUnmodifiableMap(Bank::bin, Function.identity()));
    }

    public List<Bank> banks() { return banks; }
    public boolean supports(String bin) { return bin != null && byBin.containsKey(bin); }
    public String displayName(String bin) {
        var bank = bin == null ? null : byBin.get(bin);
        return bank == null ? bin : bank.shortName() + " — " + bank.name();
    }
}
