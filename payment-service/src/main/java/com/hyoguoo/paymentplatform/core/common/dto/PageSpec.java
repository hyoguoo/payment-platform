package com.hyoguoo.paymentplatform.core.common.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PageSpec {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @Builder.Default
    private int page = DEFAULT_PAGE;

    @Builder.Default
    private int size = DEFAULT_SIZE;

    @Builder.Default
    private String sortBy = "createdAt";

    @Builder.Default
    private SortDirection sortDirection = SortDirection.DESC;

    public static PageSpec of(int oneBasedPage, int size, String sortBy, SortDirection sortDirection) {
        return PageSpec.builder()
                .page(oneBasedPage)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();
    }

    public int getZeroBasedPage() {
        return Math.max(1, page) - 1;
    }

    public int getSize() {
        return Math.clamp(size, 1, MAX_SIZE);
    }
}
