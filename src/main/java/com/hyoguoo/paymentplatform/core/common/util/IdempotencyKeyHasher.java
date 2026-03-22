package com.hyoguoo.paymentplatform.core.common.util;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyHasher {

    public String hash(Long userId, List<OrderedProduct> products) {
        String raw = userId + ":" + products.stream()
                .sorted(Comparator.comparingLong(OrderedProduct::getProductId))
                .map(p -> p.getProductId() + "x" + p.getQuantity())
                .collect(Collectors.joining(","));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없습니다", e);
        }
    }
}
