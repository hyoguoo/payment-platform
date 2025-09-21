package com.hyoguoo.paymentplatform.core.common.dto;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PageResponse<T> {

    private List<T> content;
    private int currentPage;
    private int totalPages;
    private long totalElements;
    private int pageSize;
    private boolean hasNext;
    private boolean hasPrevious;
    private boolean isFirst;
    private boolean isLast;

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = this.content.stream().map(mapper).collect(Collectors.toList());

        return PageResponse.<R>builder()
                .content(mapped)
                .currentPage(this.currentPage)
                .totalPages(this.totalPages)
                .totalElements(this.totalElements)
                .pageSize(this.pageSize)
                .hasNext(this.hasNext)
                .hasPrevious(this.hasPrevious)
                .isFirst(this.isFirst)
                .isLast(this.isLast)
                .build();
    }
}
