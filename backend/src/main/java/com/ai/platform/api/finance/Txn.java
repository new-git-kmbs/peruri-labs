package com.ai.platform.api.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Txn(
        LocalDate date,
        String description,
        BigDecimal amount
) {}
