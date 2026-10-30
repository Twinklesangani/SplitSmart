package com.splitsmart.dto;

import java.math.BigDecimal;

public record ExpenseSplitResponse(Long userId, BigDecimal amount) {
}
