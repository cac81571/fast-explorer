package com.fastexplorer.ui;

import com.fastexplorer.model.BookmarkNode;

public record BookmarkSearchPreset(
        String pathPatterns,
        String filePatterns,
        String extensions
) {
    public boolean isEmpty() {
        return (pathPatterns == null || pathPatterns.isBlank())
                && (filePatterns == null || filePatterns.isBlank())
                && (extensions == null || extensions.isBlank());
    }

    public String defaultName() {
        BookmarkNode node = BookmarkNode.search("", pathPatterns, filePatterns, extensions);
        String detail = node.detailText();
        return detail.isBlank() ? "検索条件" : detail;
    }
}
